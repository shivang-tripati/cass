# F2 — Contacts & Contact Groups

**Phase:** F2
**Previous phase:** F1 — Frontend Foundation & Contract Alignment
**Status:** IMPLEMENTATION COMPLETE
**Scope:** Contacts and Contact Groups only. No other domain was migrated.

---

## 1. Executive Summary

F2 migrated the Contacts and Contact Groups domain onto the F1 foundation, after
verifying every endpoint, DTO, capability and semantic against the backend
source. The domain is now backend-faithful, capability-aware, tenant-safe and
covered by 107 new tests.

**The most important thing to know about this domain is that it has two
resources, not one, and the F0 frontend treated them as one.** A contact is a
**tenant-level identity**; a membership is a **relationship row** that puts that
identity in one particular group. The backend models this explicitly, in a
dedicated `ContactGroupMemberService` with its own six endpoints, its own table
and its own unique constraint. F0 noted those endpoints as "entirely unused by
the frontend" and moved on.

The consequence of getting this wrong is not cosmetic. Two deletion paths exist
and they do different things:

| Action | Effect |
|---|---|
| `DELETE /contact-groups/{g}/contacts/{c}` | soft-deletes the contact **identity**; removes it from **every** group it belongs to; its phone becomes re-creatable |
| `DELETE /contact-groups/{g}/members/{c}` | removes the **relationship only**; the contact survives and stays in every other group |

F2 surfaces both, names them differently in the UI, and states the consequence
in the confirmation dialog. Before F2 the only delete was the destructive one,
offered through a bare `confirm()`.

Twelve concrete defects were found and fixed, including two dead links, a form
field that could never validate, a list that 404'd on every interaction, an
Export button wired to an empty function, and a 409 branch for a condition the
backend cannot produce.

**F2 wrote zero backend files.**

---

## 2. Backend Contract Verified

Every row below was read from the backend source during F2, not inferred from the
frontend. Line references are to the files as they stand at the end of F2.

### 2.1 There is no top-level contacts resource

**VERIFIED:** no `ContactController` exists anywhere in `backend/`. Contacts are
reachable only through a group, because a contact belongs to a tenant and
participation is a membership concern. `ContactGroupController` and
`ContactGroupService` own all of it.

The frontend therefore must not construct `/contacts/{id}` URLs, and
`contact-links.test.ts` asserts none exist.

### 2.2 Endpoints

| Domain | Operation | HTTP | Endpoint | Request | Response | Capability | Tenant scope |
|---|---|---|---|---|---|---|---|
| Group | List | GET | `/contact-groups` | `page`, `size`, `sort`, `search` | `ContactGroupResponse[]` + `PaginationMetadata` | `CONTACT_VIEW` | tenant / reseller-hierarchy / platform |
| Group | Get | GET | `/contact-groups/{id}` | — | `ContactGroupResponse` | `CONTACT_VIEW` | visible group |
| Group | Create | POST | `/contact-groups` | `CreateContactGroupRequest` | `ContactGroupResponse` (201) | `CONTACT_MANAGE` + **tenant context** | caller's own tenant |
| Group | Update | PUT | `/contact-groups/{id}` | `UpdateContactGroupRequest` | `ContactGroupResponse` | `CONTACT_MANAGE` | visible group |
| Group | Delete | DELETE | `/contact-groups/{id}` | — | 204, no body | `CONTACT_MANAGE` | visible group |
| Contact | List | GET | `/contact-groups/{id}/contacts` | `page`, `size`, `sort`, `search` | `ContactResponse[]` + `PaginationMetadata` | `CONTACT_VIEW` | group-scoped |
| Contact | Get | GET | `/contact-groups/{id}/contacts/{contactId}` | — | `ContactResponse` | `CONTACT_VIEW` | group-scoped |
| Contact | Create | POST | `/contact-groups/{id}/contacts` | `CreateContactRequest` | `ContactResponse` (201) | `CONTACT_MANAGE` | group-scoped |
| Contact | Update | PUT | `/contact-groups/{id}/contacts/{contactId}` | `UpdateContactRequest` | `ContactResponse` | `CONTACT_MANAGE` | group-scoped |
| Contact | Delete | DELETE | `/contact-groups/{id}/contacts/{contactId}` | — | 204, no body | `CONTACT_MANAGE` | group-scoped |
| Import | Bulk create | POST | `/contact-groups/{id}/contacts/import` | multipart, field `file` | `ContactImportResponse` | `CONTACT_MANAGE` | group-scoped |
| Export | Bulk read | GET | `/contact-groups/{id}/contacts/export?format=` | `csv`/`xlsx`/`json` | **raw file**, not the envelope | `CONTACT_VIEW` | group-scoped |
| Member | List roster | GET | `/contact-groups/{id}/members` | `page`, `size`, `sort`, `search` | `ContactGroupMemberResponse[]` + `PaginationMetadata` | `CONTACT_VIEW` | group-scoped |
| Member | Get one | GET | `/contact-groups/{id}/members/{contactId}` | — | `ContactGroupMemberResponse` | `CONTACT_VIEW` | group-scoped |
| Member | Add | POST | `/contact-groups/{id}/members` | `AddMemberRequest` | `ContactGroupMemberResponse` (**201 or 200**) | `CONTACT_MANAGE` | group-scoped |
| Member | Remove | DELETE | `/contact-groups/{id}/members/{contactId}` | — | 204, no body | `CONTACT_MANAGE` | group-scoped |
| Member | Batch add | POST | `/contact-groups/{id}/members/batch` | `BatchMemberRequest` | `BatchMemberResponse` (200) | `CONTACT_MANAGE` | group-scoped |
| Member | Batch remove | DELETE | `/contact-groups/{id}/members/batch` | `BatchMemberRequest` **in the body** | `BatchMemberResponse` (200) | `CONTACT_MANAGE` | group-scoped |

### 2.3 Response DTOs

**`ContactResponse`** — `contact/dto/ContactResponse.java`, exactly nine
components:

```
UUID id, UUID tenantId, String firstName, String lastName, String phoneNumber,
String email, JsonNode attributes, Instant createdAt, Instant updatedAt
```

**No `contactGroupId`. No status or lifecycle field.** The group is the parent
segment of the REST route, which is exactly why F0's phantom field was always
`undefined` at runtime.

**`ContactGroupResponse`** — `contact/dto/ContactGroupResponse.java`, seven
components:

```
UUID id, UUID tenantId, String name, String description, long memberCount,
Instant createdAt, Instant updatedAt
```

`memberCount` is the backend's own value. `ContactGroupService.withMemberCounts`
fills the whole page from **one** grouped query
(`ContactGroupMemberRepository.findCountsByGroupIds`); the detail and create
paths use `countLiveByContactGroupId`. It counts **live** contacts only —
memberships of soft-deleted contacts are excluded. F2 renders this number and
never counts anything in the browser.

**`ContactGroupMemberResponse`** — the membership row, not the contact:

```
UUID memberId, UUID groupId, UUID contactId, UUID tenantId,
ContactResponse contact, Instant createdAt, UUID createdBy
```

Three things a reader gets wrong on the first pass, so they are stated in
`contracts.ts`:

- the membership has its **own** id, `memberId`, distinct from `contactId`;
- the group field is `groupId`, **not** `contactGroupId`;
- `contact` is `ContactResponse | null` — the roster joins the live contact, so
  it is populated in practice, but the schema permits null and F2 renders that
  honestly instead of dereferencing it.

**`MemberBatchStatus`** — `CREATED · EXISTS · NOT_FOUND · NOT_FOUND_CONTACT ·
ERROR`, one per distinct requested id, in request order.

### 2.4 Request DTOs and validation

**`CreateContactGroupRequest(name, description)`** and
**`UpdateContactGroupRequest(name, description)`** are **structurally
identical** — `@NotBlank @Size(max=150)` name, `@Size(max=5000)` description.

Neither declares `tenantId` or `memberCount`. A tenant picker on the group form
would have nothing to bind to.

**`CreateContactRequest` / `UpdateContactRequest`** are also structurally
identical:

| Field | Annotation | Notes |
|---|---|---|
| `firstName` | `@Size(max=100)` | **not** `@NotBlank` |
| `lastName` | `@Size(max=100)` | |
| `phoneNumber` | `@Pattern(E164)` | **not** `@NotBlank` — see below |
| `email` | `@Email @Size(max=255)` | |
| `attributes` | `JsonNode`, unvalidated | an object, per the import path |

`phoneNumber` is not marked `@NotBlank`, but
`ContactIdentityService.canonicalPhoneNumber` throws a `VALIDATION_ERROR` for
anything that cannot reach canonical form, so it is required in practice. F2
requires it client-side and says so in the schema comment.

### 2.5 Semantics that are not obvious and were not documented

**Contact identity is `(tenant_id, canonical_phone_number)`,** DB-enforced by
`uq_contacts_tenant_phone_live` (`ContactIdentityService` Javadoc). There is no
global phone uniqueness — the tenant is inside the key. The same number in two
tenants is two different contacts and both are allowed.

**`POST /{id}/contacts` is find-or-create, not create.** If the tenant already
holds a live contact for that phone, the existing identity is reused and only the
membership is added (`ContactGroupService` L196-220). A 409 from this endpoint is
a *concurrent* create of the same tenant+phone, never a plain duplicate. F2's
create dialog says this, because "Add Contact" on an existing number neither
duplicates nor errors.

**A contact update keeps its Contact UUID,** so call history stays attached. A
phone change that collides with another live identity in the same tenant is a
typed 409 (`assertPhoneAvailableForTenant`).

**The three contact text fields do not behave the same way** on update.
`ContactMapper.applyCommon` (L63-76):

- `firstName` → `trim()`, **no** blank-to-null. Sending `""` would persist `""`.
- `lastName`, `email` → `blankToNull`. Sending `""` clears them.
- `attributes` → assigned **unconditionally**. Omitting it CLEARS the stored
  object. There is no "leave unchanged" edit for this one field.

F2's schema therefore maps a blank `firstName` to `undefined` (omitted) and a
blank `lastName`/`email` to `undefined` as well; the effects differ but the wire
result is the same and the form always sends the whole attributes object.

**`ContactGroupMapper.applyCommon` is verbatim** — `setName(name)`,
`setDescription(description)`, no trim, no blank-to-null. The client trims
before sending. An omitted description clears the stored value, which is why the
schema maps `""` to `undefined` rather than sending an empty string that would
be persisted as an empty string.

### 2.6 Pagination, filtering, sorting, search

| Resource | `page` | `size` | Sort allowlist | Default sort | Search matches |
|---|---|---|---|---|---|
| Groups | 0-based | server-clamped 1..100 | `name`, `createdAt`, `updatedAt` | `createdAt,desc` | `name` |
| Contacts | 0-based | server-clamped 1..100 | `firstName`, `phoneNumber`, `createdAt` | `firstName,asc` | `firstName`, `lastName`, `phoneNumber` |
| Members | 0-based | server-clamped 1..100 | `createdAt`, `firstName`, `phoneNumber` | `createdAt,` **ASC** | `firstName`, `lastName`, `phoneNumber` |

All three use `ResponseFactory.page(...)` and send a real `PaginationMetadata`.
None is unpaginated. F2 models the real structure and never fabricates a page
object — the F1 rule, asserted in `contacts.test.ts`.

Two search facts the F0 UI got wrong, both now corrected in code and in
placeholders:

- **contacts and members do not search `email`** —
  `ContactSpecifications.search` and `findRosterPage` cover firstName, lastName
  and phoneNumber only. The old placeholder said "Search name, phone, email…".
- **the members default sort is ASC** while the groups default is DESC.

`memberCount` is **not** in any sort allowlist, so the table column is marked
unsortable and never sent as a sort field.

### 2.7 Bulk import and export

**VERIFIED** `ContactGroupService` L331-436 and controller L379-410.

- multipart, field name `file`; `.csv`, `.xlsx` or `.json`; 5 MB / 5000 rows.
- required column `phoneNumber` (E.164); optional `firstName`, `lastName`,
  `email`, `attributes` (a JSON object **string**).
- **unknown columns are ignored** — the readers look up only the five known
  names, so a `tenantId` column in the file has no effect. Ownership comes
  exclusively from the group.
- **every row is validated individually**; duplicates within the file and against
  existing live tenant identities are **skipped and reported**. Partial success
  is normal, not exceptional.
- `errors[]` is capped at `MAX_REPORTED_ERRORS = 100`, so `errors.length` is not
  `errorCount` and the UI must not present it as such. F2 says "N reported of M
  skipped — the server reports at most 100".
- export is a **raw file download**, not the JSON envelope, with
  `Content-Disposition: attachment; filename="…"`.

### 2.8 Delete semantics — one stale annotation found

**The controller's OpenAPI annotation on `DELETE /contact-groups/{id}` claims
`409 "Group still contains contacts"`. It is wrong.**

`ContactGroupService.deleteGroup` (L171-181) calls
`memberService.removeAllForGroup(groupId)` and then soft-deletes the group. The
only `ConflictException` in the file is the contact identity race at L194. The
service Javadoc is explicit:

> *"A group with live members can be deleted — the membership links are what die,
> not the identities."*

F0 recorded the 409 as fact and the frontend's confirm dialog repeated it as
*"The group must be empty (no contacts)"* — which is both wrong and alarming in
the wrong direction. What actually happens: the group's **membership rows** are
physically removed; the **contacts survive**, stay live in the tenant, and
remain members of any other group.

F2's delete dialog states that, using the backend's own `memberCount`. The 409
branch is retained in the service call site as defence, but it is not an
expected path and is not advertised.

### 2.9 Membership operations

All six are supported, all are `CONTACT_MANAGE` for writes and `CONTACT_VIEW`
for reads, and all were unused by the frontend before F2.

- **Add is idempotent, and the STATUS carries the result:** 201 when created,
  200 when the contact was already a member. The envelope's `success` flag is
  true either way, so the status is the only signal — which is why
  `addContactGroupMember` reads `response.status` rather than the body.
- **Add never creates a contact.** `addMember` resolves the contact with
  `findByIdAndTenantIdAndDeletedAtIsNull` and throws a 404 if it is not a live
  identity of the group's tenant. A foreign-tenant contact is
  indistinguishable from a nonexistent one, by design.
- **Remove is idempotent** — a missing membership is a successful no-op
  returning 204.
- **Batch never fail-fasts.** One outcome per distinct requested id, in request
  order; a duplicate id inside one request collapses to a single deterministic
  outcome; max 500.
- **Batch remove is a `DELETE` with a request body that returns 200**, not 204.

### 2.10 Tenant scope

`ContactGroupAccess` resolves visibility from the server-derived organization
context *before* any capability decision, so a foreign group and a nonexistent
group are **both 404** (`findVisibleGroup` L92-97). The capability is then
checked against the owning tenant.

`listGroups` has three branches (L120-152):

- `scope.tenantId != null` → `CONTACT_VIEW` for the tenant, groups filtered to it;
- `scope.resellerId != null` → `CONTACT_VIEW` for the reseller, groups filtered
  to the **hierarchy tenants** — a merged list across every tenant they manage;
- neither → `CONTACT_VIEW` platform-wide.

**One operation has an extra precondition.** `createGroup` (L98-110):

```java
UUID tenantId = scope.tenantId();
if (tenantId == null) {
    throw business("A tenant must be specified for this operation.");
}
```

`CreateContactGroupRequest` has no tenant field and there is no header or query
parameter that supplies one, so **only a caller whose context HAS a tenant can
create a group.** `OrganizationContextPopulationFilter` derives
`Scope.tenantId` from `TenantMembershipResolverAdapter.resolvePrimaryTenantId`,
which is `homeRepository.findByUserId(userId).filter(homeType == TENANT)` — the
same `findByUserId` row that populates `/me`'s `homeType`
(`UserService.java:209`). So `homeType === "TENANT"` ⟺ `Scope.tenantId != null`,
an exact equivalence rather than a heuristic.

A `RESELLER_ADMIN` is granted `CONTACT_MANAGE` by V1, and a `SUPER_ADMIN` has no
organizational home at all, so **both would be shown a Create button that can
only ever return 400.** F2 gates it on the tenant context. See §5.

The asymmetry matters and is easy to get backwards: only **creation** needs a
tenant. `updateGroup`, `deleteGroup`, all contact mutations and all membership
mutations go through `authorizedGroup(groupId, …)`, which only requires the group
to be *visible* — so a reseller can edit, delete, import and manage memberships
in the groups they can already see. F2 does not restrict those.

---

## 3. Frontend Changes

### 3.1 Files added (11 source, 7 test)

**API / domain logic**
- `frontend/src/lib/api/contact-group-members.ts` — the six membership endpoints,
  query-key factory, sort whitelist.
- `frontend/src/lib/auth/contact-gates.ts` — which *enforced* capability gates
  each Contacts action, plus `canCreateContactGroup`.

**Routes**
- `frontend/src/app/(platform)/contact-groups/[contactGroupId]/contacts/[contactId]/page.tsx`
  — the route F0's "View" button pointed at and which did not exist.

**Contacts components**
- `frontend/src/components/contacts/contact-detail-view.tsx`
- `frontend/src/components/contacts/delete-contact-dialog.tsx`
- `frontend/src/components/contacts/remove-from-group-dialog.tsx`
- `frontend/src/components/contacts/export-contacts-dialog.tsx` (extracted from
  the combined import/export dialog)

**Contact Groups components**
- `frontend/src/components/contact-groups/contact-group-members-panel.tsx`
- `frontend/src/components/contact-groups/contact-group-members-table.tsx`
- `frontend/src/components/contact-groups/add-group-member-dialog.tsx`
- `frontend/src/components/contact-groups/delete-contact-group-dialog.tsx`

**Tests**
- `frontend/src/lib/api/contacts.test.ts`
- `frontend/src/lib/api/contact-groups.test.ts`
- `frontend/src/lib/api/contact-group-members.test.ts`
- `frontend/src/lib/api/contact-links.test.ts`
- `frontend/src/lib/auth/contact-gates.test.ts`
- `frontend/src/lib/schemas/contact-mutation.test.ts`
- `frontend/src/lib/schemas/contact-group-mutation.test.ts`

### 3.2 Files deleted (1)

- `frontend/src/components/contact-groups/import-contacts-dialog.tsx` — a
  byte-near-identical second copy of the contacts import/export dialog, with
  different prop names, importing functions from a module that no longer owned
  them. One implementation now.

### 3.3 Files modified (16)

| File | Change |
|---|---|
| `lib/api/contacts.ts` | Full contract rewrite. Added `forGroup` key, `getContact`, download filename, verified semantics. |
| `lib/api/contact-groups.ts` | Full contract rewrite. `importContacts`/`exportContacts` removed (duplicated from `contacts.ts`). |
| `lib/api/contracts.ts` | Membership DTO comments; group DTO provenance. |
| `lib/api/transport.ts` | **Additive only:** `unwrapDownload` + `Download`, so the server's `Content-Disposition` filename is used instead of a UUID. `unwrap`, `unwrapPage`, `unwrapBlob`, `sendVoid` untouched. |
| `lib/schemas/contact-mutation.ts` | Rewritten. `attributes` boundary fixed; E.164 canonicalization; blank-to-omitted semantics per field. |
| `lib/schemas/contact-group-mutation.ts` | Verified annotations, one shared field set, `""`→`undefined` for description. |
| `components/contacts/contacts-view.tsx` | Capability gating, group query for name + `memberCount`, prefix invalidation, shared states, responsive table, **basePath fix**. |
| `components/contacts/contact-table.tsx` | Group id from the route, `memberCount` not shown (contacts have none), gated actions, separate remove-vs-delete. |
| `components/contacts/create-contact-dialog.tsx` | Shared field-error mapper, verified find-or-create copy, third `useForm` generic for the transform. |
| `components/contacts/edit-contact-dialog.tsx` | Attributes round-trip, verified replace semantics, `router`-free, no non-null assertion. |
| `components/contacts/import-contacts-dialog.tsx` | Import only; export extracted; verified partial-success reporting; `canImport` from the caller. |
| `components/contact-groups/contact-groups-view.tsx` | Capability gating incl. the tenant precondition, shared `QueryErrorState`, real delete and export dialogs, `memberCount` column, responsive table. |
| `components/contact-groups/contact-group-detail-view.tsx` | Full rewrite — see §4. |
| `components/contact-groups/contact-group-table.tsx` | `memberCount` column, contacts link, capability gating. |
| `components/contact-groups/create-contact-group-dialog.tsx` | Shared field-error mapper. |
| `components/contact-groups/edit-contact-group-dialog.tsx` | Shared field-error mapper; error handler no longer suppresses the form summary on a non-400. |

### 3.4 Architecture

The F1 chain is unchanged and unopened:

```
ContactPage → ContactsView → useQuery(contactsKeys) → contacts.ts
                                            → transport.ts → apiClient → backend
```

- No `axios`/`fetch` call appears in any component. `grep` for direct calls in
  `components/contacts/**` and `components/contact-groups/**` returns nothing.
- `transport.ts` is still the only module that knows `ApiResponse` exists.
- Error predicates come only from the shared `lib/api/error`; no local copies.
- Query keys are stable factories per module. `contactsKeys.forGroup(id)` is a
  **prefix** of every list key for that group, so one invalidation clears the
  list whatever page, sort or search is active — the F0 code rebuilt a single
  exact key and left the visible list stale.
- Operating context: reused, not reimplemented. No `tenantId` is accepted from a
  URL, query or form anywhere in this domain.
- No second permission mechanism: `contact-gates.ts` is a lookup table over the
  F1 `Capability` catalogue plus the F1 `hasAnyCapability` predicate.

---

## 4. Contract Corrections

Twelve findings. Every one is a stale assumption removed or a real defect fixed.

### FIXED 1 — every contact "View" link was a 404

`ContactTable` linked to `/contact-groups/{contactGroupId}/contacts/{contactId}`,
**which is not a route.** There was no `[contactId]` directory under `app/`, so
every View button on a contact resolved to the not-found page.

F2 added the route and the `ContactDetailView` behind it, backed by
`GET /api/v1/contact-groups/{id}/contacts/{contactId}`.

`contact-links.test.ts` now walks `src/app` and `src/components`, collects every
internal `href` and `router.push`/`replace` target in this domain, and asserts
each matches a real route — matching literal segments **exactly**, with
`<dynamic>` as a single-segment wildcard. Verified by temporarily reintroducing
the F0 defect: the test fails with
`no page.tsx route matches /contact-groups/<dynamic>/edit`, and passes again on
revert.

### FIXED 2 — the group "Edit" link pointed at a route that never existed

`ContactGroupDetailView` linked to `/contact-groups/{id}/edit`. Editing is a
dialog. The link is now a button, and the test asserts the route stays absent so
it cannot be re-introduced by accident.

### FIXED 3 — `attributes` could never be validated or saved

The schema declared `z.record(z.string(), z.unknown())` while the form bound a
`<TextareaField>`, so a textarea **string** was validated against a record
schema. No value could ever pass: typing JSON produced a validation error, and
leaving it blank produced `""`, which also failed.

This is the F0 bug the brief refers to as the "admin email mapping" class — a
form field and a schema bound to different types. F2 fixes the boundary
properly: the form holds JSON *text* (what a textarea produces) and the schema
parses it into the object the API takes, mapping blank to `undefined` so an
empty object is never sent, and rejecting arrays and scalars because
`ContactGroupService.parseAttributes` requires an object.

React Hook Form needs its third generic for this (`useForm<Input, unknown,
Output>`); without it the resolver's output type does not line up with the field
type.

### FIXED 4 — the contacts list 404'd on every interaction

`useUrlListState` feeds `basePath` straight to `router.replace`, and the contacts
list passed the literal string `"/contact-groups/[contactGroupId]/contacts"`.
So every search keystroke, page change, page-size change and sort click
navigated to a URL containing a literal `[contactGroupId]` segment — a 404.

This was the only nested list view in the app, which is why no other list was
affected and why it survived F1. F2 interpolates the route parameter and
memoises the config so the hook's `useMemo`/`useCallback` dependencies stay
stable.

### FIXED 5 — Export did nothing

`ContactGroupsView` passed `onExport={() => {}}` to the table. The Export button
was rendered and did nothing at all. It now opens a real dialog, uses the
server's `Content-Disposition` filename instead of `contacts-<uuid>.csv`, and
revokes the object URL after the click is dispatched.

### FIXED 6 — no capability gating anywhere in the domain

Every create, edit, delete and import control rendered for every user. A
read-only user was offered "Create group" and then received a 403. F2 gates all
of them through `contact-gates.ts`.

### FIXED 7 — a raw tenant UUID was displayed as group information

The old group detail view rendered `Tenant: {group.tenantId}` as a field.
`tenantId` **is** on `ContactGroupResponse`, so the type was correct — but an
internal identifier is not group information and there is nothing a user can do
with it. It is removed, and the page says the group belongs to your organization
instead.

### FIXED 8 — a hand-copied 403 block, and `window.location.href`

The group list had its own `<Alert>` for permission failures, and the delete
handler navigated with `window.location.href`, reloading the whole document.
Both replaced: the F1 shared `QueryErrorState` and `router.push`.

### FIXED 9 — the delete confirmation stated a requirement that does not exist

`confirm("... The group must be empty (no contacts).")` — traced to the stale
409 annotation in §2.8. The new dialog states the verified consequence:
memberships are removed, contacts are kept and remain in their other groups.

### FIXED 10 — the two deletions were not distinguished

Only the destructive one existed, offered through a bare `confirm()`. F2 adds
`RemoveFromGroupDialog` (`DELETE /members/{c}`) alongside `DeleteContactDialog`
(`DELETE /contacts/{c}`), with different labels, different icons, different
tooltips, and dialogs that name the difference explicitly.

### FIXED 11 — duplicated API functions and a duplicated dialog

`importContacts` and `exportContacts` were declared **byte-for-byte
identically** in both `lib/api/contact-groups.ts` and `lib/api/contacts.ts`, and
`components/contact-groups/import-contacts-dialog.tsx` was a near-identical
second copy of the dialog with different prop names. A fix to either was
invisible in the other. F2 leaves one implementation of each, and
`contact-groups.test.ts` asserts the group module does not re-export them.

### FIXED 12 — search placeholders promised a field the backend does not search

"Search name, phone, email…" — `ContactSpecifications.search` and
`findRosterPage` cover firstName, lastName and phoneNumber only. Corrected, and
the empty-state copy states what search actually matches.

### NOT A DEFECT, BUT CORRECTED IN DOCUMENTATION

Three source comments were wrong about the backend and are now right, because a
wrong comment is a defect that only bites the next reader:

- `contacts.ts` described the contact update as a **partial** update where
  `attributes` is "set only when supplied". It is assigned unconditionally, so
  omitting it clears the value.
- `contact-groups.ts` documented the 409 that cannot occur.
- `contact-mutation.ts` required a phone number client-side without saying the
  backend does not mark it `@NotBlank`; the reason it is required anyway is now
  stated.

### VERIFIED AND LEFT ALONE

- `ContactResponse` has no `contactGroupId` — F1's removal was correct and is
  now asserted in `contact-links.test.ts`, which fails if any file reads
  `.contactGroupId`.
- The pagination shape was already correct and is passed through untouched.
- The F0 tenant-context reasoning — no tenant selector, because the backend gives
  resellers a hierarchy-wide read with no impersonation parameter — is still
  correct. **No tenant selector was built.**

---

## 5. Authorization

### 5.1 Actual capabilities used

`frontend/src/lib/auth/contact-gates.ts` records the capability each action is
gated on, with the backend call site in the comment:

| Action | Gate | Backend enforcement |
|---|---|---|
| View groups, group, contacts, roster | `CONTACT_VIEW` | `listGroups` L128/131/138, `getGroup` L114, `listContacts` L226, `getContact` L248, `listMembers` L70, `getMember` L83 |
| Create / update / delete a **group** | `CONTACT_MANAGE` | `createGroup` L106, `updateGroup` L156, `deleteGroup` L173 |
| Create / update / delete a **contact** | `CONTACT_MANAGE` | L198, L261, L296 |
| Add / remove a **membership**, single or batch | `CONTACT_MANAGE` | L100, L127, L135, L162 |
| **Import** contacts | `CONTACT_MANAGE` | `importContacts` |
| **Export** contacts | `CONTACT_VIEW` | `exportContacts` |

### 5.2 Two keys the catalogue has and the services do not enforce

The catalogue seeds **four** contact keys. `Contact_IMPORT` and
`CONTACT_EXPORT` are granted by V1 to the same roles as the enforced pair, but
**no service ever checks them.** Gating on them would be a guess that currently
happens to produce the same answer and would break silently the day role
assignments diverge from the enforced set.

`contact-gates.test.ts` pins this: `import` must be `CONTACT_MANAGE` and must
**not** be `CONTACT_IMPORT`; `export` must be `CONTACT_VIEW` and must **not** be
`CONTACT_EXPORT`; and a user holding *only* the unenforced keys is denied both.

### 5.3 Creating a group needs a tenant context as well as the capability

Covered in §2.10. `canCreateContactGroup` requires `CONTACT_MANAGE` **and**
`homeType === "TENANT"`.

This is not an invented permission. It is the endpoint's own precondition
(`createGroup` throws when `Scope.tenantId` is null), and the client can evaluate
it because `Scope.tenantId` and `/me`'s `homeType` are the same
`OrganizationalHomeEntity` row read through the same `findByUserId` call.

The effect: a `RESELLER_ADMIN` and a `SUPER_ADMIN` — both of whom hold
`CONTACT_MANAGE` — are no longer offered a Create button that can only return
400. Editing, deleting, importing and membership management are **not**
restricted for them, because those work.

Tested in `contact-gates.test.ts`: tenant admin with `CONTACT_MANAGE` may
create; without it may not; reseller admin and super admin may not, while still
passing every other write check.

### 5.4 Forbidden is not empty

Every list and detail view in the domain renders the F1 shared `QueryErrorState`,
which maps 403 to "You don't have access to this — you are signed in, but your
account is not permitted to view …". A permission failure is never shown as an
empty list, and never triggers a sign-in redirect. 401 is handled by the axios
interceptor's refresh flow and is not reached here.

### 5.5 What was not done

No capability name was invented. No `any` was introduced. No second predicate
was added — `contact-gates.ts` calls the F1 `hasAnyCapability`. The IVR blocker
carried over from F1 is untouched.

---

## 6. Tenant / Operating Context

The F1 model is reused exactly as built. F2 introduced no context mechanism, no
`tenantId` input, and no tenant selector.

**Why there is still no tenant selector.** For a reseller, `listGroups` returns
groups across their **hierarchy tenants** — a merged list. There is no
`tenantId` field on either group DTO, no header, and no query parameter that
narrows a request to one of those tenants. A picker would be a client-side
filter pretending to be a scope change, and F1's `operating-context.ts` already
documents where one would go if the backend grows the contract.

**What F2 does with the scope, concretely.** One place only: the Create Group
gate (§5.3), derived from `homeType` through the existing F1 context. Nothing
else needed it, because every other operation in this domain is authorised
against an existing, already-visible group.

**Cache isolation.** Reused from F1, not reimplemented: the scope cannot change
within a session because there is no switching to perform, and
`queryClient.clear()` runs on sign-out. Within the domain, F2 tightened
invalidation so a mutation cannot leave a stale view behind — `forGroup(id)` is a
key prefix, and every membership mutation invalidates the roster, the group's
contacts list **and** the group itself, because `memberCount` lives on the group
and changes with every membership.

**Foreign resources are 404, not 403,** by backend design. `QueryErrorState`'s
404 wording already reflects that ("does not exist, or it is outside your
organization") and F2 did not weaken it.

---

## 7. Contact Groups

### 7.1 The membership model

```
ContactGroupEntity ──1:N── ContactGroupMemberEntity ──N:1── ContactEntity
        (group)              (membership: has its own           (identity:
                               memberId, groupId, contactId,      tenant-scoped,
                               createdAt, createdBy)               (tenant_id,
                                                                    phone) unique)
```

Three consequences the UI must respect, and F2 does:

1. **A contact is not owned by a group.** `ContactResponse` has no group field
   and must not grow one. The group is the parent route segment.
2. **A contact can be in many groups.** Deleting it from any one of them, via
   the contacts endpoint, removes it from all of them. The only way to unlink
   from one group is the membership endpoint.
3. **Membership is a relationship with its own history** — `createdAt` is when
   the contact joined *this* group, which can be long after the identity was
   created. The roster column is labelled "Joined" for exactly this reason.

### 7.2 Why `/contacts` and `/members` are two surfaces, not one

They are two resources with different write semantics, and F2 keeps them apart
on purpose:

| | `/contacts` | `/members` |
|---|---|---|
| Question | which contact **identities** are in this group | who is in this group, **since when** |
| Rows | contacts | memberships (own `memberId`, own `createdAt`) |
| Sort default | `firstName,asc` | `createdAt,` ASC |
| Can create a contact | **yes** — the only place | no, by design |
| Remove | deletes the identity across all groups | removes the relationship only |
| Route | `/contact-groups/{id}/contacts` | the roster panel on `/contact-groups/{id}` |

Both show overlapping people. That is not duplication — merging them is how a
"remove from group" quietly becomes "delete this contact everywhere".

The **contacts** list is the CRUD surface (create, edit, delete, remove-from-group)
and lives at its own route. The **members** panel is the participation surface
(add existing, remove one or many) and lives on the group detail page, next to
the group's own `memberCount`.

### 7.3 Adding an existing contact — a real backend limitation

`POST /{id}/members` takes exactly one thing: `AddMemberRequest { contactId }`.
It does **not** take a phone number, and it does **not** create contacts.

Combined with the absence of `GET /api/v1/contacts` and of any tenant-wide
contact search, this means a user **cannot be offered "search all contacts in my
organization"** — no such endpoint exists. The only places a contact id is
discoverable are group rosters the caller is already allowed to read.

`AddGroupMemberDialog` therefore offers the two paths that actually work, and
states the limit rather than hiding it:

1. **Pick from another group** you can see — the normal route. The first page
   (up to 100) of that group's roster is listed, and when the group has more,
   the dialog says so instead of pretending the list is complete.
2. **Paste a contact id** — for a contact that exists in the tenant but sits
   only in a group you cannot open.

A tenant parameter is deliberately absent: the group determines the tenant
server-side. The dialog never fail-fasts silently either — batch adds report
`CREATED`, `EXISTS` and `NOT_FOUND_CONTACT` per item, and
`NOT_FOUND_CONTACT` is explained as deliberately indistinguishable from a wrong
id.

### 7.4 Removing a membership

Two routes to the same outcome, and the panel uses one code path for both.

- **A row's Remove button** opens `RemoveFromGroupDialog`, which states in plain
  language that the contact is kept and stays in every other group.
- **Select rows, then Remove N selected** calls `batchRemove`, a single mutation
  that dispatches to `DELETE /{id}/members/{contactId}` for one id and to
  `DELETE /{id}/members/batch` for many. Both outcomes are rendered from the same
  per-item result.

The batch path is used for the multi-select because it never fail-fasts:
`removeMembers` returns one `BatchMemberResult` per distinct id, a duplicate id
inside one request collapses, and a membership that is already gone reports
`NOT_FOUND` — which the UI counts as a **success**, because "already gone" is
the state the caller asked for. Only `ERROR` is counted as a failure. So a
fifty-selection remove cannot report a removal the server did not perform, and
cannot abort the other forty-nine.

No optimistic updates anywhere in this path. A membership is a relationship
whose outcome depends on a unique constraint, so the server's per-item answer is
the only one worth showing.

### 7.5 Group actions

- **Create** — `name` and `description` only. No tenant picker (no field to bind
  to), no member count (membership is a separate resource).
- **Edit** — a dialog over `PUT`. `tenantId` is immutable and absent from the
  DTO, so it is not submitted. Clearing the description clears it server-side,
  which the form expresses by omitting the field rather than sending `""`.
- **Delete** — a dialog that states the verified consequence using the backend's
  own `memberCount`.
- **Import / Export** — available from both the list and the detail page.

---

## 8. Tests

### 8.1 What was added

| File | Tests | Covers |
|---|---|---|
| `lib/schemas/contact-mutation.test.ts` | 22 | E.164 canonicalization (mirrors `ContactValidation.canonicalizePhoneNumber`); blank-name → omitted, not `""`; name/email `@Size` limits; **the `attributes` boundary** — object parsing, blank → `undefined`, malformed JSON, array rejection, scalar rejection, round-trip; `formatAttributesForInput` never throws; create and update share one field set |
| `lib/schemas/contact-group-mutation.test.ts` | 9 | name required and `@Size(150)`; description `@Size(5000)`; blank description omitted rather than `""`; name trimmed; **no `tenantId` and no `memberCount` field**; create and update produce the same payload |
| `lib/api/contacts.test.ts` | 15 | URL is group-scoped (no top-level `/contacts`); page/size/sort serialisation; search omitted when empty and forwarded when set; items and server pagination passed through untouched; empty envelope → empty list; **PUT not PATCH**; delete tolerates the bare 204; import uses the `file` field name and multipart; export is a raw download and uses the server filename; `forGroup` is a prefix of every list key; detail keyed per (group, contact); sort whitelist pinned |
| `lib/api/contact-groups.test.ts` | 9 | list URL and sort; `memberCount` returned, not counted; detail unwrapped; create sends **only** name and description; PUT; delete tolerates 204; **the group module no longer re-exports import/export**; sort whitelist; key prefixes |
| `lib/api/contact-group-members.test.ts` | 11 | roster URL and params; pagination passed through; sort whitelist and the **ASC** default; member detail; **add reports `created: true` on 201 and `false` on 200**; single remove tolerates 204; batch add returns per-item outcomes in order; **batch remove is a DELETE with a body returning 200**, and `NOT_FOUND` is a success; key prefixes |
| `lib/auth/contact-gates.test.ts` | 23 | each action's gate; **import is not `CONTACT_IMPORT` and export is not `CONTACT_EXPORT`**; view-only user is denied every write, membership and import; manage-only user is denied reads; both-keys user may do everything; the unenforced keys alone grant nothing; null/absent capabilities deny everything; **create requires a tenant context** — tenant admin yes, without `CONTACT_MANAGE` no, reseller admin no, super admin no, and a reseller is still allowed every other write |
| `lib/api/contact-links.test.ts` | 18 | route inventory; the F2 contact-detail route exists; **no `/contact-groups/{id}/edit` route**; **no top-level `/contacts` route**; every internal contact `href` and `router.push` matches a real route, matching literal segments exactly; **no file reads `.contactGroupId`**; no `href` interpolates `undefined` or `null` |

**107 new tests. 190 total in 14 files, all passing.**

### 8.2 What was NOT tested, and why

- **No component render tests.** F1 deliberately deferred jsdom and Testing
  Library, and F2 does not need them: the F2 logic that could drift is pure
  (schemas, payload builders, request shapes, capability decisions, URL
  construction), and all of it is now covered. Component rendering would test the
  shadcn primitives, not this domain. **F2 confirms the F1 decision to defer
  jsdom**; it should be revisited when a phase's logic genuinely cannot be
  reached without a DOM, and not before.
- **No empty/forbidden/error UI state tests** for the same reason. The state
  components are shared F1 infrastructure, not F2 code.
- **No live backend test.** Nothing here is integration-tested. See §9.

### 8.3 What "verified" means in this phase

| Claim type | Applies to | Source |
|---|---|---|
| **contract inspected** | every endpoint, DTO, capability, semantic and scope rule in §2 | reading `ContactGroupController`, `ContactGroupService`, `ContactGroupMemberService`, `ContactGroupAccess`, `ContactIdentityService`, `ContactMapper`, `ContactGroupMapper`, `ContactValidation`, the DTO records, `V1` |
| **unit tested** | the request shapes, payload builders, schema behaviour, capability decisions and URL construction in §8.1 | the 107 tests above, with the API client mocked |
| **integration tested** | **nothing** | — |
| **manual tested** | **nothing** | — |

The tests in §8.1 mock the API boundary. They are evidence about **what the
frontend sends and how it interprets a response shape**; they are **not**
evidence that the backend behaves that way. The two are kept distinct
throughout this document, and the §2 tables are the authority for the backend
side.

---

## 9. Validation

Exact commands and results, run in `frontend/`:

| Command | Result |
|---|---|
| `npm run typecheck` (`tsc --noEmit`) | **exit 0**, no errors |
| `npm run lint` (`eslint`) | **0 errors**, 5 warnings |
| `npm run test` (`vitest run`) | **190 passed / 190**, 14 files, ~4 s |
| `npm run build` (`next build`, Next.js 16.2.10, Turbopack) | **success** — compiled 14.7 s, TypeScript 29.0 s, 8 static pages, **23 routes** |
| `npm run validate` | **pass** (typecheck && lint && test) |

**The 5 lint warnings are pre-existing** and unchanged from F1. All five are in
`components/campaigns/` and `components/tts-templates/`: React Hook Form
`watch()` calls in the campaign and TTS dialogs, and a TanStack Table
`getCoreRowModel` call in `call-attempt-table.tsx`. **None is in a contacts or
contact-groups file.** F2's two tables carry the same
`eslint-disable-next-line react-hooks/incompatible-library` the existing campaign
table uses, for the same reason — the rule suppresses a memoization advisory,
not a correctness problem.

**Route count went from 22 to 23** — the new
`/contact-groups/[contactGroupId]/contacts/[contactId]`. The build output was
inspected and lists it as `ƒ /contact-groups/[contactGroupId]/contacts/[contactId]`.

**Host memory.** F1 recorded that `next build` is memory-fragile on this host
(5.9 GB, near-zero free) and that a clean `HEAD` worktree then the F1 tree both
built with no code change. F2's build passed on the first attempt with no
configuration change and no retry, so no host-resource issue arose and none is
claimed or worked around.

**Manual / live validation: NOT PERFORMED.** No backend was running — the stack
needs Postgres, Redis and FreeSWITCH, none of which is available in this
environment. **No claim in this document rests on observed backend behaviour.**
Every backend statement in §2 is attributed to a specific file and line range
and is marked as source inspection. The mocked tests are labelled as such
throughout.

---

## 10. Known Limitations

### Not supported by the backend

| Item | Detail |
|---|---|
| **Tenant-wide contact search** | No `GET /api/v1/contacts` and no search endpoint. "Add existing contact" can only source ids from rosters the caller can already read, or from a pasted id. §7.3. |
| **Move a contact between groups** | No such endpoint. It would be delete + add, and the delete is destructive. Not implemented — a UI that offered it would be inventing a workflow. |
| **Replace group membership** | No `PUT /{id}/members`. Adding and removing are separate operations. Not simulated. |
| **Bulk contact delete** | No batch delete for contacts. Batch exists only for membership add/remove. Not simulated. |
| **Editing a membership** | `ContactGroupMemberResponse` is documented as an immutable relationship row with no mutable fields, and there is no update endpoint. |
| **Renaming/reordering a group** | No position/priority field on `ContactGroupResponse`. |
| **Contact status / lifecycle** | `ContactResponse` has no status field. Nothing is shown because nothing exists. |
| **Group status / lifecycle** | Same. |

### Backend blockers

None newly introduced. Two pre-existing items from F1 are unchanged and neither
touches this domain:

- **IVR capability seeding.** `IvrTreeService` enforces `IVR_VIEW`/`IVR_MANAGE`
  but no migration seeds them, so every IVR endpoint 403s for every role.
  Unrelated to Contacts.
- **Untracked `V55__execution_snapshot_integration_config.sql`.** A campaign
  concern.

### Product gaps found by reading the code (no code changed)

| Gap | Detail | Recommended owner action |
|---|---|---|
| **`RESELLER_ADMIN` and `SUPER_ADMIN` cannot create a contact group** | `createGroup` requires a tenant context, and neither role has one. Both hold `CONTACT_MANAGE` (V1). F2 hides the button; the underlying gap remains. | Product: is a reseller expected to create groups for a managed tenant? If yes, the backend needs a tenant selector that is **authorised**, not trusted from the request. If no, this is intended and the UI is now correct. |
| **Stale 409 annotation on group delete** | `ContactGroupController` L130-134 advertises a conflict the service cannot produce. Any client generated from `/v3/api-docs` will handle a response that never arrives. | Backend: correct the annotation to 204-only, or add the check the annotation describes. F2 documented the actual behaviour; the annotation is still wrong. |

### Deferred to a later phase

- **jsdom / Testing Library** for component and state tests. F2 confirms the F1
  deferral rather than reversing it.
- **OpenAPI drift check.** Still not added; still would have caught the stale
  409 and most of the F0 drift. Should land before F10.
- **Campaign contact selection.** Out of F2 scope by the brief. F3+ should reuse
  `contactGroupsKeys` and the gate table; the group list already exposes
  `memberCount` and a contacts link, which is what a selector needs.

### Frontend limitations (accepted, not defects)

- **Picker page limit.** "Add existing contact" shows the first 100 contacts of
  the chosen source group and says so when there are more. Paging a picker adds
  UI complexity for a rare case; the pasted-id path covers the gap.
- **No `PATCH`.** Updates use `PUT` because the backend declares `@PutMapping`.
  There is no partial-update endpoint to use.
- **Empty-string semantics are client-normalised.** `firstName` is stored as
  `trim()` without blank-to-null, so the client maps blank to omitted. This is
  correct and necessary, but it is a client-side workaround for an inconsistent
  mapper — worth a backend note.

---

## 11. F3 Readiness

**READY** for Audio & TTS, with four specific things to carry forward.

### What F3 inherits

1. **The enforced-capability discipline.** `contact-gates.ts` is the pattern: one
   table per domain, recording the key the **service** checks rather than the key
   the catalogue happens to seed, with the call site in a comment. Audio has the
   same trap — `AUDIO_VIEW`/`AUDIO_MANAGE`/`AUDIO_APPROVE` are seeded, and F1
   already found that `RESELLER_ADMIN` holds `AUDIO_APPROVE` but **not**
   `AUDIO_MANAGE`. F3 should verify the same way before gating anything.
2. **`unwrapDownload` exists.** Audio or TTS export can reuse it and take the
   server's filename instead of inventing one. `unwrapBlob` is unchanged for
   callers that only need bytes.
3. **`applyServerFieldErrors` is the one field-error mapper.** F2 removed two
   hand-rolled copies of the loop. F3 must not add a third.
4. **The shared states.** `QueryErrorState` and `EmptyState` are in
   `components/common/query-state.tsx` and already handle 403-not-401, 404
   wording for a foreign resource, and the empty-vs-filtered-empty distinction.

### What F3 needs from Contacts & Contact Groups

| Need | Available | Note |
|---|---|---|
| **Group list for a picker** | `GET /contact-groups` with `page`/`size`/`sort`/`search` | Returns `memberCount` per group, so a picker can show sizes without an N+1. |
| **Group detail with size** | `GET /contact-groups/{id}` | `memberCount` is the backend's own count. |
| **Roster of a chosen group** | `GET /{id}/members` | Paged, searchable, sortable, with the embedded live contact. |
| **Contacts CRUD for a group** | `/contacts` | Create is find-or-create on `(tenant, phone)`. |
| **"Is this group already used?"** | — | **Not available.** `memberCount > 0` is the only signal. If F3 needs to know whether a group is referenced by a campaign, there is no endpoint. |

### Watch items for F3

- **A campaign's contact selection is NOT implemented and was not in F2 scope.**
  When it is built, the identity-vs-membership distinction is the thing to get
  right: a campaign targets a **group**, and the group's audience is its live
  **memberships**. Selecting individual contacts is not a concept the backend
  has.
- **`removeContactGroupMember` vs `deleteContact` must stay distinct** in any
  shared dialog or menu. F2's two dialogs and their copy are the reference.
- **The 404-means-out-of-scope rule applies to groups too.** A group outside the
  caller's boundary is 404, not 403, and the shared `QueryErrorState` already
  words it correctly.

---

## 12. Definition of Done

**Contract**
- [x] Contact endpoints verified against backend
- [x] Contact Group endpoints verified against backend
- [x] Request DTOs match backend
- [x] Response DTOs match backend
- [x] Pagination matches backend (0-based, clamped 1..100, real metadata, all three resources)
- [x] Filtering/search matches backend (correct fields, correct defaults, ASC members default)
- [x] Membership operations match backend (all six, including DELETE-with-body batch)

**Contacts**
- [x] List works
- [x] Detail works (route created; it did not exist)
- [x] Create works (find-or-create semantics explained in the UI)
- [x] Edit works (PUT; replace semantics explained)
- [x] Delete works (consequence stated; distinguished from membership removal)
- [x] Validation works (E.164, name/email sizes, attributes JSON)
- [x] Error handling works (shared mapper; 400 field errors, 409 conflict)
- [x] Loading / empty / filtered-empty / forbidden states work

**Contact Groups**
- [x] List works (with `memberCount`)
- [x] Detail works
- [x] Create works (tenant-context precondition handled)
- [x] Edit works
- [x] Delete works (consequence stated; no phantom 409)
- [x] Membership management works (add single/batch, remove single/batch, roster)
- [x] Validation works
- [x] Error handling works

**Authorization**
- [x] Actual backend capabilities used
- [x] Unauthorized actions are gated
- [x] 403 is not treated as 401
- [x] No permissions invented (no `CONTACT_IMPORT`/`CONTACT_EXPORT` substitution)
- [x] Null/absent capabilities deny rather than allow

**Tenant**
- [x] Operating context reused
- [x] No arbitrary tenant IDs accepted as authorization
- [x] Cache isolation preserved (F1 mechanism; F2 tightened invalidation)
- [x] No fake reseller tenant selector

**Quality**
- [x] No phantom fields (`.contactGroupId` reads are a failing test)
- [x] No undefined links (every internal contact link is a test case)
- [x] No duplicate API calls (`memberCount` is server-computed; no N+1)
- [x] No direct component-level API calls
- [x] No unrelated domain changes (16 modified files: 15 in this domain plus `lib/api/transport.ts`, the one shared module F2 genuinely needed — additive only)

**Tests**
- [x] F1 tests still pass (83 → 190)
- [x] F2 tests pass (107)
- [x] Typecheck passes
- [x] Lint passes (0 errors)
- [x] Build passes (23 routes)

**Documentation**
- [x] `docs/frontend/F2-CONTACTS-CONTACT-GROUPS.md` created
- [x] Backend contract documented (§2)
- [x] Changes documented (§3)
- [x] Limitations documented (§10, split by cause)
- [x] F3 dependencies documented (§11)

---

## 13. Final Report

```text
F2 — CONTACTS & CONTACT GROUPS

Status:
PASS WITH BLOCKERS
  (No blocker prevents Contacts or Contact Groups from being implemented
   correctly. Two product decisions are still open; both are documented and
   both are handled safely in the UI without guessing.)

Backend contract verified:
- 18 endpoints: 5 group, 5 contact, 2 bulk, 6 membership.
- DTOs: ContactResponse (9 fields, NO group id, NO status),
  ContactGroupResponse (7 fields, INCLUDES server-computed memberCount),
  ContactGroupMemberResponse (membership row with its own memberId/groupId).
- CreateContactGroupRequest / UpdateContactGroupRequest and
  CreateContactRequest / UpdateContactRequest are each structurally identical
  pairs; neither group pair has a tenantId.
- Capabilities: only CONTACT_VIEW and CONTACT_MANAGE are enforced.
  CONTACT_IMPORT and CONTACT_EXPORT are seeded but never checked.
- Pagination: 0-based, size clamped 1..100, real PaginationMetadata on all
  three list endpoints.
- Sorting: name/createdAt/updatedAt (default createdAt DESC);
  firstName/phoneNumber/createdAt (default firstName ASC);
  createdAt/firstName/phoneNumber (default createdAt ASC).
- Search: name (groups); firstName/lastName/phoneNumber (contacts AND
  members). Email is NOT searched — the F0 placeholder was wrong.
- Membership: 6 operations, all previously unused. Add is idempotent with
  201=created / 200=already-member. Batch never fail-fasts. Batch remove is a
  DELETE with a request body returning 200.
- Delete: group delete removes MEMBERSHIPS and KEEPS CONTACTS. The controller's
  409 annotation is stale and was treated as wrong.

Contacts implemented:
- List (search, sort, pagination, responsive), detail route (NEW), create,
  edit, delete, import (csv/xlsx/json), export (csv/xlsx/json).

Contact Groups implemented:
- List (with server memberCount), detail, create, edit, delete, import, export.

Membership implemented:
- Roster panel with search/sort/pagination and a "Joined" column.
- Add existing contact (single + batch) with per-item outcome reporting.
- Remove one or many: one `batchRemove` mutation dispatches to the single
  endpoint for one id and the batch endpoint for many, rendering both from the
  same per-item result. A one-selection remove is as reliable as a
  fifty-selection remove.
- Identity delete vs membership removal separated everywhere.

Authorization:
- CONTACT_VIEW: view groups, contacts, roster, export.
- CONTACT_MANAGE: all group/contact/membership writes, and import.
- NOT CONTACT_IMPORT, NOT CONTACT_EXPORT — seeded but unenforced.
- Create Group additionally requires a TENANT context, because
  createGroup throws when Scope.tenantId is null. homeType and Scope.tenantId
  are the same OrganizationalHomeEntity row, so this is an exact equivalence.
- 403 renders as forbidden via the shared QueryErrorState; never as empty,
  never as a sign-in redirect.

Operating context:
- F1 model reused unchanged. No tenant selector, no tenantId input, no second
  context mechanism. F1's cache isolation reused; F2 tightened invalidation
  (forGroup prefix + group invalidation on every membership change).

Contract corrections:
 1. Contact "View" link was a 404 — route did not exist. Route added.
 2. Group "Edit" link pointed at /contact-groups/{id}/edit — never existed.
 3. `attributes` was a textarea string validated against z.record — the field
    could never validate or save.
 4. Contacts list basePath was a literal "[contactGroupId]" — every search,
    page and sort navigation was a 404.
 5. Export button was wired to onExport={() => {}} — it did nothing.
 6. No capability gating anywhere in the domain.
 7. Raw tenantId UUID rendered as group information.
 8. Hand-copied 403 block and window.location.href navigation.
 9. Delete confirm text claimed a "must be empty" requirement that does not
    exist (traced to a stale 409 annotation).
10. Identity delete and membership removal were not distinguished.
11. importContacts/exportContacts duplicated byte-for-byte across two API
    modules; the import/export dialog duplicated across two components.
12. Search placeholders promised an `email` search the backend does not do.
    Plus 3 wrong source comments corrected (partial update, phantom 409,
    unmarked @NotBlank).

Tests:
- 107 new, 190 total, 14 files, all passing.
- 7 new test files: contact-mutation schema (22), contact-group-mutation
  schema (9), contacts API (15), contact-groups API (9), members API (11),
  contact gates (23), contact links (18).
- No jsdom. F2 confirms the F1 deferral.
- contact-links.test.ts was verified to actually fail on the reintroduced F0
  dead link before being accepted.

Typecheck:
- exit 0, no errors.

Lint:
- 0 errors, 5 warnings — all 5 pre-existing (RHF watch() and TanStack Table),
  none in F2 code.

Build:
- SUCCESS. Next.js 16.2.10, Turbopack. 23 routes (was 22). No retry needed, so
  no host-memory issue arose and none is worked around.

Manual validation:
- NOT PERFORMED. No backend running (needs Postgres, Redis, FreeSWITCH).
- No claim in this document rests on observed backend behaviour. Every
  backend statement is attributed to a file and line range. Mocked tests are
  labelled as unit tests, not integration tests.

Backend blockers:
- None introduced by F2. F2 wrote zero backend files.
- PRE-EXISTING, unchanged: IVR capabilities are enforced but never seeded, so
  every IVR endpoint 403s for every role. Unrelated to this domain.

Product decisions needed (no code changed pending these):
- Can a RESELLER_ADMIN be expected to create a contact group for a managed
  tenant? Today the answer is no, for both the reseller and the super admin.
  F2 hides the button; the underlying gap is not a frontend fix.
- The stale 409 on group delete should be corrected in the controller
  annotation, or the check it describes should be added.

Deferred items:
- jsdom / Testing Library.
- OpenAPI drift check (would have caught the stale 409 and most F0 drift).
- Campaign contact selection (F3+; must reuse this phase's group/membership
  model rather than inventing contact-level selection).

Files changed: 16
Files added: 18
Files deleted: 1

F3 readiness:
READY

Reason:
F2 proved the F1 foundation against a real domain, and the proof included a
domain that is harder than F3's: two related-but-distinct resources, a
non-obvious delete asymmetry, six previously-unused endpoints, two seeded
capabilities that the backend never enforces, and one endpoint whose
precondition is a tenant context rather than a capability. Everything a later
phase needs from Contacts and Contact Groups is present and typed: the group
list, the group detail with a server-computed memberCount, the paged roster, the
contact CRUD surface, and a gate table that records what the backend actually
checks.

The one thing F3 cannot get from this domain is whether a contact group is
already referenced by a campaign. No endpoint answers that, and no frontend
workaround should pretend otherwise.
```

---

## Appendix A — Backend files read during F2

```
backend/src/main/java/com/shivang/obd/contact/
  ContactGroupController.java          18 mappings; the stale 409 annotation
  ContactGroupService.java             list/get/create/update/delete, contacts,
                                       import, export, memberCounts
  ContactGroupMemberService.java       the 6 membership operations, roster
  ContactGroupAccess.java              scope resolution, 404 cloaking
  ContactIdentityService.java          (tenant, phone) identity, typed 409
  ContactMapper.java                   applyCommon: the per-field blank rules
  ContactGroupMapper.java              applyCommon: verbatim name/description
  ContactValidation.java               E164_REGEX, EMAIL_REGEX, canonicalizer
  ContactSpecifications.java           contact search fields
  ContactGroupSpecifications.java      group search + tenant boundaries
  ContactGroupMemberRepository.java    findRosterPage, findCountsByGroupIds
  dto/*.java                           every record, verbatim
backend/src/main/resources/db/migration/
  V1__create_multi_tenant_authorization_foundation.sql   4 contact keys, role grants
backend/src/main/java/com/shivang/obd/
  security/config/OrganizationContextPopulationFilter.java
  tenant/TenantMembershipResolverAdapter.java
  authz/context/OrganizationalHomeResolver.java
  authz/AuthorizationService.java
  UserService.java
```

## Appendix B — Finding classification index

| # | Finding | Class |
|---|---|---|
| 1 | Contact detail route missing; View link 404s | FIXED |
| 2 | Group Edit link to a non-existent route | FIXED |
| 3 | `attributes` textarea bound to a `z.record` schema | FIXED |
| 4 | Contacts list `basePath` was a literal `[contactGroupId]` | FIXED |
| 5 | Export button wired to a no-op | FIXED |
| 6 | No capability gating in the domain | FIXED |
| 7 | Raw `tenantId` rendered as a group field | FIXED |
| 8 | Hand-copied 403 block; `window.location.href` | FIXED |
| 9 | Delete confirm text asserted a non-existent requirement | FIXED |
| 10 | Identity delete vs membership removal indistinguishable | FIXED |
| 11 | Duplicated API functions and dialog | FIXED |
| 12 | Search placeholder promised an unsearched field | FIXED |
| 13 | 409 on group delete | BACKEND BLOCKER (stale annotation; behaviour documented, backend not modified) |
| 14 | Reseller/super admin cannot create a group | BACKEND BLOCKER (product decision; UI handles safely) |
| 15 | No tenant-wide contact search | NOT SUPPORTED |
| 16 | No move-between-groups, no batch contact delete, no membership update, no group reorder | NOT SUPPORTED |
| 17 | `ContactResponse` has no `contactGroupId` | VERIFIED (F1 removal correct; now a failing test to reintroduce) |
| 18 | `ContactGroupResponse.memberCount` is server-computed | VERIFIED (used, never recomputed) |
| 19 | Search excludes `email` on both contacts and members | VERIFIED |
| 20 | Members default sort is ASC | VERIFIED |
| 21 | Batch remove is a DELETE with a body returning 200 | VERIFIED |
| 22 | Add returns 201 vs 200 to signal created vs existing | VERIFIED |
| 23 | Import is per-row with partial success; errors capped at 100 | VERIFIED |
| 24 | Export is a raw file with a server filename | VERIFIED |
| 25 | Group create needs a tenant context | VERIFIED |
| 26 | Foreign group and missing group are both 404 | VERIFIED |
| 27 | Component/state UI tests | DEFERRED (jsdom deferred in F1, reconfirmed in F2) |
| 28 | OpenAPI drift check | DEFERRED (out of F2 scope; still recommended) |
| 29 | Campaign contact selection | DEFERRED (out of F2 scope; F3+) |
| 30 | IVR capabilities enforced but unseeded | BACKEND BLOCKER (pre-existing from F1, unrelated) |

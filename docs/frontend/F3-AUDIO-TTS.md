# F3 — Audio Assets & TTS

**Phase:** F3
**Predecessors:** F0 (audit), F1 (foundation), F2 (Contacts & Contact Groups)
**Status:** IMPLEMENTATION COMPLETE
**Scope:** Audio Assets and TTS Templates only. No other domain was migrated.

---

## Executive Summary

F3 migrated Audio Assets and TTS Templates onto the F1 architecture, after
verifying every endpoint, DTO, capability, lifecycle rule and scope rule against
the backend source.

**The two headline findings are absences, and both are load-bearing.**

**1. There is no way to hear an audio asset.** `AudioAssetController` exposes
seven mappings and not one returns bytes. There is no download, no preview, no
playback, no signed URL, and `AudioAssetResponse` carries no URL field.
`MediaUriResolver` exists and looks like the answer, but it is server-side only:
it turns the stored `storageReference` into a **FreeSWITCH filesystem path** for
the telephony engine. The browser has no route to the audio. F3 therefore ships
**no player, no `<audio>` element, no "open in new tab", and no blob fetch** —
and the recording page says so plainly instead of offering a dead control. A
client that "helpfully" rendered a player would be inventing an endpoint.

**2. There is no TTS generation.** The controller's own tag says *"Rendering/
synthesis belongs to the future execution layer"*, and the `tts` package contains
no provider, no voice model and no render method. A TTS template is a reusable
**text definition**; nothing in this API turns it into sound. F3 builds no
generate, preview or synthesise UI, and no "this template produces an audio
asset" flow.

Everything else in these two domains is implemented: audio upload over the real
multipart contract, the full three-state approval workflow with `AUDIO_APPROVE`
kept distinct from `AUDIO_MANAGE`, TTS `GLOBAL`/`TENANT` scope represented
explicitly, and capability gating derived from the call sites the services
actually use.

**F3 wrote zero backend files.**

Twelve defects were found and fixed, including a create form that asked a user
to hand-type a 64-character SHA-256, a hard-coded `scope: "TENANT"` that made
`GLOBAL` templates unreachable, four legal approval transitions that the UI could
not express, an approval-reset warning that was wrong most of the time, two
duplicate status badges, and a fabricated pagination object that rendered a
populated list as "0 recordings".

---

## Baseline

| | |
|---|---|
| **Frontend HEAD** | `3a89e5cf8861d3ecb574757c1a66b3552c458745` |
| **Backend working tree at F3 start** | 33 modified files, 12 untracked — **all pre-existing and concurrent with F3** |
| **Backend files modified by F3** | **0** |
| **Frontend files modified by F3** | 15 |
| **Frontend files added by F3** | 16 |
| **Frontend files deleted by F3** | 3 |

The 33 modified backend files are campaign/telephony work with timestamps from
before F3 opened. The audio and TTS packages were last touched on **09-27 20:41**
(`MediaUriResolver`) and **09-27 20:27** (`AudioStorageProperties`) — the two
newest files in either package, and both *predate* F3 opening, which is why
§5.2.4 could rely on `MediaUriResolver` being server-side. The controllers and
services themselves are older: **09-24** in both packages. **No contract F3
depends on moved during F3**, and every affected file was re-read at the end of
the phase before the conclusions above were finalised.

---

## Audit Findings

### 5.1 Repository Map

#### Audio Assets

| Area | Location | Verdict | Why |
|---|---|---|---|
| Routes | `app/(platform)/audio-assets/page.tsx`, `app/(platform)/audio-assets/[audioAssetId]/page.tsx` | **KEEP** | Both are real, backed by real endpoints, already `force-dynamic`. |
| List view | `components/audio-assets/audio-assets-view.tsx` | **REBUILD** | `/* eslint-disable no-explicit-any */`, `useUrlListState(LIST_CONFIG as any)`, `as any` on the resolver and every `setError`, a `confirm()` delete, unguarded approve/reject, no page reset on status change, and a search placeholder that did not describe the real search. |
| Table | `components/audio-assets/audio-asset-table.tsx` | **REBUILD** | Showed Approve/Reject only for `PENDING_APPROVAL` (three legal transitions hidden), no `aria-label` on sort buttons, no `aria-hidden` on icons, no `updatedAt` null handling, no `fileSize` column. |
| Detail | `components/audio-assets/audio-asset-detail-view.tsx` | **REBUILD** | Rendered every error as one string — "Not found or no permission." — for 403, 404, 500 and a network drop, with no retry. `q.data!` non-null assertion. Rendered `storageReference` and a raw `tenantId`. No actions at all. |
| Create | `components/audio-assets/create-audio-asset-dialog.tsx` | **DELETE** | Asked the user for `fileName`, `contentType`, `fileSize`, `durationSeconds`, a 64-char SHA-256 `checksum` and a `storageReference`. All derived server-side by `/upload`; none verifiable by a human. |
| Upload | — | **CREATE** | Did not exist. `uploadAudioAsset` existed in the API module with no UI. |
| Edit | `components/audio-assets/edit-audio-asset-dialog.tsx` | **REBUILD** | `asset!` assertion, a stray `// ponytail:` comment, `as any` on the resolver, no `key` on the parent so switching records kept stale form values, no `DialogDescription`. |
| Delete | — | **CREATE** | Was a bare `confirm()`. |
| Approve / Reject | — | **CREATE** | Were two unguarded button clicks with a toast. |
| API | `lib/api/audio-assets.ts` | **REBUILD** | Fabricated `totalElements: 0` when the envelope had no pagination; documented search as `name` only (it is `name` **and** `fileName`); exposed `createAudioAsset`. |
| Schemas | `lib/schemas/audio-asset-mutation.ts` | **REBUILD** | All of it existed to validate fields the upload path does not accept. |
| Types | `lib/api/contracts.ts` (Audio block) | **REBUILD** | `fileSize: number \| null` (column is `NOT NULL`); `updatedAt: string` (column is nullable); a comment claiming `AudioStorageException` surfaces as **503** (it is 500); `CreateAudioAssetPayload` retained. |
| Status badge | `components/common/audio-asset-status-badge.tsx` | **DELETE** | Byte-equivalent to the TTS badge. |

#### TTS Templates

| Area | Location | Verdict | Why |
|---|---|---|---|
| Routes | `app/(platform)/tts-templates/page.tsx`, `app/(platform)/tts-templates/[ttsTemplateId]/page.tsx` | **KEEP** | Both real. |
| List view | `components/tts-templates/tts-templates-view.tsx` | **REBUILD** | `as any`, `confirm()` delete, unguarded approve/reject, status filter did not reset the page, create gated on a bare capability. |
| Table | `components/tts-templates/tts-template-table.tsx` | **REBUILD** | Scope rendered as bare grey text; approve/reject only for `PENDING_APPROVAL`; actions gated on a single boolean that cannot express the per-scope rule. |
| Detail | `components/tts-templates/tts-template-detail-view.tsx` | **REBUILD** | **Never rendered `scope` at all.** Rendered `tenantId`, which is `null` for a global template — so a global template showed a blank "Tenant:" line. Conflated every error into one string. No actions. |
| Create | `components/tts-templates/create-tts-template-dialog.tsx` | **REBUILD** | `as any` on the resolver, `as any` on every `useFieldArray`/`register`/`watch`/`setValue`, and `scope: "TENANT"` **hard-coded** in the payload builder — making `TtsTemplateScope.GLOBAL` unreachable from the UI. |
| Edit | `components/tts-templates/edit-tts-template-dialog.tsx` | **REBUILD** | `template!` assertions, `as any` throughout, and an approval-reset warning that fired unconditionally and was wrong most of the time. |
| Delete / Approve / Reject | — | **CREATE** | `confirm()` and two unguarded clicks. |
| Variables editor | duplicated in both dialogs | **EXTRACT** | Two copies of the same field array, both `as any`. |
| Scope badge | — | **CREATE** | Scope was plain muted text, so it read as decoration. |
| API | `lib/api/tts-templates.ts` | **REBUILD** | Fabricated pagination; carried two dead helpers (`partitionTtsTemplatesByScope`, `canManageTtsTemplate` — the latter returned `true` for any tenant row **regardless of capability**). |
| Schemas | `lib/schemas/tts-template-mutation.ts` | **REBUILD** | Ported only a subset of `TtsTemplateValidation`; a heuristic stray-brace regex that missed `{{{name}}`; `templateText` trimmed although the backend stores it verbatim; `variables as TtsTemplateVariable[]` casts defeating the type check. |
| Status badge | `components/common/tts-template-status-badge.tsx` | **DELETE** | Duplicate of the audio badge. |

#### Shared

| Area | Location | Verdict | Why |
|---|---|---|---|
| Approval lifecycle | — | **CREATE** | Two services implement an identical three-state machine. Modelled once in `lib/domain/approval.ts` so the UI's "which buttons do I render" decision has one source. |
| Approval transition dialog | — | **CREATE** | The interaction is genuinely one thing across both domains. |
| Approval status badge | — | **CREATE** | Replaces the two duplicates. |
| Capability gates | — | **CREATE** | `lib/auth/content-gates.ts`, following F2's `contact-gates.ts` pattern. |
| Transport | `lib/api/transport.ts` | **KEEP, unchanged** | Both domains return JSON envelopes only. `unwrapDownload`/`unwrapBlob` are for the contacts export and are deliberately not used here — there is nothing binary to fetch. |
| Query keys, `QueryErrorState`, `applyServerFieldErrors`, `useUrlListState`, `useCan`, `useOperatingContext` | — | **KEEP, unchanged** | F1/F2 infrastructure, reused as-is. |

### 5.2 Backend Contract Map

Every row read from the backend source. Errors are the **actual** responses, not
the controller's `@ApiResponse` annotations — where those disagree, §5.2.1 says
so.

#### Audio Assets — `/api/v1/audio-assets`

| Method | Path | Request | Response | Errors | Capability | Scope |
|---|---|---|---|---|---|---|
| GET | `/audio-assets` | `page`, `size`, `sort[]`, `status`, `search` | `AudioAssetResponse[]` + `PaginationMetadata` | 400 invalid sort/status · 401 · 403 `AUDIO_VIEW` | `AUDIO_VIEW` | tenant / reseller-hierarchy / platform |
| GET | `/audio-assets/{id}` | — | `AudioAssetResponse` | 401 · 403 `AUDIO_VIEW` · **404 (404-cloaked)** | `AUDIO_VIEW` | visible asset |
| POST | `/audio-assets` | `CreateAudioAssetRequest` (JSON) | `AudioAssetResponse` 201 | 400 · 401 · 403 `AUDIO_MANAGE` · 400 "A tenant must be specified" | `AUDIO_MANAGE` **+ tenant context** | caller's own tenant |
| POST | `/audio-assets/upload` | multipart `name`, `description?`, `file` | `AudioAssetResponse` 201 | 400 unsupported/empty/oversized/unrecognised · 401 · 403 `AUDIO_MANAGE` · 400 "A tenant must be specified" · **500 storage disabled** | `AUDIO_MANAGE` **+ tenant context** | caller's own tenant |
| PUT | `/audio-assets/{id}` | `UpdateAudioAssetRequest` | `AudioAssetResponse` | 400 · 401 · 403 `AUDIO_MANAGE` · 404 | `AUDIO_MANAGE` | visible asset |
| DELETE | `/audio-assets/{id}` | — | 204, no body | 401 · 403 `AUDIO_MANAGE` · 404 | `AUDIO_MANAGE` | visible asset |
| PATCH | `/audio-assets/{id}/approve` | — | `AudioAssetResponse` | 401 · 403 `AUDIO_APPROVE` · 404 · **409 already APPROVED** | `AUDIO_APPROVE` | visible asset |
| PATCH | `/audio-assets/{id}/reject` | — | `AudioAssetResponse` | 401 · 403 `AUDIO_APPROVE` · 404 · **409 already REJECTED** | `AUDIO_APPROVE` | visible asset |

#### TTS Templates — `/api/v1/tts-templates`

| Method | Path | Request | Response | Errors | Capability | Scope |
|---|---|---|---|---|---|---|
| GET | `/tts-templates` | `page`, `size`, `sort[]`, `status`, `search` | `TtsTemplateResponse[]` + `PaginationMetadata` | 400 invalid sort/status · 401 · 403 `TTS_VIEW` | `TTS_VIEW` | tenant / reseller-hierarchy / platform — **returns a MERGED page** |
| GET | `/tts-templates/{id}` | — | `TtsTemplateResponse` | 401 · 403 `TTS_VIEW` · **404 (404-cloaked)** | `TTS_VIEW` | visible template |
| POST | `/tts-templates` | `CreateTtsTemplateRequest` | `TtsTemplateResponse` 201 | 400 schema/placeholders/undeclared · 400 GLOBAL-with-tenant · 400 "A tenant must be specified" · 401 · 403 `TTS_MANAGE` | `TTS_MANAGE`, at the scope the request selects | see §5.2.3 |
| PUT | `/tts-templates/{id}` | `UpdateTtsTemplateRequest` | `TtsTemplateResponse` | 400 · 401 · 403 `TTS_MANAGE` · 404 | `TTS_MANAGE` at `manageCheckFor` | GLOBAL ⇒ platform, TENANT ⇒ tenant |
| DELETE | `/tts-templates/{id}` | — | 204, no body | 401 · 403 `TTS_MANAGE` · 404 | `TTS_MANAGE` at `manageCheckFor` | GLOBAL ⇒ platform, TENANT ⇒ tenant |
| PATCH | `/tts-templates/{id}/approve` | — | `TtsTemplateResponse` | 401 · 403 `TTS_APPROVE` at `manageCheckFor` · 404 · **409 already APPROVED** | `TTS_APPROVE` | GLOBAL ⇒ platform, TENANT ⇒ tenant |
| PATCH | `/tts-templates/{id}/reject` | — | `TtsTemplateResponse` | 401 · 403 `TTS_APPROVE` at `manageCheckFor` · 404 · **409 already REJECTED** | `TTS_APPROVE` | GLOBAL ⇒ platform, TENANT ⇒ tenant |

**There is no audio download/preview/playback endpoint and no TTS
generate/synthesise endpoint.** These absences are load-bearing; see §5.2.4.

#### 5.2.1 Two controller annotations that are wrong

Both are documentation defects, documented and worked around — **no backend code
was changed**.

| Annotation | Claims | Reality |
|---|---|---|
| `POST /audio-assets/upload` → `409 "Audio storage is disabled"` | 409 Conflict | **500.** `AudioStorageException extends RuntimeException` and there is **no** `@ExceptionHandler` for it anywhere in `GlobalExceptionHandler`, so it falls through to the `@ExceptionHandler(Exception.class)` catch-all. This matters: `audio.storage.enabled` defaults to **false**, so an unconfigured deployment 500s on every upload. F1 recorded this as 503 in `contracts.ts`; that was also wrong and is corrected. |
| `POST /audio-assets` → `400 @Size(max=150) name` (implied by the DTO) | the request is validated | The **upload** endpoint carries no `@Valid` and no `@Size` on any `@RequestParam`. Only a blank-name check exists in the service. A 200-character name therefore passes every controller annotation and fails at `audio_assets.name VARCHAR(150)` — a **500**. F3 caps at 150 client-side and says why in the schema. |

#### 5.2.2 What is NOT validated on the upload path

`POST /api/v1/audio-assets/upload` is a `@PostMapping` with
`@RequestParam("name") String name` and no `@Valid`. The only in-code checks are
the blank-name check at `AudioAssetService.upload` L198-200 and
`AudioUploadValidator.validate`. The consequences:

| Field | Enforced where | Client behaviour |
|---|---|---|
| `name` | DB `VARCHAR(150) NOT NULL` — a longer value is a 500 | cap 150, documented as a **database** limit |
| `description` | nowhere — the column is `TEXT` | no length rule beyond a documented sanity cap |
| file size | `AudioUploadValidator`, `> maxFileSizeBytes` (default 5 MiB) | pre-flight at the default, stated as advisory |
| file type | magic bytes **and** the declared MIME must agree | extension + MIME advisory only, stated as such |

#### 5.2.3 How `scope` and `tenantId` resolve on create

VERIFIED `TtsTemplateService.create` L68-114, in order:

| # | `scope` | `tenantId` | Caller | Capability target | Status |
|---|---|---|---|---|---|
| 1 | `GLOBAL` | **must be absent** | any | `platformWide()` | `APPROVED` |
| 2 | `TENANT` (or omitted) | n/a | has a tenant context | `forTenant(own)` | `PENDING_APPROVAL` |
| 3 | `TENANT` | required | no tenant context | **`platformWide()`** | `APPROVED` |
| 4 | anything else | — | — | — | 400 "A tenant must be specified for this operation." |

`GLOBAL` **with** a `tenantId` is a 400 ("GLOBAL templates are platform-owned
and must not reference a tenant"). Case 3's target must exist and be `ACTIVE`.

Case 3 is the crucial one for authorization: it is checked with
`platformWide()`, and a `RESELLER`-scoped assignment does **not** cover
`platformWide()` (`AuthorizationService.covers` L144). So **a reseller
administrator cannot create a template at all**, despite V20 granting it
`TTS_MANAGE`. F3 shows them no Create button rather than a form that 400s on
every submit.

#### 5.2.4 The two absences, stated precisely

**No audio retrieval.** `AudioAssetController` declares `@RequestMapping
("/api/v1/audio-assets")` with seven mappings, and none returns bytes or a URL.
`AudioAssetResponse` has no `mediaUri`, `url` or `downloadUrl`. `MediaUriResolver`
(Javadoc, 09-24) exists solely to produce "a URI FreeSWITCH can actually read",
rooted at `audio.storage.freeswitch-media-root`, and is consumed only by the
campaign execution path. **A browser cannot reach the audio through this API.**

**No TTS synthesis.** `TtsTemplateController`'s `@Tag` says "Rendering/synthesis
belongs to the future execution layer." The `tts` package contains no provider
interface, no voice model and no render method. A template is a text definition;
the platform speaks it during a call through the telephony layer, not through
this API.

### 5.3 DTO Matrix

Frontend types represent **API DTOs**, never database entities. Every entry below
is a field the backend actually sends.

#### `AudioAssetResponse` — 13 fields

| Field | Type | Nullable | Source / note |
|---|---|---|---|
| `id` | `string` (UUID) | no | |
| `tenantId` | `string` | no | `tenant_id NOT NULL`; audio has no platform-owned form |
| `name` | `string` | no | `VARCHAR(150)` |
| `description` | `string` | **yes** | `TEXT` |
| `fileName` | `string` | no | `VARCHAR(255)`; sanitised server-side to a basename |
| `contentType` | `string` | no | `VARCHAR(100)`; **canonicalised** to `audio/wav` or `audio/mpeg` |
| `fileSize` | `number` | **no** | **F3 correction.** `nullable = false`, `BIGINT NOT NULL CHECK (> 0)` |
| `durationSeconds` | `number` | **yes** | `INTEGER`; only derived for WAV, best-effort, never fatal |
| `checksum` | `string` | **yes** | `VARCHAR(64)`; SHA-256 of the uploaded bytes |
| `storageReference` | `string` | **yes** | `VARCHAR(500)`; **infrastructure**, typed but never rendered |
| `status` | `AudioAssetStatus` | no | `VARCHAR(20)` + CHECK |
| `createdAt` | `string` | no | `TIMESTAMPTZ NOT NULL` |
| `updatedAt` | `string` | **yes** | **F3 correction.** `TIMESTAMPTZ` nullable; `@LastModifiedDate` stays null until first modified |

`AudioAssetStatus` = `PENDING_APPROVAL | APPROVED | REJECTED`. `DRAFT` and
`ARCHIVED` were considered and **deliberately deferred** by the backend
Javadoc, so the frontend does not render them.

#### `UpdateAudioAssetRequest` — 2 fields

`name` (`@NotBlank @Size(max=150)`), `description` (`@Size(max=5000)`).

`fileName`, `contentType`, `fileSize`, `durationSeconds`, `checksum` and
`storageReference` are **not updatable**. F3's edit form has exactly two inputs;
`updateAudioAssetSchema.shape` is asserted to be `["description", "name"]` in a
test, so a future field cannot be added without noticing.

#### `TtsTemplateResponse` — 10 fields

| Field | Type | Nullable | Source / note |
|---|---|---|---|
| `id` | `string` | no | |
| `tenantId` | `string` | **yes** | `null` **iff** `scope == "GLOBAL"` (V42 CHECK) |
| `name` | `string` | no | `VARCHAR(150)` |
| `description` | `string` | **yes** | blank-to-null in the mapper |
| `templateText` | `string` | no | stored **verbatim** (no trim) |
| `variables` | `TtsTemplateVariable[]` | **yes** | stored `null` for an empty list, so the response is `null` not `[]` |
| `status` | `TtsTemplateStatus` | no | |
| `scope` | `TtsTemplateScope` | no | `effectiveScope` derives it for legacy null-column rows; **never null on the wire** |
| `createdAt` | `string` | no | |
| `updatedAt` | `string` | **yes** | **F3 correction**, same as audio |

`TtsTemplateScope` = `GLOBAL | TENANT`. **F3 added no third value.**

`TtsTemplateVariable` = `{ name: string; type?: … | null; required?: boolean | null }`
— all three nullable in practice; `validateSchema` skips the type check when it
is `null`.

#### `CreateTtsTemplateRequest` — 6 fields

`name` (`@NotBlank @Size(max=150)`), `description` (`@Size(max=5000)`),
`templateText` (`@NotBlank @Size(max=5000)`), `variables` (`@NotNull
@Size(max=50)`), `tenantId` (UUID, platform-only), `scope` (defaults `TENANT`).

`UpdateTtsTemplateRequest` is the first four only — **no `tenantId`, no `scope`,
no `status`.** Scope is immutable after creation.

#### Pagination

Both list endpoints use `ResponseFactory.page(...)` and send a real
`PaginationMetadata`. `page` is 0-based; `size` is server-clamped to 1..100
(`MAX_PAGE_SIZE`).

| | Sort allowlist | Default | Search matches |
|---|---|---|---|
| Audio | `name, fileName, createdAt, updatedAt, status` | `createdAt` **DESC** | `name` **and** `fileName` |
| TTS | `name, createdAt, updatedAt, status` | `createdAt` **DESC** | `name` **and** `templateText` |

`buildPageable` honours `sort[1]` **only** when it equals `asc`; anything else
silently means DESC. The client therefore always sends an explicit direction.
Neither list accepts a `scope` filter.

### 5.4 Capability Matrix

VERIFIED against `AudioAssetService`, `TtsTemplateService` and the seed
migrations. **Every one of these six keys is genuinely enforced** — unlike F2's
Contacts situation, there are no seeded-but-dead keys here, so nothing had to be
substituted.

| Action | Enforced capability | Call site |
|---|---|---|
| Audio: list, get | `AUDIO_VIEW` | `AudioAssetService` L93, L101, L103, L79 |
| Audio: metadata create, update, delete | `AUDIO_MANAGE` | L69, L124, L137 |
| Audio: **upload** | `AUDIO_MANAGE` | L196 |
| Audio: approve, reject | `AUDIO_APPROVE` | L269 (`transition`) |
| TTS: list, get | `TTS_VIEW` | `TtsTemplateService` L134, L140, L150, L121 |
| TTS: create, update, delete | `TTS_MANAGE` | L103, L177, L196 |
| TTS: approve, reject | `TTS_APPROVE` | L223 (`transition`) |

**Seeded role grants** (V1 for audio, V20 for TTS):

| Role | `AUDIO_VIEW` | `AUDIO_MANAGE` | `AUDIO_APPROVE` | `TTS_VIEW` | `TTS_MANAGE` | `TTS_APPROVE` |
|---|---|---|---|---|---|---|
| `SUPER_ADMIN` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `RESELLER_ADMIN` | ✓ | **—** | ✓ | ✓ | ✓ | ✓ |
| `TENANT_ADMIN` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `AGENT` | — | — | — | — | — | — |
| `REPORT_VIEWER` | — | — | — | — | — | — |

#### The reseller distinction — preserved in both directions

`RESELLER_ADMIN` has `AUDIO_APPROVE` but **not** `AUDIO_MANAGE`. So it
legitimately **sees Approve and Reject** on every recording in its hierarchy and
**must not see** Upload, Edit or Delete. F3 does not smooth this over in either
direction, and `content-gates.test.ts` asserts both halves.

`RESELLER_ADMIN` has **all three** `TTS_*` keys, so it can fully manage its
hierarchy's templates — but **not** the global catalog, which needs platform
scope for every write.

`AGENT` and `REPORT_VIEWER` hold no Audio or TTS capability at all, so the
navigation entries (`requiredCapabilities: [AUDIO_VIEW]` / `[TTS_VIEW]`) correctly
never render for them.

#### Two actions need more than a capability

| Action | Extra precondition | Consequence |
|---|---|---|
| `POST /audio-assets` and `POST /audio-assets/upload` | `Scope.tenantId != null`, else 400 "A tenant must be specified for this operation." (`AudioAssetService` L64-67, L191-195). Neither DTO carries a tenant. | **A `SUPER_ADMIN` holds `AUDIO_MANAGE` and still cannot upload.** `canCreateAudioAsset` requires `AUDIO_MANAGE` **and** `homeType === "TENANT"`. |
| `POST /tts-templates` with `scope: "GLOBAL"` or a `tenantId` | `AccessCheck.platformWide()` (L103-105), which a `RESELLER`-scoped assignment never covers. | A `RESELLER_ADMIN` holds `TTS_MANAGE` and still has **no creatable scope**. `ttsCreateOptionsFor` reports an empty list and the Create button is not rendered. |

This is the same class of finding as F2's `canCreateContactGroup`, from the same
root cause: a service that needs a tenant context and a DTO with no field to
carry one.

### 5.5 Tenant / Scope Matrix

`AudioAssetService` and `TtsTemplateService` both derive scope from
`OrganizationContextHolder`, which `OrganizationContextPopulationFilter` fills
from **server-side membership data** — never from a header, query or body.

| Caller | Audio list | Audio create | TTS list | TTS create | TTS writes |
|---|---|---|---|---|---|
| **TENANT** | own tenant | ✓ own tenant | own TENANT rows (any status) **+ APPROVED GLOBAL catalog** | ✓ own tenant, `PENDING_APPROVAL` | ✓ on own rows |
| **RESELLER** | hierarchy tenants (merged) | — (no `AUDIO_MANAGE`) | hierarchy TENANT rows **+ APPROVED GLOBAL catalog** | **—** (no path) | ✓ on hierarchy rows; **—** on GLOBAL |
| **PLATFORM** | all | **—** (no tenant context) | all | ✓ GLOBAL, or seed into a chosen ACTIVE tenant | ✓ both scopes |

**404-cloaking, both domains.** `AudioAssetService.findVisible` and
`TtsTemplateService.findVisible` return *not found* for a foreign resource, so a
foreign asset and a missing one are indistinguishable. For TTS a tenant caller
**also** gets 404 for a `GLOBAL` template that is not `APPROVED`
(`TtsTemplateService` L312-317). The shared `QueryErrorState` 404 wording already
reflects this and F3 did not weaken it.

#### The TTS `GLOBAL` catalog is a real, separate resource

V42 makes the split a **database invariant**, not a convention:

```sql
CHECK (scope <> 'GLOBAL' OR tenant_id IS NULL)     -- platform-owned
CHECK (scope <> 'TENANT' OR tenant_id IS NOT NULL)
```

| | GLOBAL | TENANT |
|---|---|---|
| `tenantId` | `null` (DB-enforced) | owning tenant (DB-enforced) |
| created by | platform callers only | the owning tenant, or a platform caller seeding one |
| created in | **`APPROVED`** (a system catalog) | **`PENDING_APPROVAL`** |
| visible to | every caller, once approved | the owning tenant; a reseller's hierarchy |
| edit / delete / approve target | **`AccessCheck.platformWide()`** | `forTenant(tenantId)` |
| deleting it | removes it from **every** organization's catalog | removes it from one |

A `RESELLER`-scoped assignment satisfies `forTenant(T)` for `T` in the caller's
hierarchy (`AuthorizationService.coversReseller` L150-166), which is why a
reseller administrator **can** manage its own tenants' templates but not the
shared catalog.

#### What the frontend does about it

- **The list is merged by the server and F3 adds no scope filter.** The endpoint
  has no `scope` parameter, so a filter would be evaluated over one page of a
  paginated merged result — a page with no global rows would read as "no global
  templates exist" while hundreds sit on other pages. Instead the **scope is a
  column**, with a word ("Global" / "Tenant"), an icon, and an accessible title
  explaining the ownership consequence.
- **The detail page states ownership in a sentence**, not just a badge.
- **Actions are resolved per row**, because a `GLOBAL` row needs platform scope
  for every write and a `TENANT` row does not.
- **The create form's scope control is decided by `ttsCreateOptionsFor`**, and
  the target-tenant picker is shown **only** to a platform caller, populated from
  `GET /tenants` (which returns all tenants for a platform caller and is filtered
  to the caller's own tenant or hierarchy for anyone else) and filtered to
  `ACTIVE`. No `tenantId` is read from a URL, and none is free text.

**No tenant selector was added to either list or detail page**, and the reseller
tenant-switching question carried over from F0/F1 is untouched.

### 5.6 Existing Frontend Drift

| # | Drift | Classification |
|---|---|---|
| 1 | Create form asked for `checksum`, `storageReference`, `fileSize`, `contentType`, `fileName`, `durationSeconds` — all server-derived | **FIXED** (form deleted) |
| 2 | `toCreatePayload` hard-coded `scope: "TENANT"`, making `GLOBAL` unreachable | **FIXED** |
| 3 | Fabricated `PaginationMetadata` with `totalElements: 0` in both list services | **FIXED** |
| 4 | Approve/Reject offered only for `PENDING_APPROVAL` — three legal transitions hidden | **FIXED** |
| 5 | "Editing an approved template will return it to pending approval" fired unconditionally and was wrong for renames, descriptions, type changes and `required` flips | **FIXED** |
| 6 | Audio search documented as `name` only; it is `name` **and** `fileName` | **FIXED** |
| 7 | `fileSize: number \| null` — the column is `NOT NULL`; the detail page rendered "Size: null bytes" | **FIXED** |
| 8 | `updatedAt: string` on both domains — the column is nullable | **FIXED** |
| 9 | `contracts.ts` claimed `AudioStorageException` → 503; it is 500 | **FIXED** |
| 10 | `canManageTtsTemplate` returned `true` for any tenant row **regardless of capability** | **FIXED** (removed; replaced by `content-gates.ts`) |
| 11 | `partitionTtsTemplatesByScope` — dead F1 helper promising "F6 builds the real scope-aware UI" | **FIXED** (removed; superseded by the scope column and per-row gating) |
| 12 | Two byte-equivalent status badges | **FIXED** (one shared badge) |
| 13 | `storageReference` and raw `tenantId` rendered in the UI | **FIXED** (removed) |
| 14 | TTS detail never rendered `scope`; a global template showed a blank "Tenant:" line | **FIXED** |
| 15 | Both detail views conflated 403 / 404 / 500 / network into one string, with no retry | **FIXED** |
| 16 | `as any` / `no-explicit-any` in 7 files | **FIXED** (zero in F3 code) |
| 17 | `confirm()` for delete; approve/reject with no confirmation at all | **FIXED** (real dialogs) |
| 18 | Status filter did not reset to page 0 | **FIXED** |
| 19 | `EditAudioAssetDialog` had no `key`, so switching records kept stale form values | **FIXED** |
| 20 | `// ponytail:` comment explaining nothing | **FIXED** (removed) |
| 21 | Stale comment: "F1 exposes the field; F6 builds the scope-aware create form" | **FIXED** (F3 is that phase) |
| 22 | Sort buttons lacked `aria-label`; icons lacked `aria-hidden`; search inputs had no accessible label | **FIXED** |

---

## Implementation Changes

### Files Added (16)

**Shared / domain logic**
- `frontend/src/lib/domain/approval.ts` — the three-state approval lifecycle and
  the exact transition rule, shared by both services.
- `frontend/src/lib/auth/content-gates.ts` — which enforced capability gates each
  Audio/TTS action, plus the two extra preconditions.

**Components**
- `frontend/src/components/common/approval-status-badge.tsx`
- `frontend/src/components/common/approval-transition-dialog.tsx`
- `frontend/src/components/audio-assets/upload-audio-asset-dialog.tsx`
- `frontend/src/components/audio-assets/delete-audio-asset-dialog.tsx`
- `frontend/src/components/audio-assets/audio-asset-actions.tsx`
- `frontend/src/components/tts-templates/tts-template-scope-badge.tsx`
- `frontend/src/components/tts-templates/tts-template-variables-field.tsx`
- `frontend/src/components/tts-templates/delete-tts-template-dialog.tsx`
- `frontend/src/components/tts-templates/tts-template-actions.tsx`

**Tests**
- `frontend/src/lib/domain/approval.test.ts`
- `frontend/src/lib/auth/content-gates.test.ts`
- `frontend/src/lib/schemas/audio-asset-mutation.test.ts`
- `frontend/src/lib/schemas/tts-template-mutation.test.ts`
- `frontend/src/lib/api/content-assets.test.ts`

### Files Modified (15)

| File | Change |
|---|---|
| `lib/api/contracts.ts` | `fileSize: number`; `updatedAt: string \| null` on both; 503→500 correction; `CreateAudioAssetPayload` removed with a comment explaining why the endpoint has no UI. |
| `lib/api/audio-assets.ts` | Full contract rewrite. Fabricated pagination removed. `createAudioAsset` removed. Search documented correctly. |
| `lib/api/tts-templates.ts` | Full contract rewrite. Fabricated pagination removed. Two dead helpers removed. `createTtsTemplate` scope/tenantId semantics documented. |
| `lib/schemas/audio-asset-mutation.ts` | Replaced with the upload pre-flight and the two-field update schema, each limit traced to its source. |
| `lib/schemas/tts-template-mutation.ts` | Full port of `TtsTemplateValidation`; scope-aware payload builder; `willTtsEditResetApproval`; `templateText` no longer trimmed. |
| `components/audio-assets/audio-assets-view.tsx` | Gate table, no `any`, page reset on filter change, shared states, accessible search. |
| `components/audio-assets/audio-asset-table.tsx` | `fileSize`/`durationSeconds` columns, all legal transitions, `aria-label`s, null-safe `updatedAt`. |
| `components/audio-assets/audio-asset-detail-view.tsx` | Actions, shared error/loading states, no `storageReference`/`tenantId`, `fileSize` always a number. |
| `components/audio-assets/edit-audio-asset-dialog.tsx` | Shared field-error mapper, no assertion, `key` remount, reset on close. |
| `components/tts-templates/tts-templates-view.tsx` | Gate table, per-row action resolvers, no `any`, page reset, scope explained in the card copy. |
| `components/tts-templates/tts-template-table.tsx` | Scope badge, variable count, per-row gating, `aria-label`s. |
| `components/tts-templates/tts-template-detail-view.tsx` | **Scope in words** plus a badge, per-row actions, shared states, no raw `tenantId`. |
| `components/tts-templates/create-tts-template-dialog.tsx` | Scope control + platform-only tenant picker, shared variables editor, no `any`, third `useForm` generic. |
| `components/tts-templates/edit-tts-template-dialog.tsx` | Shared variables editor, no `any`, **precise** live approval-reset warning. |
| `lib/api/contact-links.test.ts` (an F1 file) | Extended to scan and assert the audio/TTS routes, reusing the same collector. |

### Files Deleted (3)

- `frontend/src/components/audio-assets/create-audio-asset-dialog.tsx` — the
  metadata-registration form. Replaced by the upload dialog.
- `frontend/src/components/common/audio-asset-status-badge.tsx`
- `frontend/src/components/common/tts-template-status-badge.tsx` — byte-equivalent
  duplicates, replaced by `approval-status-badge.tsx`.

### Architecture

Unchanged from F1, and unopened:

```
Page → View → useQuery(domainKeys) → lib/api/<domain> → transport.ts → apiClient
```

- **No `axios` / `fetch` in any F3 component** — verified by grep.
- **No `any` in F3 code** — the 7 `no-explicit-any` disables and ~30 `as any`
  casts are gone.
- `transport.ts` is still the only module that knows `ApiResponse` exists, and
  F3 **did not modify it** — neither domain has a binary endpoint.
- Error predicates come only from `lib/api/error`; no local copies.
- `QueryErrorState` / `EmptyState` / `applyServerFieldErrors` are the F1/F2
  components, reused.
- `useUrlListState`, `useCan`, `useOperatingContext` reused unchanged. The status
  filter stays in component state because `useUrlListState`'s `status` slot is
  hard-coded to the users domain's `ACTIVE`/`SUSPENDED` lifecycle — widening that
  shared hook for one screen would be an F1/F2 cross-cutting change.
- `content-gates.ts` is a lookup over the F1 `Capability` catalogue plus the F1
  `hasAnyCapability`. No new predicate, no new capability names.

---

## Tests

### What was added

| File | Tests | Covers |
|---|---|---|
| `lib/domain/approval.test.ts` | 24 | The three statuses; both transition targets; **the only refused case is a no-op**; all six status×transition combinations; REJECTED→APPROVE and APPROVED→REJECT both allowed; `availableTransitions` never offers a 409; both 409 message texts; consequence copy; the full matrix table |
| `lib/auth/content-gates.test.ts` | 28 | Every gate's key; **no `AUDIO_UPLOAD` and no `AUDIO_DELETE` exist**; **a reseller may approve/reject audio and may not edit/delete/upload**; a tenant admin may do everything and may create; **a super admin may NOT create audio** (no tenant context); a tenant may not write a GLOBAL template; a reseller may write its own but not GLOBAL; create options for all three scopes including **a reseller having none**; an unresolved scope; null/absent capabilities deny everything |
| `lib/schemas/tts-template-mutation.test.ts` | 49 | The ported contract rule by rule: `VARIABLE_NAME` is the same expression; trims before validating; duplicates; the four types, case-insensitively, null = unconstrained; placeholders with inner whitespace; undeclared references; an empty list forbids all placeholders; **a declared-but-unused variable is legal**; stray braces including **`{{{name}}`**, which F1's heuristic missed; both schemas share one refinement; verbatim `templateText`; `@Size` limits; `variables` always sent as an array; update omits scope/tenantId; the GLOBAL-never-carries-a-tenant rule; and all six `willTtsEditResetApproval` cases |
| `lib/schemas/audio-asset-mutation.test.ts` | 21 | The MIME list mirrors the validator; empty / oversized / unsupported-extension / unsupported-MIME rejection; an empty or generic declared type is allowed; **an uppercase extension is allowed**; **a renamed `.exe` passes the local check** (advisory, not authoritative); the 150 name cap traced to the DB column; no invented description limit; the update schema has exactly two fields |
| `lib/api/content-assets.test.ts` | 31 | Both list services: sort serialisation, whitelists, status/search presence, **pagination passed through instead of fabricated**; the multipart part names and the missing-boundary header; **no derived technical field is sent**; `createAudioAsset` gone; PUT with two fields; DELETE tolerating 204; **PATCH for approve/reject**, no body; query-key prefixes; the TTS list **never sends `scope`**; create forwards `scope`/`tenantId`; update omits them; and four tests asserting **no download/preview/URL and no generate/synthesise/render function exists** |
| `lib/api/contact-links.test.ts` (extended) | +13 | The four audio/TTS routes exist; **no route offers download, preview, play, export, generate or render**; no top-level `/tts`; every audio/TTS `href` resolves to a real route; no `undefined`/`null` interpolation |

**166 new tests. 356 total in 19 files, all passing.** The 190 F1+F2 tests still
pass unchanged.

### What was NOT tested, and why

- **No component render tests.** F1 deliberately deferred jsdom and Testing
  Library. F3 **confirms that deferral again**: every piece of F3 logic that could
  drift is pure — the approval state machine, the capability matrix, the ported
  validation contract, the upload pre-flight, and the request shapes — and all of
  it is now covered. Component rendering would exercise shadcn primitives, not
  this domain. **jsdom remains unjustified after three phases**; it should be
  introduced when a phase's logic genuinely cannot be reached without a DOM.
- **No UI-state tests** (empty, forbidden, error, success) for the same reason:
  the state components are shared F1 infrastructure.
- **No live backend test.** Nothing here is integration-tested.

### What "verified" means in this phase

| Claim type | Applies to | Source |
|---|---|---|
| **contract inspected** | every endpoint, DTO field, capability, transition rule, scope rule and error in §5.2–5.5 | reading `AudioAssetController`, `AudioAssetService`, `AudioUploadValidator`, `AudioStorageProperties`, `MediaUriResolver`, `LocalAudioStorage`, `NoOpAudioStorage`, `AudioAssetEntity`, `AudioAssetMapper`, `AudioAssetStatus`, `TtsTemplateController`, `TtsTemplateService`, `TtsTemplateValidation`, `TtsTemplateMapper`, `TtsTemplateSpecifications`, `TtsTemplateScope`, `TtsTemplateStatus`, `TtsTemplateEntity`, `AuthorizationService`, `OrganizationContextPopulationFilter`, `TenantService`, the DTO records, `V1`, `V20`, `V42` |
| **unit tested** | the request shapes, payload builders, ported validation, capability decisions, transition model and route coverage in the table above | the 166 tests, with the API client mocked |
| **integration tested** | **nothing** | — |
| **manual tested** | **nothing** | — |

The tests mock the API boundary. They are evidence about **what the frontend
sends and how it interprets a response shape**; they are **not** evidence that the
backend behaves that way. Every backend statement in §5.2–5.5 is attributed to a
file and, where useful, a line range.

---

## Validation

Commands run in `frontend/`:

| Command | Exit | Result |
|---|---|---|
| `npm run typecheck` | **0** | no errors |
| `npm run lint` | **0** | **0 errors, 4 warnings** |
| `npm run test` | **0** | **356 passed / 356**, 19 files |
| `npm run build` | **0** | **success** — 13.2 s compile, 19.3 s TypeScript, 8 static pages, **23 routes** |
| `npm run validate` | **0** | pass (typecheck && lint && test) |

**Backend files changed by F3: 0.**

### Lint warnings — 4, all accounted for

| Location | Origin |
|---|---|
| `components/campaigns/call-attempt-table.tsx:268` | **pre-existing** (F1 baseline) |
| `components/campaigns/create-campaign-dialog.tsx:109` | **pre-existing** |
| `components/campaigns/edit-campaign-dialog.tsx:100` | **pre-existing** |
| `components/tts-templates/edit-tts-template-dialog.tsx:109` | **new in F3** — `form.watch()` for the live approval-reset warning |

F2's baseline was 5 warnings. F3 is 4: three pre-existing campaign ones remain,
the two TTS `form.watch()` warnings disappeared because the variable editor moved
into its own component, and F3 added one for the deliberate live `form.watch()`
that powers the precise approval-reset warning. **It is left visible rather than
suppressed** — it is a real advisory about new code, and hiding it would make the
count look better while losing the information. No pre-existing warning was
hidden.

### Host memory — one build failure, then a clean success

The first `npm run build` **failed with `FATAL ERROR: Zone Allocation failed -
process out of memory`** (worker exit `2147483651`), after `✓ Compiled
successfully in 28.0s`. This is the host condition F1 recorded: **205 MB free of
5996 MB** at the retry, with no stray `node` processes and no code change.

Per F3 §46, the sequence was: classify it (a `Zone Allocation` failure during
page-data generation, after compilation and TypeScript both succeeded, is
resource exhaustion, not a code error), check for competing processes (none —
0 MB of `node` working set), remove `.next` to return to a clean state, and
retry. The retry **succeeded with no configuration change**: 13.2 s compile, 19.3
s TypeScript, 8 static pages, 23 routes, exit 0.

**No build configuration was weakened, and no workaround was committed.** The
failure is recorded here as a host-resource event, separate from application
correctness, exactly as F1 recorded its two.

**Route count is unchanged at 23.** F3 added **no** routes, which is correct: the
brief forbids creating routes for operations that do not exist, and there is no
download, preview, upload-page or generation operation to route. The build output
confirms all four audio/TTS routes are present and dynamic.

### Manual / live validation: NOT PERFORMED

No backend was running — the stack needs Postgres, Redis and FreeSWITCH, and for
audio specifically an `audio.storage.enabled=true` deployment with a writable
directory. **No claim in this document rests on observed backend behaviour.**

---

## Known Limitations

### Not supported by the backend

| Item | Detail |
|---|---|
| **Audio download / preview / playback** | No endpoint returns bytes and no URL field exists. `MediaUriResolver` is server-side. **NOT SUPPORTED** — no player is built. |
| **Audio rename / replace** | `UpdateAudioAssetRequest` has two fields. Changing the file means a new upload. |
| **Audio bulk upload, bulk approve, bulk delete** | No such endpoints. Not simulated. |
| **TTS generation / synthesis / preview** | "belongs to the future execution layer"; no provider in the package. **NOT SUPPORTED** — no UI. |
| **TTS versioning** | No version field, no version endpoint. |
| **TTS template preview with sample values** | Would need generation. Not built. |
| **TTS scope filter on the list** | The endpoint has no `scope` parameter. Deliberately **not** faked — see §5.5. |
| **"Is this recording/template in use?"** | No endpoint answers it. The delete dialogs state the consequence instead of claiming to check it. |
| **`DRAFT` / `ARCHIVED` statuses** | Considered and deliberately deferred by the backend Javadoc. Not rendered. |
| **Audio asset ↔ TTS template linkage** | None exists. Nothing implies one. |

### Backend blockers

**None introduced by F3.** Two pre-existing documentation defects were found and
are recorded here and in §5.2.1; **no backend code was changed**:

| Blocker | Location | Impact | Recommended owner action |
|---|---|---|---|
| Stale `409` annotation on upload | `AudioAssetController` L85 | A client generated from `/v3/api-docs` will handle a response that cannot occur, and will not recognise the 500 that does. F3 documents and handles the real behaviour. | Backend: correct the annotation, or add an `@ExceptionHandler(AudioStorageException.class)` returning 409. |
| No request validation on the upload endpoint | `AudioAssetController.upload` L86-100 | `name` length is enforced only by the database, so an over-long name is a **500** rather than a field error. F3 caps client-side and documents that the cap comes from the column. | Backend: add `@Size(max = 150)` to the `name` parameter, or annotate the controller with `@Validated`. |

Carried over from earlier phases, unchanged and unrelated to this domain:

- **IVR capabilities** are enforced by `IvrTreeService` but seeded by no
  migration, so every IVR endpoint 403s for every role.
- **`V55__execution_snapshot_integration_config.sql` is untracked.**

### Product decisions

| Question | Why it matters | F3's interim behaviour |
|---|---|---|
| **Should a `RESELLER_ADMIN` be able to create TTS templates?** | V20 grants it `TTS_MANAGE`, but `create` has no reseller branch and the only multi-tenant path is checked `platformWide()`, which a reseller never covers. | No Create button for a reseller. |
| **Should a `RESELLER_ADMIN` be able to create audio assets?** | V1 deliberately withholds `AUDIO_MANAGE`, so this looks **intended**. | No Upload button. Recorded as intended, not as a gap. |
| **Should a `SUPER_ADMIN` be able to upload audio?** | It holds `AUDIO_MANAGE` but has no tenant context, and the endpoint has no tenant field. | No Upload button. If the answer is yes, the backend needs an authorised tenant target on the upload path — the same shape as the TTS `tenantId` case. |
| **Should the audio edit form show read-only `fileName` / `contentType`?** | The pre-F3 form did, inside a form, which read as editable-in-place. | Moved to the detail page as read-only facts; the form has exactly two inputs. |
| **Should the create form be available at all when `audio.storage.enabled=false`?** | Storage is disabled by default, so every upload is a 500. The UI cannot read that config. | The button is shown; the 500 carries an explicit "an administrator needs to set `audio.storage.enabled`" hint rather than a bare error. |
| **Should the status filter be in the URL?** | `useUrlListState`'s `status` slot is hard-coded to the users domain's lifecycle values. | Kept in component state for both lists, with a documented reason. Widening the shared hook is an F1/F2 cross-cutting change. |

### Deferred work

- **jsdom / Testing Library** — unchanged from F1, reconfirmed by F3. Three
  phases of pure-domain logic have now been covered without it.
- **An OpenAPI drift check** — still not added. It would have caught the stale
  409 immediately, and would have caught the 503-versus-500 error F1 recorded as
  fact. Should land before F10.
- **Campaign consumption of approved content.** F4's job. F3 deliberately does
  not assume what a campaign needs: it exposes the group `memberCount`, the
  approved asset list and the approved template list, and nothing more.
- **A shared `useUrlListState` status concept** — would let audio and TTS put
  their status filter in the URL. Cross-cutting; not F3's to make.

### Frontend limitations (accepted, not defects)

- **No audio preview means the upload cannot be verified by ear.** The recording
  page shows the server-derived facts (file name, content type, size, duration,
  fingerprint) precisely so a user can confirm the upload is the file they
  expected — the only confirmation available.
- **The platform target-tenant picker shows the first 100 active tenants.** The
  list is `GET /tenants` with `size=100`; a deployment with more active tenants
  would need a searchable picker. Rare enough not to build, and stated in the
  dialog's help text.
- **The TTS list is not scope-filterable** (§5.5). Deliberate.

---

## F4 Readiness

**READY** for Campaigns, with five specific things to carry forward.

### What F4 inherits

1. **The enforced-capability discipline, now with a worked example.**
   `content-gates.ts` is the pattern: one table per domain, recording the key the
   **service** checks plus the extra precondition where one exists. F4 should
   verify `CAMPAIGN_VIEW` / `CAMPAIGN_MANAGE` / `CAMPAIGN_EXECUTE` /
   `CAMPAIGN_ASSIGN` the same way before gating anything, and should expect to
   find at least one endpoint whose precondition is a tenant context rather than a
   capability — there are already three in this codebase.
2. **`lib/domain/approval.ts` is the reference for a shared state machine across
   two services.** If Campaigns has one — the F0 notes suggest a `DRAFT`-style
   campaign lifecycle — the same shape applies: model the transition rule once,
   with the call sites in comments, and test the full matrix.
3. **A working multipart upload exists** (`uploadAudioAsset` +
   `UploadAudioAssetDialog`), including the "no boundary by hand" detail and the
   "server derives the technical fields" pattern. If F4 needs to upload anything,
   copy that, not a fresh `FormData`.
4. **Error, query and state infrastructure** is F1/F2's and unchanged:
   `QueryErrorState`, `EmptyState`, `applyServerFieldErrors`, `useUrlListState`,
   `useCan`, `useOperatingContext`, the shared `transport.ts`.

### What F4 needs from Audio Assets and TTS

| Need | Available | Note |
|---|---|---|
| **Approved audio assets for a campaign's content list** | `GET /audio-assets?status=APPROVED` | Real server-side status filter, paged, searchable by name and file name. |
| **Approved TTS templates for a campaign's content list** | `GET /tts-templates?status=APPROVED` | Returns a **merged** page of the tenant's own rows and the global catalog — a campaign referencing a template gets exactly what a tenant can see. |
| **Group audience for a campaign** | F2's group list + `memberCount` | Unchanged by F3. |
| **Asset/template size for a picker** | `memberCount` on groups; audio `fileSize`/`durationSeconds` on assets; TTS has **no** size field | |
| **"Is this content already used?"** | — | **Not available.** No endpoint reports which campaigns reference an asset or a template. |

### Watch items for F4

- **Only `APPROVED` content is usable by an executable campaign.** That is
  enforced server-side (`AudioAssetStatus` Javadoc) and it is why the approval
  workflow exists. A campaign that references a `PENDING_APPROVAL` or `REJECTED`
  asset will fail at **activation**, not immediately — the delete and reject
  dialogs now say so, and F4's activation copy should too.
- **Do not assume audio playback is available.** If F4 wants a "preview the
  recording" affordance in a campaign builder, **it does not exist** and cannot
  be built from this API. The recording facts are the only preview available.
- **Do not assume TTS synthesis is available.** A campaign can reference a
  template by id; nothing in this API renders it. Whatever speaks the text lives
  in the execution layer.
- **`RESELLER_ADMIN` can approve campaign content but cannot create it.** If F4
  builds a campaign form gated on `CAMPAIGN_MANAGE`, check whether
  `RESELLER_ADMIN` holds it before assuming a reseller sees the button.
- **F4's `basePath` must interpolate route params.** The F2 defect
  (`basePath: "/contact-groups/[contactGroupId]/contacts"` 404-ing every
  interaction) is now covered by `contact-links.test.ts` for contacts, audio and
  TTS — but that test checks `href`s, not `basePath`. A nested campaign route
  would need the same treatment F2 applied.

---

## Definition of Done

**Contract**
- [x] Every Audio endpoint verified against backend
- [x] Every TTS endpoint verified against backend
- [x] Request DTOs match backend (upload part names; `CreateTtsTemplateRequest`'s `scope`/`tenantId`)
- [x] Response DTOs match backend (13 and 10 fields, all nullability corrected)
- [x] Pagination matches backend (0-based, 1..100, real metadata, no fabrication)
- [x] Filtering/search matches backend (`status` server-side; search fields corrected; no scope filter)
- [x] Membership/approval operations match backend (both transitions, PATCH, 409 only on no-op)

**Audio Assets**
- [x] List works (search, status, sort, pagination, responsive)
- [x] Detail works (facts, no infrastructure fields, actions present)
- [x] Create works **via the real multipart upload contract**
- [x] Edit works (two fields, exactly the DTO)
- [x] Delete works (dialog states the verified consequence)
- [x] Approval workflow works (both transitions, confirmed, 409 handled)
- [x] No playback invented — none exists
- [x] Validation works (advisory file pre-flight, DB-traced name cap)
- [x] Loading/empty/filtered-empty/forbidden/error states work

**TTS Templates**
- [x] List works (merged page, scope column, status filter, search, sort, pagination)
- [x] Detail works (**scope in words** plus badge, actions present)
- [x] Create works (**scope selection + platform-only target tenant**)
- [x] Edit works (four fields; **precise** approval-reset warning)
- [x] Delete works (scope-specific consequence stated)
- [x] Approval workflow works (per-scope gating, both transitions)
- [x] No generation invented — none exists
- [x] Validation works (full port of `TtsTemplateValidation`)

**Authorization**
- [x] Actual backend capabilities used, with call sites
- [x] `AUDIO_APPROVE` never conflated with `AUDIO_MANAGE` — both directions tested
- [x] Reseller audio distinction preserved (approve yes, manage no)
- [x] TTS GLOBAL/TENANT scope gating preserved
- [x] Two capability-plus-precondition actions gated correctly
- [x] 403 is not treated as 401, and not as empty
- [x] No permissions invented; `AUDIO_UPLOAD` and `AUDIO_DELETE` asserted absent

**Tenant**
- [x] F1 operating context reused; no second mechanism
- [x] No arbitrary tenant ID from user input
- [x] The one tenant picker is platform-only and populated from an authorised list
- [x] No fake reseller tenant selector
- [x] 404-cloaking respected in the error copy

**Quality**
- [x] No phantom fields (`fileSize` and `updatedAt` corrected)
- [x] No infrastructure exposed (`storageReference`, `tenantId` removed)
- [x] No dead links — every audio/TTS `href` is a test case
- [x] No duplicate API calls; no N+1; no client-side counting
- [x] No direct component-level API calls
- [x] Zero `any` in F3 code
- [x] Two duplicate badges and one duplicate dialog removed
- [x] No unrelated domain changes

**Tests**
- [x] F1 + F2 tests still pass (190 → 356)
- [x] F3 tests pass (166)
- [x] Typecheck passes
- [x] Lint passes, 0 errors, no hidden warnings
- [x] Build passes (23 routes)
- [x] Route coverage extended to audio and TTS

**Documentation**
- [x] `docs/frontend/F3-AUDIO-TTS.md` created
- [x] Backend contract matrix documented
- [x] Capability and tenant/scope matrices documented
- [x] Drift table with 22 items, each classified
- [x] Limitations split by cause
- [x] F4 dependencies documented

---

## Final Report

```text
F3 — AUDIO ASSETS & TTS

Status:
PASS WITH BLOCKERS

Baseline:
Frontend HEAD: 3a89e5c
Backend HEAD:  3a89e5c (same commit; 33 modified + 12 untracked backend
               files were already present and are concurrent work, not mine)
Backend working tree at F3 start: 33 modified, 12 untracked — all timestamped
               before F3; the newest audio/TTS file is 09-27 20:41
Backend working tree at F3 end:   33 modified, 12 untracked — IDENTICAL
Backend files changed by F3:      0

Scope:
Audio: list, detail, upload, edit, delete, approve, reject. NO download,
       preview or playback — none exists.
TTS:   list, detail, create (incl. GLOBAL), edit, delete, approve, reject.
       NO generation or synthesis — none exists.

Backend files changed:
0

Audit findings:
- 22 drift items, all classified (§5.6)
- 2 controller annotations that are wrong: upload 409→really 500; upload
  name @Size→really a DB column, so a long name is a 500
- 2 load-bearing ABSENCES: no audio retrieval endpoint, no TTS synthesis
- 3 actions need more than a capability: audio create/upload (tenant context),
  and TTS create for GLOBAL / platform-seeded (platformWide)

Implementation:
- lib/domain/approval.ts: the shared three-state lifecycle, modelled from
  AudioAssetService.transition and TtsTemplateService.transition, which are
  identical. The only refused case is a no-op; all six state×transition
  combinations are modelled and tested.
- lib/auth/content-gates.ts: enforced keys per action with call sites, plus the
  two extra preconditions, plus per-resource TTS scope resolution.
- Upload dialog replaces a form that asked for a 64-character SHA-256, a
  storage reference, a content type and a byte count. Three inputs now, the
  exact @RequestParam set, and a dialog that states the server checks the
  actual audio content.
- Real dialogs for delete, approve and reject on both domains; both legal
  transitions are reachable, including re-approving a rejected record.
- TTS scope shown per row and explained in words on the detail page; per-row
  action gating; scope selection on create; a platform-only target-tenant picker
  fed by GET /tenants and filtered to ACTIVE.
- Full port of TtsTemplateValidation, so every placeholder and declaration
  rule is checked while typing instead of on submit.
- Two byte-equivalent status badges and a duplicated dialog removed; two dead
  API helpers removed.
- Zero `any`; ~30 `as any` casts and 7 file-level disables eliminated.
- No F1/F2 shared module was modified. transport.ts is untouched because
  neither domain has a binary endpoint.

Contract alignment:
VERIFIED: 7 audio endpoints, 7 TTS endpoints, 13 + 10 response fields, both
  create DTOs, both update DTOs, both sort whitelists, both search field sets,
  both approval lifecycles, all four TTS scope rules, the V42 CHECK constraints.
CORRECTED: fabricated PaginationMetadata (both lists); fileSize nullability;
  updatedAt nullability (both domains); the 503-vs-500 upload error; audio
  search fields; the always-on approval-reset warning; canManageTtsTemplate
  ignoring capabilities; the hard-coded scope:"TENANT"; storageReference and
  raw tenantId in the UI; TTS detail never showing scope.

Authorization:
Verified capabilities: AUDIO_VIEW (read), AUDIO_MANAGE (create/update/delete/
  upload), AUDIO_APPROVE (approve/reject), TTS_VIEW (read), TTS_MANAGE
  (create/update/delete), TTS_APPROVE (approve/reject). All six are genuinely
  enforced — nothing had to be substituted, unlike F2's Contacts.
RESELLER_ADMIN: AUDIO_VIEW + AUDIO_APPROVE but NOT AUDIO_MANAGE — preserved in
  both directions, tested. All three TTS_* keys, but no path to create a
  template, so no Create button.
SUPER_ADMIN: holds AUDIO_MANAGE and AUDIO_APPROVE, but cannot upload (no tenant
  context) and can do everything on TTS.
AGENT / REPORT_VIEWER: no Audio or TTS capability; navigation correctly absent.
Per-resource TTS gating: a GLOBAL template needs platform scope for EVERY
  write; a TENANT template needs the tenant capability, which a reseller
  satisfies for its own hierarchy.

Tenant/scope:
- Audio: tenant reads its own; reseller reads its hierarchy; platform reads all.
  Create/upload require a TENANT context, which no request field can supply.
- TTS: the list is a MERGED page (own rows + the APPROVED GLOBAL catalog).
  GLOBAL is platform-owned, tenantId null, DB-enforced by
  ck_tts_templates_scope_global_no_tenant, created APPROVED, and every write
  needs AccessCheck.platformWide(). TENANT is created PENDING_APPROVAL and
  writes need forTenant(). Scope is immutable after creation.
- No scope filter added: the endpoint has no scope parameter, and filtering one
  page of a merged paginated result would hide rows that exist elsewhere.
- One tenant picker exists, for platform callers creating a TENANT template,
  fed by GET /tenants (all tenants for a platform caller) filtered to ACTIVE.
  No arbitrary tenant ID is accepted from user input, and no reseller tenant
  selector exists.

Tests:
166 new, 356 total in 19 files, 0 failures. 5 new test files plus 13 new route
  assertions in the F1 link test.
No jsdom. F3 reconfirms the F1 deferral a third time: every piece of drift-prone
  F3 logic is pure and is now covered.

Validation:
typecheck: exit 0
lint:      exit 0 — 0 errors, 4 warnings (3 pre-existing campaign, 1 new
           form.watch() in the TTS edit dialog, left visible rather than
           suppressed; F2's baseline was 5)
test:      356 passed / 356, 19 files
build:     SUCCESS on the second attempt, exit 0, 23 routes (unchanged — F3
           correctly added no routes)
validate:  pass
One build failure first: "Zone Allocation failed - process out of memory" with
  205 MB free of 5996 MB, after compilation and TypeScript both succeeded.
  Classified as host resource exhaustion, no node processes competing, `.next`
  removed, retried clean. No build configuration was weakened and no workaround
  committed.

Manual validation:
NOT PERFORMED. No backend running (needs Postgres, Redis, FreeSWITCH, and for
  audio an audio.storage.enabled=true deployment). No claim rests on observed
  backend behaviour; every backend statement is attributed to a file and, where
  useful, a line.

Known limitations:
- No audio download/preview/playback — no endpoint returns bytes
- No TTS generation/synthesis — "belongs to the future execution layer"
- No scope filter on the TTS list — no such parameter
- No "is this content in use?" endpoint — delete dialogs state the consequence
  instead of claiming to check it
- The platform tenant picker shows the first 100 active tenants
- The audio status filter is component state, not URL state, because
  useUrlListState's status slot is hard-coded to the users domain

Backend blockers:
None introduced. Two PRE-EXISTING documentation defects found and documented,
  with no backend change:
  1. AudioAssetController L85 advertises 409 "Audio storage is disabled" for
     upload. AudioStorageException is a bare RuntimeException with no
     @ExceptionHandler, so it is a 500 — and audio.storage.enabled defaults to
     false, so this is the expected response on an unconfigured deployment.
  2. The upload endpoint carries no @Valid and no @Size, so `name` length is
     enforced only by the VARCHAR(150) column and an over-long name is a 500.
  Both would have been caught by an OpenAPI drift check, which is still not
  added and should land before F10.

Product decisions:
- Should a RESELLER_ADMIN be able to create TTS templates? V20 grants
  TTS_MANAGE but `create` has no reseller branch. F3 shows no Create button.
- Should a SUPER_ADMIN be able to upload audio? It holds AUDIO_MANAGE but has no
  tenant context. F3 shows no Upload button. If yes, the backend needs an
  authorised tenant target, as TTS already has.
- Should the audio edit form keep read-only fileName/contentType? Moved to the
  detail page so the form does not imply they are editable.
- Should a status filter live in the URL? Needs a shared useUrlListState
  change; cross-cutting, not F3's to make.

Deferred:
- jsdom / Testing Library (third phase to reconfirm the deferral)
- An OpenAPI drift check
- Campaign consumption of approved content (F4)
- A shared useUrlListState status concept

F4 readiness:
READY

Reason:
Both domains are backend-faithful, capability-aware, scope-aware and tested, and
F4 has what it needs: a status-filtered list of approved audio assets, a
status-filtered merged list of approved TTS templates including the shared global
catalog, the group audience from F2, and a working multipart upload to copy if
needed. The F1/F2 infrastructure F4 depends on is untouched by F3.

The two things F4 cannot get from here are the ones that do not exist: there is
no way to preview an audio recording in a browser, and there is no endpoint that
reports which campaigns already reference a given asset or template. Neither
should be assumed, and neither should be worked around.
```

---

## Appendix A — Backend files read during F3

```
backend/src/main/java/com/shivang/obd/audio/
  AudioAssetController.java        7 mappings; the stale 409 annotation
  AudioAssetService.java           list/get/create/update/delete/upload/transition
  AudioUploadValidator.java        ALLOWED_MIME, magic-byte detection, WAV duration
  AudioStorageProperties.java      enabled=false, 5 MiB, freeswitchMediaRoot
  AudioStorage.java                the storage seam
  LocalAudioStorage.java           storage/{tenant}/{asset}/{file}, enabled=true only
  NoOpAudioStorage.java            the default: every upload throws
  AudioStorageException.java       extends RuntimeException — no handler anywhere
  MediaUriResolver.java            server-side FreeSWITCH path resolution (VB-6E)
  AudioAssetEntity.java            column nullability
  AudioAssetMapper.java            toResponse
  AudioAssetSpecifications.java    search over name + fileName
  AudioAssetStatus.java            3 states; DRAFT/ARCHIVED deliberately deferred
  dto/{AudioAssetResponse,Create,Update}AudioAssetRequest.java
backend/src/main/java/com/shivang/obd/tts/
  TtsTemplateController.java       7 mappings
  TtsTemplateService.java          create/scope resolution, list, update, transition
  TtsTemplateValidation.java       the ported contract validator
  TtsTemplateMapper.java           verbatim templateText, effectiveScope
  TtsTemplateSpecifications.java   GLOBAL catalog predicate, search over name + text
  TtsTemplateEntity.java           column nullability
  TtsTemplateScope.java            GLOBAL | TENANT
  TtsTemplateStatus.java           3 states
  TtsTemplateVariable.java         name / type / required
  dto/{Create,Update}TtsTemplateRequest.java, TtsTemplateResponse.java
backend/src/main/java/com/shivang/obd/
  authz/AuthorizationService.java        covers / coversReseller (L140-166)
  authz/context/OrganizationContextPopulationFilter.java
  security/config/OrganizationContextPopulationFilter.java
  common/exception/GlobalExceptionHandler.java    the handler inventory
  common/audit/AuditableEntity.java                @LastModifiedDate semantics
  tenant/TenantService.java                       list scoping
backend/src/main/resources/db/migration/
  V1__create_multi_tenant_authorization_foundation.sql   audio caps + role grants
  V20__create_audio_tts_domain.sql                     TTS caps + role grants, tables
  V42__add_tts_template_scope.sql                       the two scope CHECKs
```

## Appendix B — Finding classification index

| # | Finding | Class |
|---|---|---|
| 1 | No audio download / preview / playback endpoint, no URL field | NOT SUPPORTED |
| 2 | No TTS generation / synthesis endpoint or provider | NOT SUPPORTED |
| 3 | `createAudioAsset` form asked for server-derived fields | FIXED |
| 4 | `scope: "TENANT"` hard-coded, making `GLOBAL` unreachable | FIXED |
| 5 | Fabricated `PaginationMetadata` on both lists | FIXED |
| 6 | Three of four approval transitions unrenderable | FIXED |
| 7 | Approval-reset warning fired unconditionally and was wrong | FIXED |
| 8 | Audio search documented as `name` only | FIXED |
| 9 | `fileSize: number \| null` on a `NOT NULL` column | FIXED |
| 10 | `updatedAt: string` on a nullable column (both domains) | FIXED |
| 11 | `AudioStorageException` documented as 503; it is 500 | FIXED (frontend) / BACKEND BLOCKER (annotation) |
| 12 | `canManageTtsTemplate` ignored capabilities | FIXED |
| 13 | `partitionTtsTemplatesByScope` dead helper | FIXED (removed) |
| 14 | Two byte-equivalent status badges | FIXED |
| 15 | `storageReference` and raw `tenantId` rendered | FIXED |
| 16 | TTS detail never rendered `scope` | FIXED |
| 17 | Both details conflated 403 / 404 / 500 / network | FIXED |
| 18 | `as any` / `no-explicit-any` in 7 files | FIXED |
| 19 | `confirm()` for delete; unguarded approve/reject | FIXED |
| 20 | Status filter did not reset the page | FIXED |
| 21 | Edit dialog had no `key`; stale form values | FIXED |
| 22 | `// ponytail:` comment | FIXED |
| 23 | Stale "F6 builds the scope-aware create form" comment | FIXED |
| 24 | Missing `aria-label`s on sort buttons and search inputs | FIXED |
| 25 | Upload `409 "Audio storage is disabled"` annotation | BACKEND BLOCKER (stale annotation) |
| 26 | Upload endpoint has no `@Valid` / `@Size` on any param | BACKEND BLOCKER (validation gap) |
| 27 | `AUDIO_MANAGE` held by `SUPER_ADMIN` but unusable (no tenant context) | PRODUCT DECISION |
| 28 | `TTS_MANAGE` held by `RESELLER_ADMIN` but unusable for create | PRODUCT DECISION |
| 29 | `RESELLER_ADMIN` lacks `AUDIO_MANAGE` | VERIFIED (intended by V1) |
| 30 | No scope filter on the TTS list | NOT SUPPORTED |
| 31 | No "is this content in use?" endpoint | NOT SUPPORTED |
| 32 | `DRAFT` / `ARCHIVED` statuses | NOT SUPPORTED (deliberately deferred upstream) |
| 33 | Tenant picker limited to 100 rows | Frontend limitation (documented) |
| 34 | Status filters in component state, not URL | Frontend limitation (documented) |
| 35 | jsdom / Testing Library | DEFERRED (F1, reconfirmed F3) |
| 36 | OpenAPI drift check | DEFERRED (not added) |
| 37 | First `next build` OOM | Host resource event, not an application failure |
| 38 | IVR capabilities enforced but unseeded | BACKEND BLOCKER (pre-existing, F1, unrelated) |

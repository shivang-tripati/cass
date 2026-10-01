# F4 — Campaigns

**STATUS: PASS WITH BLOCKERS**

---

## 1. Executive Summary

F4 audited the backend Campaign domain from the entity layer upward, derived the
real contract from source, and rebuilt the Campaign frontend against it.

**Backend files changed by F4: 0.**

The Campaign domain is by far the largest audited so far: 7 lifecycle states, 4
campaign types, a 5-variant `typeConfig` sealed hierarchy, typed integration
configuration, an immutable per-execution configuration snapshot, an execution
engine with a 30-second scheduler, a call-attempt model, and a real Queue domain
that `CONNECT_BY_AGENT` depends on.

21 drift items were found and fixed. The most consequential:

| # | Finding |
|---|---|
| **D3/D4** | The F1 lifecycle model offered three controls that could **only** return 409. `LEGAL_TRANSITIONS` still contains three engine-owned edges (`SCHEDULED→RUNNING`, `RUNNING→COMPLETED`, `RUNNING→FAILED`) that `changeStatus` refuses. The table filtered on `"is this terminal?"` instead of subtracting them, so a RUNNING campaign was offered "Pause", "Complete" and "Fail". |
| **D8** | `call-attempt-table.tsx` was 315 lines of complete, contract-checked UI **rendered by nothing**, and `lib/api/call-attempts.ts` (162 lines) existed only to feed it. Two dead links pointed at routes that had never existed. |
| **D9** | TTS content mode was offered for PLAYFILE and DTMF. `validateContent` rejects it for **every** type that can have content, so no campaign type can use a TTS template. Every submission was a 400. |
| **D10** | `typeConfig` and `integrationConfig` were free-text JSON textareas. Every backend parser calls `rejectUnknownFields`, so the form could only produce guaranteed-400 payloads. The edit dialog round-tripped stored JSON as a *string*, so a DTMF/CONNECT_BY_AGENT/MISSED_CALL campaign **could not be edited at all**. |
| **D7** | The Delete button called `window.location.reload()` and discarded the id. `deleteCampaign` sat unused. A destructive-looking control that did nothing. |
| **D5** | MISSED_CALL was absent from the create dialog — present in the filter and the badge, so the type was visible but uncreatable. |
| **D6** | Edit was offered on every campaign, but `assertEditable` is DRAFT-only, so any non-draft save was a 409. |
| **D11** | `UpdateCampaignPayload` sent `callOnWhitelistNumbers`, which is **not** a field of `UpdateCampaignRequest`. Silently discarded on every save. |
| **D15** | A **MID-PHASE BACKEND CHANGE** invalidated a conclusion. See §11. |

**Validation:** typecheck 0 · lint 0 errors / 2 warnings (both pre-existing
`form.watch()` React Compiler advisories) · **481 tests / 23 files** (was 356/19)
· `next build` success, 24 routes · `npm run validate` pass.

**No live/backend validation was performed** — see §14.

---

## 2. Baseline

| | |
|---|---|
| Branch | `main` |
| HEAD | `3a89e5cf8861d3ecb574757c1a66b3552c458745` |
| Frontend HEAD | same (single repo) |
| Backend modified at F4 start | 34 |
| Backend untracked at F4 start | 16 |
| Backend modified at F4 end | 34 |
| Backend untracked at F4 end | 16 |

Concurrent third-party backend work was active throughout — see §11 and §16.1.

---

## 3. Backend Campaign Contract

### 3.1 Identity — `CampaignEntity` (`campaigns`)

| Field | Type | Notes |
|---|---|---|
| `id` | UUID | |
| `tenantId` | UUID, NOT NULL | |
| `name` | varchar(200), NOT NULL | |
| `description` | text | |
| `campaignType` | `CampaignType` | |
| `status` | `CampaignStatus`, default `DRAFT` | |
| `runMode` | `CampaignRunMode`, default `ONE_TIME` | |
| `version` | Integer, default 1 | **lineage counter, not an optimistic lock** |
| `clonedFromCampaignId` | UUID | |
| `contactGroupId` | UUID | **single reference — no join table** |
| `didId` | UUID | |
| `contentMode` | `ContentMode` nullable | |
| `audioAssetId` | UUID | |
| `ttsTemplateId` | UUID | |
| `schedule` | `@Embeddable ScheduleSpec` | |
| `retryPolicy` | `@Embeddable RetryPolicySpec` | |
| `typeConfig` | JSONB | |
| `integrationConfig` | JSONB | |
| `callOnWhitelistNumbers` | Boolean, default false | |
| `dailyDialLimit` | Integer 1..3 | |
| `maxDailyAttempts` | Integer 1..10 | |
| `maxCallDurationSeconds` | Integer 1..3600 | |
| audit | `createdAt/By`, `updatedAt/By`, `deletedAt/By` | soft delete |

**No `@Version` field.** `version` is set only by `cloneOf` (`source + 1`). It is
a lineage counter; there is no optimistic locking anywhere in the campaign
aggregate.

### 3.2 Enums

```java
CampaignStatus      DRAFT, SCHEDULED, RUNNING, PAUSED, COMPLETED, FAILED, ARCHIVED
CampaignType        PLAYFILE, DTMF, CONNECT_BY_AGENT, MISSED_CALL
ContentMode         AUDIO, TTS
CampaignRunMode     ONE_TIME, RECURRING
RetryStrategy       FIXED                    // exactly one constant
RetryRuleCategory   NO_ANSWER, BUSY, HANGUP, FAILED, SWITCHED_OFF, NOT_REACHABLE
CampaignExecutionStatus  REQUESTED, RUNNING, COMPLETED, FAILED, CANCELLED
CallAttemptStatus   QUEUED, IN_PROGRESS, COMPLETED, FAILED, CANCELLED
QueueStatus         ACTIVE, INACTIVE, DISABLED
```

`CampaignType.playsMedia()` is `true` for exactly `PLAYFILE` and `DTMF`. The
switch has **no `default` arm**, so a new type cannot be added without stating
its capability — the property is compiler-enforced. Both the "content required"
and "TTS refused" rules derive from it, which is why the frontend mirrors the
property rather than a hand-maintained list.

---

## 4. Campaign Lifecycle

VERIFIED `CampaignService.java:68-83` and `:280-291`. `changeStatus` applies
**three** checks in order:

```java
LEGAL_TRANSITIONS = {
  DRAFT     -> {SCHEDULED}
  SCHEDULED -> {RUNNING, PAUSED, DRAFT, ARCHIVED}
  RUNNING   -> {PAUSED, COMPLETED, FAILED}
  PAUSED    -> {SCHEDULED, RUNNING, ARCHIVED}
  COMPLETED -> {ARCHIVED}
  FAILED    -> {ARCHIVED}
  ARCHIVED  -> {}
}
SYSTEM_DRIVEN_TRANSITIONS = {SCHEDULED>RUNNING, RUNNING>COMPLETED, RUNNING>FAILED}
```

1. edge not in `LEGAL_TRANSITIONS` → **409** "Illegal campaign lifecycle transition"
2. edge in `SYSTEM_DRIVEN_TRANSITIONS` → **409** "performed by the execution engine"
3. `DRAFT → SCHEDULED` **only** → additionally runs `validateActivation`

| From | Clickable (legal ∧ ¬engine) | Click that 409s |
|---|---|---|
| DRAFT | `SCHEDULED` | — |
| SCHEDULED | `PAUSED`, `DRAFT`, `ARCHIVED` | `RUNNING` |
| RUNNING | `PAUSED` | `COMPLETED`, `FAILED` |
| PAUSED | `SCHEDULED`, `RUNNING`, `ARCHIVED` | — |
| COMPLETED | `ARCHIVED` | — |
| FAILED | `ARCHIVED` | — |
| ARCHIVED | *(none — the only dead end)* | — |

**Editable:** `DRAFT` only. `assertEditable` → 409 otherwise. Note the asymmetry:
`SCHEDULED` can return to `DRAFT`, but **`PAUSED` cannot** — a paused campaign is
permanently locked. That is the backend's, not smoothed over.

**Executable:** `SCHEDULED` or `RUNNING` (`EXECUTABLE_STATUSES`). A DRAFT is
therefore never ready.

**Delete and clone have no lifecycle gate** — they work in any state including
ARCHIVED. Not hidden for terminal states.

Modelled once in `src/lib/domain/campaign-lifecycle.ts`; the full 7×7 matrix is
asserted in `campaign-lifecycle.test.ts` against a hand-written table so a
backend change fails the suite rather than silently drifting.

---

## 5. Campaign Type Matrix

`CampaignTypeConfig` is a **sealed interface** permitting exactly five
implementations, dispatched by an exhaustive `switch` with no `default` arm.

| Type | `typeConfig` | Bounds | Media | Referenced entities |
|---|---|---|---|---|
| `PLAYFILE` | `{}` **only** — a non-empty object is rejected | — | yes | audio asset |
| `DTMF` | `{dtmf:{expected, maxDigits, terminator?, timeoutSecs, action}}` | `expected` ≤16 collectable digits; `timeoutSecs` 1..120 (default 10); `maxDigits` defaults to `expected.length()` | yes | audio asset |
| `DTMF` (IVR variant) | `{ivr:{treeId, …frozen nodes}}` | `treeId` required | yes | IVR tree |
| `CONNECT_BY_AGENT` | `{connectByAgent:{queueId, selectionStrategy, ringDurationSeconds}}` | `ringDurationSeconds` 10..240; `selectionStrategy` **must** be `LEAST_ACTIVE_RESERVATIONS` (one constant) | no | **queue** |
| `MISSED_CALL` | `{missedCall:{ringDurationSeconds}}` | 10..60 | no | — |

The discriminator is **not** `campaignType`: DTMF has two mutually exclusive
shapes, selected by the presence of `typeConfig.ivr`. `IvrCampaignConfig
.campaignType()` returns `DTMF`.

Every parser calls `rejectUnknownFields` and returns 400 for an unrecognised
key. This is why the free-text JSON textareas were not merely poor UX.

---

## 6. Relationship Model

Only relationships verified in source are listed.

```
Campaign (tenant-owned)
 ├─ contactGroupId ──▶ ContactGroup (same tenant, not deleted)   [OPTIONAL]
 │                      └─▶ memberships ──▶ Contacts             live, not snapshotted
 ├─ didId ──────────▶ Did (same tenant, ACTIVE **and** ASSIGNED) [OPTIONAL]
 ├─ audioAssetId ───▶ AudioAsset (same tenant, APPROVED, non-blank storageReference)
 ├─ ttsTemplateId ──▶ TtsTemplate (APPROVED, GLOBAL **or** same tenant)
 └─ typeConfig.connectByAgent.queueId ──▶ Queue (same tenant, ACTIVE)

Campaign ──(POST /{id}/executions)──▶ CampaignExecution
                                          └─ configurationSnapshotId ──▶ CampaignExecutionConfiguration  [@Immutable]
                                                                                └─ the frozen config (NOT exposed by any endpoint)

CampaignExecution ──▶ CallAttempt (contactId, didId, attemptNumber, scheduledAt from the snapshot)
CallAttempt ──▶ telephony runtime (ESL/FreeSWITCH — no REST surface)
```

**Audience is a single group**, not many. `contactGroupId` is one nullable column;
there is no join table. Membership is **live**: `CallAttemptService.createAttempt`
verifies the contact is in the *snapshot's* group at creation time. Nothing
snapshots group membership.

**TTS and Audio are mutually exclusive by `contentMode`**, and both are stored as
separate columns guarded by a service invariant rather than a DB constraint.

---

## 7. Authorization Matrix

VERIFIED call sites. **There is no `@PreAuthorize` anywhere in the Campaign
package** — every check is `requireCapability` inside a service method against
the server-derived `OrganizationContext`.

| Endpoint | Method | Capability | Extra precondition |
|---|---|---|---|
| `/campaigns` | GET | `CAMPAIGN_VIEW` | tenant / reseller-hierarchy / platform branch |
| `/campaigns/{id}` | GET | `CAMPAIGN_VIEW` | boundary-constrained → foreign = 404 |
| `POST /campaigns` | POST | `CAMPAIGN_MANAGE` | **target tenant must exist and be ACTIVE** |
| `PUT /campaigns/{id}` | PUT | `CAMPAIGN_MANAGE` | **status must be DRAFT** (409) |
| `DELETE /campaigns/{id}` | DELETE | `CAMPAIGN_MANAGE` | none — any status |
| `POST /campaigns/{id}/clone` | POST | `CAMPAIGN_MANAGE` | none — any status |
| `PATCH /campaigns/{id}/status` | PATCH | **`CAMPAIGN_EXECUTE`** | legal ∧ ¬engine-driven edge |
| `GET /campaigns/{id}/readiness` | GET | `CAMPAIGN_VIEW` | boundary-constrained |
| `POST /campaigns/{id}/executions` | POST | **`CAMPAIGN_EXECUTE`** | readiness must pass (422) |
| `GET …/executions`, `…/{id}` | GET | `CAMPAIGN_EXECUTE` | boundary-constrained |
| all attempt endpoints | POST/GET/PATCH | `CAMPAIGN_EXECUTE` | execution must be dispatchable |

### `CAMPAIGN_ASSIGN` and `CAMPAIGN_EXPORT` ARE DEAD KEYS

Exhaustive search of `src/main/java` and `src/test/java`: **zero occurrences.**
They exist only in `V1` — the catalogue rows, and the grants to `SUPER_ADMIN`,
`RESELLER_ADMIN` and `TENANT_ADMIN`. `V1` even describes `CAMPAIGN_ASSIGN` as
"Assign agents or queues to campaigns", but queue assignment lives behind
`QUEUE_VIEW`/`QUEUE_MANAGE` and no campaign endpoint assigns anything.

Consequence, tested from both sides: no Campaign action is gated on either key,
**and** withholding a real action for lacking one is equally wrong. `SUPER_ADMIN`
and `TENANT_ADMIN` (hold `CAMPAIGN_ASSIGN`) and `RESELLER_ADMIN` (does not) see
an **identical** Campaign surface. `campaign-gates.test.ts` asserts this.

### Seeded role grants (V1)

| Role | VIEW | MANAGE | EXECUTE | ASSIGN | EXPORT |
|---|:-:|:-:|:-:|:-:|:-:|
| SUPER_ADMIN | ✓ | ✓ | ✓ | ✓ | ✓ |
| RESELLER_ADMIN | ✓ | ✓ | ✓ | — | ✓ |
| TENANT_ADMIN | ✓ | ✓ | ✓ | ✓ | ✓ |
| AGENT | — | — | — | — | — |
| REPORT_VIEWER | — | — | — | — | — |

---

## 8. Tenant / Scope Matrix

| Scope | List | Read | Create | Notes |
|---|---|---|---|---|
| TENANT | own tenant | own | own tenant, no param | `?tenantId=` **ignored** |
| RESELLER | own **+ ACTIVE tenants'** campaigns | hierarchy | `?tenantId=` **required** | same-tenant invariant not relaxed by `SUPER_ADMIN` |
| PLATFORM | all non-deleted | any | `?tenantId=` **required** | target must be ACTIVE |

`create` is the **fourth** endpoint in the codebase gated on a tenant context —
but unlike `POST /contact-groups` (F2) and `POST /audio-assets`/`upload` (F3), it
**honours `?tenantId=`** for platform and reseller callers, exactly as
`DidService.create` does. So this is the one place the backend genuinely offers
a target-tenant choice, and the create dialog is scope-aware rather than blind.

It is still a real parameter, not a frontend tenant selector: the service checks
`CAMPAIGN_MANAGE` against `forTenant(target)`, so a reseller can only target a
tenant in its own hierarchy (`coversReseller`). `operating-context.ts` remains
the single scope mechanism and gained nothing.

**Reference-data scoping limitation** (documented, not worked around): none of the
five list endpoints accepts `tenantId`, and `DidService.list` honours the one it
does have only in the platform branch. A platform/reseller user therefore needs
the fetched rows narrowed client-side or every pick is a guaranteed 400 — see
§12.3.

---

## 9. `CONNECT_BY_AGENT` / Queue Finding

**Queue integration IS available.** This is a positive finding and it corrects
the assumption carried from F1/F3.

- `QueueDirectoryController` at `/api/v1/queues`, 11 endpoints, fully REST-exposed
- `QUEUE_VIEW` / `QUEUE_MANAGE` seeded by `V37__create_queue_foundation.sql` and
  granted to `SUPER_ADMIN`, `RESELLER_ADMIN`, `TENANT_ADMIN` (deliberately not to
  `AGENT`)
- `QueueReferenceService implements AgentQueueReferenceChecker` — a real bean,
  so `validateQueue` does not fall through to `QUEUE_NOT_AVAILABLE`
- `QueueResponse` had **no frontend type at all** until F4 added one

So `CONNECT_BY_AGENT` is genuinely configurable and gets a real queue picker,
filtering to `QueueStatus.ACTIVE` because `usabilityOf` requires ACTIVE.
Live agent availability and queue depth are **not** filtered: they are runtime
facts the backend explicitly does not gate on.

**IVR, by contrast, remains unreachable.** `POST /campaigns/{id}/ivr-tree`
requires `IVR_MANAGE`, which no migration seeds — the F1/F3 blocker, still
unresolved. An `ivr` type config can only be produced by that endpoint, and
`IvrSnapshotCodec` then has to decode the frozen node set. Both halves of the
round-trip are unavailable, so **no IVR editor is built**; a DTMF campaign
referencing a tree is displayed read-only.

---

## 10. Snapshot Semantics

`CampaignExecutionConfiguration` is `@Immutable`, every column is
`updatable = false`, and it is created **only** by
`CampaignConfigurationService.createExecutionSnapshot` — called from
`CampaignExecutionService.execute`, i.e. **at execution creation**. Not at
create, not at update, not at activation.

**Copied:** `campaignType`, `contactGroupId`, `didId`, `contentMode`,
`audioAssetId`, `ttsTemplateId`, all 7 schedule fields, all 4 retry fields plus
per-category `rules`, `maxDailyAttempts`, `maxCallDurationSeconds`, `typeConfig`
(canonicalised, with IVR nodes frozen at execution time), `integrationConfig`
(canonicalised), `callOnWhitelistNumbers`, `dailyDialLimit`.

**Not copied:** `name`, `description`, `runMode`, `version`, lineage, timestamps.

**Therefore: updating a campaign does NOT change an existing execution.** Stated
in the edit dialog and the execution detail page. It is also why only DRAFT is
editable — a running campaign is already executing against its own snapshot.

**No REST endpoint returns the snapshot body.** An exhaustive scan of the 15
`@RequestMapping` controllers found none. `configurationSnapshotId` is the only
evidence, so the UI presents it as exactly that and offers no snapshot viewer or
editor. `CallAttemptService.createAttempt` independently documents that
`didId`, `scheduledAt` and the permitted attempt number all come from the
snapshot.

---

## 11. MID-PHASE BACKEND CHANGE — the one invalidated conclusion

The backend was being modified **concurrently throughout F4** (34 modified / 16
untracked at both start and end; files appearing at 04:21, 04:25, 04:52, 04:54,
04:55, 05:02).

F4 initially concluded, correctly from the code read at the start, that **no
execution engine existed** — `CampaignExecutionService.execute` only writes a
`REQUESTED` row, and the controller says execution "is performed by the future
execution engine". On that basis the F1 polling was **removed** and the UI was
told "nothing dials until an execution engine processes it".

A final re-verification before writing this report found that this is **no longer
true**:

- `CampaignExecutionOrchestrator` — `@Service`, `@Scheduled(fixedDelay = 30000)
  scheduledTick()`, six pipeline steps: start REQUESTED executions, process
  retries, dial due attempts, pump ESL events, reconcile RUNNING executions,
  reconcile stale calls, terminate MISSED_CALL ring budgets
- `@EnableScheduling` on `ObdApplication`, plus ten other `@Scheduled` pollers
- Status writes: `REQUESTED → RUNNING` (+`startedAt`), `RUNNING → COMPLETED |
  FAILED` (+`completedAt`)
- `CampaignExecutionStatus.TERMINAL = {COMPLETED, FAILED, CANCELLED}` and
  `DISPATCHABLE = {REQUESTED, RUNNING}`
- `OutboundDialService.processDueAttempts()` is real dialling
- Backed by three new test classes written during F4:
  `CampaignExecutionOrchestratorSchedulerTest`,
  `SchedulerTransactionBoundaryPostgresIntegrationTest` and
  `SchedulerCrashRecoveryPostgresIntegrationTest`

Note on the controller count: there are 18 files named `*Controller.java`, but
only **15** carry a `@RequestMapping`. The other three —
`VoiceMediaController`, `FreeSwitchVoiceMediaController` and
`NoOpVoiceMediaController` — have **no HTTP mapping at all**; they are
`@ConditionalOnProperty`-selected interfaces with a FreeSWITCH implementation and
a no-op default, so no media path is served over HTTP. The "0 of 15 controllers
is a scheduler" claim below is stated against the 15 that are actually mapped.

**Corrections applied:**

1. Polling restored on the execution list, the execution detail and the attempt
   list, at **5 s** — chosen against the 30 s engine tick rather than picked, so a
   transition appears within one poll of it happening. Polling stops once nothing
   is dispatchable.
2. All user-facing copy corrected: an execution is picked up "on its next
   30-second cycle", not "never".
3. The route-guard assertion re-based: the reason there is no scheduler route is
   that **the engine exists and is deliberately not exposed** (0 of the 15
   mapped controllers is a scheduler), not that it does not exist.

**The lifecycle model was unaffected and is in fact now better justified** — the
engine owns exactly the three edges `changeStatus` refuses with 409, which is
precisely why they are refused. That is the strongest possible confirmation that
`availableTransitions` subtracts the right set.

This is recorded rather than quietly fixed, because a frontend conclusion that
depended on backend state is exactly the kind of thing a later reader needs to
know was re-checked.

---

## 12. Frontend Changes

### 12.1 Added (12)

| File | Purpose |
|---|---|
| `lib/domain/campaign-lifecycle.ts` | the verified 7-state machine |
| `lib/domain/campaign-lifecycle.test.ts` | full 7×7 transition matrix |
| `lib/auth/campaign-gates.ts` | capability table + the two dead keys |
| `lib/auth/campaign-gates.test.ts` | authorization, role grants, create preconditions |
| `lib/api/campaign-references.ts` | the five reference fetchers, transport-only |
| `lib/api/campaign-references.test.ts` | tenant filtering, page-cap honesty |
| `components/campaigns/campaign-type-config-fields.tsx` | typed DTMF / CONNECT_BY_AGENT / MISSED_CALL editors |
| `components/campaigns/campaign-schedule-field.tsx` | schedule editor, timezone requirement |
| `components/campaigns/campaign-retry-policy-field.tsx` | flat defaults + per-category rules |
| `components/campaigns/campaign-integration-field.tsx` | typed webhook + report privacy |
| `components/campaigns/campaign-content-fields.tsx` | audience / DID / content, TTS refusal stated |
| `components/campaigns/campaign-readiness-panel.tsx` | typed readiness reason vocabulary |
| `components/campaigns/campaign-config-cards.tsx` | labelled renderers replacing JSON dumps |
| `components/campaigns/delete-campaign-dialog.tsx` | the delete that actually deletes |
| `components/campaigns/execution-attempts-view.tsx` | the surface that was orphaned |
| `components/common/campaign-execution-status-badge.tsx` | one badge, was two private maps |
| `app/(platform)/campaigns/[campaignId]/executions/[executionId]/attempts/page.tsx` | the missing route |

### 12.2 Modified (12)

`lib/api/campaigns.ts`, `lib/api/contracts.ts`, `lib/schemas/campaign-mutation.ts`,
`lib/api/contact-links.test.ts`, `components/campaigns/campaigns-view.tsx`,
`campaign-table.tsx`, `campaign-detail-view.tsx`, `create-campaign-dialog.tsx`,
`edit-campaign-dialog.tsx`, `change-status-dialog.tsx`,
`execution-detail-view.tsx`, `lib/auth/capabilities.ts` (comment only).

### 12.3 Deleted (1)

`components/campaigns/campaign-filter-toolbar.tsx` was **moved** from
`components/common/` to `components/campaigns/` — it is campaign-specific and was
in a shared directory.

`components/campaigns/call-attempt-table.tsx` was **deleted** (315 lines). It was
unreachable, linked to a nonexistent route, and fired mutations from `onClick`
with no invalidation or error handling. `execution-attempts-view.tsx` supersedes
it. A new test asserts **no** component under `components/campaigns` is
unimported, so the whole class recurs silently.

### 12.4 Architecture compliance

| Rule | Status |
|---|---|
| No `fetch`/axios in components | ✓ (`refetch()` matched only) |
| `transport.ts` sole owner of the envelope | ✓ — D1/D2 fixed 5 violations |
| No `any` introduced | ✓ (all matches are English prose) |
| No fabricated pagination | ✓ |
| No second API client | ✓ |
| No invented tenant selector | ✓ |
| Every `href` resolves | ✓ — test extended to campaigns |

---

## 13. Tests

| File | Tests | Covers |
|---|---:|---|
| `campaign-lifecycle.test.ts` | 38 | full 7×7 matrix, engine-driven subtraction, edit/execute sets, message reproduction |
| `campaign-gates.test.ts` | 23 | the three-way capability split, the two dead keys, role grants, target-tenant rules |
| `campaign-mutation.test.ts` | 32 | TTS impossibility, per-type `typeConfig`, ring-window bounds, schedule, PUT semantics, limit ranges |
| `campaign-references.test.ts` | 15 | tenant filtering, GLOBAL TTS exemption, page-cap honesty |
| `contact-links.test.ts` (extended) | +14 | campaign routes, no dead links, no orphan components, no phantom route params |
| **Total** | **481** / 23 files | was 356 / 19 |

Coverage of the required matrix: contract ✓ · authorization ✓ · lifecycle ✓
(exhaustive) · audience ✓ · content ✓ (APPROVED / non-approved / TTS / deleted
via readiness codes) · type-specific ✓ (all 4 + the IVR variant) ·
CONNECT_BY_AGENT ✓ (real picker + ownership) · snapshot ✓ (id-only, immutability,
update-vs-existing-execution) · route ✓ · error ✓ (403/404/409/422/5xx/network).

**Not covered, and why:** component/DOM rendering. jsdom + Testing Library remain
deferred from F1, and every drift-prone decision here was pure and testable.
Mocked tests would prove nothing about backend behaviour and are not counted as
evidence anywhere in this report.

---

## 14. Validation

| Command | Result |
|---|---|
| `npm run typecheck` | **exit 0** |
| `npm run lint` | **0 errors, 2 warnings** — both the pre-existing `form.watch()` React Compiler advisory (one F3-era in TTS, one in the campaign create dialog) |
| `npm test` | **481 passed / 23 files** |
| `npm run build` | **exit 0** — 24 routes (was 23; +1 for `/attempts`) |
| `npm run validate` | **exit 0** |

### Static vs live

**Static/contract validation only.** No backend was run. Live validation requires
PostgreSQL, Redis, FreeSWITCH, and — for the campaign execution path that now
exists — a working telephony provider. **No claim in this report is based on
observed backend behaviour.** Every "VERIFIED" is a source-level fact.

No build OOM this phase; the build completed in ~40 s.

---

## 15. Backend Changes

```
Backend files changed: 0
```

Every finding is either frontend drift or a documented backend limitation.
Backend source was read only.

---

## 16. Blockers

### 16.1 Technical — concurrent modification (process risk, not a defect)

The backend changed **during** F4, and §11 is a live example of a frontend
conclusion invalidated by it. The campaign/telephony domain is under active
development (`OrphanedDispatchRecovery`, `StaleCallReconciler`, `EslEventService`
and six new test classes appeared mid-phase). **A re-audit of the execution and
snapshot sections is warranted before F5.**

### 16.2 Backend contract blockers

| # | Blocker | Impact |
|---|---|---|
| B1 | **`IVR_VIEW`/`IVR_MANAGE` are not seeded.** `IvrTreeService` and `POST /campaigns/{id}/ivr-tree` enforce them, so **every** IVR endpoint 403s for **every** role. | No IVR editor. A DTMF campaign with an `ivr` type config is read-only. Fix: a migration mirroring V20/V37. |
| B2 | **No endpoint returns an execution configuration snapshot.** | The snapshot is evidenced only by its id. Fix: a read endpoint, or accept the id as the contract. |
| B3 | **`holidayCalendarId` is an unvalidatable UUID.** No holiday-calendar entity, repository or endpoint exists, and readiness ignores it. | Preserved on read, no editor built. Needs either a domain or removal from `ScheduleConfig`. |
| B4 | **The 100-row cap on all five reference lists.** No endpoint exposes a total, so the UI cannot know whether it is truncated. | Every picker warns when a page comes back full. Fix: a total, or a search-backed picker. |
| B5 | **Reference lists cannot be scoped to a target tenant** (§8). `DidService.list` honours `tenantId` only for platform callers; the other four ignore it. | Platform/reseller creators need client-side narrowing. A `tenantId` parameter on all five would remove the need. |
| B6 | `CampaignMapper.cloneOf` **drops** `callOnWhitelistNumbers` (defaults false) and the per-category retry `rules`. | A clone is not a faithful copy. Frontend does not claim it is; the clone dialog states the source is copied. |
| B7 | `V55__execution_snapshot_integration_config.sql` is still **untracked**. | The snapshot's `integrationConfig` contract is not reproducible from a clean checkout. |
| B8 | No `@Valid`/size validation on several campaign request bodies; bounds live only in service validators and DB CHECKs. | A client that skips Zod still gets a 400 from the service, not a 500 — acceptable, but bean validation would be more honest. |

### 16.3 Product decisions

| # | Decision | Note |
|---|---|---|
| P1 | **TTS content is unreachable for every campaign type.** Not a bug in this phase — `validateContent` refuses it for `playsMedia()` types and non-media types take no content. | Needs a backend TTS playback runtime before a TTS picker is honest. Until then the option is absent and the reason is stated. |
| P2 | `retryDelay` "measured from the moment the attempt failed and is timezone-independent; clamping into the campaign's calling hours is applied separately from the execution snapshot." | The clamping is not described anywhere. F4 does not display it. |
| P3 | `MAX_DURATION_EXCEEDED` "the campaign retry policy may then govern because the subscriber was genuinely called." | The UI does not present `maxCallDurationSeconds` as governing a ring timeout. Confirm the intent. |
| P4 | `SWITCHED_OFF` and `NOT_REACHABLE` are accepted retry categories that no provider outcome feeds. | Hidden from the picker; shown read-only if present on a stored policy. |
| P5 | A `PAUSED` campaign can never return to `DRAFT`, so it is permanently uneditable. | Is that intended? `SCHEDULED` can. |

### 16.4 Deferred functionality (deliberately not built)

Scheduler or execution dashboard · runtime metrics · audio preview/playback ·
TTS synthesis or playback · campaign analytics or delivery statistics · content
"in use" lookup · content dependency checker · live FreeSWITCH controls ·
snapshot viewer or editor · fake queue picker (**N/A — a real one was built**) ·
arbitrary tenant selector · IVR editor.

**Scheduler/runtime boundary, stated precisely:** the engine and all eleven
`@Scheduled` pollers exist and have **zero REST surface** — 0 of the 15 mapped
controllers is a scheduler. So the frontend observes execution *outcome* only, and
only through `CampaignExecutionResponse`. No FreeSWITCH, ESL, Sofia, RTP, UUID or
dial-string internal is exposed anywhere in the Campaign UI. The three
`VoiceMedia*Controller` files carry no HTTP mapping at all, so no media path is
reachable over HTTP from any deployment configuration.

---

## 17. F5 Readiness

**Ready, with one re-verification step.**

| Area | Backend verified | Frontend implemented | Tests | Status |
|---|:-:|:-:|:-:|---|
| CampaignEntity | ✓ | ✓ | ✓ | PASS |
| Lifecycle / status | ✓ | ✓ | ✓ (exhaustive) | PASS |
| Create / update DTOs | ✓ | ✓ | ✓ | PASS |
| `type` + `typeConfig` | ✓ | ✓ | ✓ | PASS |
| Audience / groups | ✓ | ✓ | ✓ | PASS |
| Audio | ✓ | ✓ | ✓ | PASS |
| TTS | ✓ | ✓ (refusal stated) | ✓ | PASS — P1 open |
| Integration | ✓ | ✓ | ✓ | PASS |
| Execution config | ✓ | ✓ | ✓ | PASS |
| Snapshot | ✓ | ✓ (id only) | ✓ | PASS — B2 open |
| Activation | ✓ | ✓ | ✓ | PASS |
| `CAMPAIGN_VIEW` | ✓ | ✓ | ✓ | PASS |
| `CAMPAIGN_MANAGE` | ✓ | ✓ | ✓ | PASS |
| `CAMPAIGN_EXECUTE` | ✓ | ✓ | ✓ | PASS |
| `CAMPAIGN_ASSIGN` | ✓ (dead) | ✓ (gates nothing) | ✓ | PASS |
| `CONNECT_BY_AGENT` | ✓ | ✓ (real picker) | ✓ | PASS |
| Queue dependency | ✓ | ✓ | ✓ | PASS |
| Scheduler boundary | ✓ | ✓ | ✓ | PASS |
| Runtime boundary | ✓ | ✓ | ✓ | PASS |
| Stale / non-approved content | ✓ | ✓ (readiness codes) | ✓ | PASS |
| Tenant isolation | ✓ | ✓ | ✓ | PASS |
| Error handling | ✓ | ✓ | ✓ | PASS |
| Tests | — | — | 481 / 23 | PASS |
| Build / typecheck / lint | — | — | — | PASS |

**Before F5 begins:** re-read the campaign and telephony packages. The backend
moved under this phase (§11) and the execution path is new, so any F5 conclusion
drawn from F4's execution/snapshot reading must be re-derived.

**Suggested F5 scope:** DIDs (already partially modelled, with a genuine
platform/reseller/tenant scope split and an `assign`/`revoke` pair that a tenant
caller is refused with **400, not 403**), and Queues — whose API is fully
REST-exposed, whose `QUEUE_VIEW`/`QUEUE_MANAGE` split is seeded and enforced, and
which has **no frontend surface at all** despite F4 needing a read-only slice of
it for `CONNECT_BY_AGENT`.

# F0 Artifact — Migration Plan

Canonical source: [`../F0-FRONTEND-AUDIT.md`](../F0-FRONTEND-AUDIT.md) §19, §20, §32.

Derived from the dependency graph the repositories actually exhibit — **not** from the
illustrative order in the F0 brief. Two evidence-based deviations are called out below.

## Sequence

```
F0  Audit (complete)                       → docs/frontend/F0-FRONTEND-AUDIT.md
  ↓
F1  Foundation — contract alignment
      • 1 hard enum + 7 missing DTO fields + 1 phantom field
      • 2 wrong request shapes (S7, S8)
      • commit /v3/api-docs → docs/api/openapi.json + a drift check
      • error.tsx / not-found.tsx, a real route guard
      • unwrap doc comment, remove zustand, resolve next-themes
      GATE: typecheck exit 0 · lint 0 errors · openapi drift check green
  ↓
F2  RBAC — action-level capability gating
      • extend the Capability catalog 19 → 34 keys
      • gate every mutating action (0 of 12 currently checked)
      • stop inferring scope from capabilities; document homeType as the only signal
      GATE: a RESELLER_ADMIN sees no action that 403s; no ungated mutation remains
  ↓
F3  Tenant & context model                  ← BLOCKED ON U3
      • decide and implement reseller tenant selection
      • wire the hardcoded resellerId: null (S17)
      GATE: a reseller can create a tenant under itself and scope its view
  ↓
F4  Contacts & Groups
      • align ContactResponse / ContactGroupResponse (S9, S10)
      • decide roster (/members) vs child-CRUD (/contacts) — resolve U8
      GATE: create/edit/delete/import/export verified against the live endpoints
  ↓
F5  Audio — implement multipart upload      ← remediation of S14
      • POST /audio-assets/upload (name, description, file)
      • remove the hand-typed checksum / storageReference fields
      GATE: an uploaded WAV/MP3 is APPROVED-eligible and selectable by a campaign
  ↓
F6  TTS — split the two scopes
      • model TtsTemplateScope; show it in list + detail (S11)
      • add scope to the create payload; GLOBAL forbids tenantId (S12)
      • make the APPROVED-only campaign picker explain itself
      GATE: GLOBAL and TENANT templates are distinguishable and each is editable
            only by its owner
  ↓
F7  Campaign foundation
      • typed per-type typeConfig editors: PLAYFILE, DTMF, MISSED_CALL (S1)
      • expose the 4 new fields + retry rules (S2, S3)
      • wire the already-written clone and status-change dialogs (S18)
      GATE: a campaign of each type round-trips its configuration exactly
  ↓
F8  Queue surface                          ← PREREQUISITE, must precede F9
      • list / detail / members per /api/v1/queues
        (mind: default sort name,asc; sort.length must be ≥ 2; members are unpaginated)
      GATE: a tenant can select a queue it owns
  ↓
F9  Campaign type configuration — complete
      • CONNECT_BY_AGENT editor driven by real queue data
      • display the snapshot boundary (configurationSnapshotId, S6) on execution detail
      GATE: CONNECT_BY_AGENT round-trips, and editing a campaign demonstrably does NOT
            alter an existing execution
  ↓
F10 Execution & attempt visibility
      • executions list page; honest unpaginated rendering for executions and attempts (S8)
      • human-readable failure-code labels grouped by disposition
      GATE: no fabricated PaginationMetadata; failure diagnostics actually persist
  ↓
F11 Agent surface (only if in product scope)
      • list / detail / endpoints; mind B8 (ignored agentId) and B9 (presence vs availability)
```

### Blocked / do not plan

| Target | Blocker |
|---|---|
| **IVR UI** | Backend defect — `IVR_VIEW`/`IVR_MANAGE` are never seeded, so all IVR endpoints 403 for every role (audit §18.1). Additionally IVR is **tenant-only** (B7). |
| **Scheduler UI** | **NOT EXPOSED BY BACKEND.** No endpoint for scheduler status, dispatch queue depth or runtime metrics. |
| **Dashboard** | No aggregate/metrics endpoint found (U4). `REPORT_VIEW`/`REPORT_EXPORT` are seeded but enforced by no controller. |

## Two evidence-based deviations from the brief's example order

1. **Queues precede Campaigns** rather than following them.
   `CONNECT_BY_AGENT.typeConfig` requires `connectByAgent.queueId` referencing a queue owned by
   the campaign's tenant (`dto/CreateCampaignRequest.java:50-56`). A tenant with queues still
   cannot configure one campaign type, because there is no queue API client, no route and no
   picker. Campaigns are therefore not feature-complete at F7.

2. **Audio upload precedes TTS/Campaign content work.**
   Campaign content selection requires `APPROVED` assets, and the only frontend creation path
   (`POST /audio-assets` with hand-typed metadata) produces un-verified records. Building
   campaign content selection on top of it would build on a broken prerequisite.

## Dependency constraints

| Constraint | Reason |
|---|---|
| F1 → **everything** | every later domain builds on the corrected type layer |
| F2 → F5, F6, F7 | approve/reject and manage must be gated before those surfaces are rebuilt, or the new UI inherits the same ungated-action defect (S22) |
| F5 → F7 | campaign content selection requires approved, genuinely uploaded assets |
| F6 → F7 | campaign content selection requires approved TTS templates |
| F8 → F9 | `CONNECT_BY_AGENT` is unusable without queue data |
| F1 → F10 | `configurationSnapshotId` must be modelled before the snapshot boundary can be shown |
| F3 → F4, F7 | tenant-scoped screens depend on the tenant/context model |

## Risks to carry into F1

| # | Risk | Mitigation |
|---|---|---|
| R1 | Backend is drifting fast (`V55` and its tests are **untracked** in git) | Commit V55 before relying on the snapshot contract; pin the OpenAPI spec in F1 |
| R2 | 34 capability keys with **no** enforced mapping to UI actions (U1) | Product decision required before F2 can be specified |
| R3 | The frontend has **zero tests** and there is no drift tripwire | F1's OpenAPI drift check is the single highest-value investment |
| R4 | `typeConfig` is a validated-but-untyped `JsonNode` on the wire | F7 needs per-type TypeScript models + zod mirroring the backend `config/` records |
| R5 | 53 `CallFailureCode` values with no display vocabulary | Define a grouped label map before F10; never surface raw codes |
| R6 | Capability presence in `/me` is a scope-**union** | Never infer scope from capabilities; use `homeType` only |
| R7 | No frontend test runner exists | Choose one in F1 (Vitest is the lowest-friction fit for Next 16) |

## F1 readiness checklist

**Already satisfied by F0** ✔ — every item in the audit §20 "Audit baseline" block, including:
repositories inspected and mapped · API inventory built from controller source · DTOs, enums
and validation annotations compared field-by-field · auth flow traced · RBAC derived from
migrations · tenant boundary traced JWT→repository · contract/staleness/RBAC matrices ·
reuse-refactor-rebuild classification · campaign config→snapshot→execution→attempt
relationship verified in source · audio/TTS ownership verified · error and pagination contracts
compared · FreeSWITCH boundary confirmed clean · tests and validation commands inspected and
executed · unknowns enumerated (U1–U12) · migration sequence derived from observed dependencies.

**Must be true before F1 code is written**

- [ ] Owner decision on **U3** — reseller tenant selection. Blocks F3 and every tenant-scoped screen.
- [ ] Owner decision on **U1** — the capability → action mapping for the 12 ungated keys. Blocks F2.
- [ ] Owner decision on **U6** — is `MISSED_CALL` in frontend scope? Fully implemented backend-side; excluding it keeps the campaign type filter permanently wrong.
- [ ] `/v3/api-docs` committed to `docs/api/openapi.json` and designated the contract source of truth.
- [ ] Written agreement that the backend is authoritative for all 25 staleness findings, and that F1 changes the frontend only.
- [ ] A test-runner decision.
- [ ] Confirmation that `docs/api/` is the right home for the committed spec, or a decision on another convention.
- [ ] Backend owner acknowledgement of **audit §18.1** (IVR capability seeding) — either scheduled, or IVR explicitly out of frontend scope.
- [ ] Backend owner acknowledgement of **B1** (nullable `admin` → 500) so the reseller-creation contract can be pinned.
- [ ] Confirmation that the untracked **`V55__execution_snapshot_integration_config.sql`** will be committed before the snapshot contract is relied upon.

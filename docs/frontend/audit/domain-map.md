# F0 Artifact — Domain Map

Canonical source: [`../F0-FRONTEND-AUDIT.md`](../F0-FRONTEND-AUDIT.md) §5.

"Backend" = endpoint count from the controller source. "Frontend" = routes under
`frontend/src/app/`.

| Domain | Backend package / prefix | Endpoints | Frontend coverage | Verdict |
|---|---|---|---|---|
| **Auth** | `security/AuthController` `/api/v1/auth` | 6 | complete | **Covered** |
| **Signup** | `tenant/TenantSignupController`, `reseller/ResellerSignupController` `/api/v1/account/signup` | 2 | complete | **Covered** |
| **Authorization** | `authz/` capability engine, 34 capability keys, 5 roles | n/a (cross-cutting) | nav only | **Under-built** |
| **Tenant** | `tenant/` `/api/v1/tenants` | 6 + 1 signup | list/detail/create/edit/delete; agent creation missing | **Partial** |
| **Reseller** | `reseller/` `/api/v1/resellers` | 5 + 1 signup | full CRUD | **Covered** |
| **Users** | `account/` `/api/v1/users` | 3 (read + update) | list/detail/update | **Covered** — backend has no create/delete either |
| **Contacts** | `contact/ContactGroupController` — **all under `/api/v1/contact-groups`** | 18 | child CRUD + import/export via group; the 6 `/members` roster endpoints **unused** | **Covered, sub-optimal** |
| **Contact Groups** | same controller | (8 of the 18) | full CRUD + members UI + import/export | **Covered** |
| **Audio** | `audio/` `/api/v1/audio-assets` | 8 (incl. multipart `POST /upload`) | metadata CRUD + approve/reject; **upload not implemented** | **Partial** |
| **TTS** | `tts/` `/api/v1/tts-templates` | 7, with `GLOBAL`/`TENANT` scope | one flat, scope-blind list; `scope` absent from UI **and** payload | **Partial** |
| **DIDs** | `did/` `/api/v1/dids` | 7 (incl. `assign`/`revoke`) | list/detail/create/edit/delete; assign/revoke unused | **Partial** |
| **Campaigns** | `campaign/CampaignController` | 15 | list/detail/create/edit/delete; **clone + status change are `TODO` stubs**; `MISSED_CALL` unmodelled | **Partial / stale** |
| **Campaign→IVR bridge** | `campaign/CampaignIvrController` `POST /campaigns/{id}/ivr-tree` | 1 | none | **No frontend** |
| **Executions** | nested under campaigns | 2 (create/get) + 1 list | detail page only; **no executions list route** | **Partial** |
| **Call Attempts** | nested under executions | 6 | table + transitions; **failed-diagnostics request shape is wrong** | **Partial / wrong** |
| **IVR** | `ivr/` `/api/v1/ivr-trees` | 6, **tenant-only** | none | **Not built + backend-blocked** (see audit §18.1) |
| **Queues** | `voice/queue/` `/api/v1/queues` | 12 | none | **Not built** — blocks `CONNECT_BY_AGENT` |
| **Agents** | `voice/agent/` `/api/v1/agents` | 17 | none | **Not built** |
| **Scheduler** | `CampaignExecutionOrchestrator`, `AgentConnectTimeoutScheduler`, `StaleCallReconciler` | **0** | none | **NOT EXPOSED BY BACKEND** |
| **FreeSWITCH / ESL** | `telephony/` | 0 (infrastructure) | none — **correctly** | Internal only |

## Entities deliberately NOT modelled in the frontend

| Backend type | Why it matters |
|---|---|
| `TtsTemplateScope` (`GLOBAL`\|`TENANT`) | Splits TTS into two differently-owned resources |
| `RetryRuleCategory` + `RetryRuleConfig` | Per-category retry rules the UI cannot express |
| `CallFailureCode` (53 values) | Frontend has `failureCode: string` with no vocabulary |
| `ContactGroupMemberResponse` / `BatchMemberResponse` / `MemberBatchStatus` | The 6 unused roster endpoints |
| `AllocationSource` (`PLATFORM`\|`RESELLER`) | DID ownership provenance |
| `AssignDidResponse` | Returned by assign/revoke |
| `IvrNodeType` / `IvrTerminalAction` / IVR DTOs | IVR domain absent |

## Known absent frontend surfaces

- **Dashboard** — `/` redirects to `/account` (`app/page.tsx:19`). No aggregate/metrics
  endpoint was found in the repository.
- **Executions list** — no route; executions reachable only from a campaign detail page.
- **Standalone contacts** — contacts are only reachable under a contact group, matching the
  backend (there is no `/api/v1/contacts` resource).
- **DID detail page** — list and dialogs exist, no `[didId]` route (other domains have one).

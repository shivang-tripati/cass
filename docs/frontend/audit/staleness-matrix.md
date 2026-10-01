# F0 Artifact — Staleness Matrix

Canonical source: [`../F0-FRONTEND-AUDIT.md`](../F0-FRONTEND-AUDIT.md) §12, §18.

**Headline finding.** This frontend is **drifted, not stale.** Every endpoint it calls still
exists and still behaves as it expects. Zero stale endpoint paths, zero removed fields, zero
obsolete role names, zero dead routes. All 25 findings below are *omission or drift* against a
moving backend, not a client built against a retired contract.

| # | Area | Stale assumption | Backend reality | Evidence | Sev | Action |
|---|---|---|---|---|---|---|
| S1 | Campaign | `CampaignType` = 3 values | **4** — `MISSED_CALL` fully implemented (V54, `MissedCallCampaignConfig`, `MissedCallExecutionService`, readiness rules) | `CampaignType.java:33` vs `contracts.ts:420` | **High** | Add `MISSED_CALL` to the union, zod enum, type filter and badge |
| S2 | Campaign | `CampaignResponse` = 20 fields | **24** — `callOnWhitelistNumbers`, `dailyDialLimit`, `maxDailyAttempts`, `maxCallDurationSeconds` | `CampaignResponse.java:66,75,88,102` vs `contracts.ts:450-471` | **High** | Extend the type; expose them in the form (present on both create and update) |
| S3 | Campaign | `RetryPolicyConfig` = 3 fields | **4** — plus `rules: List<RetryRuleConfig>` with `MM:SS` delays and per-category enable/disable | `dto/RetryPolicyConfig.java`, `dto/RetryRuleConfig.java` vs `contracts.ts:443-447` | **High** | Model `RetryRuleConfig` |
| S4 | Campaign | `intervalSeconds` ≤ 604800 | `@Max(5999)` | `dto/RetryPolicyConfig.java` vs `schemas/campaign-mutation.ts:81` | Med | Tighten zod |
| S5 | Campaign | `integrationConfig` = `Record<string, unknown>` | **Typed** `CampaignIntegrationConfig`; unknown fields **rejected 400** | `dto/CreateCampaignRequest.java:114` vs `contracts.ts:468` | Med | Type it, or remove it from the form until a consumer exists |
| S6 | Execution | `CampaignExecutionResponse` = 10 fields | **11** — `configurationSnapshotId` | `dto/CampaignExecutionResponse.java` vs `contracts.ts:529-540` | Med | Add it; it evidences the snapshot boundary |
| S7 | Attempts | `markFailed` sends a JSON body | Reads **`@RequestParam`** query params | `CampaignController.java:377-378` vs `call-attempts.ts:158-161` | **High** | Move to query params |
| S8 | Attempts | List is filterable/sortable; carries pagination | **No parameters, no pagination metadata** | `CampaignController.java:309-313` vs `call-attempts.ts:90-94,98-105` | Med | Remove dead params and the fabricated page object |
| S9 | Contacts | `ContactResponse.contactGroupId` | Field **does not exist** | `contracts.ts:281` vs `contact/dto/ContactResponse.java` | **High** | Remove the phantom field |
| S10 | Contacts | `ContactGroupResponse` = 6 fields | 7 — incl. `memberCount: long` | `contact/dto/ContactGroupResponse.java` vs `contracts.ts:234-241` | Med | Add it |
| S11 | TTS | `TtsTemplateResponse` = 9 fields | 10 — incl. `scope: GLOBAL\|TENANT` | `tts/dto/TtsTemplateResponse.java` vs `contracts.ts:378-388` | **High** | Model and display `scope` |
| S12 | TTS | Create payload has `tenantId`, no `scope` | `scope` accepted; `GLOBAL` **forbids** `tenantId` (400) | `dto/CreateTtsTemplateRequest.java:5-6`, `TtsTemplateService.java:78-86` | **High** | Add `scope` or restrict the UI explicitly |
| S13 | TTS | — | **No `scope` filter on the list endpoint** | `TtsTemplateController.java:83-87` | Info | **NOT EXPOSED BY BACKEND** — do not build a filter for it |
| S14 | Audio | Assets are created by typing metadata | A real multipart `POST /audio-assets/upload` exists | `AudioAssetController.java:86-100` vs **absent** from `lib/api/audio-assets.ts` | **High** | Implement upload; drop the hand-typed checksum form |
| S15 | DIDs | `DidResponse` = 14 fields | 15 — incl. `allocationSource` | `did/dto/DidResponse.java` vs `contracts.ts:184-199` | Low | Add it |
| S16 | DIDs | — | `assign`/`revoke` exist; no frontend calls | `DidController.java:138,157` | Med | Optional; record as unexercised |
| S17 | Tenants | Tenants can be placed under a reseller | `toCreateTenantPayload` **hardcodes `resellerId: null`** | `schemas/tenant-mutation.ts:70` | **High** | Wire it from the form |
| S18 | Campaign | Clone and status change work | Both are `TODO` stubs calling `window.location.reload()` — while the API fns, the zod schemas **and** `change-status-dialog.tsx` all exist | `campaigns-view.tsx:211-221` vs `campaigns.ts:125-139`, `schemas/campaign-mutation.ts:319-337` | **High** | Wire the existing dialogs — a wiring gap, not missing infrastructure |
| S19 | Auth | `PASSWORD_MIN = 8` everywhere | **12** for admin/agent provisioning | `schemas/auth.ts:16`, `signup.ts:34-37`, `reseller-mutation.ts:88-91` vs `TenantAdminInput`/`AdminAccountInput` | Med | Add `ADMIN_PASSWORD_MIN = 12` |
| S20 | Schedule | `allowedDaysOfWeek: string[]` | `Set<DayOfWeek>` | `dto/ScheduleConfig.java` vs `contracts.ts:439`, `schemas/campaign-mutation.ts:57` | Med | Constrain to `DayOfWeek` literals |
| S21 | RBAC | 19 capability keys | **34** exist; the frontend catalog omits `IVR_VIEW`, `IVR_MANAGE`, `QUEUE_VIEW`, `QUEUE_MANAGE`, `AGENT_VIEW`, `AGENT_MANAGE`, `CALL_VIEW` | `capabilities.ts:9-44` vs V1/V16/V20/V37 | Med | Extend when those domains are built |
| S22 | RBAC | 12 capabilities gate actions | **0 of 12 are checked anywhere**; approve/reject are ungated | `audio-assets-view.tsx:50,53`, `tts-templates-view.tsx:40,41` | **High** | Add per-action gating — a `RESELLER_ADMIN` (lacking `AUDIO_MANAGE`) currently sees buttons that 403 |
| S23 | Routing | Authenticated pages are guarded | Client render gate only; no `middleware.ts`/`error.tsx`/`not-found.tsx`/`loading.tsx` | `(platform)/layout.tsx:1,78-98` | Med | Add an error boundary and a real guard |
| S24 | Campaign | Edit requires a valid `typeConfig` for DTMF/CONNECT_BY_AGENT | `updateCampaignSchema` deliberately omits the rule ("we don't have original type here") | `schemas/campaign-mutation.ts:268-269` | Low | Acceptable — the server validates; keep the comment |
| S25 | FreeSWITCH | — | **Clean.** Zero `freeswitch`/`ESL`/`sofia`/`uuid_*`/`SIP`/`RTP` occurrences in `frontend/src` (16 grep hits were all `eslint`) | grep over `frontend/src` | **None** | No action — **preserve** |

## Additional findings not caused by backend drift

| # | Finding | Evidence | Sev |
|---|---|---|---|
| A1 | `ContactImportResponse` + `ContactImportError` each declared **twice** in `contracts.ts`. Legal TS interface merging (`tsc --noEmit` passes) but a drift hazard. | `contracts.ts:256,266` and `308,318` | Low |
| A2 | `importContacts`/`exportContacts` byte-identical in two modules | `contact-groups.ts:97-116` ≡ `contacts.ts:98-117` | Low |
| A3 | Enum unions re-declared locally, shadowing `contracts.ts` | `dids.ts:124-134`, `audio-assets.ts:104`, `tts-templates.ts:103` | Low |
| A4 | Attempt transition tables duplicated in two modules | `api/call-attempts.ts:169-182` ≡ `schemas/call-attempt-mutation.ts:39-52` | Low |
| A5 | `toCreatePayload`/`toUpdatePayload` exported from two schema modules | `audio-asset-mutation.ts:25,43` and `tts-template-mutation.ts:29,35` | Low |
| A6 | `updateTtsTemplateSchema` omits the duplicate-name and stray-brace `superRefine` that create has | `schemas/tts-template-mutation.ts:20-26` vs `:33` | Low |
| A7 | Dead exports: `CONTACT_E164_REGEX`, `ATTEMPT_NUMBER_MIN`, `NETWORK_ERROR_CODE`, `ApiError.fieldMessage`, `ApiError.isNetworkError`, `CALL_ATTEMPT_SORTABLE_FIELDS`, 2 of 4 `callAttemptsKeys` entries, 5 unused capability helpers | see audit §3 | Low |
| A8 | `zustand` declared, **zero imports** | `package.json:30` | Low — remove |
| A9 | `next-themes` consumed with **no `ThemeProvider`** mounted | `ui/sonner.tsx:3` vs `components/providers.tsx` | Low |
| A10 | `unwrap` doc comment claims it throws `ApiError`; it does not | `auth.ts:10-16` | Low |
| A11 | Layout guard redirects **without** `?next=`, unlike `client.ts` | `(platform)/layout.tsx:88-92` vs `client.ts:104-111` | Low |
| A12 | `session.data` dereferenced without a null check | `(platform)/layout.tsx:88,109` | Low |
| A13 | Reference-data fetchers hardcode `size: 100` — a silent truncation cap with no user indication | `api/campaigns.ts:210-262` | Low |
| A14 | `next.config.ts:5` hardcodes a dev API origin fallback | `next.config.ts:5` | Low |
| A15 | `hasPlatformAccess()` is a frontend-invented heuristic (`homeType === null && TENANT_VIEW`) with **no backend counterpart** and zero callers | `capabilities.ts:75-78` | Info — do not build on it (U2) |
| A16 | Backend repo hygiene: 5 `hs_err_pid*.log` + 4 `replay_pid*.log` committed in `backend/` | directory listing | Low |

## Explicitly **not** stale (checked, evidence provided)

| Checked | Result |
|---|---|
| Old endpoint paths | **None** — all 45 frontend calls resolve |
| Old DTO fields | **None removed** — every field the frontend reads still exists, except the *phantom* `contactGroupId` which never did |
| Old role names | **None in executable code** — `SUPER_ADMIN`/`RESELLER_ADMIN`/`TENANT_ADMIN` appear only in comments and dialog help text |
| Old auth behaviour | **None** — the frontend already matches the cookie-based refresh design |
| Mock APIs | **None** — zero `mock`/`fixture`/`MSW` occurrences in `frontend/src`; the root `mock/` dir is backend import data |
| Fake scheduler state | **None** — there is no scheduler UI to fake |
| Dead routes | **None** — all 19 routes are reachable from the sidebar or a parent detail page |
| Deprecated libraries | **None** — one unused dependency (`zustand`), one mis-provisioned one (`next-themes`) |
| Legacy FreeSWITCH terminology | **None in the frontend**; see S25 |

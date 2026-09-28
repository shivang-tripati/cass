# VB-7C — Integrations + Campaign Configuration Audit

**Phase 1 — READ-ONLY. No production code was modified.**

| | |
|---|---|
| Audited | `D:\work\agile\obd-platform\backend` |
| Baseline commit | `828bec4` (VB-7B) = `origin/main` |
| Migration head | V54 (53 files) |
| Audit date | 2026-09-28 |
| Repository change | this document only |

---

## 1. Audit Status

**COMPLETE.** All 27 sections produced. Read-only throughout: no Java, migration,
DTO, entity, repository, service, controller, test, or OpenAPI source was modified.
Two test runs were executed and neither was altered.

**Verdict: READY WITH DECISIONS** — see §27. Two live defects (§19.1, §19.2) are
P0 and should be fixed before any webhook or report-privacy work begins, because
both are the exact fail-open class this platform has already been burned by twice
(VB-6E for TTS, VB-7B for the content gate).

---

## 2. Repository Baseline

### 2.1 Verified state

| Item | Expected | **Actual** | Match |
|---|---|---|---|
| Branch | `main` | `main` | yes |
| HEAD | `828bec4` | `828bec4` VB-7B | yes |
| `origin/main` | `828bec4` | `828bec4` | yes |
| Ahead of remote | 0 | 0 | yes |
| Migration head | V54 | `V54__campaign_type_missed_call.sql` | yes |
| Modules | 16 | 16 | yes |
| Architecture cycles | 0 | 0 (`ArchitectureTest` PASS) | yes |
| Tests | 1754 / 0F / 0E / 1S | **PARTIAL — see §2.2** | qualified |

### 2.2 Test baseline — partial, environmental

The full suite **could not be completed**, and this is an environmental
constraint rather than a product defect.

**Full-run attempt:** crashed after 22 tests.

```
[ERROR] Failed to execute goal ...maven-surefire-plugin:3.5.6:test
[ERROR] The forked VM terminated without properly saying goodbye.
        VM crash or System.exit called?
```

**Root cause — memory exhaustion on the host:**

```
total: 5.9 GB   free: 0.0 GB
8 Docker containers running (user's parallel FreeSWITCH + auth track):
  obd-freeswitch, obd-fs-endpoint-1001, obd-fs-endpoint-1002,
  auth-starter-{keycloak,postgres,redis,mailpit},
  obd-postgres, obd-redis
```

`MAVEN_OPTS=-Xmx512m` and `-DargLine=-Xmx900m` (the bounds used successfully in
every prior phase) were both insufficient, because the host itself had **zero
free physical memory** before Maven started.

**Focused attempt (campaign/config/readiness/architecture subset):** completed
**409 tests, 0 failures, 0 errors, 0 skipped** before the fork was OOM-killed at
the same wall-clock pressure.

| Suite | Result |
|---|---|
| `ArchitectureTest` | **PASS** (1/1) — 16 modules, 0 cycles |
| `CampaignTypeConfigTest` | 9 / 0 / 0 |
| `MissedCallCampaignConfigTest` | 21 / 0 / 0 |
| `CampaignMissedCallValidationTest` | 8 / 0 / 0 |
| `MissedCallExecutionServiceTest` | 19 / 0 / 0 |
| `MissedCallPostgresIntegrationTest` | 15 / 0 / 0 |
| `CampaignOpenApiContractTest` | 28 / 0 / 0 |
| `ConnectByAgentConfigPostgresIntegrationTest` | 8 / 0 / 0 |
| `PlayfileExecutionServiceTest` | green |
| `DtmfExecutionServiceTest` | green |

**Classification, per the brief's three categories:**

1. Pre-existing failure — **no.** Nothing failed.
2. Environmental failure — **yes.** Host RAM exhaustion from the user's parallel
   Docker work.
3. Actual VB-7C finding — **no.** The crash is unrelated to campaign
   configuration.

**Not "fixed",** per the brief. The user's Docker containers were **not**
stopped, as they are user-owned parallel work. Recorded as an operational
limitation of this audit, not a repository defect.

### 2.3 User-owned work — preserved, untouched

```
 M .gitignore                                  (new since the previous phase)
 M backend/docs/future-hardening.md
 M docs/campaign-readiness.md
 M infra/.env.example
?? backend/data/
?? docs/LIVE-FREESWITCH-PHASE-B.md
?? docs/LIVE-FREESWITCH-PHASE-C.md
?? docs/freeswitch/
?? infra/docker-compose.freeswitch-endpoints.yml
?? infra/docker-compose.freeswitch.yml
?? infra/freeswitch-endpoint/
?? infra/freeswitch/
?? tools/
```

None were read into the implementation, modified, staged, or committed. This
audit produced exactly one new file: this document.

**Working-tree drift during the audit.** At the end of the audit, four
`backend/src/` files appeared as modified that were clean at baseline:

```
 M backend/src/main/java/com/shivang/obd/telephony/EslClient.java      (+39/-…)
 M backend/src/main/java/com/shivang/obd/telephony/EslEvent.java       (+103/-…)
 M backend/src/main/java/com/shivang/obd/telephony/EslEventService.java (+166/-…)
 M backend/src/test/java/com/shivang/obd/telephony/FakeEslServer.java  (+43/-…)
                                    304 insertions, 47 deletions in total
```

These are the **user's parallel FreeSWITCH/ESL work** — the diff is entirely
FreeSWITCH protocol behaviour (`Call-UUID` header handling, `+OK Message sent`
semantics, `LinkedHashMap`/`TreeMap` header normalisation, and new
`FakeEslServer` test-double behaviour), matching the running `obd-freeswitch`
and `obd-fs-endpoint-*` containers. They are **user-owned and were not touched
by this audit**; this audit issued only read commands and wrote one document.

**Consequence for this audit's validity:** the findings in §7 (which cites
`EslClient:90` for an incidental javadoc) were read from the working tree during
the audit. The changed lines concern FreeSWITCH header semantics, not the
"credential redacted" javadoc, so no finding depends on the drifted content.
`EslEventService` is named in §21/OD-12's do-not-touch list; that list is a
constraint on **future** VB-7C work and is unaffected. No conclusion in this
report depends on the uncommitted delta.

---

## 3. Scope

Audited, as commissioned:

1. Webhook configuration
2. Webhook event selection
3. Report privacy configuration
4. Campaign configuration validation
5. Campaign readiness

---

## 4. Explicit Non-Goals

Not audited, not touched, deliberately out of scope:

- Execution scheduler design or implementation (§17 audits only whether the
  necessary contracts already exist)
- Any new `@Scheduled`, polling loop, orchestrator, or dial algorithm
- Frontend / UI
- Retry, safety, eligibility, routing, or telephony internals
- FreeSWITCH / ESL / SIP
- The parallel FreeSWITCH development track

---

## 5. Current Campaign Architecture

### 5.1 The four layers are already separated

This is the single most important structural finding, and it is **positive**.

Configuration-layer outbound dependencies, extracted from source:

| Service | Dependencies | Reaches execution? |
|---|---|---|
| `CampaignService` | `CampaignRepository`, `AuthorizationService`, `CampaignEventPublisher`, `CurrentUserProvider`, `CampaignMapper`, `TenantRepository`, `ContactGroupRepository`, `CampaignResourceValidationService`, `CampaignLifecyclePolicy` | **No** |
| `CampaignReadinessService` | `CampaignRepository`, `AuthorizationService`, `CurrentUserProvider`, `ContactGroupRepository`, `CampaignResourceValidationService`, `TenantRepository` | **No** |
| `CampaignExecutionService` | `CampaignRepository`, `CampaignExecutionRepository`, `AuthorizationService`, `CurrentUserProvider`, `CampaignReadinessService`, `TenantRepository`, `CampaignConfigurationService` | **No** |
| `CampaignResourceValidationService` | `DidRepository`, `AudioAssetRepository`, `TtsTemplateRepository`, `ObjectProvider<…>` | **No** |

**No dialing, no retry, no gateway, no FreeSWITCH, no attempt dispatch anywhere
in the configuration layer.** `CampaignExecutionService` depends only on
readiness and the snapshot. The required layering
(`Configuration → Validation → Readiness → Snapshot → *future* Scheduler`) is
already the shape of the code.

### 5.2 The four campaign types

`PLAYFILE`, `DTMF`, `CONNECT_BY_AGENT`, `MISSED_CALL`.

Type dispatch is centralised in the **sealed** `CampaignTypeConfig` with an
**exhaustive, default-free** `switch` (`CampaignTypeConfig:46-52`). That is
compiler-enforced and is the platform's strongest structural guarantee against
the fail-open class.

---

## 6. Campaign Configuration Inventory

| Configuration | Stored where | Mutable? | Snapshotted? | Validated? | Readiness checked? | Runtime consumer |
|---|---|---|---|---|---|---|
| campaign type | `campaigns.campaign_type` + DB `CHECK` | yes (pre-activation) | **yes** `campaign_type` | **yes** (enum parse) | implicit | dispatch switch |
| name / description | columns | yes | no (admin metadata) | `@NotBlank @Size(200/5000)` | no | admin only |
| run mode / version | columns | yes | no | yes (lineage) | `CAMPAIGN_NOT_EXECUTABLE_STATE` | orchestrator |
| DID | `did_id` | yes | **yes** `did_id` | **yes** tenant-scoped | `DID_UNAVAILABLE` | `VoiceRoutingService` |
| audience | `contact_group_id` | yes | **yes** `contact_group_id` | **yes** tenant-scoped | `CONTACT_GROUP_UNAVAILABLE` | `createInitialAttempts` |
| schedule (7 fields) | columns | yes | **yes** (start/end/times/timezone/days/holiday) | **yes** window+zone | `INVALID_SCHEDULE`, `SCHEDULE_*` | orchestrator |
| **timezone (windowless)** | `schedule.timezone` | yes | **yes** | **NOT required when no window** | **only for 3 of 4 types — see §19.1** | `DailyDialLimitService` |
| whitelist | `call_on_whitelist_numbers` | yes | **yes** | yes | no (runtime) | `VoiceEligibilityService` |
| retry policy (3) | columns + DB `CHECK` | yes | **yes** | `@Min/@Max` + service | no | `RetryPolicyService` |
| retry rules | `retry_rules` JSONB | yes | **yes** | `RetryPolicyValidator` | no | `RetryPolicyService` |
| daily attempt limit | `max_daily_attempts` | yes | **yes** | `@CampaignDailyAttempts` | no | `DailyAttemptSafetyService` |
| daily dial limit | `daily_dial_limit` | yes | **yes** | `@DailyDialLimit` | no | `DailyDialLimitService` |
| content mode | `content_mode` | yes | **yes** | **inclusive list** (fixed VB-7B) | `INVALID_CONTENT_CONFIGURATION` | dispatch |
| audio asset / TTS template | `audio_asset_id`, `tts_template_id` | yes | **yes** (ids only) | **yes** | `AUDIO_*`, `TTS_*` | execution services |
| max call duration | `max_call_duration_seconds` | yes | **yes** | `@MaxCallDurationSeconds` | no | `PlayfileExecutionService` |
| type config | `type_config` JSONB | yes | **yes** verbatim | **delegated (fixed VB-7B)** | `INVALID_*_CONFIGURATION` | per-type service |
| PLAYFILE type config | `{}` (empty) | — | yes | sealed dispatch | `INVALID_CONTENT_CONFIGURATION` | `PlayfileExecutionService` |
| DTMF type config | `{"dtmf":…}` | yes | yes | sealed dispatch | `INVALID_CONTENT_CONFIGURATION` | `DtmfExecutionService` |
| IVR type config | `{"ivr":…}` | yes | yes | sealed dispatch | `INVALID_CONTENT_CONFIGURATION` | `IvrExecutionService` |
| CONNECT_BY_AGENT config | `{"connectByAgent":…}` | yes | yes | sealed dispatch | `INVALID_AGENT_CONFIGURATION` | `ConnectByAgentExecutionService` |
| MISSED_CALL config | `{"missedCall":…}` | yes | yes | sealed dispatch | `INVALID_MISSED_CALL_CONFIGURATION` | `MissedCallExecutionService` |
| **webhook config** | `integration_config` JSONB | **yes** | **NO — explicitly excluded** | **NOTHING** | **n/a** | **NOTHING** |
| **webhook event selection** | **does not exist** | — | — | — | — | — |
| **report privacy** | **does not exist** | — | — | — | — | — |

---

## 7. Webhook Configuration Audit

### 7.1 Headline: there is no webhook subsystem

A repository-wide search of `src/` for `webhook`, `callback`, `eventDelivery`,
`eventSubscription` returns **9 hits, none of which is webhook
implementation**:

| Location | What it is |
|---|---|
| `CreateCampaignRequest:80` | javadoc: *"Optional API/webhook integration configuration"* |
| `UpdateCampaignRequest:62` | identical javadoc |
| `CampaignEntity:102` | javadoc: *"Optional API/webhook integration configuration"* |
| `V14:71-72` | migration comment: *"optional API/webhook integration configuration"* |
| `ConnectByAgentExecutionService:46`, `ConnectByAgentService:60,325` | ESL event *callbacks* (answer/bridge/hangup) — internal, not webhooks |

**There is no webhook entity, repository, service, controller, delivery
mechanism, signer, delivery-attempt ledger, or webhook test.** Four of the nine
hits are javadoc and one is a SQL comment — the concept is *named* five times and
*implemented* zero times.

### 7.2 The one artefact: `integrationConfig`

`campaigns.integration_config JSONB` (V14, line 74), mapped to
`CampaignEntity.integrationConfig` as a raw `JsonNode`.

**Full chain trace, API → consumer:**

| Stage | State | Evidence |
|---|---|---|
| API in | **present, opaque** | `JsonNode integrationConfig` in `CreateCampaignRequest:81`, `UpdateCampaignRequest:63` |
| Bean validation | **NONE** | no `@Valid`, no constraint, not referenced by any custom constraint |
| Service validation | **NONE** | zero references in `CampaignService` |
| Persistence | **present** | `CampaignMapper:144` `entity.setIntegrationConfig(integrationConfig)` — verbatim, uninspected |
| API out | **present, echoed** | `CampaignMapper:106` `entity.getIntegrationConfig()` → `CampaignResponse` |
| Clone | **copied** | `CampaignMapper:77` `clone.setIntegrationConfig(source.getIntegrationConfig())` |
| Snapshot | **EXPLICITLY EXCLUDED** | `CampaignConfigurationSnapshot:35-36` — *"Excluded: `integrationConfig` — no code reads it today; snapshotting it would invent execution semantics it does not have"* |
| Consumer / runtime | **NONE** | zero reads outside mapper/DTO/entity |

**The chain stops at persistence.** The exclusion note is honest and was
correct when written — but the field has since become a **write-only, unvalidated,
echoed-back arbitrary JSON sink** on a tenant-facing API.

### 7.3 Defect: the secret prohibition is unenforced

`CampaignEntity:101-105` states:

> *"Extensible JSON payload; **secrets are prohibited here** — credentials belong
> to the platform's credential abstraction when that exists."*

`V14:72` repeats it: *"Extensible; never stores secrets."*

**Nothing enforces either statement.** There is no deny-list, no schema, no size
limit, no secret-key pattern check. A client may therefore:

1. `POST /api/v1/campaigns` with
   `{"integrationConfig":{"secret":"sk-live-…","apiKey":"…"}}`
2. Have it stored verbatim in `campaigns.integration_config`
3. Have it **echoed back verbatim** on every `GET /api/v1/campaigns/{id}`
4. Have it **copied into every clone** of the campaign

The documented invariant and the enforced behaviour disagree. This is a
credential-storage path, and it is the strongest argument in this audit for
VB-7C work beginning with *governance of the existing field* rather than with
new functionality.

### 7.4 Answers to the 20 commissioned questions

| # | Question | Answer |
|---|---|---|
| 1 | Does webhook configuration exist? | **Only as an untyped, unvalidated JSONB column.** No webhook model. |
| 2 | Where stored? | `campaigns.integration_config` (V14:74) |
| 3 | Campaign / tenant / execution / global level? | **Campaign** level |
| 4 | Typed or arbitrary JSON? | **Arbitrary `JsonNode`** — no schema whatsoever |
| 5 | Persisted in PostgreSQL? | Yes, `JSONB` |
| 6 | In execution snapshots? | **No — deliberately excluded** |
| 7 | Editable after execution creation? | The campaign row is; but the snapshot is immutable, so any future consumer would see a divergence (§12) |
| 8 | Consumed anywhere? | **No. Zero reads.** |
| 9 | Webhook delivery service? | **Does not exist** |
| 10 | Event dispatcher? | **Does not exist** |
| 11 | Events persisted? | **No** |
| 12 | Delivery asynchronous? | **N/A** |
| 13 | Retry implemented? | **No** |
| 14 | Signing implemented? | **No** — no HMAC, no secret, no header construction anywhere |
| 15 | Tenant isolation enforced? | **Incidentally yes** — the column is on a tenant-scoped row. But no *endpoint* isolation exists because there are no endpoints. |
| 16 | URLs validated? | **No** — no URL exists to validate |
| 17 | Secrets protected? | **No — see §7.3** |
| 18 | Are webhook events dead configuration? | **Yes.** The definition of dead. |
| 19 | Existing webhook APIs? | **None.** No endpoint accepts, validates, or returns a webhook URL. |
| 20 | Documented in generated OpenAPI? | `integrationConfig` appears as an untyped `object` in `CreateCampaignRequest`, `UpdateCampaignRequest`, `CampaignResponse` — documented only as *"Optional API/webhook integration configuration"*, with no schema |

---

## 8. Webhook Event Vocabulary Audit

### 8.1 The entire event vocabulary

Two files: `campaign/event/CampaignDomainEvent.java` and
`CampaignEventPublisher.java`.

| Existing event | Constant | Source | Persisted? | Externally exposed? | Webhook candidate? | Notes |
|---|---|---|---|---|---|---|
| Campaign created | `CAMPAIGN_CREATED` | `CampaignService:148` | No | No | Weak | Admin lifecycle, not blast progress |
| Campaign updated | `CAMPAIGN_UPDATED` | `CampaignService:237` | No | No | Weak | Same |
| Campaign deleted | `CAMPAIGN_DELETED` | `CampaignService:251` | No | No | Weak | Same |
| Campaign status changed | `CAMPAIGN_STATUS_CHANGED` | `CampaignService:287` | No | No | Weak | Same |
| Campaign cloned | `CAMPAIGN_CLONED` | `CampaignService:307` | No | No | Weak | Same |

**That is the complete vocabulary.** Five events, all campaign-CRUD, none
execution-related.

### 8.2 The events go nowhere

`CampaignEventPublisher.publish` logs at `debug` and calls
`publisher.publishEvent(event)` on Spring's **synchronous**
`ApplicationEventPublisher`.

**There are zero `@EventListener` and zero `@TransactionalEventListener`
declarations in the entire codebase.** The events are constructed, published,
and discarded. The class javadoc is candid: *"a future outbox/Kafka transport can
subscribe without changing the CRUD contract."*

### 8.3 What does not exist

Searched across every commissioned entity — **zero** events exist for:

- `CampaignExecution` (started, completed, failed)
- `CallAttempt` (dispatched, answered, completed, failed, retried, exhausted)
- `CallSession` (ringing, answered, hung up)
- DTMF / IVR (digit collected, input invalid, node entered, tree exhausted)
- CONNECT_BY_AGENT (agent selected, bridged, agent left, no agent available)
- MISSED_CALL (ringed, answered, budget elapsed)
- Daily safety (DNID limit reached, attempt ceiling reached)
- Eligibility (DND suppression, blocklist suppression, not whitelisted)

**Consequence for webhook event selection:** the vocabulary an integrator would
most want — *did my blast finish, and how* — is precisely the vocabulary that
does not exist. Of the five events that do exist, none corresponds to anything
about a call.

### 8.4 Architectural decision required (OD-2)

**Recommendation: option B — a stable, separate external webhook-event
vocabulary.** Rationale grounded in the evidence:

- The internal vocabulary is (a) campaign-CRUD-only, (b) unpersisted, (c)
  unsubscribed, and (d) built as *in-process Spring events* whose payload is a
  `CampaignDomainEvent` record carrying only `campaignId`, `tenantId`,
  `campaignType`, `status`, `eventType`, `occurredAt`.
- Binding an external, contractual, versioned integration surface to that record
  would make every internal refactor a breaking API change, and would publish a
  payload that contains **no contact, attempt, or outcome information at all**.
- A separate vocabulary is also the only shape that can be *documented and
  tested* as a contract, which an in-process event cannot be.

The internal names should remain internal. If both vocabularies are maintained,
the mapping must be explicit and tested, not by string coincidence.

---

## 9. Report Privacy Audit

### 9.1 Headline: report privacy does not exist

A search for `privacy`, `masking`, `mask(`, `redact`, `PII`, `anonymi` across
`src/` returns **three hits, all incidental**:

| Location | What it is |
|---|---|
| `EslClient:90` | javadoc: *"credential redacted"* (about a FreeSWITCH password) |
| `NoOpAgentLegDialer:24,28` | a **private** `mask(String)` helper |

`NoOpAgentLegDialer.mask` is the platform's only masking code:

```java
private String mask(String target) {
    if (target == null || target.length() <= 4) { return target; }
    return "***" + target.substring(target.length() - 4);
}
```

It logs the last four digits of a dial target in a **no-op dialer's own warning
message**, to keep a log line readable. It is log hygiene inside a stub, not a
privacy feature, and it is not reachable from any user-facing surface.

**There is no report privacy configuration, no masking helper on any DTO, no
report or export endpoint, and no privacy enforcement anywhere.**

### 9.2 There are no reports at all

Every `@RestController` base path in the platform:

```
User           /api/v1/users          Did              /api/v1/dids
AudioAsset     /api/v1/audio-assets   Reseller         /api/v1/resellers
Campaign       /api/v1/campaigns      ResellerSignup   /api/v1/account/signup
CampaignIvr    /api/v1/campaigns      ContactGroup     /api/v1/contact-groups
IvrTree        /api/v1/ivr-trees      Auth             …
```

**No `reports`, `exports`, or `analytics` controller exists.** No CSV, XLSX, PDF
or export-format code exists. So there is nothing for privacy configuration to
govern today.

### 9.3 Significant: the platform already reserved report authorization

`V1__create_multi_tenant_authorization_foundation.sql` seeds:

| Seed | Line | Meaning |
|---|---|---|
| `'REPORT_VIEWER'` role | 160 | a role named for reporting |
| `'REPORT_VIEW'` capability | 193 | `(REPORT, VIEW)` |
| `'REPORT_EXPORT'` capability | 194 | `(REPORT, EXPORT)` |
| role→capability grants | 214, 235, 255, 279, 281 | `REPORT_VIEW`/`REPORT_EXPORT` bound to several seeded roles |

**And there are zero code references to `REPORT_VIEW`, `REPORT_EXPORT` or
`REPORT_VIEWER` anywhere in `src/`.**

This is strong, concrete repository evidence for the report-privacy question
(OD-10): the platform's authorization model **anticipates reporting at the
capability level but has never implemented the surface**. Any report-privacy
work will therefore slot into an already-designed `REPORT` capability boundary
rather than inventing one.

### 9.4 Answers to the 14 commissioned questions

| # | Question | Answer |
|---|---|---|
| 1 | Does report privacy config exist? | **No** |
| 2 | What does it configure? | **N/A** |
| 3 | Where stored? | **N/A** |
| 4 | Is it typed? | **N/A** |
| 5 | Snapshotted? | **N/A** |
| 6 | Which paths consume it? | **None** |
| 7 | Affects API responses? | **No** |
| 8 | Affects exports? | **No** — no exports exist |
| 9 | Affects logs? | **Only** `NoOpAgentLegDialer`, a private stub helper |
| 10 | Affects webhook payloads? | **N/A** — no webhooks |
| 11 | UI only? | **N/A** |
| 12 | Is masking consistent? | **No — there is no masking** |
| 13 | Privacy tenant-scoped? | **N/A** |
| 14 | Accidental PII leakage path? | **Yes — see §19.3** |

---

## 10. Configuration Validation Audit

### 10.1 What is validated where

| Layer | Mechanism | Coverage |
|---|---|---|
| **Bean validation** | `@NotBlank @Size` (name, description), `@NotNull` (type), `@Min/@Max` (retry, interval), `@Pattern` (time), `@Size` (timezone) | Only 3 DTOs annotate. **`typeConfig` and `integrationConfig` are unannotated raw `JsonNode`.** |
| **Custom constraints** | `@MaxCallDurationSeconds`, `@DailyDialLimit`, `@CampaignDailyAttempts` | A real, reusable pattern for validating a scalar campaign field |
| **Write-time service** | `CampaignService.validate*` | Schedule, content, typeConfig, DID, group, agent queue, TTS, lineage |
| **Readiness** | `CampaignReadinessService` | Same concerns re-evaluated against *live* resources |
| **Execution time** | execution services | Per-type playback/interaction |
| **Persistence** | DB `CHECK`s | `ck_campaigns_type`, `retry_max_attempts`/`retry_interval` coherence |

### 10.2 VB-7B's two fixes — confirmed correct and complete at their sites

- `CampaignService:441` — content requirement is now **inclusive**
  (`PLAYFILE || DTMF`). Safe by default.
- `CampaignService` / `CampaignReadinessService` — `typeConfig` now **delegates**
  to the sealed hierarchy for every type. Strictly stronger.

### 10.3 Fail-open pattern audit

I classified **every** `CampaignType` branch in `src/main/java` into one of two
families. The distinction is essential and is the analytical core of this section.

**Family A — a self-guard on the type the service OWNS. SAFE.**

| Site | Code | Why safe |
|---|---|---|
| `PlayfileExecutionService:180,308` | `config.campaignType() != PLAYFILE → return` | One service, one owned type. Registering a 5th type does not change PLAYFILE's behaviour. |
| `DtmfExecutionService:279,399` | `!= DTMF → return` | same |
| `ConnectByAgentExecutionService:124` | `!= CONNECT_BY_AGENT → return` | same |
| `MissedCallExecutionService:132,267,323` | `!= MISSED_CALL → return` | same |
| `CampaignService:562` | `type != CONNECT_BY_AGENT → return` | Guards `validateAgentQueueReference`, which only has meaning for that type. |
| `CampaignReadinessService:364` | `!= CONNECT_BY_AGENT → return` | Self-guard of `checkConnectByAgent`. |
| `IvrFromCampaignService:110` | `!= DTMF → return` | same |

**These are the *correct* use of a negated type test** — a service asking "is this
mine?" Adding a campaign type cannot make one of them wrong.

**Family B — a rule that SHOULD apply to a set of types, expressed as a
membership list. THIS is where the danger lives.**

| Site | Code | Status |
|---|---|---|
| `CampaignService:441` | `(type == PLAYFILE \|\| type == DTMF) && mode == null` | **FIXED** (VB-7B, inclusive) |
| `CampaignService:415` | `type == PLAYFILE && mode == TTS → reject` | **STILL FAIL-OPEN — §19.2** |
| `CampaignService:425,429` | mode-based (type-agnostic) | safe |
| `CampaignReadinessService:197-199` | `PLAYFILE \|\| DTMF \|\| CONNECT_BY_AGENT` | **STILL FAIL-OPEN — §19.1** |
| `CampaignReadinessService:302` | `(type == PLAYFILE \|\| type == DTMF) && mode == null` | **FIXED** (VB-7B, inclusive) |
| `CampaignReadinessService:334` | `default → INVALID_CONTENT_CONFIGURATION` | safe (exhaustive) |

**VB-7B fixed two of the four Family-B sites. Two remain, and both are
consequential.** This is the direct answer to the brief's question *"Determine
whether similar fail-open logic still exists anywhere"* — **yes, in two places,
one of which is a live 100%-call-failure path introduced by adding MISSED_CALL.**

### 10.4 What can be persisted in an invalid state

| Invalid state | Persisted? | Detected later by |
|---|---|---|
| Unparseable `typeConfig` | No — rejected at write time since VB-7B | — |
| `typeConfig` = `null` for a type that needs one | No — rejected | — |
| `contentMode` = `TTS` for `DTMF` | **Yes** | nothing (§19.2) |
| `timezone` blank with no window, `MISSED_CALL` | **Yes** | dial time, permanently (§19.1) |
| Arbitrary `integrationConfig` incl. secrets | **Yes**, unvalidated | nothing (§7.3) |
| `contactGroupId` = `null` | **Yes** — explicitly permitted, *"no existing product rule makes a group mandatory for any campaign type yet"* (`CampaignService:583-585`) | nothing; execution is created with 0 attempts and only a `log.warn` |
| `didId` = `null` | **Yes** | readiness / dial time |

### 10.5 Duplication

The content rule and the typeConfig rule exist in **two files**
(`CampaignService` and `CampaignReadinessService`). VB-7B centralised the reason
codes in one helper (`invalidTypeConfigReasonCode`) but not the rule itself. The
two are currently consistent; nothing enforces that.

---

## 11. Readiness Audit

### 11.1 What "READY" means today

`CampaignReadinessService.evaluate` composes eight checks. `ready` is
`reasons.isEmpty()`.

```
checkLifecycleState          → CAMPAIGN_NOT_EXECUTABLE_STATE
checkScheduleReadiness       → INVALID_SCHEDULE | SCHEDULE_TIMEZONE_REQUIRED
                                | SCHEDULE_NOT_ELIGIBLE | SCHEDULE_EXPIRED
checkContentConfiguration    → INVALID_CONTENT_CONFIGURATION
                              | INVALID_MISSED_CALL_CONFIGURATION
                              | INVALID_AGENT_CONFIGURATION
                              | MISSING_REQUIRED_REFERENCE
checkConnectByAgent          → AGENT_QUEUE_NOT_ACTIVE | AGENT_QUEUE_NOT_AVAILABLE
checkContactGroup            → CONTACT_GROUP_UNAVAILABLE
checkDid                     → DID_UNAVAILABLE
checkAudioAsset              → AUDIO_NOT_APPROVED | AUDIO_STORAGE_REFERENCE_MISSING
checkTtsTemplate             → TTS_TEMPLATE_NOT_AVAILABLE | TTS_TEMPLATE_NOT_APPROVED
```

### 11.2 The dependency graph — only what exists

```
CampaignReadinessService
  ├── Lifecycle state
  ├── Schedule window (+ timezone, 3 of 4 types)   ⚠ §19.1
  ├── Type configuration (sealed dispatch)         ✓ fixed in VB-7B
  ├── Content mode + asset/template
  ├── Agent queue            [CONNECT_BY_AGENT only]
  ├── Contact group
  ├── DID
  └── — Webhooks             ✗ does not exist
      — Report privacy       ✗ does not exist
```

### 11.3 Dependency state matrix

| Dependency | READY | NOT_READY | INVALID | MISSING | DISABLED | TENANT_MISMATCH |
|---|---|---|---|---|---|---|
| Lifecycle | SCHEDULED/ACTIVE | DRAFT/COMPLETED/… | — | — | — | — |
| Schedule window | inside window | before/after | bad zone | — | — | — |
| Timezone | present | — | bad IANA id | **absent → not detected for MISSED_CALL** | — | — |
| Type config | parses | — | `INVALID_*_CONFIGURATION` | same code | — | — |
| Content mode | set + asset ok | — | `INVALID_CONTENT_CONFIGURATION` | `MISSING_REQUIRED_REFERENCE` | — | — |
| Audio asset | APPROVED + ref | — | — | `AUDIO_*` | not APPROVED | indistinguishable |
| TTS template | APPROVED | — | — | `TTS_TEMPLATE_NOT_AVAILABLE` | not APPROVED | indistinguishable |
| Agent queue | ACTIVE | — | bad config | `AGENT_QUEUE_NOT_AVAILABLE` | `AGENT_QUEUE_NOT_ACTIVE` | indistinguishable |
| Contact group | live | — | — | `CONTACT_GROUP_UNAVAILABLE` | — | indistinguishable |
| DID | ACTIVE+ASSIGNED | — | — | `DID_UNAVAILABLE` | — | indistinguishable |

Non-leaking design is consistent and deliberate: foreign references are reported
identically to missing ones (verified in `MissedCallPostgresIntegrationTest`).

### 11.4 Should webhook / report config gate readiness? (OD-5, OD-6)

**Recommendation: neither should block readiness by default, and neither should be
validated at readiness at all if it remains unconsumed.**

Evidence rather than assumption:

1. **Nothing consumes them.** A readiness rule for a field no code reads would
   make a campaign unready for a reason that cannot affect any call — the exact
   anti-pattern VB-7A fixed for agent *availability* (a runtime fact that made
   correctly configured campaigns permanently unready whenever the contact centre
   was closed).
2. **The platform's own precedent is configuration-at-write-time, validity-at-
   runtime.** DID and audio assets are checked at write time *and* readiness,
   but their **validity** is re-checked at execution; readiness never depends on
   transient availability.
3. **Report privacy has no subject.** With zero report endpoints (§9.2), a
   privacy rule has nothing to govern.

Therefore: **validate at write time when the field first gains a consumer;
never gate readiness on it; and never gate readiness on delivery health,
endpoint reachability, or a remote service's availability** — those are runtime
facts by the platform's established definition.

---

## 12. Snapshot Audit

### 12.1 What the snapshot holds

`CampaignConfigurationSnapshot` (`@Embeddable`) — 23 fields, frozen into
`campaign_execution_configurations` (V44) at execution creation.

Its own classification javadoc (lines 30-37):

> - `SNAPSHOT_REQUIRED`: type, audience/group reference, DID reference, content
>   mode and asset/template references, schedule window, retry policy, typeConfig,
>   whitelist enforcement flag
> - **Excluded: `integrationConfig` — no code reads it today; snapshotting it
>   would invent execution semantics it does not have**
>
> Resource *validity* of the referenced DID/audio/TTS is never frozen: every
> runtime check stays dynamic (VB-6A snapshot-vs-resource rule).

### 12.2 The exclusion — and the question it now raises

The stated reason for excluding `integrationConfig` was *"no code reads it
today."* That reason is still factually true, so the exclusion is *currently
correct*.

**But it is a decision that silently becomes wrong the moment a consumer
appears.** If VB-7C adds a webhook delivery that reads the campaign's
`integrationConfig`, then:

- the snapshot still does **not** contain the webhook config, and
- an operator editing a campaign's webhook URL would **change the behaviour of an
already-running execution** — silently violating the platform's central
invariant, "changing a campaign does not alter an execution that already exists."

**This is the single most important sequencing consequence in the audit:** the
exclusion must be revisited *in the same phase* that introduces the first
consumer. Shipping a consumer while leaving the exclusion in place would
reintroduce, at the integration layer, exactly the class of bug VB-6A was created
to prevent.

### 12.3 Answers: what must be snapshotted

| Configuration | Must be snapshotted? | Why |
|---|---|---|
| webhook configuration | **Yes — if and when a consumer exists** | It changes delivery behaviour of a running execution. Requires adding fields to the existing snapshot; **no new snapshot system, no versioning, no history.** |
| selected webhook events | **Yes — same phase, same reason** | A changed selection changes what a running execution emits. |
| report privacy | **Yes — if and when reports consume it** | Same argument. |
| type-specific config | **Already snapshotted** | `typeConfig` verbatim (VB-6A) |

**No new snapshot system is required.** The existing `@Embeddable` +
`campaign_execution_configurations` table accommodates additional columns exactly
as VB-6D.2 (`retry_rules` JSONB) and VB-6E (`max_call_duration_seconds`) did.
That is the precedent to follow — additive columns on the existing embeddable,
frozen verbatim, with no version column and no compatibility layer.

---

## 13. API/OpenAPI Audit

### 13.1 Campaign endpoints (all 15)

```
POST   /api/v1/campaigns                                   create
GET    /api/v1/campaigns/{id}                              get
GET    /api/v1/campaigns                                   list
PUT    /api/v1/campaigns/{id}                              update
DELETE /api/v1/campaigns/{id}                              delete
PATCH  /api/v1/campaigns/{id}/status                       lifecycle transition
POST   /api/v1/campaigns/{id}/clone                        clone
GET    /api/v1/campaigns/{id}/readiness                    readiness
POST   /api/v1/campaigns/{id}/executions                   execute
GET    /api/v1/campaigns/{campaignId}/executions/{executionId}
GET    /api/v1/campaigns/{campaignId}/executions
POST   .../executions/{executionId}/attempts               create attempt
GET    .../executions/{executionId}/attempts/{attemptId}   get attempt
GET    .../executions/{executionId}/attempts               list attempts
PATCH  .../attempts/{attemptId}/in-progress               mark in progress
PATCH  .../attempts/{attemptId}/completed                  mark completed
PATCH  .../attempts/{attemptId}/failed                     mark failed
PATCH  .../attempts/{attemptId}/cancel                     cancel
```

**No webhook endpoint. No report endpoint. No privacy endpoint.**

`@Valid` is applied on every request body. Bearer security (`bearerAuth`) and the
400 validation contract are asserted by `CampaignOpenApiContractTest` (28 tests).

### 13.2 `typeConfig` and `integrationConfig` in the generated document

Both are emitted as bare `type: object` with **no properties schema** — because
they are raw `JsonNode`. `typeConfig` carries rich prose documenting all four
types (VB-7A/VB-7B). `integrationConfig` carries only *"Optional API/webhook
integration configuration"* with **no shape, no example, no bounds, and no
statement that nothing consumes it.**

That is the OpenAPI-visible symptom of §7.2: a client sees a field that reads as
a supported feature and is in fact inert.

### 13.3 Minimal API changes VB-7C would require

Derived from evidence, not assumed:

| Addition | Justification |
|---|---|
| `integrationConfig` gets a documented typed schema **or** is deprecated | It is currently a public field with no contract (§7.2) |
| Webhook config, if introduced, rides the **existing** request/response DTOs | No new resource exists to justify a new controller; the campaign **is** the configuration owner |
| Readiness reason codes, if extended, are response-payload only | No new endpoint |
| **No new endpoint is required for webhook configuration** | Campaign-level config, owned by the campaign aggregate |

**No new controller, no new base path, no new capability.** This keeps the
campaign API surface unchanged, which is the desired outcome.

### 13.4 A boundary observation for §17

The campaign controller exposes the **attempt lifecycle** as public REST:
`create attempt`, `mark in progress`, `mark completed`, `mark failed`, `cancel`.
These let a client drive execution state directly. They are *existing* surface,
not something VB-7C should change — but they are the closest thing to a
configuration/execution coupling in the API, and the future scheduler phase
should be aware that a second, HTTP-driven attempt path exists alongside the
orchestrator.

---

## 14. Tenant Isolation Audit

### 14.1 The visibility ladder — sound, and deliberately so

`CampaignService.findVisible` and `CampaignReadinessService.findVisible` both use
a three-tier ladder:

```java
if (scope.tenantId() != null)  → findByIdAndTenantIdAndDeletedAtIsNull(id, tenantId)
if (scope.resellerId() != null) → findByIdAndTenantIdInAndDeletedAtIsNull(id, hierarchy)
                                 // hierarchy = ACTIVE tenants of that reseller (VB-5F)
else                            → findByIdAndDeletedAtIsNull(id)   // platform scope
```

The **unscoped** `findByIdAndDeletedAtIsNull` is reached only when the caller has
**neither** a tenant nor a reseller restriction — i.e. a platform-scope caller.
It is a deliberate administrative path, not a leak.

The reseller branch explicitly documents the non-leaking property: a campaign
outside the hierarchy is *indistinguishable from a missing one* (404), and an
empty hierarchy short-circuits to 404 before any query.

### 14.2 Repository-level scoping

22 repositories expose tenant-scoped finders. `CampaignRepository` is the only
one that also exposes an unscoped `findById` — used solely by the platform-scope
branch above.

**No composite foreign keys.** Cross-table integrity is enforced in **service
code**, not by the schema: e.g. `CampaignResourceValidationService.validateDid`
checks tenant + status + allocation + not-deleted, and `validateQueue` does the
equivalent for queues. This is consistent with the platform's existing style
(VB-5E established it) and means integrity depends on those validators being
called on every write path.

### 14.3 Webhook / report tenant isolation

**Not applicable — neither exists.** The only related surface is
`campaigns.integration_config`, which inherits tenant isolation incidentally
because it is a column on a tenant-scoped row.

**The gap for VB-7C:** when a webhook endpoint URL and signing secret do get
modelled, a **tenant-level** webhook (a URL reused across campaigns) would be a
new cross-tenant resource and would need its own composite-FK discipline. A
campaign-level one inherits isolation for free. This bears on OD-1.

### 14.4 Secrets

`integrationConfig` is tenant-scoped but **not secret-protected** (§7.3). A tenant
can read back only its own secrets — so this is **not** a cross-tenant leak — but
it is a plaintext-at-rest credential store, and it is echoed by the API.

---

## 15. Migration Audit

### 15.1 Inventory

V1 … V54 (53 files). Relevant recent precedent for "add a campaign configuration
value":

| Migration | What it added | Pattern |
|---|---|---|
| V48 | `daily_dial_limit` column | add column |
| V49 | `retry_rules` JSONB | add JSONB column |
| V50/V51 | daily-attempt ledger + `max_daily_attempts` | new table + column |
| V52 | `max_call_duration_seconds` | add column |
| V53 | IVR trees | new relational tables (uniqueness + FK needed) |
| V54 | `ck_campaigns_type` widened | constraint swap |

### 15.2 Is a migration required? (OD-11)

| Change | V55 required? | Reason |
|---|---|---|
| Webhook configuration | **No** | `integration_config` JSONB already exists on `campaigns` |
| Webhook event selection | **No** | same column |
| Report privacy configuration | **No** | same column |
| Typed webhook/privacy config | **No** | same column; typing happens in Java |
| Validation / readiness | **No** | pure code |
| **Snapshot columns** (if a consumer is added) | **Yes** | `campaign_execution_configurations` needs the frozen values — same additive-column pattern as V49/V52 |
| Relational tables | **No** | see §15.3 |

### 15.3 Relational modelling is not justified

VB-7C has **no query, uniqueness, referential-integrity, reporting, or
concurrency requirement** against webhook configuration. It is read once at
delivery time and written once at configuration time — a pure document. The
`V53` (IVR trees) shape is justified because IVR trees have node uniqueness,
cross-tree FK targets and cycle detection. None of that applies here.

**Recommendation: reuse the existing JSONB. No V55 for configuration. V55 becomes
necessary only in the phase that adds the first webhook consumer and therefore
must snapshot the configuration.**

---

## 16. Test Coverage Audit

### 16.1 Existing coverage

| Area | Tests | Adequate? |
|---|---|---|
| Type configuration, all 4 types | `CampaignTypeConfigTest` (9), `MissedCallCampaignConfigTest` (21), `ConnectByAgentCampaignConfigTest` (12) | **Yes** |
| The four VB-7B gates | `CampaignMissedCallValidationTest` (8) | **Yes** |
| Snapshot freezing | `CampaignConfigurationSnapshotTest`, `…PostgresIntegrationTest`, `RetryPolicySnapshot…`, `DailyDialLimitSnapshot…` | **Yes** |
| PostgreSQL / migration | `ConnectByAgentConfigPostgresIntegrationTest`, `MissedCallPostgresIntegrationTest`, `CampaignGovernanceHardeningPostgresIntegrationTest`, … | **Yes** |
| OpenAPI contract | `CampaignOpenApiContractTest` (28) | **Yes** |
| Resource validation / tenant isolation | `CampaignResourceValidationServiceTest`, `CampaignResourceValidationPostgresIntegrationTest`, `CampaignQueueResourceValidationTest` | **Yes** |
| MISSED_CALL runtime | `MissedCallExecutionServiceTest` (19) | **Yes** |
| **Readiness — dedicated class** | **NONE EXISTS** | **No — see §16.2** |
| Webhook | **none** | n/a — nothing to test |
| Report privacy | **none** | n/a — nothing to test |
| `integrationConfig` validation | **none** | **No — the field is untested entirely** |

### 16.2 Coverage gap: readiness has no owner test

**There is no `CampaignReadinessServiceTest`.** Readiness is exercised only
incidentally, through `ConnectByAgentConfigPostgresIntegrationTest` (2
assertions), `MissedCallPostgresIntegrationTest` (2 assertions), and slice tests
that never inspect reason codes.

The service has **eight independent checks, twelve reason codes, and a
fail-open branch that survived VB-7B** (§19.1) — and no test class owns it. This is
the direct reason a fourth campaign type could break a readiness rule without any
test failing: **the tests that exist assert the types they were written for, not
the invariant.**

### 16.3 Proposed test matrix for the implementation phase

Proposed only; **no tests were added in this audit.**

| # | Category | Cases |
|---|---|---|
| 1 | **Type exhaustiveness** | For **all 4** types × **every** readiness check: assert the rule fires (or deliberately does not). This is the anti-regression that would have caught §19.1. |
| 2 | Timezone rule | Windowless campaign, no timezone, **each** of the 4 types → not ready. Window + timezone → ready. Bad IANA id → not ready. |
| 3 | TTS rule | `DTMF + TTS` rejected at write time (§19.2). `PLAYFILE + TTS` still rejected. `CONNECT_BY_AGENT + TTS`, `MISSED_CALL + TTS` rejected. |
| 4 | Readiness vocabulary | Every reason code reachable and asserted by name; no dead codes; no duplicate reasons. |
| 5 | Webhook config | Valid accepted; unknown field rejected; non-HTTPS rejected; URL length/format; bad event name rejected; **secret keys rejected**; absent config valid. |
| 6 | Event selection | Each selectable event accepted; unknown event rejected; empty selection rejected when a webhook URL is present. |
| 7 | Report privacy | Each privacy level accepted; out-of-range rejected; absent valid. |
| 8 | Optionality | Campaign with neither webhook nor privacy config is **ready**. |
| 9 | Tenant isolation | Foreign queue/DID/group/secret → indistinguishable from missing. |
| 10 | Snapshot | Webhook + privacy config freeze into the snapshot; a post-creation campaign edit does not mutate them; a later execution gets the new value. |
| 11 | Migration | If V55 is added: applies cleanly; existing executions still readable. |
| 12 | OpenAPI | Generated document (not annotations) shows the typed schemas, bounds, examples, and unchanged bearer/400 contracts. |
| 13 | PostgreSQL | JSONB round-trips; `ck_campaigns_type` still rejects a bogus type. |
| 14 | Regression | All VB-6C→VB-7B suites, plus 16 modules / 0 cycles. |

---

## 17. Execution/Scheduler Boundary

### 17.1 VB-7C can stop at "READY"

**Confirmed, on evidence.** Per §5.1, the configuration layer has no execution
dependencies. The required future flow:

```
Is campaign runnable?        → CampaignReadinessService  ✓ exists
Get next eligible audience   → createInitialAttempts (Model A) ✓ exists
Check global safety policy   → DailyDialLimitService, DailyAttemptSafetyService ✓ exist
Check campaign retry policy  → RetryPolicyService ✓ exists
Check DID                    → VoiceRoutingService, VoiceEligibilityService ✓ exist
Check whitelist/DND          → VoiceEligibilityService ✓ exists
Check calling window         → schedule columns + readiness ✓ exist
Check gateway capacity       → VoiceCapacityService ✓ exists
Create/dispatch attempt      → OutboundDialService ✓ exists
```

**Every contract the future scheduler needs already exists.** The per-contact
eligibility services are already called from inside `OutboundDialService`, not
from configuration. There is **no coupling for VB-7C to fix** and none to record
as a defect.

### 17.2 Couplings that do exist (recorded, not fixed)

| Coupling | Location | Assessment |
|---|---|---|
| Attempt lifecycle exposed as REST | `CampaignController` (5 endpoints) | Pre-existing. A second, HTTP-driven attempt path alongside the orchestrator. **Flag for the scheduler phase**, not VB-7C. |
| `VoiceRoutingService`'s dead `callType` parameter | audit's prior P2 finding | Untouched per VB-7B instruction. Still dead. **P2, deferred.** |
| `integrationConfig` write-only field | `CampaignMapper` | A configuration field with no consumer — the §12.2 hazard. **VB-7C must resolve.** |

### 17.3 No new `@Scheduled`

Confirmed: 9 `@Scheduled` annotations across the platform, none in the
configuration layer. VB-7C must not add one.

---

## 18. Existing Reusable Authorities

VB-7C must use these, not replace them.

| Authority | Reuse for |
|---|---|
| `CampaignTypeConfig` (sealed, exhaustive) | The pattern a new typed config must follow. Adding a *non-campaign-type* config must not weaken it. |
| `CampaignResourceValidationService` | Queue/DID/asset validation + the non-leaking `ValidationCode` vocabulary. Model for any new resource check. |
| `CampaignReadinessService` + `CampaignReadinessReason` | Where any new readiness rule belongs. |
| `CampaignConfigurationSnapshot` | Where a new frozen value belongs. Additive columns only. |
| `CampaignConfigurationService` | The single freeze point at execution creation. |
| `AuthorizationService` + capability model | The `REPORT_VIEW`/`REPORT_EXPORT` capabilities already seeded for reports. |
| `@MaxCallDurationSeconds`, `@DailyDialLimit`, `@CampaignDailyAttempts` | The established custom-constraint pattern for validating a scalar campaign field. |
| `CampaignEventPublisher` | The existing synchronous publish seam. **Reuse the shape; do not build a transport.** |
| `CampaignDomainEvent` | Internal only. Do **not** bind an external contract to it (§8.4). |
| `CampaignMapper` | The single create/update/clone/response mapping point — where a config field is validated, frozen and echoed. |
| `OrganizationContextHolder` + the 3-tier visibility ladder | The tenant-scoping pattern to copy for any new resource. |

---

## 19. Existing Defects / Gaps

### 19.1 P0 — Readiness timezone rule fails open for MISSED_CALL

**Location:** `CampaignReadinessService:197-199`

```java
if (campaign.getCampaignType() == CampaignType.PLAYFILE
        || campaign.getCampaignType() == CampaignType.DTMF
        || campaign.getCampaignType() == CampaignType.CONNECT_BY_AGENT) {
    if (schedule.getTimezone() == null || schedule.getTimezone().isBlank()) {
        reasons.add(new CampaignReadinessReason("SCHEDULE_TIMEZONE_REQUIRED", …));
        return;
    }
}
```

**The list omits `MISSED_CALL`.** Traced end to end:

| Step | Evidence |
|---|---|
| 1. A windowless campaign may be created with **no** timezone | `CampaignService:491` — timezone required **only** `if (windowConfigured && …)` |
| 2. Readiness **skips** the timezone check for `MISSED_CALL` | the list above |
| 3. **The campaign reports READY** | no other rule requires a timezone |
| 4. At dial time the zone is invalid/absent → the attempt **fails** | `OutboundDialService:180-185` → `CallFailureCode.EXECUTION_TIMEZONE_INVALID` |
| 5. That code is **PERMANENT** | `CallFailureCode:200` — `EXECUTION_TIMEZONE_INVALID(RetryClass.PERMANENT)` |
| 6. `DailyDialLimitService` documents the same contract | *"A null/blank/invalid zone fails the dial deterministically… there is deliberately no JVM/UTC fallback"* |

**Impact:** an operator can create, approve, schedule and activate a MISSED_CALL
campaign that **fails 100% of calls** with a non-retryable failure, and the only
signal is a log line. This is *precisely* the failure mode VB-6E documented and
fixed for TTS: *"a configuration an operator can build, approve and watch fail
100% of the time, with no way to find out short of reading logs."*

**It is a regression introduced by VB-7B** — adding a fourth enum constant
without adding it to this third membership list. The readiness comment directly
above the branch states the rule's purpose ("no JVM/UTC fallback exists at dial
time… so readiness fails closed here"), which makes the omission a defect rather
than a design choice.

**Fix:** the rule is genuinely universal for Voice Blast campaigns. Express it
without a type list — e.g. key it on the dial path rather than the campaign type,
or invert to an exclusion only if a non-Voice-Blast type ever exists.

### 19.2 P0 — TTS is still allowed for DTMF, which has no TTS runtime

**Location:** `CampaignService:415`

```java
if (type == CampaignType.PLAYFILE && mode == ContentMode.TTS) {
    throw business("PLAYFILE campaigns do not support TTS content yet; …");
}
```

`ContentMode.TTS` is referenced in exactly six places, **all of them validation
or readiness** — there is **no TTS synthesis or playback runtime anywhere**:

| Reference | Role |
|---|---|
| `CampaignService:415` | the PLAYFILE-only deny |
| `CampaignService:425`, `:640` | mode-coherence validation |
| `CampaignReadinessService:283`, `:487` | readiness / template check |

VB-6E's fix was applied **per type** rather than to the mode. So:

- `PLAYFILE + TTS` → rejected ✓ (fixed in VB-6E)
- **`DTMF + TTS` → accepted**, passes readiness (an approved TTS template exists),
  is scheduled, executed — and cannot play a synthesized prompt, because nothing
  synthesizes TTS. It fails at runtime with `PLAYBACK_CONFIG_INVALID`
  (`CallFailureCode:92`, **PERMANENT**).

**Impact:** identical to §19.1 — a ready campaign that fails 100% of calls,
permanently. Same defect class, same consequence, still open.

**Fix:** the invariant belongs to the **mode**, not the type: `TTS` has no
runtime, so no campaign may be configured with it until one exists. One
type-agnostic rule replaces the per-type deny and cannot fail open again.

### 19.3 P1 — `integrationConfig` is an unvalidated, echoed, secret-bearing sink

Detailed in §7.3. Summary: the documented "secrets are prohibited" invariant is
unenforced; arbitrary JSON including credentials is stored, **echoed back by the
API**, and **copied into every clone**. Tenant-isolated, but plaintext at rest and
advertised in OpenAPI as a supported field.

### 19.4 P2 — Readiness has no dedicated test class

Detailed in §16.2. The direct cause of §19.1's survival.

### 19.5 P2 — Readiness reason vocabulary is inconsistent

`DTMF_CONFIG_INVALID` and `IVR_CONFIG_INVALID` exist — but only as **runtime
failure codes** (`DtmfExecutionService:64`, `IvrExecutionService:88`), listed in
`CallFailureCode`. They are **not** readiness reason codes. A DTMF campaign with
an unparseable `typeConfig` is reported at readiness as
`INVALID_CONTENT_CONFIGURATION` (via the `default ->` arm at
`CampaignReadinessService:334`), which is **misleading** — the fault is the *type
configuration*, not the *content*.

A caller cannot distinguish "this DTMF campaign has a broken DTMF payload" from
"this campaign's audio asset reference is wrong" — two different operator fixes.

### 19.6 P2 — Configuration rules duplicated across two services

Content and typeConfig rules exist in both `CampaignService` and
`CampaignReadinessService`. Currently consistent; nothing enforces it. The
readiness reason codes are centralised in one helper; the *rules* are not.

### 19.7 P2 — Empty audience is silent

`contactGroupId = null` is explicitly permitted, and a campaign whose group has
no members produces an execution with **zero** attempts and only a `log.warn`.
Readiness does not report it. This is a product-visible gap in the
"who do you want to call" journey.

---

## 20. Architectural Risks

| # | Risk | Severity | Mitigation |
|---|---|---|---|
| R1 | A webhook **consumer** is added while `integrationConfig` stays out of the snapshot | **High** | §12.2 — resolve the exclusion in the *same* phase as the first consumer. Otherwise campaign edits silently change running executions. |
| R2 | Reusing internal `CampaignDomainEvent` names as the external contract | **High** | §8.4 — separate external vocabulary. Internal records carry no contact/attempt data. |
| R3 | A typed webhook config **replaces** rather than **narrows** the existing JSONB, silently breaking stored arbitrary payloads | Medium | The brief forbids legacy compatibility layers, so a narrowing must be an explicit, documented, validated break — decided in implementation, not by default. |
| R4 | Webhook delivery built as a new scheduler/queue/outbox | Medium | §17 — no new `@Scheduled`; if delivery is ever built, reuse `CampaignEventPublisher`'s seam. |
| R5 | Report privacy configured before reports exist | Medium | §11.4 — configuration may precede its consumer, but readiness must not gate on it, and the config must land with the consumer. |
| R6 | `integrationConfig` accumulating real secrets before a credential abstraction exists | **High** | §19.3 — reject secret-shaped keys at write time; the platform has no secret store. |
| R7 | New Family-B type lists introduced for webhook/privacy rules | **High** | §10.3 — this is a *known, twice-executed* mistake. Every new rule must be type-agnostic or exhaustive-by-construction, and §24 T1 makes exhaustion a test. |
| R8 | Audit conclusions drawn from a **partial** test baseline | Low | §2.2 disclosed; all findings are source-traced, not test-derived. |

---

## 21. Open Decisions

Twelve decisions, each with evidence, options, recommendation and consequence.

### OD-1 — Where does webhook configuration live?

**Evidence.** `campaigns.integration_config JSONB` exists (V14:74), is written
verbatim, echoed, cloned, **excluded from the snapshot**, and read by nothing.

- **A.** Keep the existing untyped JSONB.
- **B.** Introduce a typed Java record parsed from the same column.
- **C.** New relational table.

**Recommendation: B** — a typed record in the **existing** column, following
`ConnectByAgentCampaignConfig`/`MissedCallCampaignConfig` exactly.

**Reason.** The column already exists (so no migration), but its current
untypedness is what makes §19.3 possible. Typing it in Java is the same,
already-proven technique used for all four campaign type configs; it costs no
migration and makes the field honest. **C** is unjustified — no query, uniqueness
or FK requirement exists (§15.3).

**Consequence.** `WebhookConfig` record with strict parsing and unknown-field
rejection; **no V55**; the JSONB column is reused.

### OD-2 — Does event selection use internal names or a new vocabulary?

**Evidence.** §8: five campaign-CRUD events, unpersisted, unsubscribed, carrying
no contact or attempt data. No execution events exist at all.

- **A.** Reuse `CampaignDomainEvent` constants.
- **B.** A separate, stable external webhook-event vocabulary.

**Recommendation: B.**

**Reason.** Option A would publish a contractual surface with no useful payload
and make internal refactors breaking. Option B is the only shape that can be
documented and regression-tested as a contract.

**Consequence.** A new public enum of selectable events; a tested, explicit
mapping to internal events **where one exists**. Integrators selecting
"execution completed" would be selecting an event the platform does not yet emit
— so §23 must defer delivery.

### OD-3 — Is webhook configuration snapshotted?

**Evidence.** §12.2: the exclusion's stated reason ("no code reads it") is true
today and silently becomes false the moment a consumer lands.

**Recommendation: yes — in the same phase as the first consumer.** Not now
(there is nothing to freeze), and not never.

**Consequence.** Additive columns on the existing `CampaignConfigurationSnapshot`
+ `campaign_execution_configurations`; **no new snapshot system, no versioning,
no history**. This is what makes V55 necessary, and only in that phase.

### OD-4 — Is report privacy snapshotted?

**Evidence.** §12.3; same argument as OD-3.

**Recommendation: yes, conditionally** — the moment reports consume it, via the
same additive-column mechanism.

**Consequence.** No migration in a configuration-only phase.

### OD-5 — Does webhook configuration gate readiness?

**Evidence.** §11.4. Nothing consumes it; VB-7A's precedent rejects gating on
unconsumed/transient facts.

**Recommendation: no.** Never gate readiness on delivery health, endpoint
reachability, or remote availability.

**Consequence.** Validation at **write time**; readiness untouched.

### OD-6 — Does report privacy gate readiness?

**Evidence.** §11.4. There is no report surface at all (§9.2).

**Recommendation: no**, for the same reason.

**Consequence.** As OD-5.

### OD-7 — What is validated at write time versus readiness time?

**Evidence.** §10.1; the platform's own split is *coherence at write time,
validity at readiness, validity again at execution*.

**Recommendation.**

| Concern | Write time | Readiness | Execution |
|---|---|---|---|
| Shape/type-agnostic coherence (secret keys, bad URL scheme, unknown field, event name) | **yes** | no | no |
| Type-specific config parsing | **yes** (already) | **yes** (already) | — |
| Referenced-resource **existence/ownership** | **yes** (already) | **yes** (already) | **yes** (dynamic) |
| Referenced-resource **liveness** (approval, ACTIVE) | no | **yes** | **yes** |
| Anything transient (endpoint reachability, delivery health) | no | **never** | per call |

**Reason.** Write-time rejects what can never succeed; readiness reports what is
currently untrue; execution re-checks liveness because it changes minute to
minute. The §19.1 and §19.2 defects are both **write-time** omissions — a
configuration that can never work — which is exactly where this rule says they
belong.

**Consequence.** A new `validateIntegrationConfig` on the `CampaignService`
write path, mirroring `validateSchedule`/`validateContent`.

### OD-8 — Is webhook configuration optional by default?

**Evidence.** §7.2 — it is already optional, and always has been. Nothing
requires it.

**Recommendation: yes, optional.** Absent configuration = no webhooks; a campaign
with none is ready and unchanged in behaviour.

**Consequence.** No new required fields. Readiness unchanged.

### OD-9 — Are delivery, retry and signing part of VB-7C?

**Evidence.** §7.4 — **none exist**; no dispatcher, no delivery, no signing, no
persisted events, and the event vocabulary that would drive them is campaign-CRUD
only (§8).

**Recommendation: defer all three.** VB-7C should deliver **configuration +
validation + snapshot readiness**, nothing more.

**Reason.** Delivery requires an event vocabulary that does not exist (§8.3), a
dispatcher, a delivery ledger, retry policy, and signing-secret storage — none
of which the platform has, and all of which the brief's "no new infrastructure"
rule and §17's "no new scheduler" rule push out of this phase. Building
configuration for a consumer that does not exist is how `integrationConfig`
became dead in the first place.

**Consequence.** This phase produces a **validated, ready, frozen** webhook
configuration that nothing yet acts on. That is a deliberate, disclosed
intermediate state — and it is materially better than today's
*unvalidated, unsnapshotted, untyped* one, because it can be stored correctly
and will be frozen correctly when the consumer arrives (OD-3).

**Product decision required:** confirm that a configuration-only phase is
acceptable, or that delivery must be in scope.

### OD-10 — Does report privacy need enforcement now?

**Evidence.** §9: no config, no reports, no exports, and the only masking is a
private stub helper. `REPORT_VIEW`/`REPORT_EXPORT` are seeded in V1 and
referenced by **zero** code.

**Recommendation: configuration + validation only; no enforcement.**

**Reason.** There is no report path to enforce against (§9.2). Enforcement
requires reports to exist first. Inventing masking with no consumer would be
speculative abstraction — the exact failure `integrationConfig` already
represents.

**Consequence.** A validated, optional, typed privacy config. The seeded
`REPORT` capability is where the eventual enforcement will attach.

**Product decision required:** whether privacy should also constrain the
existing attempt-listing responses, which *do* expose contact data today
(`GET .../attempts`). That is a real, present surface — see §23.

### OD-11 — Is V55 required?

**Evidence.** §15.2. All configuration rides the existing JSONB.

**Recommendation: no V55 for VB-7C.** V55 becomes necessary **only** in the phase
that adds the first webhook consumer and must snapshot the configuration (OD-3).

**Reason.** "No unnecessary migration" is a stated principle; nothing in this
phase needs new columns, tables, enums, or constraints.

**Consequence.** VB-7C ships **zero migrations**. V55 is reserved.

### OD-12 — What must remain untouched?

**Recommendation.** From §18, unchanged:

`RetryPolicyService`, `DailyDialLimitService`, `DailyAttemptSafetyService`,
`CallEligibilityService`, `VoiceEligibilityService`, `VoiceRoutingService`,
`AcdService`, `OutboundDialService`, `HangupCauseMapper`, `EslEventService`,
`CampaignTypeConfig`'s existing four cases, all four campaign types, the
`CampaignExecutionOrchestrator` tick, and the existing 9 `@Scheduled` beans.

Also untouched: the sealed-hierarchy contract itself — **VB-7C adds no campaign
type** and must not weaken the default-free exhaustive `switch`.

---

## 22. Recommended VB-7C Implementation Scope

Sequenced, smallest-first, each phase independently shippable and fully
regressed.

### Phase 1 — Close the two live fail-opens (P0, no new features)

1. **§19.1** timezone readiness rule for all four types, without a type list.
2. **§19.2** `ContentMode.TTS` rejected type-agnostically (no runtime exists).
3. **§19.4** add `CampaignReadinessServiceTest` — an owner test asserting every
   check for every type, so Family-B lists cannot be added again silently.
4. §19.5 normalise the readiness reason vocabulary.

*No migration, no new config, no API change.* Pure correctness.

### Phase 2 — Govern the existing `integrationConfig` (P1)

5. Typed `WebhookConfig` + `ReportPrivacyConfig` records in the **existing**
   column (OD-1, B).
6. Write-time `validateIntegrationConfig`: unknown fields, URL scheme/format/length,
   secret-key denial (§19.3), event names (OD-2, B).
7. Optional by default (OD-8); nothing else changes.
8. OpenAPI: document the real schema on all three DTOs, or deprecate the field.

*No migration.*

### Phase 3 — Freeze it (§12.2, OD-3/OD-4)

9. Add webhook + privacy config to `CampaignConfigurationSnapshot`; freeze at
   execution creation.
10. **This is the only phase that may need V55** — additive columns only.

*Only do this together with a consumer, or the snapshot work is speculative.
If Phase 2 ships with no consumer, Phase 3 must wait.*

---

## 23. Explicitly Deferred Work

| Deferred | Why |
|---|---|
| Webhook **delivery** | No event vocabulary exists (§8.3); no dispatcher, ledger, retry or signing (§7.4) |
| Webhook **signing / secret storage** | No credential abstraction exists; adding one is a security-design project |
| Webhook **retry** | Requires a delivery ledger — a second retry system, explicitly out of bounds |
| Report **endpoints / exports** | Do not exist at all (§9.2) |
| Report privacy **enforcement** | Needs a report surface; masking with no consumer is speculative |
| New event emission (execution/attempt/DTMF/agent/MISSED_CALL events) | A separate phase; must precede any webhook delivery |
| Outbox / Kafka transport | `CampaignEventPublisher` explicitly anticipates a *future* transport; not this phase |
| Execution scheduler | §17 — the task's core prohibition |
| New `@Scheduled` | §17 |
| Tenant-level webhook endpoints | A new cross-tenant resource (§14.3); a campaign-level URL inherits isolation free |
| Campaign versioning / history / legacy config | Explicitly prohibited |
| Empty-audience readiness reason (§19.7) | Product decision; pre-existing behaviour |
| `VoiceRoutingService` dead `callType` | P2, deferred since VB-7A |
| Attempt-lifecycle REST surface | Pre-existing; flag for the scheduler phase (§17.2) |
| Frontend / UI | Out of scope |

---

## 24. Proposed Test Matrix

Consolidated from §16.3. Proposed only — **no tests were added or modified.**

| Group | Priority | Cases |
|---|---|---|
| **T1 Type exhaustiveness** | **P0** | 4 types × every readiness check and every write-time rule. **This is the anti-regression for §19.1/§19.2.** |
| T2 Timezone | **P0** | Windowless + no timezone, each of 4 types → **not ready**. Window + zone → ready. Bad IANA → not ready. |
| T3 TTS | **P0** | `TTS` rejected for **all** 4 types. `PLAYFILE + TTS` still rejected. Regression: no TTS runtime is ever advertised. |
| T4 Readiness vocabulary | P1 | Every reason code asserted by name; no dead codes; no duplicate reasons; no misleading code. |
| T5 Webhook config | P1 | Valid accepted; unknown field rejected; non-HTTPS rejected; malformed URL rejected; over-length rejected; **secret keys rejected**; absent valid. |
| T6 Event selection | P1 | Each selectable event accepted; unknown event rejected; selection without a URL rejected. |
| T7 Report privacy | P1 | Each level accepted; out-of-range rejected; absent valid. |
| T8 Optionality | P1 | A campaign with neither webhook nor privacy config is **ready**. |
| T9 Tenant isolation | P1 | Foreign queue/DID/group/credential → indistinguishable from missing. |
| T10 Snapshot | P1 | Webhook + privacy freeze into the snapshot; post-creation edit does not mutate; later execution gets the new value. |
| T11 Migration | P1 | If V55 is added: applies cleanly; existing executions readable. |
| T12 OpenAPI | P1 | **Generated document** (not annotations) shows typed schemas, bounds, examples, and unchanged `bearerAuth` / 400 contracts. |
| T13 PostgreSQL | P1 | JSONB round-trips; `ck_campaigns_type` still rejects a bogus type. |
| T14 Regression | P0 | All VB-6C→VB-7B suites; 16 modules / 0 cycles; 1 pre-existing skip. |

---

## 25. Proposed Migration Plan

| Phase | Migration | Contents |
|---|---|---|
| **Phase 1** (P0 fixes) | **none** | Pure code |
| **Phase 2** (govern `integrationConfig`) | **none** | Existing `JSONB` column reused (OD-11) |
| **Phase 3** (freeze) | **V55, only if a consumer lands** | Additive columns on `campaign_execution_configurations` (webhook config + event selection + privacy config), following the V49/V52 precedent |

**VB-7C as commissioned (config + validation + readiness) requires no
migration.** V55 is reserved and must not be created speculatively.

No new tables, columns, enums, indexes, or constraints are justified for webhook
or privacy configuration: no query, uniqueness, referential-integrity, reporting
or concurrency requirement exists (§15.3).

---

## 26. Acceptance Criteria

For a future implementation phase, derived from this audit's findings:

1. Both P0 fail-opens closed, and each closed **type-agnostically** so a fifth
   type cannot reopen them.
2. `CampaignReadinessServiceTest` exists and asserts every check for every type.
3. No Family-B campaign-type list remains in the configuration path (§10.3).
4. `integrationConfig` is typed, strictly parsed, and rejects secret-shaped keys.
5. Every reason code is reachable, correctly named, and reported exactly once.
6. Webhook and report-privacy configuration are optional and absent-by-default.
7. Neither gates readiness; neither gates on transient availability.
8. Webhook configuration is validated at write time for everything that can
   never succeed.
9. Snapshot freeze added **in the same phase** as the first consumer.
10. No `integrationConfig` consumer ships while it is still outside the snapshot.
11. Generated OpenAPI documents the real shape (or deprecates the field).
12. All four campaign types remain valid; no new type is introduced.
13. Zero migrations unless a consumer requires the snapshot columns.
14. Tenant isolation and non-leaking preserved for every new surface.
15. Full suite green; 16 modules, 0 cycles; only the 1 pre-existing skip.
16. No test deleted, disabled, weakened, or hidden.
17. User-owned work untouched.
18. Delivery, signing, retry, reports and enforcement explicitly **not**
    implemented.

---

## 27. Final Audit Verdict

# READY WITH DECISIONS

**Why not plain READY:** two P0 fail-opens are live today, and both let an
operator build a campaign that is approved, ready, scheduled and then fails 100%
of calls with a **PERMANENT**, non-retryable failure code. They are the same
defect class VB-6E fixed for PLAYFILE+TTS and VB-7B fixed for the content gate —
and one of them was *introduced* by VB-7B adding a fourth enum constant. Shipping
new configuration on top of them would compound the problem.

**Why READY, not NOT READY:**

- The architecture VB-7C must reinforce **already exists and is sound.** The
  configuration layer has zero execution dependencies (§5.1), the sealed
  type-config hierarchy is compiler-enforced (§5.2), tenant isolation is a
  deliberate three-tier ladder (§14.1), and the snapshot is a reusable additive
  model (§12.3).
- Webhooks and report privacy are **absent rather than broken.** There is no
  half-built subsystem to untangle, no legacy data to migrate, and no consumer to
  keep consistent. `integrationConfig` is a five-minute-of-cleanup
  typed-config exercise, not a migration.
- The reusable authorities are identified and unmoved (§18), and no new
  infrastructure is required.

**Decisions required before implementation** (from §21):

| Must be answered | OD |
|---|---|
| Is a **configuration-only** webhook phase acceptable, with delivery/signing/retry deferred? | **OD-9** |
| Should report privacy also constrain the **existing** attempt-listing responses, which expose contact data today? | **OD-10** |
| Confirm the external webhook-event vocabulary is **separate** from internal domain event names | **OD-2** |

OD-1, OD-3 through OD-8, and OD-11 through OD-12 have clear evidence-based
recommendations that stand unless the product overrules them.

**Recommended sequencing** (§22): **Phase 1 first, alone.** It is pure
correctness, needs no migration, adds no feature, and closes the reason a fifth
campaign type could break the platform again.

---

### Answers to the closing questions

| | |
|---|---|
| HEAD | `828bec4` |
| `origin/main` | `828bec4` |
| Migration head | V54 |
| Tests | **409/0F/0E verified**; full 1754 run **OOM-crashed (environmental, 0 GB free RAM, 8 user Docker containers). Not fixed, per the brief.** |
| Architecture | **PASS — 16 modules, 0 cycles** |
| Changed files | **`docs/VB-7C-INTEGRATIONS-CAMPAIGN-CONFIGURATION-AUDIT.md` (this file) only** |
| User-owned files preserved | **Yes — 4 modified + 8 untracked paths, none read, staged, or committed** |
| Webhook findings | **Zero implementation.** `integrationConfig` is a dead, unvalidated, echoed, secret-bearing sink; explicitly excluded from snapshots |
| Event selection | **No viable vocabulary.** 5 campaign-CRUD events, 0 listeners, 0 execution events |
| Report privacy | **Entirely absent.** No config, no reports, no exports. `REPORT_VIEW`/`REPORT_EXPORT` seeded in V1, referenced by zero code |
| Validation findings | **VB-7B's 2 gates confirmed correct; 2 Family-B fail-opens remain (both P0)**, plus untested `integrationConfig` |
| Readiness findings | **8 checks, 12 codes, no owner test; `MISSED_CALL` omitted from the timezone rule** |
| Snapshot findings | **Existing embeddable accommodates everything additively; the `integrationConfig` exclusion must be resolved with the first consumer** |
| API/OpenAPI | **No webhook/report endpoint. `integrationConfig` documented as an untyped object. No new endpoint required for VB-7C.** |
| Tenant isolation | **Sound.** 3-tier ladder; the unscoped lookup is a deliberate platform-scope path |
| Migration recommendation | **No V55.** V55 required only in the phase that adds the first snapshot-frozen consumer |
| Open decisions | **3 need product input** (OD-9, OD-10, OD-2); 9 have evidence-based recommendations |
| Implementation phases | **3** — P0 correctness; typed+validated integration config; snapshot freeze (conditional) |
| Explicit non-goals | §4 and §23 |

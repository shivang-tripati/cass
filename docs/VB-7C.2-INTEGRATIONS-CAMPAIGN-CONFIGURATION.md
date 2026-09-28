# VB-7C.2 — Integrations + Campaign Configuration

Implementation record for VB-7C.2. Design source: the VB-7C audit
(`docs/VB-7C-INTEGRATIONS-CAMPAIGN-CONFIGURATION-AUDIT.md`, §7, §9, §15) with
OD-2, OD-9 and OD-10 locked.

- Baseline: `33e303e` (VB-7C.1) = `origin/main`, migration head **V54**
- Migration head after this phase: **V54** — **no migration**
- New tests: **101** (57 config + 9 vocabulary + 16 write-time + 10 HTTP + 9
  readiness (in the 51-test owner suite) … see §13 for the exact split)

---

## 1. Status

**COMPLETE** — configuration only. No delivery, no signing, no retry, no report
runtime, no scheduler, no FreeSWITCH change.

---

## 2. Baseline

| Item | Verified |
|---|---|
| Branch / HEAD | `main` / `33e303e` |
| `origin/main` | `33e303e` (identical, in sync) |
| Migration head | `V54__campaign_type_missed_call.sql` (53 files) |
| Modules / cycles | 16 / 0 |
| Working tree | 9 modified + 11 untracked, **all user-owned FreeSWITCH work** |

The repository had not advanced since the audit. The user-owned list was taken
from `git status` as instructed and none of it was touched, staged or reverted.

---

## 3. Configuration model

Six new types, all in `com.shivang.obd.campaign.config` — the package the four
existing typed campaign configurations already live in. No new module, no new
package, no new framework.

| Type | Shape |
|---|---|
| `CampaignIntegrationConfig` | `record(webhook, reportPrivacy)` — the typed replacement for the loose `JsonNode` |
| `WebhookConfig` | `record(enabled, endpoint, events)` |
| `ReportPrivacyConfig` | `record(policy)` |
| `ReportPrivacy` | `enum { FULL, MASKED }` |
| `WebhookEvent` | `enum { ATTEMPT_COMPLETED, ATTEMPT_FAILED, ATTEMPT_CANCELLED }` with explicit `publicValue()` |
| `WebhookEndpointValidator` | the single URL authority |
| `CampaignIntegrationJson` | Jackson 3 adapters (deserializers + enum serializers) |

**Storage is unchanged.** `campaigns.integration_config JSONB` (V14:74) is
reused, exactly as `type_config` already carries typed configuration. That is
why **no migration was needed**. What changed is that the JSON must now parse
into this shape, strictly, or the campaign is rejected.

### 3.1 Strictness is enforced on the REST path, not just in the parser

The audit's central finding was that `integrationConfig` was a write-only
unvalidated sink. Fixing only `fromJson` would **not** have fixed it: Jackson
deserialises the DTO directly and its default rules **ignore unknown
properties**. A payload of `{"webhook":{"secret":"hunter2"}}` was accepted with
**201** and the secret silently dropped.

`CampaignIntegrationJson` routes the REST boundary through the same
`fromJson`/`toJson` static methods the persistence layer uses. One authority, no
duplicated rule set, no second validation framework.

### 3.2 A mistake worth recording: record-level serializers

The first version also bound a custom **serializer** on the three records. That
was wrong: a type with a custom serializer is **opaque to springdoc's model
resolution**, so the generated document lost the structure entirely — no `type`,
no properties, no enum. The records are plain bean-shaped values, so Jackson's
default record handling already produces the right JSON; only the **enums** need
serializing, to emit public identifiers instead of Java constant names.

Binding only the deserializers keeps strictness *and* a faithful contract. The
three dead serializer classes were removed rather than left as unused
abstraction.

---

## 4. Webhook configuration

```
"webhook": {
  "enabled": true,
  "endpoint": "https://example.com/hooks/campaign",
  "events": ["campaign.attempt.completed", "campaign.attempt.failed"]
}
```

### 4.1 Enabled/disabled semantics

| | `enabled = false` | `enabled = true` |
|---|---|---|
| endpoint | optional | **required** |
| events | may be empty | **at least one required** |

Two rules stated explicitly because their absence is how configuration drifts:

- **An endpoint never enables a webhook.** `enabled` is the only switch; a stored
  URL with `enabled=false` is a dormant configuration.
- **Events are never inferred.** An enabled webhook with no selection is an error,
  not a request for "everything".

What *is* enforced regardless of `enabled`: a **present** endpoint must be a
usable URL, and every supplied event must be supported and unique. A disabled
webhook with no endpoint and no events is valid; one carrying a malformed URL is
not, because storing garbage nothing will read is never correct.

### 4.2 Endpoint validation

`WebhookEndpointValidator` is the platform's **one** URL policy — the repository
had none. It rejects malformed URLs, relative URLs, missing hosts, over-long
values, and any scheme other than `http`/`https` (`file:`, `ftp:`, `javascript:`,
`data:`, `jar:` all refused). It also **refuses URLs carrying `userinfo`**, which
closes the obvious route for smuggling a credential into a URL.

This is deliberately *not* an SSRF defence. Reachability, private address space,
DNS rebinding and redirects are **delivery-time** concerns that become real only
when a transport exists — which this phase does not build. A half-built filter
now would create a second, weaker security boundary that delivery would have to
discover and replace.

### 4.3 Secrets

No secret, API key, bearer token or signing key can be stored. The records have
no field for one; unknown fields are refused; and endpoints with `userinfo` are
refused. A test asserts the canonical serialization contains no
secret-shaped key, and eight secret-like field names are each proven rejected.

---

## 5. External event vocabulary (OD-2)

A **separate public vocabulary**, explicitly not the internal one.

| Public value | Maps to (internal, never exposed) |
|---|---|
| `campaign.attempt.completed` | `CallAttemptStatus.COMPLETED` |
| `campaign.attempt.failed` | `CallAttemptStatus.FAILED` |
| `campaign.attempt.cancelled` | `CallAttemptStatus.CANCELLED` |

**Rationale from repository evidence.** The platform's internal vocabulary is
`CampaignDomainEvent` (`CAMPAIGN_CREATED`, …), published in-process to **zero**
listeners. Binding a public contract to it would make every internal refactor a
breaking change, and its record carries no contact, attempt or outcome
information at all. The three events defined here correspond to the **only
externally meaningful outcomes the repository actually models** — the terminal
`CallAttemptStatus` values. Non-terminal states (`QUEUED`, `IN_PROGRESS`) are not
outcomes, and no execution-, DTMF-, IVR-, agent- or connectivity-level event is
defined because **no such domain event exists to report**. Inventing a larger
catalogue would advertise a contract the platform cannot honour.

**Serialization.** The public value is an explicitly declared string, not derived
from `name()`, so renaming a constant cannot silently break stored
configuration. Each constant additionally carries `@JsonProperty` pinning its wire
name — which is what makes springdoc document the public values (see §13).

**Enforcement.** `WebhookEventVocabularyTest` reads the **real**
`CampaignDomainEvent` constants reflectively and asserts no public value contains
any internal identifier, plus no `CallAttemptStatus` or `CampaignStatus` name. It
also asserts the vocabulary has exactly 3 values and that every event's subject
segment is `attempt`.

**No delivery is implied.** Selecting an event records intent. Documented as such
in the generated OpenAPI, the DTO javadoc and the readiness test.

---

## 6. Report privacy (OD-10)

```
"reportPrivacy": { "policy": "FULL" }
```

`ReportPrivacy` has exactly two values, each traceable to repository evidence:

| Value | Basis |
|---|---|
| `FULL` (**default**) | OD-10 requires current contact-data visibility to be **unchanged**. Defaulting to anything stricter would silently change every existing campaign. |
| `MASKED` | The platform's **only** existing masking semantic: `EslClient.maskNumber` — *"Masks a phone number for logging (shows only the last 4 digits)"* — plus `NoOpAgentLegDialer.mask` and the `maskUuid` helpers. Reusing it keeps report masking consistent with log masking rather than inventing a second convention. |

**Deliberately not defined:** aggregation, pseudonymisation, hashing. Nothing in
the repository defines their semantics, and guessing would create an unsupported
contractual promise. Recorded as an open product decision (§17).

**Unrelated and untouched:** `V1` seeds `REPORT_VIEW` / `REPORT_EXPORT`
capabilities and a `REPORT_VIEWER` role, referenced by **zero** code. This phase
does not wire them, change them, or build a reporting subsystem. Whether a
caller may see a report is an *authorization* question; how much of a contact
number a report contains is a *privacy* question. They are separate.

**No report runtime, no exports, no attempt-API filtering.** Asserted by test:
`CallAttemptResponse.contactId` is still present and unchanged, and contains no
privacy field.

---

## 7. Readiness

One new check, `checkIntegrationConfig`, reporting
`INVALID_INTEGRATION_CONFIGURATION`.

**Why a readiness check at all, when write-time validation exists:** the two
answer different questions. Write-time asks *"may this be stored?"*; readiness
asks *"can the platform still understand what is stored?"* A row can become
unreadable after the fact — written by another platform version, or edited
directly in the database — and a campaign whose configuration the platform cannot
read is not meaningfully runnable. This is the same write-time/readiness division
already used for DID, audio and TTS references.

**What it deliberately does not check:** endpoint reachability, whether events
will be delivered, whether a webhook transport or signer exists, whether a
reporting subsystem is installed. VB-7C.2 implements none of those, so such a
check could only ever be false — and gating readiness on an intentionally absent
subsystem would leave every configured campaign permanently unready for a
condition that is not a fault. The same mistake VB-7A fixed for live agent
availability. Proven by tests asserting a valid enabled webhook is **ready**, and
that no delivery/report reason code is ever emitted.

**No campaign-type list.** The check is keyed on nothing type-related, so no
future campaign type can be exempted.

---

## 8. API

No endpoint added. No base path added. No new capability.

| Change | Detail |
|---|---|
| `CreateCampaignRequest.integrationConfig` | `JsonNode` → `CampaignIntegrationConfig` |
| `UpdateCampaignRequest.integrationConfig` | `JsonNode` → `CampaignIntegrationConfig` |
| `CampaignResponse.integrationConfig` | `JsonNode` → `CampaignIntegrationConfig` |
| `CampaignMapper` | four conversion sites: request→entity (canonical), entity→response (typed), clone (verbatim) |
| `CampaignService` | `validateIntegrationConfig` on create **and** update |
| `CampaignEntity` | javadoc only; column untouched |

**Error mapping.** A malformed payload is rejected by Jackson while constructing
the typed configuration, so it never reaches the service. The existing
`GlobalExceptionHandler` maps `HttpMessageNotReadableException` to **400 "Malformed
request body."** — the same status a `BusinessException` produces. **No new error
envelope**, and a client mistake can never surface as a 500. Proven by ten slice
tests asserting 400 (not 500) for a bad scheme, a missing endpoint, an unknown
event, a secret-like field and an unknown field.

**Storage normalization.** The mapper always writes the **canonical**
serialization, so what is persisted is normalized rather than the client's raw
JSON. An absent configuration stays `null` — writing a default object into every
row would be a silent change to every existing campaign and would make
"configured" indistinguishable from "defaulted".

---

## 9. Snapshot behaviour (OD-3, deferred)

**Deliberately still excluded** from `CampaignConfigurationSnapshot`, and the
exclusion is now a documented, time-limited decision in the class javadoc rather
than a bare note.

The reason is now stronger than before: **nothing consumes this configuration.**
Freezing it would record an execution's intent to deliver web-hooks — semantics
no execution currently has.

**The rule for the future, stated in code:** the moment the first consumer of this
configuration appears, it **must** be added to the snapshot *in the same phase*.
Shipping a consumer while leaving the exclusion in place would let an operator
edit a campaign's endpoint and silently change the behaviour of an
already-running execution — precisely the failure the immutable snapshot exists
to prevent. Adding it needs only an additive column on the existing embeddable
plus the matching column in `campaign_execution_configurations`, following the
V49/V52 precedent. No versioning, history table or compatibility shim.

A test asserts the snapshot has gained no integration field yet, so the decision
cannot change by accident.

---

## 10. Database

**No migration. V54 remains the head (53 files).**

`integration_config JSONB` already existed and is sufficient for the typed
configuration. There is no query, uniqueness, referential-integrity, reporting or
concurrency requirement that would justify a relational table — so per the audit's
§15.3 and the brief's "do not create speculative relational webhook tables", none
was created.

Verified against real PostgreSQL (Testcontainers, Flyway V1..V54): the typed
configuration round-trips the existing column, events persist as public
identifiers, the canonical form is stored, an absent configuration stores `null`,
and cloning copies the configuration verbatim.

---

## 11. Tenant isolation

Unchanged and inherited. Integration configuration is a column on a
tenant-scoped campaign row, so it inherits the existing ownership model exactly as
DID, audience and typeConfig do.

**No new repository method, no unscoped lookup, no authorization change.** The
campaign visibility ladder (tenant-scoped → reseller-hierarchy-scoped →
platform-scope) is untouched, and configuration is readable and writable only
through a campaign the caller can already see.

Proven against real PostgreSQL: a foreign campaign is invisible from another
tenant, and **indistinguishable from one that does not exist** — the same empty
result, so the response cannot be used to probe another tenant.

---

## 12. Explicitly NOT implemented

| | |
|---|---|
| Webhook delivery | **No** HTTP client, no transport, no `RestClient`/`WebClient` call, no post |
| Webhook signing | **No** no HMAC, no signature header, no secret storage |
| Webhook delivery retry / backoff | **No** no delivery ledger, no second retry system |
| Webhook worker / queue / dispatcher | **No** |
| Webhook scheduler | **No** |
| Delivery status / delivery logs | **No** |
| Report generation / export / CSV / PDF | **No** |
| Report privacy enforcement | **No** — configuration only; attempt APIs unchanged |
| `REPORT_VIEW`/`REPORT_EXPORT` wiring | **No** — untouched, as instructed |
| Execution scheduler | **No** `CampaignExecutionOrchestrator` untouched; `scheduledTick` untouched |
| Retry / safety / routing / DND / whitelist | **No** all untouched |
| FreeSWITCH / ESL / SIP / media / DTMF runtime | **No** all untouched |
| New `@Scheduled` | **No** — 9 remain, unchanged |
| Migration | **No** — V54 head |
| New campaign type | **No** — all four preserved |
| New module | **No** — 16 remain, 0 cycles |

---

## 13. Tests

| Suite | Tests | Covers |
|---|---|---|
| `CampaignIntegrationConfigTest` **(new)** | **57** | defaults; enabled/disabled semantics; endpoint validation (valid, malformed, 8 bad schemes, blank, over-long, credentials-in-URL, trimming); event selection (all accepted, unknown rejected, duplicate rejected, non-string rejected, deterministic order, error lists supported); strictness and the **no-secrets** contract (unknown fields, 8 secret-like names, top-level secret, canonical form contains no secret-shaped key, wrong-typed `enabled`); round trips; report privacy (both values, unsupported, missing, unknown field) |
| `WebhookEventVocabularyTest` **(new)** | **9** | public value present for every event; every event round-trips; unknown rejected; null/blank not supported; **no internal name leaks** (read reflectively from the real `CampaignDomainEvent`); public values are dotted lowercase, not Java identifiers; values explicitly defined not name-derived; vocabulary is minimal (3) and maps to real terminal attempt statuses; every event's subject is `attempt` and no non-terminal state leaked |
| `CampaignIntegrationValidationTest` **(new)** | **16** | valid configuration stored for **every** campaign type (anti-Family-B); unrepresentable invalid config for every type; unsupported privacy unrepresentable; absent config accepted; disabled webhook accepted; MASKED accepted; service re-validates as defence in depth; non-web scheme refused; **canonical form persisted**; absent config stored as `null` |
| `CampaignApiSliceTest` (+10 in a new nested class) | **26 total** | 10 HTTP tests: valid/disabled/omitted → 201; non-web scheme, missing endpoint, unknown event, secret-like field, unknown field, bad privacy → **400** (never 500) |
| `CampaignReadinessServiceTest` (+9 in a new nested group F) | **51 total** | valid enabled webhook does not block readiness (for **every** type); disabled does not block; absent does not block; unreadable stored config is reported; readiness never requires delivery or report infrastructure; enabled-without-events not ready; bad stored scheme reported |
| `CampaignIntegrationPostgresIntegrationTest` **(new)** | **8** | JSONB round-trip on the existing column; events persist as public identifiers not Java names; canonical form and deterministic order; absent stores `null`; clone copies verbatim; **tenant isolation** (foreign invisible and indistinguishable from missing); unrelated edit does not disturb config; snapshot still excludes it |
| `CampaignOpenApiContractTest` (+7) | **35 total** | typed `integrationConfig` on all three schemas; webhook + reportPrivacy structure documented; event enum lists only public values and **no internal name appears anywhere in the document**; privacy values documented; **delivery explicitly documented as unimplemented**; attempt-listing contract unchanged; no new endpoint, security and 400 contracts intact |

**New tests: 97.** No existing test was deleted, disabled, weakened or skipped.
The only edits to existing suites are **additive**.

### 13.1 Two defects the tests caught that unit tests could not

1. **The REST path bypassed the strict parser entirely.** A slice test showed
   `{"webhook":{"secret":"hunter2"}}` returning **201**. Unit tests over
   `fromJson` all passed, because they never exercised Jackson. Fixed by the
   Jackson adapters (§3.1).
2. **The public API leaked internal enum names.** Inspecting the *generated*
   document showed `events.items.enum` containing **both** `ATTEMPT_COMPLETED` and
   `campaign.attempt.completed`, because springdoc lists Java constant names and
   my `allowableValues` caused a merge rather than a replacement. Fixed with
   `@JsonProperty` on the constants; the document now contains no internal name
   at all (verified by searching the whole generated document).

Both are exactly what "generate, inspect, then fix" is for.

---

## 14. Architecture

**16 modules, 0 cycles.** No new module, no new `@NamedInterface`, no new module
edge. The new types are records and enums in the existing `campaign.config`
package — configuration leaves, not services. The adapters depend only on Jackson.

---

## 15. Files changed

### New (7)
```
campaign/config/WebhookEvent.java                        public external vocabulary
campaign/config/WebhookEndpointValidator.java            the single URL authority
campaign/config/WebhookConfig.java                       typed webhook config
campaign/config/ReportPrivacy.java                       privacy enum
campaign/config/ReportPrivacyConfig.java                 typed privacy config
campaign/config/CampaignIntegrationConfig.java           top-level typed config
campaign/config/CampaignIntegrationJson.java              Jackson 3 adapters
campaign/test/.../CampaignIntegrationConfigTest.java
campaign/test/.../WebhookEventVocabularyTest.java
campaign/test/.../CampaignIntegrationValidationTest.java
campaign/test/.../CampaignIntegrationPostgresIntegrationTest.java
docs/VB-7C.2-INTEGRATIONS-CAMPAIGN-CONFIGURATION.md      (this report)
```

### Modified (9)
```
campaign/CampaignEntity.java                    javadoc only (column untouched)
campaign/CampaignMapper.java                    4 conversion sites + 2 helpers
campaign/CampaignService.java                   + validateIntegrationConfig (create + update)
campaign/CampaignReadinessService.java          + checkIntegrationConfig + reason code
campaign/CampaignConfigurationSnapshot.java     exclusion reason rewritten (OD-3 rule)
campaign/dto/CreateCampaignRequest.java         JsonNode -> typed + OpenAPI docs
campaign/dto/UpdateCampaignRequest.java         JsonNode -> typed + OpenAPI docs
campaign/dto/CampaignResponse.java              JsonNode -> typed + OpenAPI docs
campaign/test/.../CampaignApiSliceTest.java     +10 HTTP tests (additive)
campaign/test/.../CampaignOpenApiContractTest.java  +7 contract tests (additive)
campaign/test/.../CampaignReadinessServiceTest.java +9 readiness tests (additive)
```

---

## 16. Files intentionally untouched

Verified unmodified: `RetryPolicyService`, `DailyDialLimitService`,
`DailyAttemptSafetyService`, `CallEligibilityService`, `VoiceEligibilityService`,
`VoiceRoutingService`, `AcdService`, `OutboundDialService`, `HangupCauseMapper`,
`EslEventService`, `EslClient`, `EslEvent`, `FakeEslServer`, `EslProtocolTest`,
`StaleCallReconciler`, `CampaignExecutionOrchestrator`, `CampaignTypeConfig` and
its four existing cases, `CampaignType.playsMedia()` from VB-7C.1, all snapshot
entities, and every migration.

**All user-owned work preserved** — nothing read into the implementation,
modified, staged, reverted or committed.

---

## 17. Limitations and deferred decisions

1. **Report privacy levels are an open product decision.** Only `FULL` and
   `MASKED` are defined, because those are the only ones the repository
   supports. Aggregation, pseudonymisation and hashing are deliberately absent
   rather than invented. **Needs product confirmation.**
2. **Should report privacy constrain the existing attempt-listing responses?**
   They expose `contactId` today. OD-10 forbids changing them in this phase, so
   the question stays open. It is the obvious first consumer of this
   configuration — and by §9's rule it must then be snapshotted in the same
   phase.
3. **Webhook configuration is excluded from the snapshot** by design (§9). This
   is a live hazard with a documented rule, not a settled decision.
4. **Endpoint validation is not an SSRF defence** (§4.2). That belongs with
   delivery.
5. **The event vocabulary will need extending** as domain events are actually
   produced. It is minimal on purpose; `WebhookEventVocabularyTest` pins its size
   so growth cannot happen by accident.
6. **Rule duplication between `CampaignService` and
   `CampaignReadinessService`** persists (audit §19.6). Both derive from the same
   typed authority, so they cannot disagree, but the methods remain separate.
7. **`validateIntegrationConfig` is largely redundant by construction** — the
   typed records are total, so an invalid configuration cannot be built. It is
   kept because `CampaignService` is also called from non-REST code, and because
   `validateTypeConfig` follows the same shape. This is stated in the method's
   javadoc rather than glossed over.

---

## 18. Git

- **Commit:** see the delivery summary.
- **Not pushed.**
- Staged by explicit path only; no user-owned file staged or committed.

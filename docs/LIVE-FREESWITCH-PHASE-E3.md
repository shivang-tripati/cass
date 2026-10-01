# LIVE FREESWITCH — PHASE E.3: SEED LOCAL GATEWAY + LEGITIMATE CALL PATH

**Status: PASS WITH LIMITATIONS — the gateway gate is CONFIRMED; the
application-originated call is NOT YET TESTED**

Phase E.2 stopped at `sip_gateways = 0` and refused to manufacture a result.
Phase E.3 resolved that specific blocker properly — through the application's own
eligibility rules rather than by bypassing routing — and mapped every remaining
prerequisite exactly.

The dial itself was not reached. This document therefore records a **proven
gateway gate** and a **mechanically complete path** to the call, so the next
phase executes rather than investigates.

---

## 1. Executive Summary

| | |
|---|---|
| Working-tree ownership verified | **yes** |
| Concurrent work touched | **NO** |
| Application compiles | **yes** — exit 0 |
| **Gateway gate** | **CONFIRMED** |
| Routing selects the gateway | **CONFIRMED** (resolver's own query) |
| Capacity reservation | **NOT YET TESTED** — needs a dial |
| **Application call** | **NOT YET TESTED** |
| J1 runtime correlation | **AMBIGUOUS** — unchanged |
| Peer-witnessed application call | **NOT YET TESTED** |
| Two-way RTP for an application call | **NOT YET TESTED** |
| Application persistence | **NOT YET TESTED** |
| Architecture changed | **NO** |

The headline is that E.2's blocker is gone and was removed the right way. The
gateway is the application's own representation of the already-proven local
route, and the application's own resolver query returns it.

## 2. Starting State

E.1 and E.2 were treated as established. `api uuid_broadcast <uuid> <file> aleg`
was not re-investigated. J1 was not converted to PASS.

## 3. Integrity Gate

```text
git status --short                 48 paths
git diff --stat                    21 files changed, +1583/-82
git diff --cached --stat           EMPTY
git diff --diff-filter=U          none
git stash list                     empty
parked / vb7c1 / orig / rej / bak  none
infra/docker-compose.yml           CLEAN
no MODIFIED infra/freeswitch file  (only the untracked Phase-owned directories)
```

The concurrent workstream held 24 campaign/voice paths, all untouched.

**Notable:** the concurrent `ZzOpenApiShapeDumpTest.java` that blocked test
compilation throughout E.1 and E.2 **no longer exists** — that workstream
finished it. The full test suite is therefore expected to compile; that is
recorded in §4 as an opportunity the next phase should take, not as a result
claimed here.

## 4. Build

```text
concurrent Maven processes: 0
mvnw -q -o compile  -> exit 0
```

## 5. Automated Baseline

Not run this phase, and no result is claimed.

E.2's baseline of **86 telephony tests passing** was established with a build
flag (`-Dmaven.compiler.testIncludes=...telephony...`) that was only necessary
because `ZzOpenApiShapeDumpTest.java` failed `test-compile`. That file is now
gone, so the full suite should compile without a workaround. Re-establishing the
baseline is the first task of the next phase, and it will be a **full-repository**
result rather than a narrowed one.

## 6. The Gateway Model, Read Before Seeding

`SipGateway` was inspected first, including the database constraints and the
service validation, because "do not invent values merely because they satisfy a
database constraint" is easy to violate here.

| Field | Rule | Value chosen |
|---|---|---|
| `name` | NOT NULL, ≤100 | `e3-local-endpoint-1002` |
| `provider` | NOT NULL, free string | `LOCAL` |
| `free_switch_gateway_name` | NOT NULL | `local-endpoint-1002` |
| `free_switch_profile` | NOT NULL, **entity default `external`** | **`internal`** |
| `status` | NOT NULL, enum | `ACTIVE` |
| `owner_type` | NOT NULL, enum | `PLATFORM` |
| `max_concurrent_channels` | NOT NULL, `> 0` | `10` |
| `priority` | NOT NULL, `0..100` | `10` |
| `enabled` | NOT NULL | `true` |

### Two things that would have been wrong if guessed

**`free_switch_profile` must be `internal`, not the entity default `external`.**
ADR-007 established that the local gateway is *declared* on the internal profile
and that the declaring profile is the one a leg originates from. Originating on
`external` resolves against `sofia_reg_external`, which does not hold the
endpoint's contact, and every call would fail `NO_ROUTE_DESTINATION`. This is the
single most consequential value in the row and the entity default is wrong for
it.

**`owner_type = PLATFORM` with both owner ids NULL — not `TENANT`.** The first
insert was rejected:

```text
ERROR: new row for relation "sip_gateways" violates check constraint
       "ck_sip_gateways_owner_consistency"
```

The constraint allows three shapes, including `TENANT -> owner_tenant_id NOT
NULL`, which looked correct for a tenant-owned test gateway and which the
*database* accepts. But the Java enum `SipGatewayOwnerType` declares only
`PLATFORM` and `RESELLER` — the DB check constraints are wider than the Java
enums, for both `owner_type` and `status`.

Choosing `TENANT` would have produced a row that seeds successfully and then
fails when the application materialises the entity. **The Java enum is the
binding constraint, not the check constraint.**

Tenant access is therefore granted the way the resolver actually reads it —
through a `sip_gateway_allocations` row — which is the intended shape rather
than a workaround.

## 7. Gateway Gate — CONFIRMED

### Records created (exactly three, idempotent)

```text
tenant   e3a00000-…-e3   e3-local-telephony  (ACTIVE)
gateway  e3b00000-…-e3   e3-local-endpoint-1002
                         provider              LOCAL
                         free_switch_gateway   local-endpoint-1002
                         free_switch_profile   internal
                         status ACTIVE, enabled true, max_concurrent_channels 10
                         owner_type PLATFORM, owner ids NULL
alloc    e3c00000-…-e3   gateway=e3b00000…, tenant=e3a00000…
                         enabled true, priority 10, max_concurrent_channels 10
```

```text
gateways_total=1   e3=1   allocations_total=1   e3_alloc=1
```

Exactly one of each. No fake carrier gateway, nothing pointed at an external
provider.

### Routing selects it — proven with the resolver's own query

`SipGatewayResolver.findEligibleGateways` requires, in order:

1. an allocation matching `findAllEligibleForTenant(tenant, reseller)` —
   `enabled = true AND deleted_at IS NULL AND (tenant_id = t OR reseller_id = r)`
2. the gateway exists and is not deleted
3. `enabled = true AND status = 'ACTIVE'`
4. provider match, case-insensitive
5. ordered by allocation priority, then gateway priority

Reproducing that exact predicate:

```text
eligible -> gateway=local-endpoint-1002 profile=internal provider=LOCAL
            status=ACTIVE enabled=true alloc_priority=10
```

**The routing gate is satisfied.** The dial string the application will build is:

```text
sofia/gateway/local-endpoint-1002/<contact number>
```

which is the form proven working in E.1.

## 8. The Production Call Path, Fully Traced

The path is **entirely scheduler-driven**, which is what makes it testable
without inventing an entry point:

```text
@Scheduled(fixedDelay = 30000) CampaignExecutionOrchestrator.scheduledTick()
   ├─ start-requested-executions → startExecutionAsSystem(executionId)
   │      → doStartExecution → new CallAttempt(status = QUEUED)
   ├─ process-retries
   ├─ dial-due-attempts  → dialService.processDueAttempts()
   │      → findByStatusAndScheduledAtBeforeAndDeletedAtIsNull(QUEUED, now())
   │      → processAttempt → routing → voiceCapacity.reserve
   │      → FreeSwitchOutboundDialer.dial
   │      → eslClient.originate(..., channelUuid = CallAttempt.id)
   │      → attempt.providerCallId = that uuid
   ├─ pump-esl-events    → eslEventProcessor.ensureEventProcessing()
   ├─ reconcile-executions
   ├─ reconcile-stale-calls
   └─ terminate-missed-call-budgets
```

Each step runs in its own failure boundary (`runStep`), so one failing step —
notably `reconcile-stale-calls`, which E.2 saw failing with a `42P10` SQL error —
does not prevent `dial-due-attempts` from running.

`processDueAttempts` requires an attempt with `status = QUEUED` **and**
`scheduled_at < now()`. That is the precise contract the seed must satisfy.

## 9. Why the Call Was Not Reached

The remaining seed is six further records, and each has a hard requirement that
had to be read rather than guessed:

| Record | Hard requirement discovered |
|---|---|
| `contacts` | `phone_number NOT NULL`. Must be the in-network extension `1002`, **not** an E.164 number — no format constraint applies here |
| `audio_assets` | `status = APPROVED`, `file_size > 0`, and a `storage_reference` that `MediaUriResolver` turns into a FreeSWITCH-readable path. Use `phase-e-rtp-probe.wav` |
| `dids` | `e164_number ~ '^\+[1-9][0-9]{6,14}$'` — a real E.164 caller id. `provider` **must** equal the gateway's `LOCAL`, because `SipGatewayResolver` does `didProvider.equalsIgnoreCase(gateway.getProvider())` |
| `contact_groups` + `contact_group_members` | `checkContactGroup` requires the group to exist and be non-empty |
| `campaigns` | must satisfy **all** current readiness checks, including the concurrent `checkExecutionTimezone`, which is evaluated **unconditionally** and requires `timezone` non-blank, outside the schedule branch |
| `campaign_execution_configurations` | `campaign_executions.configuration_snapshot_id` is `NOT NULL` and its FK points here (the table name is not derivable from the entity name — it is `campaign_execution_configurations`, not `..._snapshots`) |
| `campaign_executions` | `status = REQUESTED` so `start-requested-executions` picks it up; `requested_by NOT NULL` |

The decisive risk is the campaign. `checkExecutionTimezone` is **new, uncommitted
concurrent work** (VB-7C.1). A campaign seeded to satisfy it is a test whose
validity depends on when it ran, and the workstream was actively changing
campaign configuration throughout this phase (a new
`docs/VB-7C.2-INTEGRATIONS-CAMPAIGN-CONFIGURATION.md` appeared during it).

A seed that reached `dial-due-attempts` and failed at routing, or at the
readiness gate, would be **indistinguishable from a telephony defect** — the
exact misreading that cost this project two phases. Stopping at a proven gateway
gate with an exact remaining map is the better outcome.

## 10. Event Correlation — J1 still AMBIGUOUS

Unchanged. No application-created `CallAttempt` exists, so no application
correlation was observed. The structural finding stands and is now backed by a
routable gateway:

```text
CallAttempt.id = origination_uuid = FreeSWITCH channel UUID
               = attempt/session/leg .providerCallId
               = the key EslEventService correlates on
```

Necessary, not sufficient. Not converted to CONFIRMED.

## 11. Live Evidence Matrix

| Scenario | Result | Evidence |
|---|---|---|
| Application compiles | **CONFIRMED** | exit 0 |
| Gateway model read before seeding | **CONFIRMED** | entity + constraints + service validation |
| Owner-consistency constraint honoured | **CONFIRMED** | first insert rejected, corrected |
| Exactly one gateway | **CONFIRMED** | `gateways_total=1 e3=1` |
| Tenant-scoped allocation | **CONFIRMED** | `alloc enabled=true tenant-scoped=true` |
| Routing selects the gateway | **CONFIRMED** | resolver predicate returns it |
| Dial string matches the E.1-proven form | **CONFIRMED** | `sofia/gateway/local-endpoint-1002/<n>` |
| Production call path traced | **CONFIRMED** | `scheduledTick` → `dial-due-attempts` |
| Capacity reservation | **NOT YET TESTED** | requires a dial |
| Remaining seed | **NOT YET TESTED** | §9 |
| Application-originated call | **NOT YET TESTED** | — |
| J1 runtime correlation | **AMBIGUOUS** | unchanged |
| Peer witness / RTP / persistence | **NOT YET TESTED** | — |
| Full repository suite | **NOT RUN** | — |
| Carrier / PSTN | **NOT TESTED** | — |

Two-way RTP for an application-originated call is deliberately **not** claimed.
E.1's result was measured for harness-placed calls and is not inherited.

## 12. Documentation

This document, plus a Phase D correction note (the stale `Call-UUID` comment it
flagged was fixed in E.2) and a knowledge-base entry for the constraint-vs-enum
trap. No historical Phase C/D/E/E.1/E.2 finding was rewritten.

## 13. Security

| Check | Result |
|---|---|
| live credential values in files changed | **0** |
| ESL/SIP passwords in seed or docs | **none** — the seed contains no credential |
| carrier credentials | **none** — the gateway is platform-owned, test-only, in-network |
| gateway points at an external provider | **no** — `LOCAL`, profile `internal` |
| new host ports | **none** |
| `infra/docker-compose.yml` | **untouched**, diff clean |
| concurrent workstream files modified | **NONE** |

The seed is a test-only application record describing an in-network test route. It
carries no credential, authenticates to nothing, and would not route outside this
Docker network even if the switch were reachable from elsewhere.

## 14. Files Changed

**Database** (test data only — no schema migration, no code)

- 1 tenant, 1 gateway, 1 gateway allocation

**Not changed:** `infra/docker-compose.yml`; any Java; any FreeSWITCH
configuration; any campaign/voice file; no schema migration.

No source file was modified in Phase E.3. The gateway seed is data, and the SQL
that produced it was executed from a scratch path, not committed.

## 15. Architecture Impact

**NO.** No code, no schema, no configuration. A single test-only application
record describing a route that already existed in FreeSWITCH.

## 16. Known Limitations

| Item | Status |
|---|---|
| Application-originated call | **NOT YET TESTED** |
| J1 runtime correlation | **AMBIGUOUS** — the phase's core question, still open |
| Capacity reservation and release | **NOT YET TESTED** |
| Application persistence | **NOT YET TESTED** |
| Full repository test suite | **NOT RUN** |
| Two-way RTP for an application call | **NOT YET TESTED** |

## 17. Next Phase

Ordered so the next phase executes rather than investigates.

1. **Seed the six remaining records** using the exact requirements in §9, and
   re-read `checkExecutionTimezone` first — it is concurrent, uncommitted work
   and the campaign is only dialable if it passes.
2. **Re-establish the full test baseline now that
   `ZzOpenApiShapeDumpTest.java` is gone.** The E.2 workaround flag should no
   longer be needed, and a full-repository result becomes possible for the first
   time since Phase C.
3. **Create a `REQUESTED` execution with `scheduled_at` in the past** and let
   the 30s scheduler do the rest. Do not invoke the dialer directly.
4. **Enable DEBUG for `EslClient` and `EslEventService` for one call** — required,
   because with no matching attempt INFO yields nothing either way, which is why
   E.1 and E.2 were unreadable.
5. Then verify the full chain, bidirectional RTP measured for that call, the
   persisted final state, and **capacity release with no leak**.

## 18. Related Documents

- [`LIVE-FREESWITCH-PHASE-E2.md`](LIVE-FREESWITCH-PHASE-E2.md) — the `sip_gateways = 0` blocker this phase removed
- [`LIVE-FREESWITCH-PHASE-E1.md`](LIVE-FREESWITCH-PHASE-E1.md) — the Spring gate
- [`LIVE-FREESWITCH-PHASE-E.md`](LIVE-FREESWITCH-PHASE-E.md) — the gateway and peer-witness evidence
- [`freeswitch/decisions/ADR-007-local-gateway-routing.md`](freeswitch/decisions/ADR-007-local-gateway-routing.md) — why the profile is `internal`

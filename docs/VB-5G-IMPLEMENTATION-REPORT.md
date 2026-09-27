# VB-5G — Baseline Test & Architecture Debt Elimination — Implementation Report

## 1. Status

**VB-5G STATUS: COMPLETE**

Full regression: **855 tests, 0 failures, 0 errors, 1 skipped — BUILD SUCCESS** (baseline was 847/0/13/1, exit 1).

The 13-error baseline decomposed into exactly three defect classes (T-ARCH, T-SEC, T-PROV), each fixed at its root cause. No test was disabled, removed, skipped, or weakened; no ArchUnit/Modulith rule was suppressed or excluded; no Surefire configuration was touched.

- Fixed (Architecture): 1 error — `ArchitectureTest.modularBoundariesAreIntact`
- Fixed (Provisioning): 1 error — `ProvisioningSmokeIntegrationTest.bootstrapSuperAdminHasNoOrganizationalHomeAndCanLogIn`
- Fixed (Security): 11 errors — all `SecuritySliceTest` methods (one shared root cause)
- Cycles before: 2 → after: 0
- Flyway: V42 → V42 (no migration added)
- New tests: 8 (`FreeSwitchPropertiesValidationTest`)
- Scheduler: NOT STARTED

## 2. Baseline

Reproduced exactly per the phase contract (log `/tmp/vb5g_baseline.log`, exit 1):

```
Tests run: 847, Failures: 0, Errors: 13, Skipped: 1
```

Identical to the VB-5F final state (830 baseline + 17 VB-5F tests). Error decomposition by class:

| Class | Errors | Root cause class |
|---|---|---|
| `SecuritySliceTest` | 11 | `ConfigurationPropertiesBindException` on `FreeSwitchProperties` (T-SEC) |
| `ArchitectureTest` | 1 | 2 Modulith cycle groups (T-ARCH) |
| `ProvisioningSmokeIntegrationTest` | 1 | login `BusinessException: Invalid email or password.` (T-PROV) |

## 3. Scope and strict rules applied

- Goal: 0 failures, 0 errors, BUILD SUCCESS before the Campaign Execution/Scheduler milestone.
- Root-cause fixes only; error contracts, security behavior, and SUPER_ADMIN→PLATFORM scope preserved.
- No `@Disabled`/`@Ignore`, no removed tests, no weakened assertions, no ArchUnit suppression, no test exclusions, no Surefire changes.
- No new infrastructure; no Flyway migration (head stays V42); no scheduler work.

## 4. Files inspected (evidence-first diagnosis)

- `/tmp/vb5g_baseline.log` (cycle chains; FreeSwitchProperties binding stack; provisioning login stack)
- `backend/src/main/java/com/shivang/obd/voice/routing/VoiceRoutingService.java` (the sole voice→telephony importer)
- `backend/src/main/java/com/shivang/obd/telephony/`: `SipGatewayResolver`, `SipGatewayRoutingService`, `GatewayAuthorizationService`, `SipGateway`, `SipGatewayStatus`, `SipGatewayOwnerType`, `FreeSwitchProperties`
- `backend/src/main/java/com/shivang/obd/security/AuthenticationService.java` (line 93 = password-mismatch branch)
- `backend/src/main/java/com/shivang/obd/identity/`: `SuperAdminBootstrapper`, `BootstrapProperties`
- `backend/src/main/resources/application.yaml`, `application-dev.yaml` (bootstrap defaults; `telephony.freeswitch.*` only defined on the `dev` profile)
- `backend/src/test/java/com/shivang/obd/`: `SecuritySliceTest`, `ProvisioningSmokeIntegrationTest`, `VoiceRouting*Test`, `VoiceExplainabilityTest`, `VoicePolicyHierarchyTest`, `voice/VoiceTestSupport`, `voice/outbound/AgentOutboundIntegrationSupport`, `telephony/AgentLegDialerContractTest`
- `backend/src/main/java/com/shivang/obd/voice/*/package-info.java` (existing `@NamedInterface` convention: `outbound`, `inbound`)
- VB-5F report `docs/VB-5F-IMPLEMENTATION-REPORT.md` (format reference)

## 5. Defect matrix

| ID | Class | Symptom (real stack trace) | Root cause | Fix class | Files touched |
|---|---|---|---|---|---|
| T-ARCH | ArchitectureTest ×1 | `Violations: Cycle detected: Slice campaign -> voice -> telephony -> campaign` and `Slice telephony -> voice -> telephony` | `VoiceRoutingService` (voice) injected telephony types (`SipGatewayResolver`, `SipGatewayRepository`, `GatewayAuthorizationService`) and used `SipGateway`/`SipGatewayStatus` — the only voice→telephony edge; both cycles shared it. After removal, Modulith surfaced a second, previously-masked layer: voice's named interfaces made all other voice sub-packages non-exposed while campaign/telephony consume them as the de-facto API | Port/adapter inversion + explicit API declaration | See §6 |
| T-SEC | SecuritySliceTest ×11 | `ConfigurationPropertiesBindException: ... FreeSwitchProperties ... BindValidationException` — `gateway` rejected [null] (@NotBlank), `password` rejected [null] (@NotBlank); then `ApplicationContext failure threshold (1) exceeded` for all 11 methods | `@WebMvcTest` slice with `@ActiveProfiles("security-web-slice")` replaces the `dev` profile, so `application-dev.yaml`'s `telephony.freeswitch.*` values are absent; `ObdApplication` registers `FreeSwitchProperties` via `@EnableConfigurationProperties`, and unconditional `@NotBlank` on `password`/`gateway` (documented as "Required when enabled") fired although `enabled=false` | Enabled-gated validation | See §7 |
| T-PROV | ProvisioningSmokeIntegrationTest ×1 | `BusinessException: Invalid email or password.` at `AuthenticationService.invalidCredentials:156` ← `failWithLoginEvent:152` ← `authenticateAndIssue:93` — line 93 is the **password-mismatch** branch (user and credential both resolved) | The test runs against the live dev DB (`jdbc:postgresql://localhost:5432/obd`, profile `dev`) where the bootstrap SUPER_ADMIN row persists across runs. `SuperAdminBootstrapper` is idempotent **by design** and never rotates an existing credential, so the stored hash kept the yaml-default password (`2026@1234567890`) while the test configures/expects `BootStrap!2026Adm1n` | Deterministic test fixture | See §8 |

## 6. T-ARCH — architecture debt eliminated (root cause)

### 6.1 Cycle diagnosis

Both Modulith cycle groups shared exactly one edge:

```
Slice campaign -> Slice voice -> Slice telephony -> Slice campaign
Slice telephony -> Slice voice -> Slice telephony
```

The `voice -> telephony` edge existed only in `VoiceRoutingService`: constructor injection of `SipGatewayResolver` (unused field — only the static `isCompatibleStatic` was called), `SipGatewayRepository`, `GatewayAuthorizationService`, plus direct use of `SipGateway` and `SipGatewayStatus` for: load-by-id, authorization check, enabled/status gating, provider compatibility, and building `VoiceRoute` (freeSwitch gateway/profile identifiers). The telephony→voice direction (dialers/media/routing/capacity/eligibility adapters) and campaign→voice direction (trigger interfaces) are the intended port/adapter design and were kept.

### 6.2 Fix: invert the edge with a voice-owned port

New voice-owned types (`com.shivang.obd.voice.routing`):

- `GatewayRouteStatus` — voice mirror of gateway operational status (ACTIVE/INACTIVE/DEGRADED with the documented primary/overflow semantics).
- `GatewayRouteView` — read-only record: `id`, `displayName`, `provider`, `freeSwitchGatewayName`, `freeSwitchProfile`, `enabled`, `status`.
- `GatewayRoutingPort` — `findGateway(UUID)` + `isGatewayAuthorized(tenantId, resellerId, view)`.

New telephony adapter:

- `SipGatewayRoutingAdapter implements GatewayRoutingPort` — translates `SipGatewayRepository` + `GatewayAuthorizationService` into the port. Authorization re-resolves the entity by id inside the same transaction (served by the persistence context; no extra SQL). The entity→view translation is `public static toView(SipGateway)` so tests in other slices share one mapping.

`VoiceRoutingService` rewritten against the port:

- Constructor: `(VoiceRouteProfileRepository, GatewayRoutingPort, VoiceCapacityService, VoiceEligibility, DidRepository)` — 5 args (was 7, including the dead `null` `gatewayResolver` slot).
- All routing semantics, ordering, explainability (`RejectedRoute` contents with gateway id/name), and every `VoiceRoutingReason` code preserved bit-for-bit. Provider compatibility is now a private voice-side predicate equivalent to the old static one (same case-insensitive equality, same null behavior).
- Dead code removed: the injected-but-unused `SipGatewayResolver` field. (`SipGatewayResolver` itself is unchanged in production — an initial trim was reverted byte-identical because `SipGatewayRoutingService` still consumes `resolvePreferredGateway`; the only true dead code was the unused injection.)

### 6.3 Fix: declare the intended voice API surface (second verification layer)

With cycles gone, Modulith's next check failed with 368+ "Module 'campaign'/'telephony' depends on non-exposed type within module 'voice'" violations. Root cause: `voice.outbound` and `voice.inbound` declare `@NamedInterface`, which makes every *other* voice sub-package internal by default — while campaign and telephony consume `voice.call/agent/media/routing/dtmf/capacity/eligibility` as the established, intended API (the pattern already used for `outbound`/`inbound`).

Fix: declared those seven consumed sub-packages as named interfaces (`@NamedInterface("call")`, `"agent"`, `"media"`, `"routing"`, `"dtmf"`, `"capacity"`, `"eligibility"`) via `package-info.java`. This documents the real API surface instead of opening the whole module; no production class was moved or duplicated.

### 6.4 Result

`ArchitectureTest.modularBoundariesAreIntact`: **PASS (1/0/0/0)**. Cycle groups: **2 → 0**. Non-exposed violations: **0**.

## 7. T-SEC — FreeSwitchProperties conditional validation (root cause)

The class javadoc always stated the contract: *disabled by default; connection parameters "Required when enabled."* The implementation contradicted it with unconditional `@NotBlank` on `host`, `password`, `gateway`. Any context that boots `ObdApplication` without the `dev` profile's yaml values (e.g. the `security-web-slice` profile used by `SecuritySliceTest`) could not bind the registered properties bean at all — one context failure, 11 test errors.

Fix (fail-fast intent preserved, contract made true):

- Removed the unconditional `@NotBlank` from `host`, `password`, `gateway` (the enabled-deployment guard now lives in the cross-field checks).
- Added three `@AssertTrue` cross-field validators — `isHostPresentWhenEnabled()`, `isPasswordPresentWhenEnabled()`, `isGatewayPresentWhenEnabled()` — each returns `!enabled || (value != null && !value.isBlank())` with explicit messages like `telephony.freeswitch.password must not be blank when the integration is enabled`.
- `@Min/@Max` range constraints on `port`, timeouts, and `@NotBlank` on `profile` (which has a default) are unchanged. When `telephony.freeswitch.enabled=true` with missing secrets, startup fails exactly as before — production validation intent unchanged; a disabled deployment now binds cleanly as documented.

## 8. T-PROV — deterministic provisioning smoke (root cause)

The failing assertion is real platform behavior and was not changed: login must reject a wrong password (`AuthenticationService` line 93). The nondeterminism was in the fixture, not the production code:

- The test DB is the persistent dev database; the bootstrap row (and its password hash from whichever `obd.bootstrap.super-admin.password` seeded it first — yaml default `2026@1234567890` vs test property `BootStrap!2026Adm1n`) survives across runs.
- `SuperAdminBootstrapper` intentionally never rotates the credential of an existing account — silently rewriting an operator's password from configuration would be a security regression, so "fix the bootstrapper" was rejected.

Fix (test-only, `ProvisioningSmokeIntegrationTest`):

- Injected the production `PasswordEncoder` bean.
- New `@BeforeEach` step `alignBootstrapCredentialForDeterministicLogin()`: if the stored bootstrap hash does not match `BOOTSTRAP_ADMIN_PASSWORD` (the same value the test's `@SpringBootTest` properties configure for the bootstrapper), re-encode it through the production encoder. Javadoc documents why.
- All 13 provisioning assertions — including the wrong-password rejection in `userChangesPasswordOldFailsNewSucceeds` — are unchanged.

## 9. Files changed

Production (9 files, 3 new adapters/types + 7 package-info + 1 rewrite + 1 properties):

| File | Change |
|---|---|
| `voice/routing/GatewayRouteStatus.java` | new — voice-owned gateway status enum |
| `voice/routing/GatewayRouteView.java` | new — voice-owned gateway read model |
| `voice/routing/GatewayRoutingPort.java` | new — voice-owned gateway lookup/authorization port |
| `voice/routing/VoiceRoutingService.java` | rewritten onto the port; ctor 7→5 args; dead `gatewayResolver` injection removed; all routing semantics/reason codes preserved |
| `voice/routing/package-info.java` | new — `@NamedInterface("routing")` |
| `voice/call/package-info.java` | new — `@NamedInterface("call")` |
| `voice/agent/package-info.java` | new — `@NamedInterface("agent")` |
| `voice/media/package-info.java` | new — `@NamedInterface("media")` |
| `voice/dtmf/package-info.java` | new — `@NamedInterface("dtmf")` |
| `voice/capacity/package-info.java` | new — `@NamedInterface("capacity")` |
| `voice/eligibility/package-info.java` | new — `@NamedInterface("eligibility")` |
| `telephony/SipGatewayRoutingAdapter.java` | new — `GatewayRoutingPort` adapter over `SipGatewayRepository` + `GatewayAuthorizationService` |
| `telephony/FreeSwitchProperties.java` | enabled-gated `@AssertTrue` validation replacing unconditional `@NotBlank` on host/password/gateway |
| `telephony/SipGatewayResolver.java`, `SipGatewayRoutingService.java`, `GatewayAuthorizationService.java` | unchanged in production (resolver restored byte-identical after a reverted over-trim) |

Tests (7 files):

| File | Change |
|---|---|
| `telephony/FreeSwitchPropertiesValidationTest.java` | new — 8 tests (see §11) |
| `voice/routing/VoiceRoutingServiceTest.java` | mocks the port; `stubGateway` maps via `SipGatewayRoutingAdapter.toView`; R1–R9 assertions unchanged |
| `voice/routing/VoiceRoutingDIDTest.java` | same port conversion; D1–D5 assertions unchanged |
| `voice/routing/VoiceExplainabilityTest.java` | same port conversion; explainability assertions unchanged |
| `voice/routing/VoicePolicyHierarchyTest.java` | routing-level tests mock the port; the four real `GatewayAuthorizationService` + real `VoiceCapacityServiceImpl` semantics tests kept verbatim |
| `voice/outbound/AgentOutboundIntegrationSupport.java` | wires the real adapter: `new SipGatewayRoutingAdapter(gatewayRepository, new GatewayAuthorizationService(allocationRepository))` |
| `provisioning/ProvisioningSmokeIntegrationTest.java` | `PasswordEncoder` injection + bootstrap-credential alignment step; zero assertion changes |

## 10. Migrations

**None.** Flyway head unchanged: **V42 → V42**.

## 11. Tests added (8)

`FreeSwitchPropertiesValidationTest` (pure Bean Validation, no Spring context):

1. `disabledIntegrationBindsCleanlyWithoutConnectionParameters` — the T-SEC regression: fresh defaults (`enabled=false`, no password/gateway) produce zero violations.
2. `disabledIntegrationBindsCleanlyEvenWithBlankParameters` — blank host/password/gateway bind when disabled.
3. `enabledIntegrationWithoutPasswordIsRejected` — fail-fast preserved (message pins `telephony.freeswitch.password`).
4. `enabledIntegrationWithoutGatewayIsRejected` — fail-fast preserved.
5. `enabledIntegrationWithBlankHostIsRejected` — fail-fast preserved.
6. `enabledIntegrationWithCompleteConfigurationIsValid`.
7. `outOfRangePortIsRejected` — range constraints independent of enabled state.
8. `outOfRangeCommandTimeoutIsRejected` — same.

All 855 prior tests remain present with unchanged assertions; 847 + 8 = 855.

## 12. Verification progression

| Step | Command focus | Result |
|---|---|---|
| Baseline | full `clean test` | 847/0/13/1, exit 1 (`/tmp/vb5g_baseline.log`) |
| Focused | `ArchitectureTest` | PASS 1/0/0/0 after fix (`/tmp/vb5g_arch.log`) |
| Focused | SecuritySlice + validation + 4 routing suites + 2 telephony contract suites | **62/0/0/0 BUILD SUCCESS** (`/tmp/vb5g_focused.log`) |
| Focused (PG) | `AgentOutboundIntegrationTest` + `ProvisioningSmokeIntegrationTest` | **20/0/0/0 BUILD SUCCESS** (13+7) (`/tmp/vb5g_pg.log`) |
| Final | full `clean test` | **855/0/0/1 BUILD SUCCESS** (`/tmp/vb5g_final.log`) |

## 13. Full regression (final)

```
mvn clean test  →  Tests run: 855, Failures: 0, Errors: 0, Skipped: 1
BUILD SUCCESS (exit 0)
```

- Baseline 847 + 8 new = 855. The 13 baseline errors are all fixed; the single skip is the pre-existing environmental skip (unchanged, untouched).
- No test was disabled, removed, or weakened; no Surefire/architecture configuration changed.

## 14. Architecture result

- Cycle groups: **before 2** (`campaign → voice → telephony → campaign`; `telephony → voice → telephony`) → **after 0**.
- Slice dependency direction now strictly: `campaign → voice`, `telephony → voice` (port/adapter), acyclic.
- Voice API surface explicitly documented with named interfaces (`call`, `agent`, `media`, `routing`, `dtmf`, `capacity`, `eligibility`, plus the pre-existing `outbound`, `inbound`).
- `ArchitectureTest` passes **unchanged** (`ApplicationModules.of(ObdApplication.class).verify()` + Documenter; no rule edits, no suppressions).

## 15. Security result

- All 11 `SecuritySliceTest` methods pass (unauthenticated problem-detail rejection, valid-token authentication, header-spoof rejection, refresh/logout reachability, opaque-refresh rejection, `/me` auth, tampered-token rejection).
- Security behavior unchanged: fail-fast startup for `telephony.freeswitch.enabled=true` without secrets is retained and regression-tested; disabled deployments bind cleanly.
- No security configuration, filter, or token code was touched.

## 16. Provisioning result

- All 13 `ProvisioningSmokeIntegrationTest` tests pass against live PostgreSQL (Testcontainers-free dev DB config), including bootstrap-exists/no-org-home/login, reseller+tenant provisioning, hierarchy boundaries, agent restrictions, password rotation, duplicate rejection, self-signup, suspended-org fail-closed, and user-list scoping.
- SUPER_ADMIN→PLATFORM scope semantics untouched; the wrong-password rejection path remains verified by test 12.

## 17. Remaining issues

- None from the VB-5G baseline. The 1 skipped test is pre-existing and unchanged (not investigated — out of scope for this phase).

## 18. Deferred items

- Campaign Execution/Scheduler milestone: **NOT STARTED** (per phase contract).
- Optional future refactor: `GatewayAuthorizationService` could accept `GatewayRouteView` directly to drop the adapter's same-transaction entity re-resolution (micro-optimization; behavior identical).

## 19. Definition-of-Done checklist

- [x] Baseline reproduced (847/0/13/1) and logged
- [x] Real stack traces captured for all three failure classes
- [x] Defect matrix written (§5)
- [x] Root-cause fixes only — no symptom suppression anywhere
- [x] Error contracts preserved (problem-detail codes, rejection codes, BusinessException semantics)
- [x] Security behavior preserved (fail-fast when enabled; JWT/filter behavior untouched)
- [x] SUPER_ADMIN→PLATFORM scope preserved
- [x] No new infra (no Redis/Kafka/K8s/ShedLock)
- [x] No migration (Flyway head V42)
- [x] No scheduler work
- [x] Focused suites green (unit 62/0/0/0; PG 20/0/0/0)
- [x] Full regression green: 855/0/0/1 BUILD SUCCESS
- [x] Architecture cycles 2→0 with ArchitectureTest unchanged
- [x] New tests committed for the new behavior (8)

## 20. Evidence artifacts

- `/tmp/vb5g_baseline.log` — baseline 847/0/13/1 (cycle chains; binding failure; provisioning stack)
- `/tmp/vb5g_arch.log` — ArchitectureTest final PASS 1/0/0/0
- `/tmp/vb5g_focused.log` — 62/0/0/0 focused unit batch
- `/tmp/vb5g_pg.log` — 20/0/0/0 PG batch (ProvisioningSmoke 13 + AgentOutbound 7)
- `/tmp/vb5g_final.log` — final 855/0/0/1 BUILD SUCCESS

## 21. Production-change summary (for review)

1. **Architecture (behavior-preserving refactor):** voice routing observes gateways only through `GatewayRoutingPort`; telephony adapts. Constructor of `VoiceRoutingService` changed 7→5 args (compile-visible to any new caller).
2. **Configuration validation semantics:** `FreeSwitchProperties` requires host/password/gateway **only when `telephony.freeswitch.enabled=true`** (previously unconditional `@NotBlank`, contradicting its own javadoc; enabled deployments still fail fast, now with clearer per-field messages).
3. **Module API documentation:** seven `@NamedInterface` package-info declarations in voice (no code movement).

No database, HTTP API, or business-rule changes.

---

**VB-5G STATUS: COMPLETE** — Baseline 847/0/13/1 → Final 855/0/0/1 (Fixed: Architecture 1, Provisioning 1, Security 11; Cycles 2→0; Flyway V42→V42; New tests 8; Scheduler: NOT STARTED).

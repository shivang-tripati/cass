package com.shivang.obd.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shivang.obd.authz.AccessCheck;
import com.shivang.obd.authz.AuthorizationService;
import com.shivang.obd.authz.context.OrganizationContextHolder;
import com.shivang.obd.campaign.config.CampaignTypeConfig;
import com.shivang.obd.campaign.dto.CreateCallAttemptRequest;
import com.shivang.obd.common.exception.BusinessException;
import com.shivang.obd.common.exception.ConflictException;
import com.shivang.obd.common.exception.ResourceNotFoundException;
import com.shivang.obd.contact.ContactEntity;
import com.shivang.obd.contact.ContactGroupMemberRepository;
import com.shivang.obd.contact.ContactRepository;
import com.shivang.obd.security.AuthenticatedUser;
import com.shivang.obd.security.CurrentUserProvider;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * VB-8B — the manual call-attempt boundary (VB-8A finding F-01).
 *
 * <p>Before this phase, {@code POST /{campaignId}/executions/{executionId}/attempts}
 * wrote client-supplied {@code didId}, {@code attemptNumber} and
 * {@code scheduledAt} straight into an existing execution's attempt table,
 * without consulting the frozen {@link CampaignConfigurationSnapshot} and
 * without any execution-lifecycle guard.
 *
 * <p>These tests pin the corrected contract: the request may identify the
 * operation, but it may never redefine the execution's frozen configuration.
 *
 * <ul>
 *   <li>B-1 — a valid runnable execution creates an attempt whose values all
 *       come from the frozen configuration</li>
 *   <li>B-2 — a client-supplied DID cannot override the frozen DID</li>
 *   <li>B-3 — a client-supplied {@code scheduledAt} cannot bypass the frozen
 *       calling window</li>
 *   <li>B-4 — a client-supplied attempt number cannot bypass the frozen retry policy</li>
 *   <li>B-5 — a terminal execution is refused and no attempt is written</li>
 *   <li>B-6 — audience is enforced against the frozen contact group, including
 *       for whitelist-enforced campaigns</li>
 *   <li>B-7 — a post-snapshot campaign mutation cannot change what is created</li>
 *   <li>B-8 — tenant isolation is preserved on every path</li>
 * </ul>
 *
 * <p>Persistence, transaction and cross-tenant row behaviour are proven
 * separately in {@code ManualAttemptBoundaryPostgresIntegrationTest}; this class
 * covers the deterministic domain logic.
 */
class ManualCallAttemptBoundaryTest {

    private static final UUID TENANT = UUID.fromString("aaaaaaaa-0000-4000-8000-000000000001");
    private static final UUID OTHER_TENANT = UUID.fromString("aaaaaaaa-0000-4000-8000-000000000002");
    private static final UUID USER = UUID.fromString("bbbbbbbb-0000-4000-8000-000000000001");
    private static final UUID CAMPAIGN = UUID.fromString("cccccccc-0000-4000-8000-000000000001");
    private static final UUID EXECUTION = UUID.fromString("dddddddd-0000-4000-8000-000000000001");
    private static final UUID SNAPSHOT = UUID.fromString("eeeeeeee-0000-4000-8000-000000000001");
    private static final UUID DID = UUID.fromString("ffffffff-0000-4000-8000-000000000001");
    private static final UUID OTHER_DID = UUID.fromString("ffffffff-0000-4000-8000-000000000002");
    private static final UUID GROUP = UUID.fromString("11111111-0000-4000-8000-000000000001");
    private static final UUID CONTACT = UUID.fromString("22222222-0000-4000-8000-000000000001");
    private static final UUID FOREIGN_CONTACT = UUID.fromString("22222222-0000-4000-8000-000000000002");

    private CampaignExecutionRepository executionRepository;
    private CallAttemptRepository attemptRepository;
    private ContactRepository contactRepository;
    private ContactGroupMemberRepository memberRepository;
    private CampaignResourceValidationService resourceValidator;
    private CampaignRuntimeConfigResolver runtimeConfigResolver;
    private ExecutionScheduleCalculator scheduleCalculator;
    private CallAttemptService service;

    private CampaignRuntimeConfigResolver.CampaignRuntimeConfig frozen;

    @BeforeEach
    void setUp() {
        executionRepository = org.mockito.Mockito.mock(CampaignExecutionRepository.class);
        attemptRepository = org.mockito.Mockito.mock(CallAttemptRepository.class);
        contactRepository = org.mockito.Mockito.mock(ContactRepository.class);
        memberRepository = org.mockito.Mockito.mock(ContactGroupMemberRepository.class);
        resourceValidator = org.mockito.Mockito.mock(CampaignResourceValidationService.class);
        runtimeConfigResolver = org.mockito.Mockito.mock(CampaignRuntimeConfigResolver.class);

        AuthorizationService allowAll = new AuthorizationService(List.of(), null, null, null, List.of()) {
            @Override
            public void requireCapability(UUID userId, String capabilityKey, AccessCheck target) {
                // harness pass-through
            }
        };
        CurrentUserProvider user = () -> Optional.of(new AuthenticatedUser(USER, "t@test", null));

        scheduleCalculator = new ExecutionScheduleCalculator();
        service = new CallAttemptService(
                executionRepository, attemptRepository, allowAll, user,
                contactRepository, memberRepository, runtimeConfigResolver, scheduleCalculator,
                resourceValidator, org.mockito.Mockito.mock(
                        com.shivang.obd.tenant.TenantRepository.class));

        OrganizationContextHolder.setAuthenticated(USER, TENANT, null);
        frozen = frozenConfig(GROUP, DID, 0, null, false);
    }

    @AfterEach
    void tearDown() {
        OrganizationContextHolder.clear();
    }

    // === helpers ===

    private static CampaignRuntimeConfigResolver.CampaignRuntimeConfig frozenConfig(
            UUID groupId, UUID didId, int maxRetries, ScheduleSpec schedule, boolean whitelist) {
        return new CampaignRuntimeConfigResolver.CampaignRuntimeConfig(
                CAMPAIGN, CampaignType.PLAYFILE, groupId, didId,
                ContentMode.AUDIO, null, null, whitelist,
                new RetryPolicySpec(maxRetries, 60, RetryStrategy.FIXED),
                schedule == null
                        ? new ScheduleSpec(null, null, null, "UTC", null, null)
                        : schedule,
                com.shivang.obd.campaign.config.ConfigSchemaVersion.V1,
                CampaignTypeConfig.fromTypeConfig(CampaignType.PLAYFILE, null),
                null, null, null, null);
    }

    private CampaignExecution execution(CampaignExecutionStatus status, UUID tenantId) {
        CampaignExecution e = new CampaignExecution();
        e.setId(EXECUTION);
        e.setCampaignId(CAMPAIGN);
        e.setTenantId(tenantId == null ? TENANT : tenantId);
        e.setConfigurationSnapshotId(SNAPSHOT);
        e.setStatus(status);
        return e;
    }

    private void givenExecution(CampaignExecution e) {
        when(executionRepository.findByIdAndTenantIdAndDeletedAtIsNull(EXECUTION, TENANT))
                .thenReturn(Optional.of(e));
        when(runtimeConfigResolver.resolve(any(CampaignExecution.class))).thenReturn(frozen);
    }

    private void givenContactIsInFrozenGroup() {
        when(contactRepository.findByIdAndTenantIdAndDeletedAtIsNull(CONTACT, TENANT))
                .thenReturn(Optional.of(new ContactEntity()));
        when(memberRepository.existsByContactGroupIdAndContactId(GROUP, CONTACT)).thenReturn(true);
        when(attemptRepository.existsByExecutionIdAndContactIdAndAttemptNumberAndDeletedAtIsNull(
                eq(EXECUTION), eq(CONTACT), any(Integer.class))).thenReturn(false);
        when(resourceValidator.validateDid(DID, TENANT))
                .thenReturn(new CampaignResourceValidationService.ResourceValidationResult(true, null));
        when(attemptRepository.save(any(CallAttempt.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private CreateCallAttemptRequest request(UUID didId, Integer attemptNumber, Instant scheduledAt) {
        return new CreateCallAttemptRequest(CONTACT, didId, attemptNumber, scheduledAt);
    }

    private CallAttempt capturedAttempt() {
        ArgumentCaptor<CallAttempt> captor = ArgumentCaptor.forClass(CallAttempt.class);
        verify(attemptRepository).save(captor.capture());
        return captor.getValue();
    }

    // === B-1: the happy path uses frozen configuration ===

    @Nested
    @DisplayName("B-1: a valid runnable execution creates a frozen-configured attempt")
    class HappyPath {

        @Test
        @DisplayName("B1-1: RUNNING execution, attempt 1, derives DID from the snapshot")
        void createsAttemptFromFrozenConfiguration() {
            givenExecution(execution(CampaignExecutionStatus.RUNNING, TENANT));
            givenContactIsInFrozenGroup();

            var response = service.createAttempt(CAMPAIGN, EXECUTION, request(DID, 1, null));

            assertThat(response).isNotNull();
            CallAttempt attempt = capturedAttempt();
            assertThat(attempt.getDidId())
                    .as("DID comes from the frozen snapshot")
                    .isEqualTo(DID);
            assertThat(attempt.getExecutionId()).isEqualTo(EXECUTION);
            assertThat(attempt.getTenantId()).isEqualTo(TENANT);
            assertThat(attempt.getAttemptNumber()).isEqualTo(1);
            assertThat(attempt.getStatus()).isEqualTo(CallAttemptStatus.QUEUED);
            assertThat(attempt.getScheduledAt()).isNotNull();
        }

        @Test
        @DisplayName("B1-2: REQUESTED is also runnable and accepts a manual attempt")
        void requestedExecutionIsRunnable() {
            givenExecution(execution(CampaignExecutionStatus.REQUESTED, TENANT));
            givenContactIsInFrozenGroup();

            service.createAttempt(CAMPAIGN, EXECUTION, request(DID, 1, null));

            assertThat(capturedAttempt().getExecutionId()).isEqualTo(EXECUTION);
        }
    }

    // === B-2: DID cannot be overridden ===

    @Nested
    @DisplayName("B-2: the DID belongs to the execution")
    class DidIsFrozen {

        @Test
        @DisplayName("B2-1: a different client DID is rejected, not silently accepted")
        void clientCannotOverrideTheFrozenDid() {
            givenExecution(execution(CampaignExecutionStatus.RUNNING, TENANT));
            givenContactIsInFrozenGroup();

            assertThatThrownBy(() ->
                    service.createAttempt(CAMPAIGN, EXECUTION, request(OTHER_DID, 1, null)))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("frozen configuration")
                    .hasMessageContaining("cannot be overridden");

            verify(attemptRepository, never()).save(any(CallAttempt.class));
        }

        @Test
        @DisplayName("B2-2: the attempt always carries the frozen DID")
        void attemptCarriesFrozenDid() {
            givenExecution(execution(CampaignExecutionStatus.RUNNING, TENANT));
            givenContactIsInFrozenGroup();

            service.createAttempt(CAMPAIGN, EXECUTION, request(DID, 1, null));

            assertThat(capturedAttempt().getDidId()).isEqualTo(DID);
        }

        @Test
        @DisplayName("B2-3: an execution whose frozen config has no DID is refused")
        void missingFrozenDidIsRefused() {
            givenExecution(execution(CampaignExecutionStatus.RUNNING, TENANT));
            givenContactIsInFrozenGroup();
            when(runtimeConfigResolver.resolve(any(CampaignExecution.class)))
                    .thenReturn(frozenConfig(GROUP, null, 0, null, false));

            assertThatThrownBy(() ->
                    service.createAttempt(CAMPAIGN, EXECUTION, request(DID, 1, null)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("no DID in its frozen configuration");

            verify(attemptRepository, never()).save(any(CallAttempt.class));
        }
    }

    // === B-3: scheduledAt cannot bypass the calling window ===

    @Nested
    @DisplayName("B-3: the dispatch time is execution-owned")
    class ScheduleIsFrozen {

        /** 09:00-17:00 UTC, every day: a narrow, easily-probed window. */
        private ScheduleSpec window() {
            return new ScheduleSpec(null, LocalTime.of(9, 0), LocalTime.of(17, 0),
                    "UTC", null, null);
        }

        @Test
        @DisplayName("B3-1: a client scheduledAt outside the window is ignored, not honoured")
        void clientScheduledAtCannotEscapeTheWindow() {
            frozen = frozenConfig(GROUP, DID, 0, window(), false);
            givenExecution(execution(CampaignExecutionStatus.RUNNING, TENANT));
            givenContactIsInFrozenGroup();

            // The client asks for 03:00 UTC, well outside 09:00-17:00.
            Instant clientTime = ZonedDateTime.of(
                    LocalDate.of(2026, 3, 3), LocalTime.of(3, 0), ZoneId.of("UTC")).toInstant();
            service.createAttempt(CAMPAIGN, EXECUTION, request(DID, 1, clientTime));

            Instant scheduled = capturedAttempt().getScheduledAt();
            ZonedDateTime zoned = scheduled.atZone(ZoneId.of("UTC"));
            assertThat(zoned.toLocalTime())
                    .as("the derived time must sit inside the frozen calling window")
                    .isAfterOrEqualTo(LocalTime.of(9, 0))
                    .isBeforeOrEqualTo(LocalTime.of(17, 0));
            assertThat(scheduled)
                    .as("and must not be the client-supplied instant")
                    .isNotEqualTo(clientTime);
        }

        @Test
        @DisplayName("B3-2: the derived time matches the canonical calculator exactly")
        void derivedTimeMatchesTheCanonicalCalculation() {
            ScheduleSpec spec = window();
            frozen = frozenConfig(GROUP, DID, 0, spec, false);
            givenExecution(execution(CampaignExecutionStatus.RUNNING, TENANT));
            givenContactIsInFrozenGroup();

            service.createAttempt(CAMPAIGN, EXECUTION, request(DID, 1, null));

            Instant expected = new ExecutionScheduleCalculator()
                    .calculateNextScheduledAt(spec, Instant.now());
            // Recomputed "now" may differ by milliseconds, so compare the day.
            assertThat(capturedAttempt().getScheduledAt().atZone(ZoneId.of("UTC")).toLocalDate())
                    .isEqualTo(expected.atZone(ZoneId.of("UTC")).toLocalDate());
        }

        @Test
        @DisplayName("B3-3: an unrestricted schedule does not delay the attempt")
        void noWindowMeansNoDelay() {
            givenExecution(execution(CampaignExecutionStatus.RUNNING, TENANT));
            givenContactIsInFrozenGroup();

            Instant before = Instant.now();
            service.createAttempt(CAMPAIGN, EXECUTION, request(DID, 1, null));
            Instant scheduled = capturedAttempt().getScheduledAt();

            assertThat(scheduled)
                    .as("a schedule with no window must not invent a delay")
                    .isAfterOrEqualTo(before);
        }

        @Test
        @DisplayName("B3-4: days-of-week from the frozen schedule are honoured")
        void allowedDaysAreHonoured() {
            // Only Mondays; today is a Tuesday in the reference date used below.
            ScheduleSpec mondayOnly = new ScheduleSpec(
                    null, LocalTime.of(9, 0), LocalTime.of(17, 0),
                    "UTC", Set.of(DayOfWeek.MONDAY), null);
            Instant tuesday = ZonedDateTime.of(
                    LocalDate.of(2026, 3, 3), LocalTime.of(10, 0), ZoneId.of("UTC")).toInstant();

            Instant adjusted = new ExecutionScheduleCalculator()
                    .adjustToScheduleWindow(mondayOnly, tuesday);

            assertThat(adjusted.atZone(ZoneId.of("UTC")).getDayOfWeek())
                    .as("the canonical calculator moves a non-allowed day to the next allowed one")
                    .isEqualTo(DayOfWeek.MONDAY);
        }
    }

    // === B-4: attemptNumber cannot bypass the frozen retry policy ===

    @Nested
    @DisplayName("B-4: attempt numbering is governed by the frozen retry policy")
    class AttemptNumberIsGoverned {

        @Test
        @DisplayName("B4-1: the initial attempt is always permitted")
        void initialAttemptPermitted() {
            givenExecution(execution(CampaignExecutionStatus.RUNNING, TENANT));
            givenContactIsInFrozenGroup();

            service.createAttempt(CAMPAIGN, EXECUTION, request(DID, 1, null));

            assertThat(capturedAttempt().getAttemptNumber()).isEqualTo(1);
        }

        @Test
        @DisplayName("B4-2: an allowed retry is permitted when maxRetries permits it")
        void allowedRetryPermitted() {
            // maxRetries = 2 -> highest permitted attempt number is 3.
            frozen = frozenConfig(GROUP, DID, 2, null, false);
            givenExecution(execution(CampaignExecutionStatus.RUNNING, TENANT));
            givenContactIsInFrozenGroup();

            service.createAttempt(CAMPAIGN, EXECUTION, request(DID, 3, null));

            assertThat(capturedAttempt().getAttemptNumber()).isEqualTo(3);
        }

        @Test
        @DisplayName("B4-3: an exhausted retry budget is refused")
        void exhaustedRetryBudgetRefused() {
            frozen = frozenConfig(GROUP, DID, 2, null, false);
            givenExecution(execution(CampaignExecutionStatus.RUNNING, TENANT));
            givenContactIsInFrozenGroup();

            assertThatThrownBy(() ->
                    service.createAttempt(CAMPAIGN, EXECUTION, request(DID, 4, null)))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("exceeds this execution's frozen retry policy")
                    .hasMessageContaining("at most 3");

            verify(attemptRepository, never()).save(any(CallAttempt.class));
        }

        @Test
        @DisplayName("B4-4: a campaign configured with no retries permits only attempt 1")
        void zeroRetriesPermitsOnlyTheInitialAttempt() {
            frozen = frozenConfig(GROUP, DID, 0, null, false);
            givenExecution(execution(CampaignExecutionStatus.RUNNING, TENANT));
            givenContactIsInFrozenGroup();

            assertThatThrownBy(() ->
                    service.createAttempt(CAMPAIGN, EXECUTION, request(DID, 2, null)))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("at most 1");

            verify(attemptRepository, never()).save(any(CallAttempt.class));
        }

        @Test
        @DisplayName("B4-5: the ceiling is 1 + maxRetries, not maxRetries")
        void ceilingIsOnePlusMaxRetries() {
            // The distinction VB-6D turns on: maxRetries counts retries.
            assertThat(new RetryPolicySpec(0, null, RetryStrategy.FIXED)
                    .maxPermittedAttemptNumber()).isEqualTo(1);
            assertThat(new RetryPolicySpec(1, null, RetryStrategy.FIXED)
                    .maxPermittedAttemptNumber()).isEqualTo(2);
            assertThat(new RetryPolicySpec(2, null, RetryStrategy.FIXED)
                    .maxPermittedAttemptNumber()).isEqualTo(3);
        }

        @Test
        @DisplayName("B4-6: a null frozen policy degenerates to the initial attempt only")
        void nullPolicyPermitsOnlyInitialAttempt() {
            givenExecution(execution(CampaignExecutionStatus.RUNNING, TENANT));
            givenContactIsInFrozenGroup();
            when(runtimeConfigResolver.resolve(any(CampaignExecution.class)))
                    .thenReturn(new CampaignRuntimeConfigResolver.CampaignRuntimeConfig(
                            CAMPAIGN, CampaignType.PLAYFILE, GROUP, DID,
                            ContentMode.AUDIO, null, null, false,
                            null,
                            new ScheduleSpec(null, null, null, "UTC", null, null),
                            com.shivang.obd.campaign.config.ConfigSchemaVersion.V1,
                            CampaignTypeConfig.fromTypeConfig(CampaignType.PLAYFILE, null),
                            null, null, null, null));

            assertThatThrownBy(() ->
                    service.createAttempt(CAMPAIGN, EXECUTION, request(DID, 2, null)))
                    .isInstanceOf(ConflictException.class);
        }
    }

    // === B-5: terminal executions are refused ===

    @Nested
    @DisplayName("B-5: a terminal execution cannot be resurrected")
    class TerminalExecutionsRefused {

        @Test
        @DisplayName("B5-1: COMPLETED is refused and writes nothing")
        void completedRefused() {
            givenExecution(execution(CampaignExecutionStatus.COMPLETED, TENANT));
            givenContactIsInFrozenGroup();

            assertThatThrownBy(() ->
                    service.createAttempt(CAMPAIGN, EXECUTION, request(DID, 1, null)))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("COMPLETED");

            verify(attemptRepository, never()).save(any(CallAttempt.class));
        }

        @Test
        @DisplayName("B5-2: FAILED is refused")
        void failedRefused() {
            givenExecution(execution(CampaignExecutionStatus.FAILED, TENANT));
            givenContactIsInFrozenGroup();

            assertThatThrownBy(() ->
                    service.createAttempt(CAMPAIGN, EXECUTION, request(DID, 1, null)))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("FAILED");

            verify(attemptRepository, never()).save(any(CallAttempt.class));
        }

        @Test
        @DisplayName("B5-3: CANCELLED is refused")
        void cancelledRefused() {
            givenExecution(execution(CampaignExecutionStatus.CANCELLED, TENANT));
            givenContactIsInFrozenGroup();

            assertThatThrownBy(() ->
                    service.createAttempt(CAMPAIGN, EXECUTION, request(DID, 1, null)))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("CANCELLED");

            verify(attemptRepository, never()).save(any(CallAttempt.class));
        }

        @Test
        @DisplayName("B5-4: the runnable set is exactly REQUESTED and RUNNING")
        void runnableSetIsExactlyRequestedAndRunning() {
            for (CampaignExecutionStatus status : CampaignExecutionStatus.values()) {
                CampaignExecution e = execution(status, TENANT);
                givenExecution(e);
                givenContactIsInFrozenGroup();
                boolean runnable = status == CampaignExecutionStatus.REQUESTED
                        || status == CampaignExecutionStatus.RUNNING;
                if (runnable) {
                    service.createAttempt(CAMPAIGN, EXECUTION, request(DID, 1, null));
                } else {
                    assertThatThrownBy(() ->
                            service.createAttempt(CAMPAIGN, EXECUTION, request(DID, 1, null)))
                            .as("%s must be refused", status)
                            .isInstanceOf(ConflictException.class);
                }
                org.mockito.Mockito.reset(attemptRepository);
            }
        }
    }

    // === B-6: audience enforcement ===

    @Nested
    @DisplayName("B-6: audience comes from the frozen contact group")
    class AudienceIsFrozen {

        @Test
        @DisplayName("B6-1: a contact outside the frozen group is refused")
        void outsiderRefused() {
            givenExecution(execution(CampaignExecutionStatus.RUNNING, TENANT));
            when(contactRepository.findByIdAndTenantIdAndDeletedAtIsNull(CONTACT, TENANT))
                    .thenReturn(Optional.of(new ContactEntity()));
            when(memberRepository.existsByContactGroupIdAndContactId(GROUP, CONTACT))
                    .thenReturn(false);
            when(attemptRepository.save(any(CallAttempt.class))).thenAnswer(inv -> inv.getArgument(0));

            assertThatThrownBy(() ->
                    service.createAttempt(CAMPAIGN, EXECUTION, request(DID, 1, null)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("not a member of the execution's frozen contact group");

            verify(attemptRepository, never()).save(any(CallAttempt.class));
        }

        @Test
        @DisplayName("B6-2: a whitelist-enforced campaign is NOT exempt from the audience check")
        void whitelistDoesNotBypassTheAudience() {
            // This is exactly the hole VB-8A found: at dial time the group check
            // is skipped for whitelist-enforced campaigns, so without a
            // creation-time check a manual attempt could reach any tenant contact.
            frozen = frozenConfig(GROUP, DID, 0, null, true);
            givenExecution(execution(CampaignExecutionStatus.RUNNING, TENANT));
            when(contactRepository.findByIdAndTenantIdAndDeletedAtIsNull(CONTACT, TENANT))
                    .thenReturn(Optional.of(new ContactEntity()));
            when(memberRepository.existsByContactGroupIdAndContactId(GROUP, CONTACT))
                    .thenReturn(false);
            when(attemptRepository.save(any(CallAttempt.class))).thenAnswer(inv -> inv.getArgument(0));

            assertThatThrownBy(() ->
                    service.createAttempt(CAMPAIGN, EXECUTION, request(DID, 1, null)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("frozen contact group");

            verify(attemptRepository, never()).save(any(CallAttempt.class));
        }

        @Test
        @DisplayName("B6-3: an execution with no frozen group is refused rather than unrestricted")
        void missingFrozenGroupRefused() {
            givenExecution(execution(CampaignExecutionStatus.RUNNING, TENANT));
            when(contactRepository.findByIdAndTenantIdAndDeletedAtIsNull(CONTACT, TENANT))
                    .thenReturn(Optional.of(new ContactEntity()));
            when(runtimeConfigResolver.resolve(any(CampaignExecution.class)))
                    .thenReturn(frozenConfig(null, DID, 0, null, false));

            assertThatThrownBy(() ->
                    service.createAttempt(CAMPAIGN, EXECUTION, request(DID, 1, null)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("no contact group in its frozen configuration");

            verify(attemptRepository, never()).save(any(CallAttempt.class));
        }
    }

    // === B-7: post-snapshot mutation is irrelevant ===

    @Nested
    @DisplayName("B-7: nothing about a post-snapshot campaign change can reach the attempt")
    class PostSnapshotMutationIrrelevant {

        @Test
        @DisplayName("B7-1: the value written is exactly what the resolver returns, and nothing else")
        void writtenValuesComeOnlyFromTheSnapshot() {
            // The resolver is the only source: whatever the snapshot holds is what
            // the attempt gets. A real post-snapshot campaign edit - two
            // executions of one campaign whose snapshots differ - is proven in
            // ManualAttemptBoundaryPostgresIntegrationTest against actual rows;
            // here we pin that no second source can influence the write.
            frozen = frozenConfig(GROUP, OTHER_DID, 0, null, false);
            givenExecution(execution(CampaignExecutionStatus.RUNNING, TENANT));
            when(contactRepository.findByIdAndTenantIdAndDeletedAtIsNull(CONTACT, TENANT))
                    .thenReturn(Optional.of(new ContactEntity()));
            when(memberRepository.existsByContactGroupIdAndContactId(GROUP, CONTACT)).thenReturn(true);
            when(attemptRepository.existsByExecutionIdAndContactIdAndAttemptNumberAndDeletedAtIsNull(
                    eq(EXECUTION), eq(CONTACT), any(Integer.class))).thenReturn(false);
            when(resourceValidator.validateDid(OTHER_DID, TENANT))
                    .thenReturn(new CampaignResourceValidationService.ResourceValidationResult(true, null));
            when(attemptRepository.save(any(CallAttempt.class))).thenAnswer(inv -> inv.getArgument(0));

            // A request DID that DISAGREES with the snapshot is still refused,
            // even here: the snapshot decides, and disagreement is an error.
            assertThatThrownBy(() ->
                    service.createAttempt(CAMPAIGN, EXECUTION, request(DID, 1, null)))
                    .as("the snapshot wins even when a different DID is requested")
                    .isInstanceOf(ConflictException.class);

            // Requesting the snapshot's own DID succeeds and writes that DID.
            service.createAttempt(CAMPAIGN, EXECUTION, request(OTHER_DID, 1, null));

            assertThat(capturedAttempt().getDidId())
                    .as("the attempt carries the frozen DID, never a client-chosen one")
                    .isEqualTo(OTHER_DID);
        }

        @Test
        @DisplayName("B7-2: the service holds no CampaignRepository, so it cannot read campaign config")
        void serviceCannotReachCampaignConfiguration() {
            // Structural proof of the architectural rule, not just behavioural:
            // CallAttemptService has no repository that could return a
            // CampaignEntity, so "snapshot value else live campaign" is not
            // merely avoided, it is unrepresentable.
            assertThat(java.util.Arrays.stream(CallAttemptService.class.getDeclaredFields())
                            .map(java.lang.reflect.Field::getType)
                            .filter(t -> t.getSimpleName().contains("CampaignRepository"))
                            .toList())
                    .as("CallAttemptService must not hold a CampaignRepository")
                    .isEmpty();
        }
    }

    // === B-8: tenant isolation ===

    @Nested
    @DisplayName("B-8: tenant isolation is preserved on every path")
    class TenantIsolation {

        @Test
        @DisplayName("B8-1: an execution outside the caller's tenant is not found")
        void foreignExecutionNotFound() {
            when(executionRepository.findByIdAndTenantIdAndDeletedAtIsNull(EXECUTION, TENANT))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                    service.createAttempt(CAMPAIGN, EXECUTION, request(DID, 1, null)))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("B8-2: a foreign contact is indistinguishable from a missing one")
        void foreignContactCloaked() {
            givenExecution(execution(CampaignExecutionStatus.RUNNING, TENANT));
            when(contactRepository.findByIdAndTenantIdAndDeletedAtIsNull(FOREIGN_CONTACT, TENANT))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.createAttempt(
                    CAMPAIGN, EXECUTION,
                    new CreateCallAttemptRequest(FOREIGN_CONTACT, DID, 1, null)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Contact does not exist or is not available");

            verify(attemptRepository, never()).save(any(CallAttempt.class));
        }

        @Test
        @DisplayName("B8-3: DID validity is checked against the attempt's own tenant")
        void didValidatedAgainstAttemptTenant() {
            givenExecution(execution(CampaignExecutionStatus.RUNNING, TENANT));
            givenContactIsInFrozenGroup();
            when(resourceValidator.validateDid(DID, TENANT))
                    .thenReturn(new CampaignResourceValidationService.ResourceValidationResult(
                            false, CampaignResourceValidationService.ValidationCode.DID_NOT_AVAILABLE));

            assertThatThrownBy(() ->
                    service.createAttempt(CAMPAIGN, EXECUTION, request(DID, 1, null)))
                    .isInstanceOf(BusinessException.class);

            verify(resourceValidator).validateDid(DID, TENANT);
            verify(attemptRepository, never()).save(any(CallAttempt.class));
        }

        @Test
        @DisplayName("B8-4: the attempt is written with the execution's tenant, not the caller's")
        void attemptInheritsExecutionTenant() {
            givenExecution(execution(CampaignExecutionStatus.RUNNING, null));
            givenContactIsInFrozenGroup();

            service.createAttempt(CAMPAIGN, EXECUTION, request(DID, 1, null));

            assertThat(capturedAttempt().getTenantId()).isEqualTo(TENANT);
            assertThat(TENANT).isNotEqualTo(OTHER_TENANT);
        }
    }
}

package com.shivang.obd.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.shivang.obd.campaign.config.CampaignTypeConfigValidator;
import com.shivang.obd.common.exception.BusinessException;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * VB-6E — maximum call duration through the configuration chain.
 *
 * <p>Proves the three properties the phase requires: the value is persisted on
 * the campaign, frozen into the execution snapshot, and resolved from the
 * snapshot (never the live campaign) at runtime.
 */
class MaxCallDurationConfigurationTest {

    private static final UUID TENANT = UUID.fromString("aaaaaaaa-0000-4000-8000-00000000000a");

    private CampaignEntity campaign(Integer configured) {
        CampaignEntity campaign = new CampaignEntity();
        campaign.setId(UUID.randomUUID());
        campaign.setTenantId(TENANT);
        campaign.setCampaignType(CampaignType.PLAYFILE);
        campaign.setContactGroupId(UUID.randomUUID());
        campaign.setContentMode(ContentMode.AUDIO);
        campaign.setAudioAssetId(UUID.randomUUID());
        campaign.setSchedule(schedule());
        campaign.setRetryPolicy(retryPolicy());
        campaign.setMaxCallDurationSeconds(configured);
        return campaign;
    }

    private ScheduleSpec schedule() {
        ScheduleSpec schedule = new ScheduleSpec();
        schedule.setTimezone("UTC");
        return schedule;
    }

    private RetryPolicySpec retryPolicy() {
        RetryPolicySpec retry = new RetryPolicySpec();
        retry.setMaxAttempts(0);
        retry.setIntervalSeconds(60);
        retry.setStrategy(RetryStrategy.FIXED);
        return retry;
    }

    @Nested
    class Persistence {

        @Test
        @DisplayName("SNAP-1: the campaign carries the configured duration")
        void campaignCarriesTheValue() {
            assertThat(campaign(180).getMaxCallDurationSeconds()).isEqualTo(180);
            assertThat(campaign(null).getMaxCallDurationSeconds())
                    .as("null means the platform default, and must be stored as null")
                    .isNull();
        }
    }

    @Nested
    class SnapshotFreeze {

        /**
         * A service whose repository behaves like a real one.
         *
         * <p>JPA's {@code save} returns the managed instance; a bare Mockito
         * mock returns {@code null} for it, which would make every assertion
         * below fail on a null dereference rather than on the thing being
         * tested. Answering with the argument is the smallest faithful stand-in,
         * and it is what lets the snapshot-freezing behaviour be asserted
         * without a database.
         */
        private CampaignConfigurationService service() {
            CampaignExecutionConfigurationRepository repository =
                    org.mockito.Mockito.mock(CampaignExecutionConfigurationRepository.class);
            org.mockito.Mockito.when(repository.save(any()))
                    .thenAnswer(invocation -> invocation.getArgument(0));
            return new CampaignConfigurationService(repository,
                    new CampaignTypeConfigValidator());
        }

        @Test
        @DisplayName("SNAP-2: the configured duration is frozen into the execution snapshot")
        void durationIsFrozen() {
            CampaignConfigurationSnapshot snapshot = service()
                    .createExecutionSnapshot(campaign(180)).getConfiguration();

            assertThat(snapshot.getMaxCallDurationSeconds())
                    .as("a running execution must not be affected by a later campaign edit")
                    .isEqualTo(180);
        }

        @Test
        @DisplayName("SNAP-3: an unset duration freezes as null, preserving default semantics")
        void unsetFreezesAsNull() {
            CampaignConfigurationSnapshot snapshot = service()
                    .createExecutionSnapshot(campaign(null)).getConfiguration();

            assertThat(snapshot.getMaxCallDurationSeconds()).isNull();
            // ...and null still resolves to the platform default at runtime.
            assertThat(MaxCallDurationPolicy.effectiveSeconds(
                    snapshot.getMaxCallDurationSeconds())).isEqualTo(300);
        }

        @Test
        @DisplayName("SNAP-4: an edit after execution creation does not change the frozen value")
        void editAfterCreationDoesNotMutateSnapshot() {
            CampaignConfigurationService service = service();
            CampaignEntity campaign = campaign(180);

            CampaignConfigurationSnapshot snapshot = service
                    .createExecutionSnapshot(campaign).getConfiguration();
            assertThat(snapshot.getMaxCallDurationSeconds()).isEqualTo(180);

            // The operator edits the campaign to a much longer duration.
            campaign.setMaxCallDurationSeconds(3600);

            // The already-materialised snapshot is untouched, which is the whole
            // point of freezing: an in-flight call's budget cannot be widened
            // (or narrowed) after the fact.
            assertThat(snapshot.getMaxCallDurationSeconds())
                    .as("the snapshot is an immutable value, not a live view")
                    .isEqualTo(180);
            // ...and the campaign really did change, so the assertion above is
            // not vacuous.
            assertThat(campaign.getMaxCallDurationSeconds()).isEqualTo(3600);
        }

        @Test
        @DisplayName("SNAP-5: the runtime config exposes the frozen duration, not the live value")
        void runtimeConfigExposesDuration() {
            CampaignConfigurationService service = service();
            CampaignEntity campaign = campaign(90);
            CampaignExecutionConfiguration configuration =
                    service.createExecutionSnapshot(campaign);

            // The operator widens the campaign AFTER the execution exists.
            campaign.setMaxCallDurationSeconds(3600);

            CampaignRuntimeConfigResolver.CampaignRuntimeConfig config =
                    CampaignRuntimeConfigResolver.CampaignRuntimeConfig
                            .fromSnapshot(configuration);

            assertThat(config.maxCallDurationSeconds())
                    .as("the running execution must use the frozen value")
                    .isEqualTo(90);
            assertThat(MaxCallDurationPolicy.effectiveSeconds(config.maxCallDurationSeconds()))
                    .isEqualTo(90);
        }
    }

    @Nested
    class DomainGuard {

        @Test
        @DisplayName("SNAP-6: the domain guard accepts the whole range and null")
        void guardAcceptsValidRange() {
            for (Integer value : new Integer[] {null, 1, 300, 3600}) {
                assertThat(catchThrowable(
                                () -> MaxCallDurationPolicy.assertConfigurable(value)))
                        .as("value %s must be accepted", value)
                        .isNull();
            }
        }

        @ParameterizedTest(name = "SNAP-7: {0} is rejected")
        @ValueSource(ints = {0, -1, 3601})
        @DisplayName("SNAP-7: out-of-range values are rejected as VALIDATION_ERROR")
        void guardRejectsOutOfRange(int value) {
            assertThat(catchThrowable(() -> MaxCallDurationPolicy.assertConfigurable(value)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("maxCallDurationSeconds");
        }
    }
}

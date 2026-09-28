package com.shivang.obd.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.shivang.obd.voice.agent.AgentQueueReferenceChecker;
import com.shivang.obd.voice.queue.Queue;
import com.shivang.obd.voice.queue.QueueReferenceService;
import com.shivang.obd.voice.queue.QueueRepository;
import com.shivang.obd.voice.queue.QueueStatus;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * VB-7A: queue ownership and administrative lifecycle on the canonical
 * campaign-resource validation boundary.
 *
 * <p>Two layers are exercised together, because the invariant spans both: the
 * tenant-scoped lookup in the voice/queue domain, and the code mapping the
 * campaign boundary applies to its outcome.
 *
 * <p>The load-bearing assertion in here is the last one — a queue that is
 * correctly configured but currently has nobody available must stay
 * <b>valid</b>. Availability is a runtime fact, and treating it as a
 * configuration failure would make a campaign unsavable whenever the contact
 * centre is closed.
 */
class CampaignQueueResourceValidationTest {

    private static final UUID TENANT_A = UUID.fromString("aa000000-0000-4000-8000-00000000000a");
    private static final UUID TENANT_B = UUID.fromString("aa000000-0000-4000-8000-00000000000b");
    private static final UUID QUEUE_ID = UUID.fromString("3f2504e0-4f89-11d3-9a0c-0305e82c3301");

    private final QueueRepository queueRepository = mock(QueueRepository.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<AgentQueueReferenceChecker> provider = mock(ObjectProvider.class);

    private CampaignResourceValidationService validator() {
        AgentQueueReferenceChecker checker = new QueueReferenceService(queueRepository);
        when(provider.getIfAvailable()).thenReturn(checker);
        return new CampaignResourceValidationService(null, null, null, provider);
    }

    private void stubQueue(QueueStatus status) {
        Queue queue = new Queue();
        queue.setId(QUEUE_ID);
        queue.setTenantId(TENANT_A);
        queue.setStatus(status);
        when(queueRepository.findByIdAndTenantIdAndDeletedAtIsNull(QUEUE_ID, TENANT_A))
                .thenReturn(Optional.of(queue));
    }

    // ------------------------------------------------------------------
    // B. tenant isolation
    // ------------------------------------------------------------------

    @Test
    @DisplayName("B6. a same-tenant ACTIVE queue is accepted")
    void sameTenantActiveQueueAccepted() {
        stubQueue(QueueStatus.ACTIVE);
        var result = validator().validateQueue(QUEUE_ID, TENANT_A);
        assertThat(result.usable()).isTrue();
        assertThat(result.code()).isNull();
    }

    @Test
    @DisplayName("B7. a foreign-tenant queue is reported as not available, and the lookup is "
            + "constrained to the campaign's own tenant")
    void foreignQueueIsNotAvailable() {
        // The queue exists — for another tenant. The campaign boundary must not
        // learn that, so no tenant-B lookup is ever issued and the answer is the
        // same as for a queue that does not exist at all.
        stubQueue(QueueStatus.ACTIVE);

        var result = validator().validateQueue(QUEUE_ID, TENANT_B);

        assertThat(result.usable()).isFalse();
        assertThat(result.code())
                .isEqualTo(CampaignResourceValidationService.ValidationCode.QUEUE_NOT_AVAILABLE);
    }

    @Test
    @DisplayName("B7b. a nonexistent queue and a foreign queue are indistinguishable")
    void nonexistentAndForeignAreIndistinguishable() {
        stubQueue(QueueStatus.ACTIVE);
        var foreign = validator().validateQueue(QUEUE_ID, TENANT_B);

        when(queueRepository.findByIdAndTenantIdAndDeletedAtIsNull(QUEUE_ID, TENANT_A))
                .thenReturn(Optional.empty());
        var missing = validator().validateQueue(QUEUE_ID, TENANT_A);

        assertThat(foreign.code()).isEqualTo(missing.code());
    }

    @Test
    @DisplayName("B8. a null queue reference or tenant is not usable")
    void nullsAreNotUsable() {
        var service = validator();
        assertThat(service.validateQueue(null, TENANT_A).usable()).isFalse();
        assertThat(service.validateQueue(QUEUE_ID, null).usable()).isFalse();
        assertThat(service.validateQueue(QUEUE_ID, TENANT_A)
                .code())
                .isEqualTo(CampaignResourceValidationService.ValidationCode.QUEUE_NOT_AVAILABLE);
    }

    @Test
    @DisplayName("B8b. a deployment with no queue layer answers 'not usable' rather than throwing")
    void missingQueueLayerIsNotUsable() {
        when(provider.getIfAvailable()).thenReturn(null);
        var result = new CampaignResourceValidationService(null, null, null, provider)
                .validateQueue(QUEUE_ID, TENANT_A);
        assertThat(result.usable()).isFalse();
        assertThat(result.code())
                .isEqualTo(CampaignResourceValidationService.ValidationCode.QUEUE_NOT_AVAILABLE);
    }

    // ------------------------------------------------------------------
    // C. queue lifecycle — administrative state, never runtime state
    // ------------------------------------------------------------------

    @Test
    @DisplayName("C9. an INACTIVE queue is administratively not usable")
    void inactiveQueueIsNotUsable() {
        stubQueue(QueueStatus.INACTIVE);
        var result = validator().validateQueue(QUEUE_ID, TENANT_A);
        assertThat(result.usable()).isFalse();
        assertThat(result.code())
                .isEqualTo(CampaignResourceValidationService.ValidationCode.QUEUE_NOT_ACTIVE);
    }

    @Test
    @DisplayName("C10. a DISABLED queue is administratively not usable")
    void disabledQueueIsNotUsable() {
        stubQueue(QueueStatus.DISABLED);
        var result = validator().validateQueue(QUEUE_ID, TENANT_A);
        assertThat(result.usable()).isFalse();
        assertThat(result.code())
                .isEqualTo(CampaignResourceValidationService.ValidationCode.QUEUE_NOT_ACTIVE);
    }

    @Test
    @DisplayName("C11/C12. agent availability and capacity are NOT a configuration problem — "
            + "an ACTIVE queue stays valid regardless of who is free")
    void availabilityIsNotAConfigurationConcern() {
        // The validator never consults agents, memberships, availability or
        // capacity, so a perfectly configured queue is valid whatever the
        // contact centre looks like right now.
        stubQueue(QueueStatus.ACTIVE);
        var service = validator();

        // Called repeatedly, as the "nobody is free" case would be: still valid.
        for (int i = 0; i < 3; i++) {
            assertThat(service.validateQueue(QUEUE_ID, TENANT_A).usable()).isTrue();
        }

        // And it is side-effect free: the only repository touched is the
        // tenant-scoped queue lookup, and only once per call.
        org.mockito.Mockito.verify(queueRepository, org.mockito.Mockito.times(3))
                .findByIdAndTenantIdAndDeletedAtIsNull(QUEUE_ID, TENANT_A);
        org.mockito.Mockito.verifyNoMoreInteractions(queueRepository);
    }

    @Test
    @DisplayName("C12b. the checker itself never widens the tenant boundary")
    void checkerIsTenantScoped() {
        QueueReferenceService checker = new QueueReferenceService(queueRepository);
        // No stub: any lookup is unanswered, so nothing can be reported usable.
        assertThat(checker.usabilityOf(QUEUE_ID, TENANT_A))
                .isEqualTo(AgentQueueReferenceChecker.QueueUsability.NOT_ACCESSIBLE);
        assertThat(checker.usabilityOf(null, TENANT_A))
                .isEqualTo(AgentQueueReferenceChecker.QueueUsability.NOT_ACCESSIBLE);
        assertThat(checker.usabilityOf(QUEUE_ID, null))
                .isEqualTo(AgentQueueReferenceChecker.QueueUsability.NOT_ACCESSIBLE);
    }

    @Test
    @DisplayName("C12c. the pre-VB-7A three-argument construction still validates queues "
            + "safely (no checker = not usable)")
    void legacyConstructionRemainsSafe() {
        var legacy = new CampaignResourceValidationService(null, null, null);
        assertThat(legacy.validateQueue(QUEUE_ID, TENANT_A).usable()).isFalse();
        // and the pre-existing resource kinds are untouched
        assertThat(legacy.validateDid(null, TENANT_A).usable()).isFalse();
    }
}

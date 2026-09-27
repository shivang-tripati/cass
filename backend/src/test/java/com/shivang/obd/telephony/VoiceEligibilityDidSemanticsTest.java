package com.shivang.obd.telephony;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

import com.shivang.obd.did.AllocationState;
import com.shivang.obd.did.DidEntity;
import com.shivang.obd.did.DidRepository;
import com.shivang.obd.did.DidStatus;
import com.shivang.obd.tenant.TenantEntity;
import com.shivang.obd.tenant.TenantRepository;
import com.shivang.obd.voice.capacity.VoiceCapacityService;
import com.shivang.obd.voice.eligibility.VoiceEligibility.EligibilityResult;
import com.shivang.obd.voice.routing.VoiceRoute;
import com.shivang.obd.voice.routing.VoiceRouting;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * VB-5F: dial-time DID semantics of {@link VoiceEligibilityService} (step 6
 * of the voice eligibility precedence) are the voice-layer superset of the
 * canonical campaign-resource DID validation. A DID that is deleted,
 * missing, inactive, unassigned (revoked back to a pool), or owned by
 * another tenant must block the dial with {@code INVALID_DID} — this is the
 * runtime boundary that rejects a campaign whose DID was revoked after
 * activation (stale-resource governance).
 *
 * <p>No dedicated unit tests existed for this service before VB-5F; the
 * DB-backed behavior was proven in {@code DidAllocationPostgresIntegrationTest#voiceEligibilityIntegration}
 * (PG-B2). This suite pins the full reason-code matrix at the unit level.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VoiceEligibilityDidSemanticsTest {

    private static final UUID TENANT =
        UUID.fromString("bb000000-0000-4000-8000-0000000000f1");
    private static final UUID FOREIGN_TENANT =
        UUID.fromString("bb000000-0000-4000-8000-0000000000f2");
    private static final UUID DID =
        UUID.fromString("bb000000-0000-4000-8000-0000000000d1");
    private static final UUID GATEWAY =
        UUID.fromString("bb000000-0000-4000-8000-0000000000b1");
    private static final String DEST = "+919876543210";

    @Mock PhoneListEntryRepository phoneListRepository;
    @Mock VoiceRouting voiceRouting;
    @Mock VoiceCapacityService voiceCapacity;
    @Mock TenantRepository tenantRepository;
    @Mock DidRepository didRepository;

    private VoiceEligibilityService service;

    /** Fully usable DID: ACTIVE, ASSIGNED, owned by TENANT. */
    private DidEntity usableDid(UUID tenantId) {
        DidEntity did = new DidEntity();
        did.setId(DID);
        did.setE164Number("+919812345678");
        did.setCountryCode("+91");
        did.setProvider("TATA");
        did.setStatus(DidStatus.ACTIVE);
        did.setAllocationState(AllocationState.ASSIGNED);
        did.setAllocationSource(com.shivang.obd.did.AllocationSource.PLATFORM);
        did.setTenantId(tenantId);
        return did;
    }

    @BeforeEach
    void setUp() {
        service = new VoiceEligibilityService(
            phoneListRepository, voiceRouting, voiceCapacity,
            tenantRepository, didRepository);

        lenient().when(tenantRepository.findByIdAndDeletedAtIsNull(TENANT))
            .thenReturn(Optional.of(new TenantEntity()));
        // Blocklists are empty by default; these tests vary only the DID.
        lenient().when(phoneListRepository
            .findByTypeAndScopeTypeAndActiveTrueAndDeletedAtIsNull(any(), any()))
            .thenReturn(java.util.List.of());
        lenient().when(phoneListRepository
            .findByTypeAndScopeTypeAndScopeTenantIdAndActiveTrueAndDeletedAtIsNull(
                any(), any(), any()))
            .thenReturn(java.util.List.of());
        lenient().when(phoneListRepository
            .findByTypeAndScopeTypeAndScopeResellerIdAndActiveTrueAndDeletedAtIsNull(
                any(), any(), any()))
            .thenReturn(java.util.List.of());
        // Gateway + capacity pass so the only variable is the DID state.
        lenient().when(voiceRouting.resolve(any(), any(), anyString()))
            .thenReturn(Optional.of(new VoiceRoute(
                GATEWAY, "fs-gw-test", "external", "TATA", DID, "+919812345678")));
        lenient().when(voiceCapacity.isAvailable(GATEWAY, TENANT)).thenReturn(true);
    }

    @Nested
    @DisplayName("step 6: DID validity blocks with INVALID_DID")
    class DidValidity {

        @Test
        @DisplayName("missing/deleted DID (deletedAt-filtered lookup empty) blocks")
        void missingOrDeletedDidBlocks() {
            // The deletedAt-filtered finder returns empty for both a missing
            // row and a soft-deleted row — identical rejection either way.
            lenient().when(didRepository.findByIdAndDeletedAtIsNull(DID))
                .thenReturn(Optional.empty());

            EligibilityResult result = service.evaluate(TENANT, DEST, DID);

            assertThat(result.isAllowed()).isFalse();
            assertThat(result.getReasonCode()).isEqualTo("INVALID_DID");
            assertThat(result.getReasonMessage()).isEqualTo("DID not found");
        }

        @Test
        @DisplayName("null DID reference blocks")
        void nullDidBlocks() {
            EligibilityResult result = service.evaluate(TENANT, DEST, null);

            assertThat(result.isAllowed()).isFalse();
            assertThat(result.getReasonCode()).isEqualTo("INVALID_DID");
            assertThat(result.getReasonMessage()).isEqualTo("No DID assigned");
        }

        @Test
        @DisplayName("INACTIVE DID blocks")
        void inactiveDidBlocks() {
            DidEntity did = usableDid(TENANT);
            did.setStatus(DidStatus.INACTIVE);
            lenient().when(didRepository.findByIdAndDeletedAtIsNull(DID))
                .thenReturn(Optional.of(did));

            EligibilityResult result = service.evaluate(TENANT, DEST, DID);

            assertThat(result.isAllowed()).isFalse();
            assertThat(result.getReasonCode()).isEqualTo("INVALID_DID");
            assertThat(result.getReasonMessage()).isEqualTo("DID is not active");
        }

        @Test
        @DisplayName("revoked DID (back to AVAILABLE pool state) blocks")
        void revokedDidBlocks() {
            DidEntity did = usableDid(TENANT);
            did.setAllocationState(AllocationState.AVAILABLE);
            did.setTenantId(null); // revoke returns the number to the pool
            lenient().when(didRepository.findByIdAndDeletedAtIsNull(DID))
                .thenReturn(Optional.of(did));

            EligibilityResult result = service.evaluate(TENANT, DEST, DID);

            assertThat(result.isAllowed()).isFalse();
            assertThat(result.getReasonCode()).isEqualTo("INVALID_DID");
            assertThat(result.getReasonMessage()).isEqualTo("DID is not assigned");
        }

        @Test
        @DisplayName("reassigned DID (foreign tenant owner) blocks")
        void reassignedToForeignTenantBlocks() {
            DidEntity did = usableDid(FOREIGN_TENANT);
            lenient().when(didRepository.findByIdAndDeletedAtIsNull(DID))
                .thenReturn(Optional.of(did));

            EligibilityResult result = service.evaluate(TENANT, DEST, DID);

            assertThat(result.isAllowed()).isFalse();
            assertThat(result.getReasonCode()).isEqualTo("INVALID_DID");
            assertThat(result.getReasonMessage()).isEqualTo("DID does not belong to tenant");
        }

        @Test
        @DisplayName("usable DID passes the DID checks (gateway/capacity still apply)")
        void usableDidPasses() {
            lenient().when(didRepository.findByIdAndDeletedAtIsNull(DID))
                .thenReturn(Optional.of(usableDid(TENANT)));

            EligibilityResult result = service.evaluate(TENANT, DEST, DID);

            assertThat(result.isAllowed()).isTrue();
        }

        @Test
        @DisplayName("DID revoked after being usable blocks on the next dial (stale resource)")
        void revokedAfterUsableBlocksOnNextDial() {
            lenient().when(didRepository.findByIdAndDeletedAtIsNull(DID))
                .thenReturn(Optional.of(usableDid(TENANT)));
            assertThat(service.evaluate(TENANT, DEST, DID).isAllowed()).isTrue();

            // Revocation: DID returns to the pool, no longer tenant-owned.
            DidEntity revoked = usableDid(TENANT);
            revoked.setAllocationState(AllocationState.AVAILABLE);
            revoked.setTenantId(null);
            lenient().when(didRepository.findByIdAndDeletedAtIsNull(DID))
                .thenReturn(Optional.of(revoked));

            EligibilityResult result = service.evaluate(TENANT, DEST, DID);

            assertThat(result.isAllowed()).isFalse();
            assertThat(result.getReasonCode()).isEqualTo("INVALID_DID");
        }
    }
}

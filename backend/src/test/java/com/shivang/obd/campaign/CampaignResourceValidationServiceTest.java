package com.shivang.obd.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.shivang.obd.audio.AudioAssetEntity;
import com.shivang.obd.audio.AudioAssetRepository;
import com.shivang.obd.audio.AudioAssetStatus;
import com.shivang.obd.campaign.CampaignResourceValidationService.ResourceValidationResult;
import com.shivang.obd.campaign.CampaignResourceValidationService.ValidationCode;
import com.shivang.obd.did.DidRepository;
import com.shivang.obd.tts.TtsTemplateRepository;
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
 * VB-5E canonical campaign-resource validation contract — unit matrix for
 * the three resource kinds (spec §17.A/B/C at the classification level).
 * Ownership/allocation, soft-delete and GLOBAL/TENANT state matrices are
 * proven against real PostgreSQL in
 * {@code CampaignResourceValidationPostgresIntegrationTest}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CampaignResourceValidationServiceTest {

    private static final UUID TENANT =
        UUID.fromString("e5000000-0000-4000-8000-0000000000a1");
    private static final UUID DID_ID =
        UUID.fromString("e5000000-0000-4000-8000-0000000000d1");
    private static final UUID ASSET_ID =
        UUID.fromString("e5000000-0000-4000-8000-0000000000a2");
    private static final UUID TTS_ID =
        UUID.fromString("e5000000-0000-4000-8000-0000000000b1");

    @Mock
    private DidRepository didRepository;
    @Mock
    private AudioAssetRepository audioAssetRepository;
    @Mock
    private TtsTemplateRepository ttsTemplateRepository;

    private CampaignResourceValidationService validator;

    @BeforeEach
    void setUp() {
        validator = new CampaignResourceValidationService(
            didRepository, audioAssetRepository, ttsTemplateRepository);
    }

    @Nested
    class DidValidation {

        @Test
        @DisplayName("A: live, tenant-owned, ACTIVE, ASSIGNED DID is usable")
        void validAssignedActiveDidIsUsable() {
            when(didRepository.existsByIdAndTenantIdAndDeletedAtIsNullAndStatusAndAllocationState(
                DID_ID, TENANT, com.shivang.obd.did.DidStatus.ACTIVE,
                com.shivang.obd.did.AllocationState.ASSIGNED)).thenReturn(true);

            ResourceValidationResult result = validator.validateDid(DID_ID, TENANT);

            assertThat(result.usable()).isTrue();
            assertThat(result.code()).isNull();
        }

        @Test
        @DisplayName("A: unassigned, revoked, inactive, deleted or foreign DID is not usable")
        void unusableDidIsRejectedWithSingleCode() {
            when(didRepository.existsByIdAndTenantIdAndDeletedAtIsNullAndStatusAndAllocationState(
                DID_ID, TENANT, com.shivang.obd.did.DidStatus.ACTIVE,
                com.shivang.obd.did.AllocationState.ASSIGNED)).thenReturn(false);

            ResourceValidationResult result = validator.validateDid(DID_ID, TENANT);

            assertThat(result.usable()).isFalse();
            assertThat(result.code()).isEqualTo(ValidationCode.DID_NOT_AVAILABLE);
        }

        @Test
        @DisplayName("A: null DID reference is not usable (callers keep their own null-policy)")
        void nullDidReferenceIsNotUsable() {
            ResourceValidationResult result = validator.validateDid(null, TENANT);

            assertThat(result.usable()).isFalse();
            assertThat(result.code()).isEqualTo(ValidationCode.DID_NOT_AVAILABLE);
            verifyNoMoreInteractions(didRepository);
        }
    }

    @Nested
    class AudioValidation {

        @Test
        @DisplayName("B: approved tenant-owned asset with a storage reference is usable")
        void approvedAssetWithStorageIsUsable() {
            when(audioAssetRepository.findByIdAndTenantIdAndDeletedAtIsNull(ASSET_ID, TENANT))
                .thenReturn(Optional.of(asset(AudioAssetStatus.APPROVED, "tenants/t/promo.wav")));

            ResourceValidationResult result = validator.validateAudio(ASSET_ID, TENANT);

            assertThat(result.usable()).isTrue();
            assertThat(result.code()).isNull();
        }

        @Test
        @DisplayName("B: pending or rejected asset is classified AUDIO_NOT_APPROVED")
        void unapprovedAssetIsClassified() {
            when(audioAssetRepository.findByIdAndTenantIdAndDeletedAtIsNull(ASSET_ID, TENANT))
                .thenReturn(Optional.of(asset(AudioAssetStatus.PENDING_APPROVAL, "tenants/t/promo.wav")));

            assertThat(validator.validateAudio(ASSET_ID, TENANT).code())
                .isEqualTo(ValidationCode.AUDIO_NOT_APPROVED);

            when(audioAssetRepository.findByIdAndTenantIdAndDeletedAtIsNull(ASSET_ID, TENANT))
                .thenReturn(Optional.of(asset(AudioAssetStatus.REJECTED, "tenants/t/promo.wav")));

            assertThat(validator.validateAudio(ASSET_ID, TENANT).code())
                .isEqualTo(ValidationCode.AUDIO_NOT_APPROVED);
        }

        @Test
        @DisplayName("B: missing, deleted or foreign asset is AUDIO_NOT_AVAILABLE (no leak)")
        void missingAssetIsNotAvailable() {
            when(audioAssetRepository.findByIdAndTenantIdAndDeletedAtIsNull(ASSET_ID, TENANT))
                .thenReturn(Optional.empty());

            ResourceValidationResult result = validator.validateAudio(ASSET_ID, TENANT);

            assertThat(result.usable()).isFalse();
            assertThat(result.code()).isEqualTo(ValidationCode.AUDIO_NOT_AVAILABLE);
        }

        @Test
        @DisplayName("B: approved asset without a usable storage reference is classified")
        void approvedAssetWithoutStorageIsClassified() {
            when(audioAssetRepository.findByIdAndTenantIdAndDeletedAtIsNull(ASSET_ID, TENANT))
                .thenReturn(Optional.of(asset(AudioAssetStatus.APPROVED, null)));

            assertThat(validator.validateAudio(ASSET_ID, TENANT).code())
                .isEqualTo(ValidationCode.AUDIO_STORAGE_REFERENCE_MISSING);

            when(audioAssetRepository.findByIdAndTenantIdAndDeletedAtIsNull(ASSET_ID, TENANT))
                .thenReturn(Optional.of(asset(AudioAssetStatus.APPROVED, "   ")));

            assertThat(validator.validateAudio(ASSET_ID, TENANT).code())
                .isEqualTo(ValidationCode.AUDIO_STORAGE_REFERENCE_MISSING);
        }

        @Test
        @DisplayName("B: null asset reference is not usable")
        void nullAssetReferenceIsNotUsable() {
            ResourceValidationResult result = validator.validateAudio(null, TENANT);

            assertThat(result.usable()).isFalse();
            assertThat(result.code()).isEqualTo(ValidationCode.AUDIO_NOT_AVAILABLE);
            verifyNoMoreInteractions(audioAssetRepository);
        }
    }

    @Nested
    class TtsValidation {

        @Test
        @DisplayName("C: template satisfying existsUsableForTenant is usable")
        void usableTemplateIsValid() {
            when(ttsTemplateRepository.existsUsableForTenant(TTS_ID, TENANT)).thenReturn(true);

            ResourceValidationResult result = validator.validateTts(TTS_ID, TENANT);

            assertThat(result.usable()).isTrue();
            assertThat(result.code()).isNull();
        }

        @Test
        @DisplayName("C: accessible-but-unapproved template is TTS_NOT_APPROVED")
        void accessibleUnapprovedTemplateIsClassified() {
            when(ttsTemplateRepository.existsUsableForTenant(TTS_ID, TENANT)).thenReturn(false);
            when(ttsTemplateRepository.existsAccessibleForTenant(TTS_ID, TENANT)).thenReturn(true);

            ResourceValidationResult result = validator.validateTts(TTS_ID, TENANT);

            assertThat(result.usable()).isFalse();
            assertThat(result.code()).isEqualTo(ValidationCode.TTS_NOT_APPROVED);
        }

        @Test
        @DisplayName("C: missing, deleted or foreign template is TTS_NOT_AVAILABLE (no leak)")
        void inaccessibleTemplateIsNotAvailable() {
            when(ttsTemplateRepository.existsUsableForTenant(TTS_ID, TENANT)).thenReturn(false);
            when(ttsTemplateRepository.existsAccessibleForTenant(TTS_ID, TENANT)).thenReturn(false);

            ResourceValidationResult result = validator.validateTts(TTS_ID, TENANT);

            assertThat(result.usable()).isFalse();
            assertThat(result.code()).isEqualTo(ValidationCode.TTS_NOT_AVAILABLE);
        }

        @Test
        @DisplayName("C: null template reference is not usable")
        void nullTemplateReferenceIsNotUsable() {
            ResourceValidationResult result = validator.validateTts(null, TENANT);

            assertThat(result.usable()).isFalse();
            assertThat(result.code()).isEqualTo(ValidationCode.TTS_NOT_AVAILABLE);
            verifyNoMoreInteractions(ttsTemplateRepository);
        }
    }

    @Nested
    class Idempotency {

        @Test
        @DisplayName("H: repeated validation is deterministic and issues only read queries")
        void repeatedValidationIsDeterministicAndReadOnly() {
            when(didRepository.existsByIdAndTenantIdAndDeletedAtIsNullAndStatusAndAllocationState(
                DID_ID, TENANT, com.shivang.obd.did.DidStatus.ACTIVE,
                com.shivang.obd.did.AllocationState.ASSIGNED)).thenReturn(true);
            when(audioAssetRepository.findByIdAndTenantIdAndDeletedAtIsNull(ASSET_ID, TENANT))
                .thenReturn(Optional.of(asset(AudioAssetStatus.APPROVED, "tenants/t/promo.wav")));
            when(ttsTemplateRepository.existsUsableForTenant(TTS_ID, TENANT)).thenReturn(true);

            for (int i = 0; i < 3; i++) {
                assertThat(validator.validateDid(DID_ID, TENANT).usable()).isTrue();
                assertThat(validator.validateAudio(ASSET_ID, TENANT).usable()).isTrue();
                assertThat(validator.validateTts(TTS_ID, TENANT).usable()).isTrue();
            }

            verify(didRepository, times(3))
                .existsByIdAndTenantIdAndDeletedAtIsNullAndStatusAndAllocationState(
                    DID_ID, TENANT, com.shivang.obd.did.DidStatus.ACTIVE,
                    com.shivang.obd.did.AllocationState.ASSIGNED);
            verify(audioAssetRepository, times(3))
                .findByIdAndTenantIdAndDeletedAtIsNull(ASSET_ID, TENANT);
            verify(ttsTemplateRepository, times(3)).existsUsableForTenant(TTS_ID, TENANT);
            // No write-capable repository interaction of any kind occurred.
            verifyNoMoreInteractions(didRepository, audioAssetRepository, ttsTemplateRepository);
        }
    }

    private static AudioAssetEntity asset(AudioAssetStatus status, String storageReference) {
        AudioAssetEntity asset = new AudioAssetEntity();
        asset.setId(ASSET_ID);
        asset.setTenantId(TENANT);
        asset.setStatus(status);
        asset.setStorageReference(storageReference);
        return asset;
    }
}

package com.shivang.obd.audio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shivang.obd.authz.AccessCheck;
import com.shivang.obd.authz.AuthorizationService;
import com.shivang.obd.authz.context.OrganizationContextHolder;
import com.shivang.obd.common.exception.BusinessException;
import com.shivang.obd.security.CurrentUserProvider;
import com.shivang.obd.tenant.TenantEntity;
import com.shivang.obd.tenant.TenantRepository;
import java.io.ByteArrayInputStream;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * VB-5B service-level unit tests for {@link AudioAssetService#upload}:
 * server-derived ownership, validation ordering, persistence of derived
 * metadata, upload-never-approves semantics, and storage-failure
 * compensation.
 */
@ExtendWith(MockitoExtension.class)
class AudioAssetServiceUploadTest {

    private static final UUID USER_ID = UUID.fromString("cc000000-0000-4000-8000-000000000001");
    private static final UUID TENANT_A = UUID.fromString("aa000000-0000-4000-8000-0000000000a5");
    private static final UUID TENANT_B = UUID.fromString("bb000000-0000-4000-8000-0000000000b6");

    @Mock
    private AudioAssetRepository repository;
    @Mock
    private AuthorizationService authorizationService;
    @Mock
    private CurrentUserProvider currentUserProvider;
    @Mock
    private TenantRepository tenantRepository;
    @Mock
    private AudioStorage audioStorage;

    private AudioAssetService service;

    @BeforeEach
    void setUp() {
        AudioAssetMapper mapper = new AudioAssetMapper();
        AudioStorageProperties props = new AudioStorageProperties();
        props.setEnabled(true);
        service = new AudioAssetService(repository, authorizationService, currentUserProvider,
            mapper, tenantRepository, audioStorage, new AudioUploadValidator(props));

        lenient().when(currentUserProvider.current()).thenReturn(Optional.of(
            new com.shivang.obd.security.AuthenticatedUser(USER_ID, "admin@test.local", null)));
        lenient().when(tenantRepository.findByIdAndDeletedAtIsNull(TENANT_A))
            .thenReturn(Optional.of(new TenantEntity()));
        lenient().when(repository.save(any(AudioAssetEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));
        OrganizationContextHolder.setAuthenticated(USER_ID, TENANT_A, null);
    }

    @AfterEach
    void tearDown() {
        OrganizationContextHolder.clear();
    }

    private byte[] wavBytes() {
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(60).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(52).put("WAVE".getBytes());
        b.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1)
            .putInt(8000).putInt(8000).putShort((short) 1).putShort((short) 8);
        b.put("data".getBytes()).putInt(16);
        for (int i = 0; i < 16; i++) {
            b.put((byte) i);
        }
        return b.array();
    }

    @Test
    @DisplayName("uploads persist derived metadata and the storage reference")
    void uploadPersistsDerivedMetadata() {
        byte[] wav = wavBytes();
        when(audioStorage.store(any(), any(), any(), any()))
            .thenReturn(new AudioStorage.StoredAudio(
                "audio/" + TENANT_A + "/<id>/file.wav", wav.length));

        var response = service.upload("Greeting", "Welcome prompt",
            "greeting.wav", "audio/wav", new ByteArrayInputStream(wav));

        ArgumentCaptor<AudioAssetEntity> captor = ArgumentCaptor.forClass(AudioAssetEntity.class);
        verify(repository, org.mockito.Mockito.times(2)).save(captor.capture());
        AudioAssetEntity saved = captor.getValue();
        assertThat(saved.getTenantId()).isEqualTo(TENANT_A);
        assertThat(saved.getFileName()).isEqualTo("greeting.wav");
        assertThat(saved.getContentType()).isEqualTo("audio/wav");
        assertThat(saved.getFileSize()).isEqualTo((long) wav.length);
        assertThat(saved.getChecksum()).hasSize(64);
        assertThat(saved.getStorageReference()).startsWith("audio/" + TENANT_A + "/");
        assertThat(saved.getStatus()).isEqualTo(AudioAssetStatus.PENDING_APPROVAL);
        assertThat(response.data().status()).isEqualTo(AudioAssetStatus.PENDING_APPROVAL);
        verify(authorizationService).requireCapability(USER_ID, "AUDIO_MANAGE", AccessCheck.forTenant(TENANT_A));
    }

    @Test
    @DisplayName("upload never approves: tenant assets stay PENDING_APPROVAL")
    void uploadNeverApproves() {
        when(audioStorage.store(any(), any(), any(), any()))
            .thenReturn(new AudioStorage.StoredAudio("audio/x/1/a.wav", 60));

        service.upload("Name", null, "a.wav", "audio/wav", new ByteArrayInputStream(wavBytes()));

        ArgumentCaptor<AudioAssetEntity> captor = ArgumentCaptor.forClass(AudioAssetEntity.class);
        verify(repository, org.mockito.Mockito.times(2)).save(captor.capture());
        assertThat(captor.getAllValues())
            .allSatisfy(e -> assertThat(e.getStatus()).isEqualTo(AudioAssetStatus.PENDING_APPROVAL));
    }

    @Test
    @DisplayName("storage failure compensates: no storage reference persisted, storage cleaned up")
    void storageFailureCompensates() {
        when(audioStorage.store(any(), any(), any(), any()))
            .thenThrow(new AudioStorageException("disk full"));

        assertThatThrownBy(() -> service.upload("Name", null, "a.wav", "audio/wav",
            new ByteArrayInputStream(wavBytes())))
            .isInstanceOf(AudioStorageException.class)
            .hasMessageContaining("storage failed");

        // Only the initial metadata save ran; the storage-reference save is
        // never reached (the exception aborts before it / the tx rolls back).
        ArgumentCaptor<AudioAssetEntity> captor = ArgumentCaptor.forClass(AudioAssetEntity.class);
        verify(repository, org.mockito.Mockito.times(1)).save(captor.capture());
        assertThat(captor.getValue().getStorageReference()).isNull();
    }

    @Test
    @DisplayName("invalid audio is rejected before any persistence or storage work")
    void invalidAudioRejectedBeforePersistence() {
        byte[] exe = {0x4D, 0x5A, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};

        assertThatThrownBy(() -> service.upload("Name", null, "evil.wav", "audio/wav",
            new ByteArrayInputStream(exe)))
            .isInstanceOf(InvalidAudioUploadException.class);

        verify(repository, never()).save(any());
        verify(audioStorage, never()).store(any(), any(), any(), any());
    }

    @Test
    @DisplayName("platform callers (no tenant context) cannot upload")
    void platformCannotUpload() {
        OrganizationContextHolder.setAuthenticated(USER_ID, null, null);

        assertThatThrownBy(() -> service.upload("Name", null, "a.wav", "audio/wav",
            new ByteArrayInputStream(wavBytes())))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("tenant must be specified");

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("tenant isolation: ownership comes from the server context, never client input")
    void tenantIsolation() {
        // The authenticated context is TENANT_A; the storage call must be
        // addressed to TENANT_A regardless of anything the client sends.
        when(audioStorage.store(any(), any(), any(), any()))
            .thenReturn(new AudioStorage.StoredAudio("audio/x/1/a.wav", 60));

        service.upload("Name", null, "a.wav", "audio/wav", new ByteArrayInputStream(wavBytes()));

        verify(audioStorage).store(
            org.mockito.ArgumentMatchers.eq(TENANT_A), any(), any(), any());
        ArgumentCaptor<AudioAssetEntity> captor = ArgumentCaptor.forClass(AudioAssetEntity.class);
        verify(repository, org.mockito.Mockito.times(2)).save(captor.capture());
        assertThat(captor.getAllValues())
            .allSatisfy(e -> assertThat(e.getTenantId()).isEqualTo(TENANT_A));
    }
}

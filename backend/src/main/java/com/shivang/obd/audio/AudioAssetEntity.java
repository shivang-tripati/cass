package com.shivang.obd.audio;

import com.shivang.obd.common.audit.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Metadata for a tenant-owned audio asset that Campaign can reference by
 * identifier. This phase intentionally stores metadata plus a logical
 * storage reference only — binary transfer/object-storage wiring is
 * deferred; the reference keeps the model storage-agnostic.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "audio_assets", indexes = {
    @Index(name = "idx_audio_assets_tenant_deleted", columnList = "tenant_id,deleted_at")
})
public class AudioAssetEntity extends AuditableEntity {

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "description")
    private String description;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "file_size", nullable = false)
    private Long fileSize;

    /** Optional; reliable duration extraction requires a media library (deferred). */
    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    /** Optional SHA-256 hex of the payload when known. */
    @Column(name = "checksum", length = 64)
    private String checksum;

    /** Logical storage location for the future object-store integration. */
    @Column(name = "storage_reference", length = 500)
    private String storageReference;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AudioAssetStatus status = AudioAssetStatus.PENDING_APPROVAL;
}

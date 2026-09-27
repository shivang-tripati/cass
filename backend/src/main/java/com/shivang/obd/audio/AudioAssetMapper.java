package com.shivang.obd.audio;

import com.shivang.obd.audio.dto.AudioAssetResponse;
import com.shivang.obd.audio.dto.CreateAudioAssetRequest;
import com.shivang.obd.audio.dto.UpdateAudioAssetRequest;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Single DTO &lt;-&gt; entity conversion point for audio assets. */
@Component
public class AudioAssetMapper {

    public AudioAssetEntity toEntity(CreateAudioAssetRequest request, UUID tenantId) {
        AudioAssetEntity entity = new AudioAssetEntity();
        applyCommon(entity, request.name(), request.description());
        entity.setTenantId(tenantId);
        entity.setFileName(request.fileName().trim());
        entity.setContentType(request.contentType().trim());
        entity.setFileSize(request.fileSize());
        entity.setDurationSeconds(request.durationSeconds());
        entity.setChecksum(blankToNull(request.checksum()));
        entity.setStorageReference(blankToNull(request.storageReference()));
        return entity;
    }

    public void updateEntity(AudioAssetEntity entity, UpdateAudioAssetRequest request) {
        applyCommon(entity, request.name(), request.description());
    }

    public AudioAssetResponse toResponse(AudioAssetEntity entity) {
        return new AudioAssetResponse(
            entity.getId(),
            entity.getTenantId(),
            entity.getName(),
            entity.getDescription(),
            entity.getFileName(),
            entity.getContentType(),
            entity.getFileSize(),
            entity.getDurationSeconds(),
            entity.getChecksum(),
            entity.getStorageReference(),
            entity.getStatus(),
            entity.getCreatedAt(),
            entity.getUpdatedAt()
        );
    }

    private void applyCommon(AudioAssetEntity entity, String name, String description) {
        entity.setName(name.trim());
        entity.setDescription(description == null || description.isBlank() ? null : description);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}

package com.shivang.obd.audio.dto;

import com.shivang.obd.audio.AudioAssetStatus;
import java.time.Instant;
import java.util.UUID;

public record AudioAssetResponse(
    UUID id,
    UUID tenantId,
    String name,
    String description,
    String fileName,
    String contentType,
    Long fileSize,
    Integer durationSeconds,
    String checksum,
    String storageReference,
    AudioAssetStatus status,
    Instant createdAt,
    Instant updatedAt
) {
}

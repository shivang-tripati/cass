package com.shivang.obd.tts.dto;

import com.shivang.obd.tts.TtsTemplateScope;
import com.shivang.obd.tts.TtsTemplateStatus;
import com.shivang.obd.tts.TtsTemplateVariable;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TtsTemplateResponse(
    UUID id,
    UUID tenantId,
    String name,
    String description,
    String templateText,
    List<TtsTemplateVariable> variables,
    TtsTemplateStatus status,
    TtsTemplateScope scope,
    Instant createdAt,
    Instant updatedAt
) {
}

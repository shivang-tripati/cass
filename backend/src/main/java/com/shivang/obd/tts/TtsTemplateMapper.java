package com.shivang.obd.tts;

import com.shivang.obd.tts.dto.CreateTtsTemplateRequest;
import com.shivang.obd.tts.dto.TtsTemplateResponse;
import com.shivang.obd.tts.dto.UpdateTtsTemplateRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Single DTO &lt;-&gt; entity conversion point for TTS templates.
 * Status/ownership are applied by the service, never from requests.
 */
@Component
public class TtsTemplateMapper {

    public TtsTemplateEntity toEntity(
        CreateTtsTemplateRequest request, UUID tenantId, TtsTemplateStatus initialStatus
    ) {
        TtsTemplateEntity entity = new TtsTemplateEntity();
        applyCommon(entity, request.name(), request.description(),
            request.templateText(), request.variables());
        entity.setTenantId(tenantId);
        entity.setStatus(initialStatus);
        entity.setScope(request.scope() == null ? TtsTemplateScope.TENANT : request.scope());
        return entity;
    }

    public void updateEntity(TtsTemplateEntity entity, UpdateTtsTemplateRequest request) {
        applyCommon(entity, request.name(), request.description(),
            request.templateText(), request.variables());
    }

    public TtsTemplateResponse toResponse(TtsTemplateEntity entity) {
        return new TtsTemplateResponse(
            entity.getId(),
            entity.getTenantId(),
            entity.getName(),
            entity.getDescription(),
            entity.getTemplateText(),
            copyVariables(entity.getVariables()),
            entity.getStatus(),
            effectiveScope(entity),
            entity.getCreatedAt(),
            entity.getUpdatedAt()
        );
    }

    private void applyCommon(
        TtsTemplateEntity entity,
        String name,
        String description,
        String templateText,
        List<TtsTemplateVariable> variables
    ) {
        entity.setName(name.trim());
        entity.setDescription(description == null || description.isBlank() ? null : description);
        entity.setTemplateText(templateText);
        entity.setVariables(variables == null || variables.isEmpty() ? null : List.copyOf(variables));
    }

    private List<TtsTemplateVariable> copyVariables(List<TtsTemplateVariable> variables) {
        return variables == null ? null : List.copyOf(variables);
    }

    /** Tolerates missing/ambiguous scope markers when a tenantId is present (defensive). */
    private static TtsTemplateScope effectiveScope(TtsTemplateEntity entity) {
        if (entity.getScope() != null) {
            return entity.getScope();
        }
        return entity.getTenantId() == null ? TtsTemplateScope.GLOBAL : TtsTemplateScope.TENANT;
    }
}

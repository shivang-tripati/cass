package com.shivang.obd.tts.dto;

import com.shivang.obd.tts.TtsTemplateScope;
import com.shivang.obd.tts.TtsTemplateVariable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * TTS template creation. Variables are simple named placeholders
 * ({{name}}) declared explicitly; the template text may only reference
 * declared variables. Platform (SUPER_ADMIN) callers create templates in
 * APPROVED state (system/prebuilt); tenant-created templates start
 * PENDING_APPROVAL.
 *
 * <p>Scope (V42): GLOBAL requests are platform-managed system templates
 * (tenantId must be null; created APPROVED by platform callers); TENANT
 * is the default and follows the pre-VB-5D rules, including the
 * platform-caller seeding path into a target tenant.
 */
public record CreateTtsTemplateRequest(
    @NotBlank @Size(max = 150) String name,
    @Size(max = 5000) String description,

    @NotBlank @Size(max = 5000) String templateText,

    @NotNull @Size(max = 50) List<TtsTemplateVariable> variables,

    /** Optional target tenant for platform-scope callers; otherwise the caller's context tenant. */
    UUID tenantId,

    /** Optional ownership scope; defaults to TENANT when omitted. GLOBAL requires platform scope. */
    TtsTemplateScope scope
) {
}

package com.shivang.obd.tts;

import com.shivang.obd.security.AuthenticatedUser;
import java.util.List;
import java.util.UUID;
import org.mockito.invocation.InvocationOnMock;

/** Shared fixtures for TTS template service unit tests (VB-5D). */
final class TtsTemplateTestSupport {

    static final UUID USER_ID = UUID.fromString("cc000000-0000-4000-8000-000000000001");
    static final UUID GLOBAL_ID = UUID.fromString("dd000000-0000-4000-8000-000000000001");

    private static final List<TtsTemplateVariable> NAME_VARS =
        List.of(new TtsTemplateVariable("name", "STRING", true));

    private TtsTemplateTestSupport() {
    }

    static AuthenticatedUser authenticatedUser() {
        return new AuthenticatedUser(USER_ID, "admin@test.local", null);
    }

    static UUID tenantA() {
        return UUID.fromString("aa000000-0000-4000-8000-00000000000a");
    }

    static UUID tenantB() {
        return UUID.fromString("aa000000-0000-4000-8000-00000000000b");
    }

    /** Tenant-owned template in its creation state. */
    static TtsTemplateEntity tenantTemplate(UUID tenantId) {
        TtsTemplateEntity entity = new TtsTemplateEntity();
        entity.setId(UUID.randomUUID());
        entity.setTenantId(tenantId);
        entity.setName("Tenant promo");
        entity.setTemplateText("Hello {{name}}.");
        entity.setVariables(NAME_VARS);
        entity.setStatus(TtsTemplateStatus.PENDING_APPROVAL);
        entity.setScope(TtsTemplateScope.TENANT);
        return entity;
    }

    /** Tenant-owned template already approved. */
    static TtsTemplateEntity pendingTenant(UUID tenantId) {
        return tenantTemplate(tenantId);
    }

    /** Platform-owned GLOBAL template in the shared approved catalog. */
    static TtsTemplateEntity approvedGlobal() {
        TtsTemplateEntity entity = new TtsTemplateEntity();
        entity.setId(GLOBAL_ID);
        entity.setTenantId(null);
        entity.setName("Global promo");
        entity.setTemplateText("Welcome {{name}}.");
        entity.setVariables(NAME_VARS);
        entity.setStatus(TtsTemplateStatus.APPROVED);
        entity.setScope(TtsTemplateScope.GLOBAL);
        return entity;
    }

    /** Mockito save answer: identity persistence for unit tests. */
    static TtsTemplateEntity persisted(InvocationOnMock invocation) {
        return invocation.getArgument(0);
    }
}

package com.shivang.obd.tts;

import static com.shivang.obd.tts.TtsTemplateTestSupport.approvedGlobal;
import static com.shivang.obd.tts.TtsTemplateTestSupport.authenticatedUser;
import static com.shivang.obd.tts.TtsTemplateTestSupport.pendingTenant;
import static com.shivang.obd.tts.TtsTemplateTestSupport.tenantA;
import static com.shivang.obd.tts.TtsTemplateTestSupport.tenantB;
import static com.shivang.obd.tts.TtsTemplateTestSupport.tenantTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.shivang.obd.authz.AuthorizationService;
import com.shivang.obd.common.exception.BusinessException;
import com.shivang.obd.common.exception.ForbiddenException;
import com.shivang.obd.common.exception.ResourceNotFoundException;
import com.shivang.obd.security.CurrentUserProvider;
import com.shivang.obd.tenant.TenantEntity;
import com.shivang.obd.tenant.TenantRepository;
import com.shivang.obd.tts.dto.CreateTtsTemplateRequest;
import com.shivang.obd.tts.dto.TtsTemplateResponse;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;

/**
 * VB-5D scope-governance unit matrix for {@link TtsTemplateService}.
 * Authorization is passed through (module tests exercise rules, not the
 * capability store); a stubbed repository feeds both lookup and
 * usability paths.
 */
class TtsTemplateServiceTest {

    private TtsTemplateRepository repository;
    private AuthorizationService authorizationService;
    private TtsTemplateService service;

    @BeforeEach
    void setUp() {
        repository = org.mockito.Mockito.mock(TtsTemplateRepository.class);
        // Emulates the real AuthorizationService scope semantics: only
        // PLATFORM-scope callers satisfy platformWide(); scoped checks
        // pass (capability-store behavior is out of scope here).
        authorizationService = new AuthorizationService(List.of(), null, null, null, List.of()) {
            @Override
            public void requireCapability(
                java.util.UUID userId, String capabilityKey, com.shivang.obd.authz.AccessCheck target
            ) {
                var ctx = com.shivang.obd.authz.context.OrganizationContextHolder.current().orElse(null);
                boolean callerIsPlatform =
                    ctx == null || (ctx.tenantId() == null && ctx.resellerId() == null);
                boolean platformWideRequested = target == null
                    || (target.tenantId() == null && target.resellerId() == null);
                if (platformWideRequested && !callerIsPlatform) {
                    throw new ForbiddenException();
                }
            }
        };
        var currentUserProvider = org.mockito.Mockito.mock(CurrentUserProvider.class);
        when(currentUserProvider.current()).thenReturn(Optional.of(authenticatedUser()));
        service = new TtsTemplateService(
            repository, authorizationService, currentUserProvider,
            new TtsTemplateMapper(), org.mockito.Mockito.mock(TenantRepository.class));
    }

    @AfterEach
    void tearDown() {
        com.shivang.obd.authz.context.OrganizationContextHolder.clear();
    }

    // === G: GLOBAL governance ===

    @Test
    void g1_platformCreatesGlobal_approvedTenantless() {
        com.shivang.obd.authz.context.OrganizationContextHolder.setAuthenticated(
            TtsTemplateTestSupport.USER_ID, null, null);
        when(repository.save(any())).thenAnswer(TtsTemplateTestSupport::persisted);
        CreateTtsTemplateRequest request = new CreateTtsTemplateRequest(
            "Global promo", null, "Hello {{name}}.",
            List.of(new TtsTemplateVariable("name", "STRING", true)),
            null, TtsTemplateScope.GLOBAL);

        TtsTemplateResponse response = service.create(request).data();

        assertThat(response.scope()).isEqualTo(TtsTemplateScope.GLOBAL);
        assertThat(response.tenantId()).isNull();
        assertThat(response.status()).isEqualTo(TtsTemplateStatus.APPROVED);
    }

    @Test
    void g2_tenantCallerCannotCreateGlobal() {
        com.shivang.obd.authz.context.OrganizationContextHolder.setAuthenticated(
            TtsTemplateTestSupport.USER_ID, tenantA(), null);
        CreateTtsTemplateRequest request = new CreateTtsTemplateRequest(
            "Global promo", null, "Hello.", List.of(), null, TtsTemplateScope.GLOBAL);

        assertThatThrownBy(() -> service.create(request))
            .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void g2b_globalWithTenantIdRejected() {
        com.shivang.obd.authz.context.OrganizationContextHolder.setAuthenticated(
            TtsTemplateTestSupport.USER_ID, null, null);
        CreateTtsTemplateRequest request = new CreateTtsTemplateRequest(
            "Global promo", null, "Hello.", List.of(), tenantA(), TtsTemplateScope.GLOBAL);

        assertThatThrownBy(() -> service.create(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("platform-owned");
    }

    @Test
    void g3_tenantCannotReadPendingGlobal() {
        com.shivang.obd.authz.context.OrganizationContextHolder.setAuthenticated(
            TtsTemplateTestSupport.USER_ID, tenantA(), null);
        TtsTemplateEntity globalPending = approvedGlobal();
        globalPending.setStatus(TtsTemplateStatus.PENDING_APPROVAL);
        when(repository.findByIdAndDeletedAtIsNull(TtsTemplateTestSupport.GLOBAL_ID))
            .thenReturn(Optional.of(globalPending));

        assertThatThrownBy(() -> service.getById(TtsTemplateTestSupport.GLOBAL_ID))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void g3b_tenantReadsApprovedGlobal() {
        com.shivang.obd.authz.context.OrganizationContextHolder.setAuthenticated(
            TtsTemplateTestSupport.USER_ID, tenantA(), null);
        when(repository.findByIdAndDeletedAtIsNull(TtsTemplateTestSupport.GLOBAL_ID))
            .thenReturn(Optional.of(approvedGlobal()));

        TtsTemplateResponse response = service.getById(TtsTemplateTestSupport.GLOBAL_ID).data();

        assertThat(response.scope()).isEqualTo(TtsTemplateScope.GLOBAL);
    }

    @Test
    void g4_tenantCannotMutateGlobal() {
        com.shivang.obd.authz.context.OrganizationContextHolder.setAuthenticated(
            TtsTemplateTestSupport.USER_ID, tenantA(), null);
        when(repository.findByIdAndDeletedAtIsNull(TtsTemplateTestSupport.GLOBAL_ID))
            .thenReturn(Optional.of(approvedGlobal()));

        // findVisible lets the tenant see the APPROVED GLOBAL row; the
        // platform-wide manage gate must then reject the mutation.
        assertThatThrownBy(() -> service.update(
                TtsTemplateTestSupport.GLOBAL_ID,
                new com.shivang.obd.tts.dto.UpdateTtsTemplateRequest(
                    "Renamed", null, "Hello.", List.of())))
            .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.delete(TtsTemplateTestSupport.GLOBAL_ID))
            .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.reject(TtsTemplateTestSupport.GLOBAL_ID))
            .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void g4b_platformCanUpdateAndDeleteGlobal() {
        com.shivang.obd.authz.context.OrganizationContextHolder.setAuthenticated(
            TtsTemplateTestSupport.USER_ID, null, null);
        TtsTemplateEntity global = approvedGlobal();
        when(repository.findByIdAndDeletedAtIsNull(TtsTemplateTestSupport.GLOBAL_ID))
            .thenReturn(Optional.of(global));
        when(repository.save(any())).thenAnswer(TtsTemplateTestSupport::persisted);

        service.update(TtsTemplateTestSupport.GLOBAL_ID,
            new com.shivang.obd.tts.dto.UpdateTtsTemplateRequest(
                "Renamed", null, global.getTemplateText(),
                List.of(new TtsTemplateVariable("name", "STRING", true))));
        service.delete(TtsTemplateTestSupport.GLOBAL_ID);

        assertThat(global.getDeletedAt()).isNotNull();
    }

    @Test
    void g4c_globalEditDowngradesApproval() {
        com.shivang.obd.authz.context.OrganizationContextHolder.setAuthenticated(
            TtsTemplateTestSupport.USER_ID, null, null);
        TtsTemplateEntity global = approvedGlobal();
        when(repository.findByIdAndDeletedAtIsNull(TtsTemplateTestSupport.GLOBAL_ID))
            .thenReturn(Optional.of(global));
        when(repository.save(any())).thenAnswer(TtsTemplateTestSupport::persisted);

        service.update(TtsTemplateTestSupport.GLOBAL_ID,
            new com.shivang.obd.tts.dto.UpdateTtsTemplateRequest(
                "Renamed", null, "Changed text.", List.of()));

        assertThat(global.getStatus()).isEqualTo(TtsTemplateStatus.PENDING_APPROVAL);
    }

    // === A: approve/reject transitions ===

    @Test
    void a1_tenantAdminApprovesOwnTenantTemplate() {
        com.shivang.obd.authz.context.OrganizationContextHolder.setAuthenticated(
            TtsTemplateTestSupport.USER_ID, tenantA(), null);
        TtsTemplateEntity own = tenantTemplate(tenantA());
        when(repository.findByIdAndDeletedAtIsNull(own.getId())).thenReturn(Optional.of(own));
        when(repository.save(any())).thenAnswer(TtsTemplateTestSupport::persisted);

        TtsTemplateResponse response = service.approve(own.getId()).data();

        assertThat(response.status()).isEqualTo(TtsTemplateStatus.APPROVED);
        assertThat(response.scope()).isEqualTo(TtsTemplateScope.TENANT);
    }

    @Test
    void a1b_tenantCannotApproveForeignTenantTemplate() {
        com.shivang.obd.authz.context.OrganizationContextHolder.setAuthenticated(
            TtsTemplateTestSupport.USER_ID, tenantB(), null);
        TtsTemplateEntity foreign = tenantTemplate(tenantA());
        when(repository.findByIdAndDeletedAtIsNull(foreign.getId()))
            .thenReturn(Optional.of(foreign));

        // Foreign-tenant rows 404-cloak before any authorization check.
        assertThatThrownBy(() -> service.approve(foreign.getId()))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void a1c_platformGatesGlobalTemplate() {
        com.shivang.obd.authz.context.OrganizationContextHolder.setAuthenticated(
            TtsTemplateTestSupport.USER_ID, null, null);
        TtsTemplateEntity global = approvedGlobal();
        global.setStatus(TtsTemplateStatus.PENDING_APPROVAL);
        when(repository.findByIdAndDeletedAtIsNull(TtsTemplateTestSupport.GLOBAL_ID))
            .thenReturn(Optional.of(global));
        when(repository.save(any())).thenAnswer(TtsTemplateTestSupport::persisted);

        TtsTemplateResponse response = service.approve(TtsTemplateTestSupport.GLOBAL_ID).data();

        assertThat(response.status()).isEqualTo(TtsTemplateStatus.APPROVED);
        assertThat(response.tenantId()).isNull();
    }

    @Test
    void a2_approveTwiceConflicts() {
        com.shivang.obd.authz.context.OrganizationContextHolder.setAuthenticated(
            TtsTemplateTestSupport.USER_ID, tenantA(), null);
        TtsTemplateEntity own = approvedTenant(tenantA());
        when(repository.findByIdAndDeletedAtIsNull(own.getId())).thenReturn(Optional.of(own));

        assertThatThrownBy(() -> service.approve(own.getId()))
            .isInstanceOf(com.shivang.obd.common.exception.ConflictException.class);
    }

    @Test
    void a2b_rejectAfterApproveRevokesApproval() {
        com.shivang.obd.authz.context.OrganizationContextHolder.setAuthenticated(
            TtsTemplateTestSupport.USER_ID, tenantA(), null);
        TtsTemplateEntity own = approvedTenant(tenantA());
        when(repository.findByIdAndDeletedAtIsNull(own.getId())).thenReturn(Optional.of(own));
        when(repository.save(any())).thenAnswer(TtsTemplateTestSupport::persisted);

        // APPROVED → REJECTED is a legitimate revocation; only a repeated
        // same-target transition conflicts (a2).
        TtsTemplateResponse response = service.reject(own.getId()).data();

        assertThat(response.status()).isEqualTo(TtsTemplateStatus.REJECTED);
    }

    @Test
    void a3_approveResumesApprovalAfterContentEdit() {
        com.shivang.obd.authz.context.OrganizationContextHolder.setAuthenticated(
            TtsTemplateTestSupport.USER_ID, tenantA(), null);
        TtsTemplateEntity own = approvedTenant(tenantA());
        when(repository.findByIdAndDeletedAtIsNull(own.getId())).thenReturn(Optional.of(own));
        when(repository.save(any())).thenAnswer(TtsTemplateTestSupport::persisted);

        service.update(own.getId(), new com.shivang.obd.tts.dto.UpdateTtsTemplateRequest(
            "Still fine", null, "Brand new text.", List.of()));

        assertThat(own.getStatus()).isEqualTo(TtsTemplateStatus.PENDING_APPROVAL);
    }

    // === B: tenant boundary ===

    @Test
    void b1_tenantCannotReadForeignTenantTemplate() {
        com.shivang.obd.authz.context.OrganizationContextHolder.setAuthenticated(
            TtsTemplateTestSupport.USER_ID, tenantB(), null);
        TtsTemplateEntity foreign = tenantTemplate(tenantA());
        when(repository.findByIdAndDeletedAtIsNull(foreign.getId()))
            .thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.getById(foreign.getId()))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void b1b_tenantCannotDeleteForeignTenantTemplate() {
        com.shivang.obd.authz.context.OrganizationContextHolder.setAuthenticated(
            TtsTemplateTestSupport.USER_ID, tenantB(), null);
        TtsTemplateEntity foreign = tenantTemplate(tenantA());
        when(repository.findByIdAndDeletedAtIsNull(foreign.getId()))
            .thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.delete(foreign.getId()))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    // === helpers ===

    private static TtsTemplateEntity approvedTenant(UUID tenantId) {
        TtsTemplateEntity entity = tenantTemplate(tenantId);
        entity.setStatus(TtsTemplateStatus.APPROVED);
        return entity;
    }
}

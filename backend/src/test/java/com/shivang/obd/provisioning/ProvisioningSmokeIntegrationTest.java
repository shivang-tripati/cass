package com.shivang.obd.provisioning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.shivang.obd.ObdApplication;
import com.shivang.obd.authz.AccessCheck;
import com.shivang.obd.authz.AuthorizationService;
import com.shivang.obd.authz.home.OrganizationalHomeEntity;
import com.shivang.obd.authz.home.OrganizationalHomeRepository;
import com.shivang.obd.authz.home.OrganizationalHomeType;
import com.shivang.obd.account.UserService;
import com.shivang.obd.account.dto.UserResponse;
import com.shivang.obd.common.api.response.ApiResponse;
import com.shivang.obd.common.exception.BusinessException;
import com.shivang.obd.common.exception.ConflictException;
import com.shivang.obd.common.exception.ForbiddenException;
import com.shivang.obd.identity.CredentialType;
import com.shivang.obd.identity.UserCredentialRepository;
import com.shivang.obd.identity.UserRepository;
import com.shivang.obd.reseller.ResellerMembershipRepository;
import com.shivang.obd.reseller.ResellerProvisioningService;
import com.shivang.obd.reseller.ResellerService;
import com.shivang.obd.reseller.dto.AdminAccountInput;
import com.shivang.obd.reseller.dto.CreateResellerRequest;
import com.shivang.obd.reseller.dto.ResellerResponse;
import com.shivang.obd.security.AuthenticationService;
import com.shivang.obd.security.ChangePasswordRequest;
import com.shivang.obd.security.AuthSessionResult;
import com.shivang.obd.security.ChangePasswordRequest;
import com.shivang.obd.security.LoginRequest;
import com.shivang.obd.security.PasswordChangeService;
import com.shivang.obd.tenant.TenantMembershipRepository;
import com.shivang.obd.tenant.TenantProvisioningService;
import com.shivang.obd.tenant.TenantService;
import com.shivang.obd.tenant.dto.CreateAgentRequest;
import com.shivang.obd.tenant.dto.CreateTenantRequest;
import com.shivang.obd.tenant.dto.TenantAdminInput;
import com.shivang.obd.tenant.dto.TenantResponse;
import com.shivang.obd.tenant.dto.TenantSignupRequest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Focused end-to-end smoke of the Phase 2E provisioning lifecycle against
 * the live dev PostgreSQL/Redis (mirrors the manual smoke matrix):
 * bootstrap, reseller/tenant/agent provisioning, hierarchy boundaries,
 * login and password change, duplicate rejection.
 */
@SpringBootTest(classes = ObdApplication.class, properties = {
    "spring.datasource.url=jdbc:postgresql://localhost:5432/obd",
    "spring.datasource.username=obd_user",
    "spring.datasource.password=obd_password",
    "obd.security.jwt.secret=obd-test-secret-0123456789abcdef0123456789abcdef",
    // Keep in sync with BOOTSTRAP_ADMIN_PASSWORD below; the persisted bootstrap
    // credential is aligned to this value in @BeforeEach (see
    // alignBootstrapCredentialForDeterministicLogin).
    "obd.bootstrap.super-admin.email=admin@obd.local",
    "obd.bootstrap.super-admin.password=BootStrap!2026Adm1n",
    "obd.bootstrap.super-admin.display-name=Platform Administrator"
})
@ActiveProfiles("dev")
@Transactional
class ProvisioningSmokeIntegrationTest {

    private static final String BOOTSTRAP_ADMIN_EMAIL = "admin@obd.local";
    private static final String BOOTSTRAP_ADMIN_PASSWORD = "BootStrap!2026Adm1n";
    private static final String INITIAL_PASSWORD = "Prov!Smoke2026Pwd";

    @Autowired ResellerService resellerService;
    @Autowired ResellerProvisioningService resellerProvisioning;
    @Autowired TenantService tenantService;
    @Autowired TenantProvisioningService tenantProvisioning;
    @Autowired UserService userService;
    @Autowired AuthenticationService authenticationService;
    @Autowired PasswordChangeService passwordChangeService;
    @Autowired AuthorizationService authorizationService;
    @Autowired UserRepository userRepository;
    @Autowired UserCredentialRepository credentialRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired OrganizationalHomeRepository homeRepository;
    @Autowired ResellerMembershipRepository resellerMembershipRepository;
    @Autowired TenantMembershipRepository tenantMembershipRepository;
    @Autowired com.shivang.obd.reseller.ResellerRepository resellerRepository;

    private UUID superAdminId;
    private UUID resellerId;
    private UUID resellerAdminId;
    private UUID directTenantId;
    private UUID directTenantAdminId;

    @BeforeEach
    void actAsSuperAdmin() {
        superAdminId = userRepository.findByNormalizedEmail(BOOTSTRAP_ADMIN_EMAIL)
            .orElseThrow(() -> new IllegalStateException("Bootstrap SUPER_ADMIN missing"))
            .getId();
        alignBootstrapCredentialForDeterministicLogin();
        authenticate(superAdminId);
    }

    /**
     * The bootstrap SUPER_ADMIN row persists in the dev database across test
     * runs while {@code SuperAdminBootstrapper} is idempotent by design: it
     * never rotates the credential of an already-existing account (rotating
     * operator credentials from configuration silently would be a security
     * regression). The stored hash is therefore aligned here through the
     * production {@link PasswordEncoder} bean with the password configured
     * for this test class, so the login assertions below are deterministic
     * regardless of which bootstrap password previously seeded this database.
     */
    private void alignBootstrapCredentialForDeterministicLogin() {
        var credential = credentialRepository
            .findByUserIdAndIdentityType(superAdminId, CredentialType.PASSWORD)
            .orElseThrow(() -> new IllegalStateException("Bootstrap SUPER_ADMIN credential missing"));
        if (!passwordEncoder.matches(BOOTSTRAP_ADMIN_PASSWORD, credential.getCredentialHash())) {
            credential.setCredentialHash(passwordEncoder.encode(BOOTSTRAP_ADMIN_PASSWORD));
            credentialRepository.save(credential);
        }
    }

    @AfterEach
    void clearAuthContext() {
        SecurityContextHolder.clearContext();
        com.shivang.obd.authz.context.OrganizationContextHolder.clear();
    }

    // === 1-3: bootstrap + login + reseller provisioning ===

    @Test
    void bootstrapSuperAdminHasNoOrganizationalHomeAndCanLogIn() {
        var user = userRepository.findByNormalizedEmail(BOOTSTRAP_ADMIN_EMAIL).orElseThrow();

        assertThat(homeRepository.findByUserId(user.getId())).isEmpty();
        assertThat(resellerMembershipRepository.findByUserIdAndStatus(
            user.getId(), com.shivang.obd.common.lifecycle.LifecycleStatus.ACTIVE)).isEmpty();
        assertThat(tenantMembershipRepository.findByUserIdAndStatus(
            user.getId(), com.shivang.obd.common.lifecycle.LifecycleStatus.ACTIVE)).isEmpty();

        AuthSessionResult login = authenticationService.login(
            new LoginRequest(BOOTSTRAP_ADMIN_EMAIL, BOOTSTRAP_ADMIN_PASSWORD));
        assertThat(login.accessToken()).isNotBlank();
        assertThat(login.rawRefreshToken()).isNotBlank();
    }

    @Test
    void superAdminProvisionsResellerWithResellerAdmin() {
        CreateResellerRequest request = createResellerRequest("smoke-r-" + suffix());
        ApiResponse<ResellerResponse> response = resellerService.create(request);

        assertThat(response.data()).isNotNull();
        resellerId = response.data().id();

        var admin = userRepository.findByNormalizedEmail(
            request.admin().email().toLowerCase()).orElseThrow();
        resellerAdminId = admin.getId();
        assertThat(credentialOf(admin.getId())).isNotNull();

        OrganizationalHomeEntity home = homeRepository.findByUserId(admin.getId()).orElseThrow();
        assertThat(home.getHomeType()).isEqualTo(OrganizationalHomeType.RESELLER);
        assertThat(home.getOrganizationId()).isEqualTo(resellerId);

        assertThat(resellerMembershipRepository
            .existsByUserIdAndResellerIdAndStatus(admin.getId(), resellerId,
                com.shivang.obd.common.lifecycle.LifecycleStatus.ACTIVE)).isTrue();

        // RESELLER_ADMIN can now log in.
        AuthSessionResult adminLogin = authenticationService.login(
            new LoginRequest(request.admin().email(), request.admin().password()));
        assertThat(adminLogin.accessToken()).isNotBlank();
    }

    // === 4-7: tenant flows ===

    @Test
    void superAdminCreatesDirectTenantWithoutReseller() {
        CreateTenantRequest request = createTenantRequest(null);
        TenantResponse tenant = tenantService.create(request).data();

        assertThat(tenant.resellerId()).isNull();
        assertTenantAdminWiring(tenant.id(), request);
    }

    @Test
    void superAdminCreatesTenantUnderExistingReseller() {
        provisionResellerFixture();
        CreateTenantRequest request = createTenantRequest(resellerId);
        TenantResponse tenant = tenantService.create(request).data();

        assertThat(tenant.resellerId()).isEqualTo(resellerId);
        assertTenantAdminWiring(tenant.id(), request);

        // Hierarchy: reseller admin can read the tenant inside its scope.
        authenticate(resellerAdminId);
        setContext(resellerAdminId, tenant.id(), resellerId);
        assertThat(catchThrowable(() -> tenantService.getById(tenant.id())))
            .doesNotThrowAnyException();
    }

    @Test
    void resellerAdminCreatesTenantOnlyInsideOwnHierarchy() {
        provisionResellerFixture();
        authenticate(resellerAdminId);
        setContext(resellerAdminId, null, resellerId);

        CreateTenantRequest request = createTenantRequest(null);
        TenantResponse tenant = tenantService.create(request).data();
        assertThat(tenant.resellerId()).isEqualTo(resellerId);
        assertTenantAdminWiring(tenant.id(), request);
    }

    @Test
    void resellerAdminCannotCreateTenantUnderForeignReseller() {
        provisionResellerFixture();
        UUID foreignResellerId = resellerService.create(createResellerRequest("smoke-fr-" + suffix()))
            .data().id();

        authenticate(resellerAdminId);
        setContext(resellerAdminId, null, resellerId);

        CreateTenantRequest spoofed = new CreateTenantRequest(
            "Spoofed Tenant", "spoof-t-" + suffix(), foreignResellerId,
            new TenantAdminInput("spoof+" + suffix() + "@obd.test", INITIAL_PASSWORD, "Spoof Admin"));

        Throwable thrown = catchThrowable(() -> tenantService.create(spoofed));
        assertThat(thrown).isInstanceOf(ForbiddenException.class);

        // Nothing was created: no tenant with that slug exists.
        var tenantRepoData = userRepository.findByNormalizedEmail(
            spoofed.admin().email().toLowerCase());
        assertThat(tenantRepoData).isEmpty();
    }

    @Test
    void resellerAdminCannotAccessForeignReseller() {
        provisionResellerFixture();
        UUID foreignResellerId = resellerService.create(createResellerRequest("smoke-xr-" + suffix()))
            .data().id();

        authenticate(resellerAdminId);
        setContext(resellerAdminId, null, resellerId);

        assertThat(catchThrowable(() -> resellerService.getById(foreignResellerId)))
            .isInstanceOf(ForbiddenException.class);

        var page = resellerListForCurrentCaller();
        assertThat(page).allMatch(r -> r.id().equals(resellerId));
    }

    // === 10-11: agent provisioning and restrictions ===

    @Test
    void superAdminCreatesAgentRestrictedToSuperAdminOnly() {
        provisionResellerFixture();
        CreateTenantRequest request = createTenantRequest(resellerId);
        TenantResponse tenant = tenantService.create(request).data();
        var tenantAdmin = userRepository.findByNormalizedEmail(
            request.admin().email().toLowerCase()).orElseThrow();
        UUID tenantAdminId = tenantAdmin.getId();

        String agentEmail = "agent+" + suffix() + "@obd.test";
        TenantResponse agentResult = tenantService.createAgent(
            tenant.id(),
            new CreateAgentRequest(agentEmail, INITIAL_PASSWORD, "Smoke Agent")).data();
        assertThat(agentResult.id()).isEqualTo(tenant.id());

        var agent = userRepository.findByNormalizedEmail(agentEmail).orElseThrow();
        assertThat(homeRepository.findByUserId(agent.getId()).orElseThrow().getOrganizationId())
            .isEqualTo(tenant.id());

        // AGENT cannot access tenant-admin functionality (no TENANT_MANAGE capability).
        authenticate(agent.getId());
        setContext(agent.getId(), tenant.id(), null);
        assertThat(authorizationService.hasCapability(
            agent.getId(), "TENANT_MANAGE", AccessCheck.forTenant(tenant.id()))).isFalse();
        assertThat(catchThrowable(() -> tenantService.update(
            tenant.id(), new com.shivang.obd.tenant.dto.UpdateTenantRequest("Renamed"))))
            .isInstanceOf(ForbiddenException.class);

        // TENANT_ADMIN holds USER_MANAGE but only tenant-scoped: cannot create agents.
        authenticate(tenantAdminId);
        setContext(tenantAdminId, tenant.id(), null);
        assertThat(authorizationService.hasCapability(
            tenantAdminId, "USER_MANAGE", AccessCheck.platformWide())).isFalse();
        assertThat(catchThrowable(() -> tenantService.createAgent(
            tenant.id(),
            new CreateAgentRequest("escalated+" + suffix() + "@obd.test", INITIAL_PASSWORD, "X"))))
            .isInstanceOf(ForbiddenException.class);

        // SUPER_ADMIN path still works for a second agent.
        authenticate(superAdminId);
        setContext(superAdminId, null, null);
        TenantResponse secondAgent = tenantService.createAgent(
            tenant.id(),
            new CreateAgentRequest("agent2+" + suffix() + "@obd.test", INITIAL_PASSWORD, "A2"))
            .data();
        assertThat(secondAgent.id()).isEqualTo(tenant.id());
    }

    // === 12-14: password change ===

    @Test
    void userChangesPasswordOldFailsNewSucceeds() {
        provisionResellerFixture();
        authenticate(resellerAdminId);

        String newPassword = "Rotated!2026PwdNew";
        passwordChangeService.changePassword(
            new ChangePasswordRequest(INITIAL_PASSWORD, newPassword));

        // Old password must fail.
        Throwable oldRejected = catchThrowable(() -> passwordChangeService.changePassword(
            new ChangePasswordRequest(INITIAL_PASSWORD, "Whatever!2026Pwd")));
        assertThat(oldRejected).isInstanceOf(BusinessException.class)
            .hasMessageContaining("Invalid email or password.");

        // New password succeeds on the stored hash and at login.
        var admin = userRepository.findById(resellerAdminId).orElseThrow();
        var credential = credentialOf(admin.getId());
        assertThat(org.springframework.security.crypto.factory.PasswordEncoderFactories
            .createDelegatingPasswordEncoder().matches(newPassword, credential.getCredentialHash()))
            .isTrue();

        AuthSessionResult reLogin = authenticationService.login(
            new LoginRequest(admin.getEmail(), newPassword));
        assertThat(reLogin.accessToken()).isNotBlank();
    }

    // === 20-22: duplicate rejection ===

    @Test
    void duplicateEmailSlugAreRejected() {
        CreateResellerRequest first = createResellerRequest("smoke-dup-" + suffix());
        resellerService.create(first);

        // Duplicate email via another reseller signup.
        CreateResellerRequest sameEmail = new CreateResellerRequest(
            "Smoke Reseller dup2", "smoke-dup2-" + suffix(), null, null, null, null, null,
            new AdminAccountInput(first.admin().email(), INITIAL_PASSWORD, "Dup"));
        assertThat(catchThrowable(() -> resellerService.create(sameEmail)))
            .isInstanceOf(ConflictException.class);

        // Duplicate reseller slug.
        assertThat(catchThrowable(() -> resellerService.create(createResellerRequestWithSlug(
            first.slug())))).isInstanceOf(ConflictException.class);

        // Duplicate tenant slug.
        CreateTenantRequest tenantFirst = createTenantRequest(null);
        tenantService.create(tenantFirst);
        CreateTenantRequest tenantAgain = createTenantRequestWithSlug(tenantFirst.slug());
        assertThat(catchThrowable(() -> tenantService.create(tenantAgain)))
            .isInstanceOf(ConflictException.class);
    }

    // === self-signup ===

    @Test
    void anonymousSelfSignupCreatesResellerAndDirectTenant() {
        CreateResellerRequest resellerReq = createResellerRequest("smoke-signup-r-" + suffix());
        ResellerResponse signedUpReseller = resellerProvisioning.signup(resellerReq).data();
        assertThat(signedUpReseller.id()).isNotNull();
        var owner = userRepository.findByNormalizedEmail(
            resellerReq.admin().email().toLowerCase()).orElseThrow();
        assertThat(homeRepository.findByUserId(owner.getId()).orElseThrow()
            .getOrganizationId()).isEqualTo(signedUpReseller.id());

        TenantSignupRequest tenantReq = new TenantSignupRequest(
            "Self Signup Tenant", "smoke-signup-t-" + suffix(),
            new TenantAdminInput("selftenant+" + suffix() + "@obd.test", INITIAL_PASSWORD, "Self"));
        TenantResponse signedUpTenant = tenantProvisioning.signup(tenantReq).data();
        assertThat(signedUpTenant.resellerId()).isNull();
        var tOwner = userRepository.findByNormalizedEmail(
            tenantReq.admin().email().toLowerCase()).orElseThrow();
        assertThat(homeRepository.findByUserId(tOwner.getId()).orElseThrow())
            .satisfies(h -> {
                assertThat(h.getHomeType()).isEqualTo(OrganizationalHomeType.TENANT);
                assertThat(h.getOrganizationId()).isEqualTo(signedUpTenant.id());
            });
    }

    // === R1 regression: suspended organization fails closed ===

    @Test
    void suspendedResellerFailsClosedForItsAdminButNotForPlatform() {
        provisionResellerFixture();

        authenticate(resellerAdminId);
        setContext(resellerAdminId, null, resellerId);
        assertThat(authorizationService.hasCapability(
            resellerAdminId, "RESELLER_VIEW", AccessCheck.forReseller(resellerId))).isTrue();

        var reseller = resellerRepository.findById(resellerId).orElseThrow();
        reseller.setStatus(com.shivang.obd.common.lifecycle.LifecycleStatus.SUSPENDED);
        resellerRepository.save(reseller);

        // Reseller admin loses ALL capabilities derived from the suspended org.
        assertThat(authorizationService.hasCapability(
            resellerAdminId, "RESELLER_VIEW", AccessCheck.forReseller(resellerId))).isFalse();
        assertThat(catchThrowable(() -> resellerService.getById(resellerId)))
            .isInstanceOf(ForbiddenException.class);

        // PLATFORM authority is unaffected and keeps managing the org.
        authenticate(superAdminId);
        setContext(superAdminId, null, null);
        assertThat(catchThrowable(() -> resellerService.getById(resellerId)))
            .doesNotThrowAnyException();
    }

    // === user management scoping ===

    @Test
    void userListIsScopedToCallerBoundary() {
        provisionResellerFixture();
        CreateTenantRequest request = createTenantRequest(resellerId);
        TenantResponse tenant = tenantService.create(request).data();
        var tenantAdmin = userRepository.findByNormalizedEmail(
            request.admin().email().toLowerCase()).orElseThrow();

        // Tenant admin sees only users bound to its own tenant.
        authenticate(tenantAdmin.getId());
        setContext(tenantAdmin.getId(), tenant.id(), null);
        List<UserResponse> visible = userService.list(0, 50, new String[0], null, null).data();
        assertThat(visible).extracting(UserResponse::id)
            .containsExactlyInAnyOrder(tenantAdmin.getId());

        // Super admin sees everything including platform account.
        authenticate(superAdminId);
        setContext(superAdminId, null, null);
        List<UserResponse> all = userService.list(0, 100, new String[0], null, BOOTSTRAP_ADMIN_EMAIL.split("@")[0]).data();
        assertThat(all).anyMatch(u -> u.id().equals(superAdminId));
    }

    // === helpers ===

    private void provisionResellerFixture() {
        if (resellerId != null && resellerAdminId != null) {
            return;
        }
        CreateResellerRequest request = createResellerRequest("smoke-fx-" + suffix());
        ResellerResponse created = resellerService.create(request).data();
        resellerId = created.id();
        resellerAdminId = userRepository.findByNormalizedEmail(
            request.admin().email().toLowerCase()).orElseThrow().getId();

        authenticate(resellerAdminId);
        setContext(resellerAdminId, null, resellerId);
        CreateTenantRequest tenantRequest = createTenantRequest(null);
        directTenantId = tenantService.create(tenantRequest).data().id();
        directTenantAdminId = userRepository.findByNormalizedEmail(
            tenantRequest.admin().email().toLowerCase()).orElseThrow().getId();
        authenticate(superAdminId);
    }

    private void assertTenantAdminWiring(UUID tenantId, CreateTenantRequest request) {
        var admin = userRepository.findByNormalizedEmail(
            request.admin().email().toLowerCase()).orElseThrow();
        assertThat(credentialOf(admin.getId())).isNotNull();
        var home = homeRepository.findByUserId(admin.getId()).orElseThrow();
        assertThat(home.getHomeType()).isEqualTo(OrganizationalHomeType.TENANT);
        assertThat(home.getOrganizationId()).isEqualTo(tenantId);
        assertThat(tenantMembershipRepository.existsByUserIdAndTenantIdAndStatus(
            admin.getId(), tenantId, com.shivang.obd.common.lifecycle.LifecycleStatus.ACTIVE))
            .isTrue();
    }

    private List<ResellerResponse> resellerListForCurrentCaller() {
        return resellerService.list(0, 50, new String[0], null, null).data();
    }

    private void authenticate(UUID userId) {
        AuthenticatedPrincipalHolder.set(userId);
    }

    private void setContext(UUID userId, UUID tenantId, UUID resellerId) {
        com.shivang.obd.authz.context.OrganizationContextHolder.setAuthenticated(
            userId, tenantId, resellerId);
    }

    private com.shivang.obd.identity.UserCredentialEntity credentialOf(UUID userId) {
        return credentialRepository.findByUserIdAndIdentityType(userId, CredentialType.PASSWORD)
            .orElse(null);
    }

    private static final class AuthenticatedPrincipalHolder {
        static void set(UUID userId) {
            var principal = new com.shivang.obd.security.AuthenticatedUser(
                userId, userId + "@ctx.test", null);
            SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                    principal, null, AuthorityUtils.NO_AUTHORITIES));
        }
    }

    private static CreateResellerRequest createResellerRequest(String slug) {
        return createResellerRequestWithSlug(slug);
    }

    private static CreateResellerRequest createResellerRequestWithSlug(String slug) {
        return new CreateResellerRequest(
            "Smoke Reseller " + slug,
            slug,
            "Smoke Reseller Display",
            "support+" + slug + "@obd.test",
            null,
            null,
            "#0055ff",
            new AdminAccountInput("ra+" + slug + "@obd.test", INITIAL_PASSWORD, "RA " + slug));
    }

    private static CreateTenantRequest createTenantRequest(UUID resellerIdValue) {
        String slug = "smoke-t-" + suffix();
        return new CreateTenantRequest(
            "Smoke Tenant " + slug,
            slug,
            resellerIdValue,
            new TenantAdminInput("ta+" + slug + "@obd.test", INITIAL_PASSWORD, "TA " + slug));
    }

    private static CreateTenantRequest createTenantRequestWithSlug(String slug) {
        return new CreateTenantRequest(
            "Smoke Tenant " + slug,
            slug,
            null,
            new TenantAdminInput("ta+" + slug + "@obd.test", INITIAL_PASSWORD, "TA " + slug));
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}

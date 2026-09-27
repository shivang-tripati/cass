package com.shivang.obd.tenant;

import com.shivang.obd.authz.RoleAssignmentPolicy;
import com.shivang.obd.authz.RoleEntity;
import com.shivang.obd.authz.RoleRepository;
import com.shivang.obd.authz.Scope;
import com.shivang.obd.authz.home.OrganizationalHomeService;
import com.shivang.obd.common.api.response.ApiResponse;
import com.shivang.obd.common.api.response.ResponseFactory;
import com.shivang.obd.common.exception.ConflictException;
import com.shivang.obd.common.exception.ForbiddenException;
import com.shivang.obd.common.exception.ResourceNotFoundException;
import com.shivang.obd.identity.UserAccountService;
import com.shivang.obd.identity.UserEntity;
import com.shivang.obd.reseller.ResellerRepository;
import com.shivang.obd.tenant.dto.CreateAgentRequest;
import com.shivang.obd.tenant.dto.CreateTenantRequest;
import com.shivang.obd.tenant.dto.TenantResponse;
import com.shivang.obd.tenant.dto.TenantSignupRequest;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional provisioning of tenants and tenant-bound accounts.
 *
 * <p>Every operation commits atomically: tenant + admin user + credential +
 * TENANT organizational home + membership + role. The system never keeps a
 * tenant without its admin or a home without its matching membership.</p>
 *
 * <p>Roles are fixed per provisioning kind (TENANT_ADMIN, AGENT); client
 * input can never select an arbitrary role or organization. The V8/V11
 * database triggers are the final integrity boundary.</p>
 */
@Service
public class TenantProvisioningService {

    static final String TENANT_ADMIN_ROLE_KEY = "TENANT_ADMIN";
    static final String AGENT_ROLE_KEY = "AGENT";

    private final TenantRepository tenantRepository;
    private final ResellerRepository resellerRepository;
    private final UserAccountService userAccountService;
    private final OrganizationalHomeService organizationalHomeService;
    private final TenantMembershipRepository membershipRepository;
    private final RoleAssignmentPolicy roleAssignmentPolicy;
    private final RoleRepository roleRepository;
    private final com.shivang.obd.security.PasswordPolicy passwordPolicy;
    private final TenantMapper mapper;

    public TenantProvisioningService(
        TenantRepository tenantRepository,
        ResellerRepository resellerRepository,
        UserAccountService userAccountService,
        OrganizationalHomeService organizationalHomeService,
        TenantMembershipRepository membershipRepository,
        RoleAssignmentPolicy roleAssignmentPolicy,
        RoleRepository roleRepository,
        com.shivang.obd.security.PasswordPolicy passwordPolicy,
        TenantMapper mapper
    ) {
        this.tenantRepository = tenantRepository;
        this.resellerRepository = resellerRepository;
        this.userAccountService = userAccountService;
        this.organizationalHomeService = organizationalHomeService;
        this.membershipRepository = membershipRepository;
        this.roleAssignmentPolicy = roleAssignmentPolicy;
        this.roleRepository = roleRepository;
        this.passwordPolicy = passwordPolicy;
        this.mapper = mapper;
    }

    /**
     * SUPER_ADMIN path: direct tenant (resellerId NULL) or under any chosen
     * reseller.
     */
    @Transactional
    public ApiResponse<TenantResponse> provisionForSuperAdmin(CreateTenantRequest request) {
        UUID resellerId = null;
        if (request.resellerId() != null) {
            var reseller = resellerRepository.findByIdAndDeletedAtIsNull(request.resellerId())
                .orElseThrow(() -> new ResourceNotFoundException("Reseller not found"));
            if (reseller.getStatus() != com.shivang.obd.common.lifecycle.LifecycleStatus.ACTIVE) {
                throw new ConflictException("Reseller is not active.");
            }
            resellerId = reseller.getId();
        }
        return provision(request, resellerId);
    }

    /**
     * RESELLER_ADMIN path: the target reseller ALWAYS comes from the
     * caller's own organizational home. A client-supplied resellerId that
     * deviates from the server-derived boundary is rejected (403).
     */
    @Transactional
    public ApiResponse<TenantResponse> provisionUnderCallerReseller(
        UUID callerResellerId, CreateTenantRequest request
    ) {
        if (request.resellerId() != null && !request.resellerId().equals(callerResellerId)) {
            throw new ForbiddenException();
        }
        return provision(request, callerResellerId);
    }

    /** Anonymous direct-tenant self-signup; reseller_id stays NULL. */
    @Transactional
    public ApiResponse<TenantResponse> signup(TenantSignupRequest request) {
        var createRequest = new CreateTenantRequest(
            request.name(), request.slug(), null, request.admin());
        return provision(createRequest, null);
    }

    /**
     * SUPER_ADMIN-only AGENT provisioning: tenant-bound account with the
     * ASSIGNED-scoped AGENT role — never TENANT_ADMIN capabilities.
     */
    @Transactional
    public ApiResponse<TenantResponse> createAgent(UUID tenantId, CreateAgentRequest request) {
        var tenant = tenantRepository.findByIdAndDeletedAtIsNull(tenantId)
            .orElseThrow(() -> new ResourceNotFoundException("Tenant not found"));
        if (tenant.getStatus() != com.shivang.obd.common.lifecycle.LifecycleStatus.ACTIVE) {
            throw new ConflictException("Tenant is not active.");
        }

        provisionTenantUser(
            request.email(), request.password(), request.displayName(),
            tenant.getId(), activeRole(AGENT_ROLE_KEY));

        return ResponseFactory.created(new TenantResponse(
            tenant.getId(), tenant.getName(), tenant.getSlug(), tenant.getStatus(),
            tenant.getResellerId(), tenant.getCreatedAt(), tenant.getUpdatedAt()));
    }

    // === internal ===

    private ApiResponse<TenantResponse> provision(CreateTenantRequest request, UUID resellerId) {
        ensureSlugAvailable(request.slug());
        var tenant = tenantRepository.save(mapper.toEntity(request, resellerId));
        provisionTenantUser(
            request.admin().email(), request.admin().password(), request.admin().displayName(),
            tenant.getId(), activeRole(TENANT_ADMIN_ROLE_KEY));
        return ResponseFactory.created(mapper.toResponse(tenant));
    }

    /**
     * Tenant-bound account: user + PASSWORD credential + TENANT home +
     * membership + role. Allowed role scopes are strictly TENANT (admins)
     * and ASSIGNED (agents); RESELLER/PLATFORM/OWN roles can never enter a
     * tenant membership.
     */
    private UserEntity provisionTenantUser(
        String email, String plaintextPassword, String displayName,
        UUID tenantId, RoleEntity role
    ) {
        passwordPolicy.validateNewPassword(plaintextPassword);
        var user = userAccountService.createUserWithPassword(email, plaintextPassword, displayName);
        var home = organizationalHomeService.createTenantHome(user.getId(), tenantId);
        roleAssignmentPolicy.requireAssignableToTenantMembership(
            user.getId(), role.getId(), Scope.TENANT, Scope.ASSIGNED);
        var membership = new TenantMembershipEntity();
        membership.setUserId(user.getId());
        membership.setTenantId(tenantId);
        membership.setRoleId(role.getId());
        membership.setOrganizationalHomeId(home.getId());
        membershipRepository.save(membership);
        return user;
    }

    private void ensureSlugAvailable(String slug) {
        if (tenantRepository.existsBySlug(slug)) {
            throw new ConflictException("A tenant with this slug already exists.");
        }
    }

    private RoleEntity activeRole(String key) {
        return roleRepository.findByKeyIgnoreCase(key)
            .filter(RoleEntity::isActive)
            .orElseThrow(() -> new IllegalStateException(
                "System role " + key + " is missing; run migrations."));
    }
}

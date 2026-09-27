package com.shivang.obd.reseller;

import com.shivang.obd.authz.RoleAssignmentPolicy;
import com.shivang.obd.authz.RoleEntity;
import com.shivang.obd.authz.RoleRepository;
import com.shivang.obd.authz.home.OrganizationalHomeService;
import com.shivang.obd.common.api.response.ApiResponse;
import com.shivang.obd.common.api.response.ResponseFactory;
import com.shivang.obd.common.exception.ConflictException;
import com.shivang.obd.identity.UserAccountService;
import com.shivang.obd.identity.UserEntity;
import com.shivang.obd.reseller.dto.CreateResellerRequest;
import com.shivang.obd.reseller.dto.ResellerResponse;
import com.shivang.obd.security.PasswordPolicy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional provisioning of a RESELLER together with its initial
 * RESELLER_ADMIN account. Either every step commits or nothing does: the
 * system never keeps a reseller without admin, a user without credential,
 * or a home without its matching membership.
 *
 * <p>The assigned role is fixed (RESELLER_ADMIN); clients can never select
 * an arbitrary role. The database triggers from V8/V11 act as the final
 * integrity boundary for home/membership/role consistency.</p>
 */
@Service
public class ResellerProvisioningService {

    static final String RESELLER_ADMIN_ROLE_KEY = "RESELLER_ADMIN";

    private final ResellerRepository resellerRepository;
    private final UserAccountService userAccountService;
    private final OrganizationalHomeService organizationalHomeService;
    private final ResellerMembershipRepository membershipRepository;
    private final RoleAssignmentPolicy roleAssignmentPolicy;
    private final RoleRepository roleRepository;
    private final PasswordPolicy passwordPolicy;
    private final ResellerMapper mapper;

    public ResellerProvisioningService(
        ResellerRepository resellerRepository,
        UserAccountService userAccountService,
        OrganizationalHomeService organizationalHomeService,
        ResellerMembershipRepository membershipRepository,
        RoleAssignmentPolicy roleAssignmentPolicy,
        RoleRepository roleRepository,
        PasswordPolicy passwordPolicy,
        ResellerMapper mapper
    ) {
        this.resellerRepository = resellerRepository;
        this.userAccountService = userAccountService;
        this.organizationalHomeService = organizationalHomeService;
        this.membershipRepository = membershipRepository;
        this.roleAssignmentPolicy = roleAssignmentPolicy;
        this.roleRepository = roleRepository;
        this.passwordPolicy = passwordPolicy;
        this.mapper = mapper;
    }

    /** Anonymous self-signup: creates reseller + its RESELLER_ADMIN owner. */
    @Transactional
    public ApiResponse<ResellerResponse> signup(CreateResellerRequest request) {
        return provision(request);
    }

    @Transactional
    ApiResponse<ResellerResponse> provisionForAuthenticatedAdmin(CreateResellerRequest request) {
        return provision(request);
    }

    private ApiResponse<ResellerResponse> provision(CreateResellerRequest request) {
        passwordPolicy.validateNewPassword(request.admin().password());
        ensureSlugAvailable(request.slug());
        ensureCustomDomainAvailable(request.customDomain());

        var reseller = resellerRepository.save(mapper.toEntity(request));

        UserEntity admin = userAccountService.createUserWithPassword(
            request.admin().email(), request.admin().password(), request.admin().displayName());

        var home = organizationalHomeService.createResellerHome(admin.getId(), reseller.getId());

        RoleEntity role = activeSystemRole();
        roleAssignmentPolicy.requireAssignableToResellerMembership(admin.getId(), role.getId());

        var membership = new ResellerMembershipEntity();
        membership.setUserId(admin.getId());
        membership.setResellerId(reseller.getId());
        membership.setRoleId(role.getId());
        membership.setOrganizationalHomeId(home.getId());
        membershipRepository.save(membership);

        return ResponseFactory.created(mapper.toResponse(reseller));
    }

    private void ensureSlugAvailable(String slug) {
        if (resellerRepository.existsBySlug(slug)) {
            throw new ConflictException("A reseller with this slug already exists.");
        }
    }

    private void ensureCustomDomainAvailable(String customDomain) {
        if (customDomain == null || customDomain.isBlank()) {
            return;
        }
        if (resellerRepository.findByCustomDomainIgnoreCase(customDomain.trim()).isPresent()) {
            throw new ConflictException("A reseller with this custom domain already exists.");
        }
    }

    private RoleEntity activeSystemRole() {
        return roleRepository.findByKeyIgnoreCase(RESELLER_ADMIN_ROLE_KEY)
            .filter(RoleEntity::isActive)
            .orElseThrow(() -> new IllegalStateException(
                "System role " + RESELLER_ADMIN_ROLE_KEY + " is missing; run migrations."));
    }
}

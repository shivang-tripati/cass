package com.shivang.obd.did;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shivang.obd.authz.AccessCheck;
import com.shivang.obd.authz.AuthorizationService;
import com.shivang.obd.authz.context.OrganizationContextHolder;
import com.shivang.obd.common.exception.BusinessException;
import com.shivang.obd.common.exception.ConflictException;
import com.shivang.obd.common.exception.ResourceNotFoundException;
import com.shivang.obd.common.lifecycle.LifecycleStatus;
import com.shivang.obd.did.dto.CreateDidRequest;
import com.shivang.obd.did.dto.UpdateDidRequest;
import com.shivang.obd.reseller.ResellerRepository;
import com.shivang.obd.security.AuthenticatedUser;
import com.shivang.obd.security.CurrentUserProvider;
import com.shivang.obd.tenant.TenantEntity;
import com.shivang.obd.tenant.TenantRepository;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Security-boundary protection for the new DID scoping model: scoped
 * lookups, reseller pool visibility, server-derived ownership on create,
 * duplicate handling and allocation consistency.
 */
class DidScopingServiceTest {

    private static final UUID USER_ID = UUID.fromString("dd000000-0000-4000-8000-000000000001");
    private static final UUID TENANT_A = UUID.fromString("aa000000-0000-4000-8000-00000000000a");
    private static final UUID TENANT_B = UUID.fromString("bb000000-0000-4000-8000-00000000000b");
    private static final UUID RESELLER_R = UUID.fromString("c0000000-0000-4000-8000-00000000000c");

    private DidRepository repository;
    private AuthorizationService authorizationService;
    private TenantRepository tenantRepository;
    private ResellerRepository resellerRepository;
    private DidService service;

    @BeforeEach
    void setUp() {
        repository = org.mockito.Mockito.mock(DidRepository.class);
        authorizationService = org.mockito.Mockito.mock(AuthorizationService.class);
        tenantRepository = org.mockito.Mockito.mock(TenantRepository.class);
        resellerRepository = org.mockito.Mockito.mock(ResellerRepository.class);
        CurrentUserProvider currentUserProvider = org.mockito.Mockito.mock(CurrentUserProvider.class);
        when(currentUserProvider.current())
            .thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "did-admin@test.local", null)));
        service = new DidService(repository, authorizationService, currentUserProvider,
            new DidMapper(), tenantRepository, resellerRepository);
    }

    @AfterEach
    void tearDown() {
        OrganizationContextHolder.clear();
    }

    private void asTenant(UUID tenantId) {
        OrganizationContextHolder.setAuthenticated(USER_ID, tenantId, null);
    }

    private void asReseller(UUID resellerId) {
        OrganizationContextHolder.setAuthenticated(USER_ID, null, resellerId);
    }

    @Nested
    class ScopedLookups {

        @Test
        void tenantCannotSeeForeignOrMissingDid() {
            asTenant(TENANT_A);
            UUID didId = UUID.randomUUID();
            when(repository.findByIdAndTenantIdAndDeletedAtIsNull(didId, TENANT_A))
                .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getById(didId))
                .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        void resellerSeesItsOwnPoolNumbers() {
            asReseller(RESELLER_R);
            DidEntity poolDid = poolDid(RESELLER_R);
            when(repository.findByIdAndDeletedAtIsNull(poolDid.getId()))
                .thenReturn(Optional.of(poolDid));

            var response = service.getById(poolDid.getId());

            assertThat(response.data().e164Number()).isEqualTo("+918012345678");
            verify(authorizationService).requireCapability(
                eq(USER_ID), eq("DID_VIEW"), any(AccessCheck.class));
        }

        @Test
        void resellerCannotSeeAnotherResellersAssignedDid() {
            asReseller(RESELLER_R);
            DidEntity foreign = assignedDid(TENANT_B, UUID.randomUUID());
            when(repository.findByIdAndDeletedAtIsNull(foreign.getId()))
                .thenReturn(Optional.of(foreign));
            when(tenantRepository.findAllByResellerIdAndStatus(RESELLER_R, LifecycleStatus.ACTIVE))
                .thenReturn(List.of(tenant(TENANT_A)));

            assertThatThrownBy(() -> service.getById(foreign.getId()))
                .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @Nested
    class Creation {

        @Test
        void tenantCallerGetsServerDerivedOwnershipIgnoringClientIds() {
            asTenant(TENANT_A);
            TenantEntity own = tenant(TENANT_A);
            own.setResellerId(RESELLER_R);
            when(tenantRepository.findByIdAndDeletedAtIsNull(TENANT_A)).thenReturn(Optional.of(own));
            when(repository.existsByE164NumberAndDeletedAtIsNull("+918001112233")).thenReturn(false);
            when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            CreateDidRequest request = new CreateDidRequest(
                "+918001112233", "91", "80", "Karnataka",
                NumberType.LANDLINE, "TATA",
                Set.of(DidCapability.VOICE_OUTBOUND),
                DidStatus.ACTIVE, AllocationState.ASSIGNED,
                UUID.randomUUID(), UUID.randomUUID());

            service.create(request);

            ArgumentCaptor<DidEntity> captor = ArgumentCaptor.forClass(DidEntity.class);
            verify(repository).save(captor.capture());
            DidEntity saved = captor.getValue();
            assertThat(saved.getTenantId()).isEqualTo(TENANT_A);
            assertThat(saved.getResellerId()).isEqualTo(RESELLER_R);
            verify(authorizationService).requireCapability(
                eq(USER_ID), eq("DID_MANAGE"), any(AccessCheck.class));
        }

        @Test
        void duplicateLiveNumberConflicts() {
            asTenant(TENANT_A);
            TenantEntity own = tenant(TENANT_A);
            when(tenantRepository.findByIdAndDeletedAtIsNull(TENANT_A)).thenReturn(Optional.of(own));
            when(repository.existsByE164NumberAndDeletedAtIsNull("+918001112233")).thenReturn(true);

            assertThatThrownBy(() -> service.create(createRequest()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already exists");
        }
    }

    @Nested
    class UpdateConsistency {

        @Test
        void assignedRequiresAnOwningTenant() {
            asReseller(RESELLER_R);
            DidEntity poolDid = poolDid(RESELLER_R);
            when(repository.findByIdAndDeletedAtIsNull(poolDid.getId()))
                .thenReturn(Optional.of(poolDid));

            UpdateDidRequest assignedWithoutTenant = new UpdateDidRequest(
                "91", "80", "Karnataka", NumberType.MOBILE, "AIRTEL",
                Set.of(DidCapability.VOICE_OUTBOUND), DidStatus.ACTIVE, AllocationState.ASSIGNED);

            assertThatThrownBy(() -> service.update(poolDid.getId(), assignedWithoutTenant))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("ASSIGNED");
        }
    }

    private CreateDidRequest createRequest() {
        return new CreateDidRequest(
            "+918001112233", "91", "80", "Karnataka",
            NumberType.LANDLINE, "TATA", Set.of(DidCapability.VOICE_OUTBOUND),
            null, AllocationState.ASSIGNED, null, null);
    }


    private DidEntity poolDid(UUID resellerId) {
        DidEntity did = new DidEntity();
        did.setId(UUID.randomUUID());
        did.setTenantId(null);
        did.setResellerId(resellerId);
        did.setE164Number("+918012345678");
        did.setCountryCode("91");
        did.setNumberType(NumberType.LANDLINE);
        did.setProvider("TATA");
        did.setStatus(DidStatus.ACTIVE);
        did.setAllocationState(AllocationState.AVAILABLE);
        return did;
    }

    private DidEntity assignedDid(UUID tenantId, UUID resellerId) {
        DidEntity did = poolDid(resellerId);
        did.setTenantId(tenantId);
        did.setAllocationState(AllocationState.ASSIGNED);
        return did;
    }

    private TenantEntity tenant(UUID id) {
        TenantEntity tenant = new TenantEntity();
        tenant.setId(id);
        tenant.setStatus(LifecycleStatus.ACTIVE);
        return tenant;
    }
}

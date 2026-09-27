package com.shivang.obd.did;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shivang.obd.authz.AccessCheck;
import com.shivang.obd.authz.AuthorizationService;
import com.shivang.obd.authz.context.OrganizationContextHolder;
import com.shivang.obd.common.exception.BusinessException;
import com.shivang.obd.common.exception.ConflictException;
import com.shivang.obd.did.dto.AssignDidRequest;
import com.shivang.obd.did.dto.AssignDidResponse;
import com.shivang.obd.reseller.ResellerEntity;
import com.shivang.obd.reseller.ResellerRepository;
import com.shivang.obd.security.CurrentUserProvider;
import com.shivang.obd.tenant.TenantEntity;
import com.shivang.obd.tenant.TenantRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * VB-5C unit tests: assignment/revocation flows, authorization boundaries,
 * provenance transitions, and conflict semantics. Repository conditional
 * UPDATEs are mocked to return 1 (won) unless a test simulates a lost
 * race; persistence-level behavior is proven by the PostgreSQL suite.
 */
@ExtendWith(MockitoExtension.class)
class DidAllocationServiceTest {

    private static final UUID USER_ID = UUID.fromString("dd000000-0000-4000-8000-000000000001");
    private static final UUID DID = UUID.fromString("dd000000-0000-4000-8000-0000000000d1");
    private static final UUID RESELLER_A = UUID.fromString("dd000000-0000-4000-8000-0000000000a1");
    private static final UUID RESELLER_B = UUID.fromString("dd000000-0000-4000-8000-0000000000a2");
    private static final UUID TENANT_A1 = UUID.fromString("dd000000-0000-4000-8000-0000000000b1");
    private static final UUID TENANT_B1 = UUID.fromString("dd000000-0000-4000-8000-0000000000b2");

    @Mock
    private DidRepository repository;
    @Mock
    private AuthorizationService authorizationService;
    @Mock
    private CurrentUserProvider currentUserProvider;
    @Mock
    private TenantRepository tenantRepository;
    @Mock
    private ResellerRepository resellerRepository;

    private DidService service;

    @BeforeEach
    void setUp() {
        service = new DidService(repository, authorizationService, currentUserProvider,
            new DidMapper(), tenantRepository, resellerRepository);
        lenient().when(currentUserProvider.current()).thenReturn(Optional.of(
            new com.shivang.obd.security.AuthenticatedUser(USER_ID, "admin@test.local", null)));
    }

    @AfterEach
    void tearDown() {
        OrganizationContextHolder.clear();
    }

    private void platformScope() {
        OrganizationContextHolder.setAuthenticated(USER_ID, null, null);
    }

    private void resellerScope(UUID resellerId) {
        OrganizationContextHolder.setAuthenticated(USER_ID, null, resellerId);
    }

    private void tenantScope(UUID tenantId) {
        OrganizationContextHolder.setAuthenticated(USER_ID, tenantId, null);
    }

    private static DidEntity did(AllocationState state, UUID tenantId, UUID resellerId, AllocationSource source) {
        DidEntity d = new DidEntity();
        d.setId(DID);
        d.setE164Number("+919800000001");
        d.setCountryCode("+91");
        d.setNumberType(NumberType.MOBILE);
        d.setProvider("TATA");
        d.setStatus(DidStatus.ACTIVE);
        d.setAllocationState(state);
        d.setAllocationSource(source);
        d.setTenantId(tenantId);
        d.setResellerId(resellerId);
        return d;
    }

    private void resellerTargetsActive(UUID resellerId) {
        ResellerEntity r = new ResellerEntity();
        r.setId(resellerId);
        r.setStatus(com.shivang.obd.common.lifecycle.LifecycleStatus.ACTIVE);
        lenient().when(resellerRepository.findByIdAndDeletedAtIsNull(resellerId)).thenReturn(Optional.of(r));
    }

    private void tenantTarget(UUID tenantId, UUID resellerId) {
        TenantEntity t = new TenantEntity();
        t.setId(tenantId);
        t.setResellerId(resellerId);
        t.setStatus(com.shivang.obd.common.lifecycle.LifecycleStatus.ACTIVE);
        lenient().when(tenantRepository.findByIdAndDeletedAtIsNull(tenantId)).thenReturn(Optional.of(t));
    }

    // === Flow A/B: platform scope ===

    @Nested
    @DisplayName("Platform scope")
    class Platform {

        @Test
        @DisplayName("assigns a platform-pool DID to a reseller pool")
        void platformToReseller() {
            platformScope();
            when(repository.findByIdAndDeletedAtIsNull(DID))
                .thenReturn(Optional.of(did(AllocationState.AVAILABLE, null, null, null)));
            resellerTargetsActive(RESELLER_A);
            when(repository.assignFromPlatformPoolToReseller(DID, RESELLER_A)).thenReturn(1);
            when(repository.findById(DID)).thenReturn(Optional.of(
                did(AllocationState.AVAILABLE, null, RESELLER_A, AllocationSource.RESELLER)));

            AssignDidResponse response = service.assign(DID, new AssignDidRequest(RESELLER_A)).data();

            assertThat(response.allocationState()).isEqualTo(AllocationState.AVAILABLE);
            assertThat(response.allocationSource()).isEqualTo(AllocationSource.RESELLER);
            assertThat(response.resellerId()).isEqualTo(RESELLER_A);
            verify(repository).assignFromPlatformPoolToReseller(DID, RESELLER_A);
            verify(authorizationService).requireCapability(USER_ID, "DID_MANAGE", AccessCheck.platformWide());
        }

        @Test
        @DisplayName("assigns a platform-pool DID directly to a tenant")
        void platformToTenant() {
            platformScope();
            when(repository.findByIdAndDeletedAtIsNull(DID))
                .thenReturn(Optional.of(did(AllocationState.AVAILABLE, null, null, null)));
            resellerTargetsActive(RESELLER_A);
            tenantTarget(TENANT_A1, null);
            when(repository.assignFromPlatformPoolToTenant(DID, TENANT_A1)).thenReturn(1);
            when(repository.findById(DID)).thenReturn(Optional.of(
                did(AllocationState.ASSIGNED, TENANT_A1, null, AllocationSource.PLATFORM)));

            AssignDidResponse response = service.assign(DID, new AssignDidRequest(TENANT_A1)).data();

            assertThat(response.allocationState()).isEqualTo(AllocationState.ASSIGNED);
            assertThat(response.allocationSource()).isEqualTo(AllocationSource.PLATFORM);
            assertThat(response.tenantId()).isEqualTo(TENANT_A1);
            verify(repository).assignFromPlatformPoolToTenant(DID, TENANT_A1);
        }

        @Test
        @DisplayName("rejects an already-assigned DID with a conflict")
        void alreadyAssigned() {
            platformScope();
            when(repository.findByIdAndDeletedAtIsNull(DID))
                .thenReturn(Optional.of(did(AllocationState.ASSIGNED, TENANT_A1, null, AllocationSource.PLATFORM)));

            assertThatThrownBy(() -> service.assign(DID, new AssignDidRequest(TENANT_A1)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already assigned");
            verify(repository, never()).assignFromPlatformPoolToTenant(any(), any());
        }

        @Test
        @DisplayName("rejects an inactive DID before any authorization or transition")
        void inactiveDid() {
            platformScope();
            DidEntity inactive = did(AllocationState.AVAILABLE, null, null, null);
            inactive.setStatus(DidStatus.INACTIVE);
            when(repository.findByIdAndDeletedAtIsNull(DID)).thenReturn(Optional.of(inactive));

            assertThatThrownBy(() -> service.assign(DID, new AssignDidRequest(TENANT_A1)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("inactive");
            verify(repository, never()).assignFromPlatformPoolToTenant(any(), any());
        }

        @Test
        @DisplayName("rejects an unknown target")
        void unknownTarget() {
            platformScope();
            when(repository.findByIdAndDeletedAtIsNull(DID))
                .thenReturn(Optional.of(did(AllocationState.AVAILABLE, null, null, null)));
            when(resellerRepository.findByIdAndDeletedAtIsNull(TENANT_B1)).thenReturn(Optional.empty());
            when(tenantRepository.findByIdAndDeletedAtIsNull(TENANT_B1)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.assign(DID, new AssignDidRequest(TENANT_B1)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("target");
        }

        @Test
        @DisplayName("lost conditional UPDATE (0 rows) surfaces a conflict")
        void lostRace() {
            platformScope();
            when(repository.findByIdAndDeletedAtIsNull(DID))
                .thenReturn(Optional.of(did(AllocationState.AVAILABLE, null, null, null)));
            resellerTargetsActive(RESELLER_A);
            when(repository.assignFromPlatformPoolToReseller(DID, RESELLER_A)).thenReturn(0);

            assertThatThrownBy(() -> service.assign(DID, new AssignDidRequest(RESELLER_A)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("not available");
        }
    }

    // === Flow C: reseller scope ===

    @Nested
    @DisplayName("Reseller scope")
    class Reseller {

        @Test
        @DisplayName("assigns its own AVAILABLE pool DID to its own active tenant")
        void resellerPoolToOwnTenant() {
            resellerScope(RESELLER_A);
            when(repository.findByIdAndDeletedAtIsNull(DID))
                .thenReturn(Optional.of(did(AllocationState.AVAILABLE, null, RESELLER_A, AllocationSource.RESELLER)));
            tenantTarget(TENANT_A1, RESELLER_A);
            when(repository.assignFromResellerPoolToTenant(DID, RESELLER_A, TENANT_A1)).thenReturn(1);
            when(repository.findById(DID)).thenReturn(Optional.of(
                did(AllocationState.ASSIGNED, TENANT_A1, RESELLER_A, AllocationSource.RESELLER)));

            AssignDidResponse response = service.assign(DID, new AssignDidRequest(TENANT_A1)).data();

            assertThat(response.allocationState()).isEqualTo(AllocationState.ASSIGNED);
            assertThat(response.tenantId()).isEqualTo(TENANT_A1);
            assertThat(response.resellerId()).isEqualTo(RESELLER_A);
            verify(repository).assignFromResellerPoolToTenant(DID, RESELLER_A, TENANT_A1);
        }

        @Test
        @DisplayName("cannot assign a DID from another reseller's pool (404-cloaked)")
        void foreignPoolRejected() {
            resellerScope(RESELLER_B);
            when(repository.findByIdAndDeletedAtIsNull(DID))
                .thenReturn(Optional.of(did(AllocationState.AVAILABLE, null, RESELLER_A, AllocationSource.RESELLER)));
            tenantTarget(TENANT_B1, RESELLER_B);

            // Established boundary convention: a foreign DID and a missing
            // DID are indistinguishable for a scoped caller.
            assertThatThrownBy(() -> service.assign(DID, new AssignDidRequest(TENANT_B1)))
                .isInstanceOf(com.shivang.obd.common.exception.ResourceNotFoundException.class);
            verify(repository, never()).assignFromResellerPoolToTenant(any(), any(), any());
        }

        @Test
        @DisplayName("cannot assign into a tenant outside its hierarchy")
        void foreignTenantRejected() {
            resellerScope(RESELLER_A);
            when(repository.findByIdAndDeletedAtIsNull(DID))
                .thenReturn(Optional.of(did(AllocationState.AVAILABLE, null, RESELLER_A, AllocationSource.RESELLER)));
            tenantTarget(TENANT_B1, RESELLER_B); // tenant belongs to RESELLER_B

            assertThatThrownBy(() -> service.assign(DID, new AssignDidRequest(TENANT_B1)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("managed hierarchy");
            verify(repository, never()).assignFromResellerPoolToTenant(any(), any(), any());
        }

        @Test
        @DisplayName("cannot assign a platform-pool DID (not visible to reseller scope)")
        void platformDidRejected() {
            resellerScope(RESELLER_A);
            when(repository.findByIdAndDeletedAtIsNull(DID))
                .thenReturn(Optional.of(did(AllocationState.AVAILABLE, null, null, null)));
            tenantTarget(TENANT_A1, RESELLER_A);

            // Platform-pool DIDs (no reseller stamp, no tenant) are outside
            // the reseller boundary entirely.
            assertThatThrownBy(() -> service.assign(DID, new AssignDidRequest(TENANT_A1)))
                .isInstanceOf(com.shivang.obd.common.exception.ResourceNotFoundException.class);
        }
    }

    // === Tenant scope ===

    @Nested
    @DisplayName("Tenant scope")
    class Tenant {

        @Test
        @DisplayName("tenants cannot assign")
        void tenantCannotAssign() {
            tenantScope(TENANT_A1);
            when(repository.findByIdAndTenantIdAndDeletedAtIsNull(DID, TENANT_A1))
                .thenReturn(Optional.of(did(AllocationState.ASSIGNED, TENANT_A1, null, AllocationSource.PLATFORM)));

            assertThatThrownBy(() -> service.assign(DID, new AssignDidRequest(TENANT_A1)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Tenants cannot assign");
        }

        @Test
        @DisplayName("tenants cannot revoke")
        void tenantCannotRevoke() {
            tenantScope(TENANT_A1);
            when(repository.findByIdAndTenantIdAndDeletedAtIsNull(DID, TENANT_A1))
                .thenReturn(Optional.of(did(AllocationState.ASSIGNED, TENANT_A1, null, AllocationSource.PLATFORM)));

            assertThatThrownBy(() -> service.revoke(DID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Tenants cannot revoke");
        }
    }

    // === Revoke ===

    @Nested
    @DisplayName("Revoke")
    class Revoke {

        @Test
        @DisplayName("platform revokes a tenant assignment back to the platform pool")
        void revokeTenantToPlatform() {
            platformScope();
            when(repository.findByIdAndDeletedAtIsNull(DID))
                .thenReturn(Optional.of(did(AllocationState.ASSIGNED, TENANT_A1, null, AllocationSource.PLATFORM)));
            when(repository.revokeFromTenant(DID, TENANT_A1)).thenReturn(1);
            when(repository.findById(DID)).thenReturn(Optional.of(
                did(AllocationState.AVAILABLE, null, null, null)));

            AssignDidResponse response = service.revoke(DID).data();

            assertThat(response.allocationState()).isEqualTo(AllocationState.AVAILABLE);
            assertThat(response.tenantId()).isNull();
            assertThat(response.allocationSource()).isNull();
            verify(repository).revokeFromTenant(DID, TENANT_A1);
        }

        @Test
        @DisplayName("platform revokes a reseller assignment back to the reseller pool")
        void revokeResellerToResellerPool() {
            platformScope();
            when(repository.findByIdAndDeletedAtIsNull(DID))
                .thenReturn(Optional.of(did(AllocationState.AVAILABLE, null, RESELLER_A, AllocationSource.RESELLER)));
            when(repository.revokeFromReseller(DID, RESELLER_A)).thenReturn(1);
            when(repository.findById(DID)).thenReturn(Optional.of(
                did(AllocationState.AVAILABLE, null, RESELLER_A, AllocationSource.RESELLER)));

            AssignDidResponse response = service.revoke(DID).data();

            assertThat(response.allocationState()).isEqualTo(AllocationState.AVAILABLE);
            assertThat(response.resellerId()).isEqualTo(RESELLER_A);
            assertThat(response.allocationSource()).isEqualTo(AllocationSource.RESELLER);
        }

        @Test
        @DisplayName("reseller revokes its tenant assignment")
        void resellerRevokesTenant() {
            resellerScope(RESELLER_A);
            when(repository.findByIdAndDeletedAtIsNull(DID))
                .thenReturn(Optional.of(did(AllocationState.ASSIGNED, TENANT_A1, RESELLER_A, AllocationSource.RESELLER)));
            lenient().when(tenantRepository.findAllByResellerIdAndStatus(
                    org.mockito.ArgumentMatchers.eq(RESELLER_A), any()))
                .thenReturn(java.util.List.of());
            when(repository.revokeFromTenant(DID, TENANT_A1)).thenReturn(1);
            when(repository.findById(DID)).thenReturn(Optional.of(
                did(AllocationState.AVAILABLE, null, RESELLER_A, AllocationSource.RESELLER)));

            AssignDidResponse response = service.revoke(DID).data();

            assertThat(response.allocationState()).isEqualTo(AllocationState.AVAILABLE);
            verify(repository).revokeFromTenant(DID, TENANT_A1);
        }

        @Test
        @DisplayName("reseller cannot revoke a DID held by a foreign tenant")
        void resellerCannotRevokeForeignTenant() {
            resellerScope(RESELLER_B);
            when(repository.findByIdAndDeletedAtIsNull(DID))
                .thenReturn(Optional.of(did(AllocationState.ASSIGNED, TENANT_A1, RESELLER_A, AllocationSource.RESELLER)));
            when(tenantRepository.findAllByResellerIdAndStatus(
                    org.mockito.ArgumentMatchers.eq(RESELLER_B), any()))
                .thenReturn(java.util.List.of());

            assertThatThrownBy(() -> service.revoke(DID))
                .isInstanceOf(com.shivang.obd.common.exception.ResourceNotFoundException.class);
            verify(repository, never()).revokeFromTenant(any(), any());
        }

        @Test
        @DisplayName("revoking an unassigned DID conflicts")
        void revokeUnassigned() {
            platformScope();
            when(repository.findByIdAndDeletedAtIsNull(DID))
                .thenReturn(Optional.of(did(AllocationState.AVAILABLE, null, null, null)));

            assertThatThrownBy(() -> service.revoke(DID))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("not assigned");
        }
    }
}

package com.shivang.obd.contact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.shivang.obd.authz.AccessCheck;
import com.shivang.obd.authz.AuthorizationService;
import com.shivang.obd.authz.context.OrganizationContextHolder;
import com.shivang.obd.common.exception.ConflictException;
import com.shivang.obd.common.exception.ResourceNotFoundException;
import com.shivang.obd.common.lifecycle.LifecycleStatus;
import com.shivang.obd.contact.dto.CreateContactRequest;
import com.shivang.obd.security.AuthenticatedUser;
import com.shivang.obd.security.CurrentUserProvider;
import com.shivang.obd.tenant.TenantEntity;
import com.shivang.obd.tenant.TenantRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Focused protection for the genuine ownership rules: contacts inherit
 * the parent group's tenant, cross-tenant access is indistinguishable
 * from missing (404), reseller visibility follows the active hierarchy,
 * and a non-empty group cannot be deleted.
 */
class ContactOwnershipServiceTest {

    private static final UUID USER_ID = UUID.fromString("ee000000-0000-4000-8000-000000000001");
    private static final UUID TENANT_A = UUID.fromString("aa000000-0000-4000-8000-00000000000a");
    private static final UUID TENANT_B = UUID.fromString("bb000000-0000-4000-8000-00000000000b");
    private static final UUID RESELLER_R = UUID.fromString("f0000000-0000-4000-8000-00000000000f");

    private ContactGroupRepository groupRepository;
    private ContactRepository contactRepository;
    private com.shivang.obd.contact.ContactGroupMemberRepository memberRepository;
    private AuthorizationService authorizationService;
    private TenantRepository tenantRepository;
    private ContactGroupService service;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        groupRepository = org.mockito.Mockito.mock(ContactGroupRepository.class);
        contactRepository = org.mockito.Mockito.mock(ContactRepository.class);
        memberRepository = org.mockito.Mockito.mock(com.shivang.obd.contact.ContactGroupMemberRepository.class);
        authorizationService = org.mockito.Mockito.mock(AuthorizationService.class);
        tenantRepository = org.mockito.Mockito.mock(TenantRepository.class);
        CurrentUserProvider currentUserProvider = org.mockito.Mockito.mock(CurrentUserProvider.class);
        when(currentUserProvider.current())
            .thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "contact-admin@test.local", null)));
        objectMapper = new ObjectMapper();
        com.shivang.obd.contact.ContactGroupAccess groupAccess =
            new com.shivang.obd.contact.ContactGroupAccess(
                groupRepository, authorizationService, currentUserProvider, tenantRepository);
        com.shivang.obd.contact.ContactGroupMemberService memberService =
            new com.shivang.obd.contact.ContactGroupMemberService(
                memberRepository, contactRepository, groupAccess, currentUserProvider);
        service = new ContactGroupService(groupRepository, contactRepository,
            memberService, new ContactIdentityService(contactRepository), groupAccess,
            new ContactGroupMapper(), new ContactMapper(),
            List.of(new CsvContactImportReader(),
                new JsonContactImportReader(objectMapper),
                new XlsxContactImportReader()),
            objectMapper);
        OrganizationContextHolder.setAuthenticated(USER_ID, TENANT_A, null);
    }

    @AfterEach
    void tearDown() {
        OrganizationContextHolder.clear();
    }

    private ContactGroupEntity group(UUID id, UUID tenantId) {
        ContactGroupEntity entity = new ContactGroupEntity();
        entity.setId(id);
        entity.setTenantId(tenantId);
        entity.setName("Group");
        return entity;
    }

    private TenantEntity tenant(UUID id) {
        TenantEntity tenant = new TenantEntity();
        tenant.setId(id);
        tenant.setStatus(LifecycleStatus.ACTIVE);
        return tenant;
    }

    @Test
    void contactInheritsTheGroupsTenantNeverTheClients() throws Exception {
        when(groupRepository.findByIdAndTenantIdAndDeletedAtIsNull(any(), eq(TENANT_A)))
            .thenAnswer(inv -> Optional.of(group(inv.getArgument(0), TENANT_A)));
        when(contactRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        JsonNode attributes = objectMapper.valueToTree(Map.of("orderId", "ORD-1"));
        CreateContactRequest request =
            new CreateContactRequest("Rahul", "Sharma", "+919876543210", null, attributes);

        service.createContact(UUID.randomUUID(), request);

        ArgumentCaptor<ContactEntity> captor = ArgumentCaptor.forClass(ContactEntity.class);
        verify(contactRepository).save(captor.capture());
        assertThat(captor.getValue().getTenantId()).isEqualTo(TENANT_A);
        verify(authorizationService).requireCapability(
            eq(USER_ID), eq("CONTACT_MANAGE"), any(AccessCheck.class));
    }

    @Test
    void foreignTenantCannotSeeTheGroup() {
        OrganizationContextHolder.setAuthenticated(USER_ID, TENANT_B, null);
        UUID groupId = UUID.randomUUID();
        when(groupRepository.findByIdAndTenantIdAndDeletedAtIsNull(groupId, TENANT_B))
            .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getGroup(groupId))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void resellerSeesHierarchyGroupsButNotStandaloneTenants() {
        OrganizationContextHolder.setAuthenticated(USER_ID, null, RESELLER_R);
        UUID hierarchyGroupId = UUID.randomUUID();
        UUID foreignGroupId = UUID.randomUUID();

        when(tenantRepository.findAllByResellerIdAndStatus(RESELLER_R, LifecycleStatus.ACTIVE))
            .thenReturn(List.of(tenant(TENANT_A)));
        when(groupRepository.findByIdAndDeletedAtIsNull(hierarchyGroupId))
            .thenReturn(Optional.of(group(hierarchyGroupId, TENANT_A)));
        when(groupRepository.findByIdAndDeletedAtIsNull(foreignGroupId))
            .thenReturn(Optional.of(group(foreignGroupId, TENANT_B)));

        assertThat(service.getGroup(hierarchyGroupId).data().id()).isEqualTo(hierarchyGroupId);
        assertThatThrownBy(() -> service.getGroup(foreignGroupId))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void nonEmptyGroupCanBeDeletedWithMembershipsRemoved() {
        UUID groupId = UUID.randomUUID();
        when(groupRepository.findByIdAndTenantIdAndDeletedAtIsNull(groupId, TENANT_A))
            .thenReturn(Optional.of(group(groupId, TENANT_A)));
        when(memberRepository.findByContactGroupId(groupId))
            .thenReturn(List.of(new ContactGroupMemberEntity()));

        // VB-6B.1: group deletion removes membership rows (physical links;
        // delegated to the canonical membership service in VB-6B.2);
        // the 409 "still contains contacts" guard is obsolete.
        service.deleteGroup(groupId);

        org.mockito.Mockito.verify(memberRepository).deleteAll(any());
    }

    @Test
    void contactOutsideItsGroupIsIndistinguishableFromMissing() {
        UUID groupId = UUID.randomUUID();
        UUID contactId = UUID.randomUUID();
        when(groupRepository.findByIdAndTenantIdAndDeletedAtIsNull(groupId, TENANT_A))
            .thenReturn(Optional.of(group(groupId, TENANT_A)));
        when(contactRepository.findByIdAndTenantIdAndDeletedAtIsNull(contactId, TENANT_A))
            .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getContact(groupId, contactId))
            .isInstanceOf(ResourceNotFoundException.class);
    }
}

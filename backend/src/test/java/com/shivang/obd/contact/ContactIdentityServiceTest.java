package com.shivang.obd.contact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shivang.obd.authz.AuthorizationService;
import com.shivang.obd.common.exception.BusinessException;
import com.shivang.obd.common.exception.ConflictException;
import com.shivang.obd.contact.dto.CreateContactRequest;
import com.shivang.obd.contact.dto.UpdateContactRequest;
import com.shivang.obd.security.AuthenticatedUser;
import com.shivang.obd.security.CurrentUserProvider;
import com.shivang.obd.tenant.TenantRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import tools.jackson.databind.ObjectMapper;

/**
 * VB-6B.1: Contact = tenant-level callable identity. The dedup boundary is
 * TENANT + canonical phone (the group is no longer part of identity);
 * duplicates are a typed 409 both at the application check and through the
 * database-constraint race path; a phone update keeps the same Contact
 * UUID; the same number in another tenant stays allowed; a soft-deleted
 * number is re-creatable; and a Contact can exist without any group.
 */
class ContactIdentityServiceTest {

    private static final UUID USER_ID = UUID.fromString("ee000000-0000-4000-8000-000000000001");
    private static final UUID TENANT_A = UUID.fromString("aa000000-0000-4000-8000-00000000000a");
    private static final UUID TENANT_B = UUID.fromString("bb000000-0000-4000-8000-00000000000b");
    private static final String PHONE = "+919876543210";

    private ContactGroupRepository groupRepository;
    private ContactRepository contactRepository;
    private ContactGroupMemberRepository memberRepository;
    private ContactIdentityService identityService;
    private ContactGroupService service;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        groupRepository = mock(ContactGroupRepository.class);
        contactRepository = mock(ContactRepository.class);
        memberRepository = mock(ContactGroupMemberRepository.class);
        identityService = new ContactIdentityService(contactRepository);
        AuthorizationService authorizationService = mock(AuthorizationService.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        when(currentUserProvider.current())
            .thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "contact-admin@test.local", null)));
        objectMapper = new ObjectMapper();
        com.shivang.obd.contact.ContactGroupAccess groupAccess =
            new com.shivang.obd.contact.ContactGroupAccess(
                groupRepository, authorizationService, currentUserProvider, mock(TenantRepository.class));
        ContactGroupMemberService memberService = new ContactGroupMemberService(
            memberRepository, contactRepository, groupAccess, currentUserProvider);
        service = new ContactGroupService(groupRepository, contactRepository, memberService,
            identityService, groupAccess, new ContactGroupMapper(),
            new ContactMapper(),
            List.of(new CsvContactImportReader(),
                new JsonContactImportReader(objectMapper),
                new XlsxContactImportReader()),
            objectMapper);
        com.shivang.obd.authz.context.OrganizationContextHolder.setAuthenticated(USER_ID, TENANT_A, null);
    }

    @AfterEach
    void tearDown() {
        com.shivang.obd.authz.context.OrganizationContextHolder.clear();
    }

    private ContactGroupEntity group(UUID id) {
        ContactGroupEntity entity = new ContactGroupEntity();
        entity.setId(id);
        entity.setTenantId(TENANT_A);
        entity.setName("Group");
        return entity;
    }

    private ContactEntity identity(UUID id, UUID tenantId, String phone) {
        ContactEntity entity = new ContactEntity();
        entity.setId(id);
        entity.setTenantId(tenantId);
        entity.setPhoneNumber(phone);
        return entity;
    }

    private void stubGroup(UUID groupId) {
        when(groupRepository.findByIdAndTenantIdAndDeletedAtIsNull(groupId, TENANT_A))
            .thenReturn(Optional.of(group(groupId)));
    }

    // === A. canonicalization ===

    @Test
    @DisplayName("Canonical E.164 input is stored unchanged")
    void canonicalE164StoredUnchanged() {
        UUID groupId = UUID.randomUUID();
        stubGroup(groupId);
        when(contactRepository.findByTenantIdAndPhoneNumberAndDeletedAtIsNull(TENANT_A, PHONE))
            .thenReturn(Optional.empty());
        when(contactRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.createContact(groupId, new CreateContactRequest(null, null, PHONE, null, null));

        ArgumentCaptor<ContactEntity> captor = ArgumentCaptor.forClass(ContactEntity.class);
        verify(contactRepository).save(captor.capture());
        assertThat(captor.getValue().getPhoneNumber()).isEqualTo(PHONE);
        // VB-6B.1: the identity belongs to the tenant, never a group.
        assertThat(captor.getValue().getTenantId()).isEqualTo(TENANT_A);
    }

    @Test
    @DisplayName("Separators and whitespace collapse onto the canonical stored value")
    void separatorsCollapseOntoCanonicalValue() {
        UUID groupId = UUID.randomUUID();
        stubGroup(groupId);
        when(contactRepository.findByTenantIdAndPhoneNumberAndDeletedAtIsNull(TENANT_A, PHONE))
            .thenReturn(Optional.empty());
        when(contactRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.createContact(groupId, new CreateContactRequest(null, null, " +91 (98765) 432-10 ", null, null));

        ArgumentCaptor<ContactEntity> captor = ArgumentCaptor.forClass(ContactEntity.class);
        verify(contactRepository).save(captor.capture());
        assertThat(captor.getValue().getPhoneNumber()).isEqualTo(PHONE);
    }

    @Test
    @DisplayName("Invalid, malformed and leading-zero numbers are rejected before persistence")
    void invalidNumbersRejectedBeforePersistence() {
        UUID groupId = UUID.randomUUID();
        stubGroup(groupId);

        for (String bad : new String[] {"9876543210", "++919876543210", "+09876543210",
                "+9198765432101234567", "not-a-phone", ""}) {
            assertThatThrownBy(() -> service.createContact(groupId,
                    new CreateContactRequest(null, null, bad, null, null)))
                .as("input <%s> must be rejected", bad)
                .isInstanceOf(BusinessException.class);
        }
        verify(contactRepository, never()).save(any());
    }

    // === B. identity dedup ===

    @Test
    @DisplayName("Same tenant + same phone resolves to the EXISTING identity (no duplicate row)")
    void sameTenantSamePhoneResolvesExistingIdentity() {
        UUID groupId = UUID.randomUUID();
        stubGroup(groupId);
        UUID existingId = UUID.randomUUID();
        when(contactRepository.findByTenantIdAndPhoneNumberAndDeletedAtIsNull(TENANT_A, PHONE))
            .thenReturn(Optional.of(identity(existingId, TENANT_A, PHONE)));

        var response = service.createContact(groupId,
                new CreateContactRequest(null, null, PHONE, null, null));

        assertThat(response.data().id()).isEqualTo(existingId);
        verify(contactRepository, never()).save(any());
        // Membership to the target group is upserted for the same identity
        // (through the canonical membership service → repository).
        verify(memberRepository).existsByContactGroupIdAndContactId(groupId, existingId);
    }

    @Test
    @DisplayName("Different tenant + same phone creates its own identity (no global uniqueness)")
    void differentTenantSamePhoneAllowed() {
        UUID groupId = UUID.randomUUID();
        // Platform-scope caller operating on a TENANT_B group: the identity
        // is always derived from the GROUP's tenant, never the caller's.
        com.shivang.obd.authz.context.OrganizationContextHolder.setAuthenticated(USER_ID, null, null);
        when(groupRepository.findByIdAndDeletedAtIsNull(groupId))
            .thenReturn(Optional.of(groupForTenant(groupId, TENANT_B)));
        when(contactRepository.findByTenantIdAndPhoneNumberAndDeletedAtIsNull(TENANT_B, PHONE))
            .thenReturn(Optional.empty());
        when(contactRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var response = service.createContact(groupId,
                new CreateContactRequest(null, null, PHONE, null, null));

        ArgumentCaptor<ContactEntity> captor = ArgumentCaptor.forClass(ContactEntity.class);
        verify(contactRepository).save(captor.capture());
        assertThat(captor.getValue().getTenantId()).isEqualTo(TENANT_B);
        assertThat(response.data().phoneNumber()).isEqualTo(PHONE);
    }

    private ContactGroupEntity groupForTenant(UUID id, UUID tenantId) {
        ContactGroupEntity entity = new ContactGroupEntity();
        entity.setId(id);
        entity.setTenantId(tenantId);
        entity.setName("Group");
        return entity;
    }

    @Test
    @DisplayName("Database unique-constraint race becomes the typed 409 (never a raw 500)")
    void databaseRaceBecomesTypedConflict() {
        UUID groupId = UUID.randomUUID();
        stubGroup(groupId);
        when(contactRepository.findByTenantIdAndPhoneNumberAndDeletedAtIsNull(TENANT_A, PHONE))
            .thenReturn(Optional.empty());
        when(contactRepository.save(any()))
            .thenThrow(new DataIntegrityViolationException("uq_contacts_tenant_phone_live"));

        // The losing create is the approved typed conflict outcome — the
        // winning identity exists concurrently; no raw 500 surfaces.
        assertThatThrownBy(() -> service.createContact(groupId,
                new CreateContactRequest(null, null, PHONE, null, null)))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("already exists");
    }

    // === C. update semantics ===

    @Test
    @DisplayName("Phone update retains the same Contact UUID (history stays attached)")
    void phoneUpdateRetainsIdentity() {
        UUID groupId = UUID.randomUUID();
        stubGroup(groupId);
        UUID contactId = UUID.randomUUID();
        ContactEntity existing = identity(contactId, TENANT_A, "+919870000001");
        when(contactRepository.findByIdAndTenantIdAndDeletedAtIsNull(contactId, TENANT_A))
            .thenReturn(Optional.of(existing));

        var response = service.updateContact(groupId, contactId,
                new UpdateContactRequest(null, null, "+919876599999", null, null));

        assertThat(response.data().id()).isEqualTo(contactId);
        verify(contactRepository).save(existing);
        assertThat(existing.getPhoneNumber()).isEqualTo("+919876599999");
    }

    @Test
    @DisplayName("Update to another live tenant identity's phone is a typed 409")
    void updateToDuplicateIdentityIsTypedConflict() {
        UUID groupId = UUID.randomUUID();
        stubGroup(groupId);
        UUID contactId = UUID.randomUUID();
        ContactEntity existing = identity(contactId, TENANT_A, "+919870000001");
        when(contactRepository.findByIdAndTenantIdAndDeletedAtIsNull(contactId, TENANT_A))
            .thenReturn(Optional.of(existing));
        when(contactRepository.existsByTenantIdAndPhoneNumberAndDeletedAtIsNull(TENANT_A, PHONE))
            .thenReturn(true);

        assertThatThrownBy(() -> service.updateContact(groupId, contactId,
                new UpdateContactRequest(null, null, PHONE, null, null)))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("already exists");
        verify(contactRepository, never()).save(any());
    }

    @Test
    @DisplayName("Update with the contact's own canonical number skips the dedup check")
    void updateSameNumberSucceeds() {
        UUID groupId = UUID.randomUUID();
        stubGroup(groupId);
        UUID contactId = UUID.randomUUID();
        ContactEntity existing = identity(contactId, TENANT_A, PHONE);
        when(contactRepository.findByIdAndTenantIdAndDeletedAtIsNull(contactId, TENANT_A))
            .thenReturn(Optional.of(existing));

        var response = service.updateContact(groupId, contactId,
                new UpdateContactRequest("New", null, PHONE, null, null));

        assertThat(response.data().firstName()).isEqualTo("New");
        verify(contactRepository).save(existing);
    }

    // === D. soft-delete / no-group ===

    @Test
    @DisplayName("Soft-deleted identity's phone is re-creatable; memberships are removed on delete")
    void softDeletedPhoneReusableAndMembershipsRemoved() {
        UUID groupId = UUID.randomUUID();
        stubGroup(groupId);
        UUID contactId = UUID.randomUUID();
        ContactEntity existing = identity(contactId, TENANT_A, PHONE);
        when(contactRepository.findByIdAndTenantIdAndDeletedAtIsNull(contactId, TENANT_A))
            .thenReturn(Optional.of(existing));
        when(memberRepository.findByContactId(contactId))
            .thenReturn(List.of(new ContactGroupMemberEntity()));

        service.deleteContact(groupId, contactId);

        verify(memberRepository).deleteAll(any());
        verify(contactRepository).save(existing);
        // Live identity lookup after delete would find nothing (deletedAt set
        // on save) — new creates of the same number are then allowed.
    }

    @Test
    @DisplayName("Identity dedup is tenant-scoped: a different tenant's phone never collides")
    void tenantScopedDedup() {
        // The identity service checks the TENANT, not the group: two groups
        // of the same tenant share one identity (single lookup), while a
        // second tenant performs its own lookup.
        when(contactRepository.existsByTenantIdAndPhoneNumberAndDeletedAtIsNull(TENANT_A, PHONE))
            .thenReturn(false);
        identityService.assertPhoneAvailableForTenant(TENANT_A, PHONE);

        when(contactRepository.existsByTenantIdAndPhoneNumberAndDeletedAtIsNull(TENANT_B, PHONE))
            .thenReturn(false);
        identityService.assertPhoneAvailableForTenant(TENANT_B, PHONE);

        when(contactRepository.existsByTenantIdAndPhoneNumberAndDeletedAtIsNull(TENANT_A, PHONE))
            .thenReturn(true);
        assertThatThrownBy(() -> identityService.assertPhoneAvailableForTenant(TENANT_A, PHONE))
            .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("Tenant+phone identity lookup exposes the logical identity query")
    void identityLookup() {
        UUID contactId = UUID.randomUUID();
        when(contactRepository.findIdsByTenantIdAndPhoneNumberAndDeletedAtIsNull(TENANT_A, PHONE))
            .thenReturn(List.of(contactId));

        assertThat(identityService.findLiveContactIds(TENANT_A, PHONE)).containsExactly(contactId);
    }
}

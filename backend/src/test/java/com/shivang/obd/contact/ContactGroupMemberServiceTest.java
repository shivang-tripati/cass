package com.shivang.obd.contact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shivang.obd.authz.AuthorizationService;
import com.shivang.obd.authz.context.OrganizationContextHolder;
import com.shivang.obd.common.api.response.ApiResponse;
import com.shivang.obd.common.exception.ResourceNotFoundException;
import com.shivang.obd.contact.dto.AddMemberRequest;
import com.shivang.obd.contact.dto.BatchMemberRequest;
import com.shivang.obd.contact.dto.BatchMemberResult;
import com.shivang.obd.contact.dto.ContactGroupMemberResponse;
import com.shivang.obd.contact.dto.MemberBatchStatus;
import com.shivang.obd.security.AuthenticatedUser;
import com.shivang.obd.security.CurrentUserProvider;
import com.shivang.obd.tenant.TenantRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/**
 * VB-6B.2 unit coverage for the canonical membership service:
 * idempotent add/remove, 404-cloaked tenant boundaries, constraint-race
 * idempotency, batch per-item outcomes, roster paging/sort whitelist,
 * member counts and the createdBy stamp.
 */
class ContactGroupMemberServiceTest {

    private static final UUID USER_ID = UUID.fromString("99000000-0000-4000-8000-000000000001");
    private static final UUID TENANT_A = UUID.fromString("aa000000-0000-4000-8000-00000000000a");
    private static final UUID TENANT_B = UUID.fromString("bb000000-0000-4000-8000-00000000000b");
    private static final UUID GROUP_A = UUID.fromString("55000000-0000-4000-8000-000000000005");

    private ContactGroupMemberRepository memberRepository;
    private ContactRepository contactRepository;
    private ContactGroupRepository groupRepository;
    private AuthorizationService authorizationService;
    private CurrentUserProvider currentUserProvider;
    private ContactGroupMemberService service;

    @BeforeEach
    void setUp() {
        memberRepository = mock(ContactGroupMemberRepository.class);
        contactRepository = mock(ContactRepository.class);
        groupRepository = mock(ContactGroupRepository.class);
        authorizationService = mock(AuthorizationService.class);
        currentUserProvider = mock(CurrentUserProvider.class);
        when(currentUserProvider.current())
            .thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "member-admin@test.local", null)));

        ContactGroupAccess groupAccess = new ContactGroupAccess(
            groupRepository, authorizationService, currentUserProvider, mock(TenantRepository.class));
        service = new ContactGroupMemberService(
            memberRepository, contactRepository, groupAccess, currentUserProvider);

        OrganizationContextHolder.setAuthenticated(USER_ID, TENANT_A, null);
        when(groupRepository.findByIdAndTenantIdAndDeletedAtIsNull(GROUP_A, TENANT_A))
            .thenReturn(Optional.of(group(GROUP_A, TENANT_A)));
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

    private ContactEntity contact(UUID id, UUID tenantId) {
        ContactEntity entity = new ContactEntity();
        entity.setId(id);
        entity.setTenantId(tenantId);
        entity.setPhoneNumber("+919876543210");
        entity.setFirstName("Rahul");
        return entity;
    }

    private ContactGroupMemberEntity member(UUID id, UUID groupId, UUID contactId, String createdBy) {
        ContactGroupMemberEntity entity = new ContactGroupMemberEntity();
        entity.setId(id);
        entity.setTenantId(TENANT_A);
        entity.setContactGroupId(groupId);
        entity.setContactId(contactId);
        entity.setCreatedBy(createdBy);
        entity.setContact(contact(contactId, TENANT_A));
        return entity;
    }

    private void stubRoster(UUID groupId, List<ContactGroupMemberEntity> rows, long total) {
        when(memberRepository.findRosterPage(eq(groupId), any(), any()))
            .thenReturn(new PageImpl<>(rows, PageRequest.of(0, 20), total));
    }

    // 1. add new membership → created, createdBy stamped
    @Test
    @DisplayName("MEM-1: add of a live tenant contact creates the membership and stamps createdBy")
    void addNewMembershipStampsCreatedBy() {
        UUID contactId = UUID.randomUUID();
        when(contactRepository.findByIdAndTenantIdAndDeletedAtIsNull(contactId, TENANT_A))
            .thenReturn(Optional.of(contact(contactId, TENANT_A)));
        when(memberRepository.existsByContactGroupIdAndContactId(GROUP_A, contactId)).thenReturn(false);
        when(memberRepository.saveAndFlush(any())).thenAnswer(inv -> {
            ContactGroupMemberEntity m = inv.getArgument(0);
            m.setId(UUID.randomUUID());
            m.setContact(contact(contactId, TENANT_A));
            return m;
        });

        var outcome = service.addMember(GROUP_A, new AddMemberRequest(contactId));

        assertThat(outcome.created()).isTrue();
        assertThat(outcome.member().contactId()).isEqualTo(contactId);
        assertThat(outcome.member().tenantId()).isEqualTo(TENANT_A);
        ArgumentCaptor<ContactGroupMemberEntity> captor =
            ArgumentCaptor.forClass(ContactGroupMemberEntity.class);
        verify(memberRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getCreatedBy()).isEqualTo(USER_ID.toString());
        assertThat(captor.getValue().getTenantId()).isEqualTo(TENANT_A);
        assertThat(captor.getValue().getContactGroupId()).isEqualTo(GROUP_A);
    }

    // 2. add existing membership → EXISTS, no duplicate save
    @Test
    @DisplayName("MEM-2: add of an already-member contact is idempotent (no second row)")
    void addExistingMembershipIsIdempotent() {
        UUID contactId = UUID.randomUUID();
        when(contactRepository.findByIdAndTenantIdAndDeletedAtIsNull(contactId, TENANT_A))
            .thenReturn(Optional.of(contact(contactId, TENANT_A)));
        when(memberRepository.existsByContactGroupIdAndContactId(GROUP_A, contactId)).thenReturn(true);
        when(memberRepository.findByContactGroupIdAndContactId(GROUP_A, contactId))
            .thenReturn(Optional.of(member(UUID.randomUUID(), GROUP_A, contactId, null)));

        var outcome = service.addMember(GROUP_A, new AddMemberRequest(contactId));

        assertThat(outcome.created()).isFalse();
        assertThat(outcome.member().contactId()).isEqualTo(contactId);
        verify(memberRepository, never()).saveAndFlush(any());
    }

    // 3. missing Contact → 404
    @Test
    @DisplayName("MEM-3: add with a contact that is not live in the group's tenant → 404")
    void addMissingContactIs404() {
        UUID contactId = UUID.randomUUID();
        when(contactRepository.findByIdAndTenantIdAndDeletedAtIsNull(contactId, TENANT_A))
            .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.addMember(GROUP_A, new AddMemberRequest(contactId)))
            .isInstanceOf(ResourceNotFoundException.class);
        verify(memberRepository, never()).saveAndFlush(any());
    }

    // 4. foreign-tenant Contact → 404 (indistinguishable from missing)
    @Test
    @DisplayName("MEM-4: a foreign-tenant contact is indistinguishable from missing (404-cloak)")
    void addForeignTenantContactIs404() {
        UUID contactId = UUID.randomUUID();
        // The lookup is tenant-scoped: a foreign contact is simply absent.
        when(contactRepository.findByIdAndTenantIdAndDeletedAtIsNull(contactId, TENANT_A))
            .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.addMember(GROUP_A, new AddMemberRequest(contactId)))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("Contact not found");
    }

    // 5. missing/unauthorized Group → 404
    @Test
    @DisplayName("MEM-5: a group outside the caller boundary is 404-cloaked")
    void addWithForeignGroupIs404() {
        UUID groupId = UUID.randomUUID();
        UUID contactId = UUID.randomUUID();
        when(groupRepository.findByIdAndTenantIdAndDeletedAtIsNull(groupId, TENANT_A))
            .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.addMember(groupId, new AddMemberRequest(contactId)))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("group");
    }

    // 6. concurrent membership race → constraint → EXISTS (never 500)
    @Test
    @DisplayName("MEM-6: a raced duplicate add resolves via the unique constraint as idempotent EXISTS")
    void concurrentDuplicateAddResolvesIdempotently() {
        UUID contactId = UUID.randomUUID();
        when(contactRepository.findByIdAndTenantIdAndDeletedAtIsNull(contactId, TENANT_A))
            .thenReturn(Optional.of(contact(contactId, TENANT_A)));
        when(memberRepository.existsByContactGroupIdAndContactId(GROUP_A, contactId)).thenReturn(false);
        when(memberRepository.saveAndFlush(any()))
            .thenThrow(new org.springframework.dao.DataIntegrityViolationException("uq_cgm_group_contact"));
        when(memberRepository.findByContactGroupIdAndContactId(GROUP_A, contactId))
            .thenReturn(Optional.of(member(UUID.randomUUID(), GROUP_A, contactId, null)));

        var outcome = service.addMember(GROUP_A, new AddMemberRequest(contactId));

        // The raced insert loses to the constraint and becomes EXISTS — never a 500.
        assertThat(outcome.created()).isFalse();
        assertThat(outcome.member().contactId()).isEqualTo(contactId);
    }

    // 7. remove existing → delete
    @Test
    @DisplayName("MEM-7: removing an existing membership deletes the row")
    void removeExistingDeletesRow() {
        UUID contactId = UUID.randomUUID();
        when(memberRepository.deleteByContactGroupIdAndContactId(GROUP_A, contactId)).thenReturn(1L);

        service.removeMember(GROUP_A, contactId);

        verify(memberRepository).deleteByContactGroupIdAndContactId(GROUP_A, contactId);
    }

    // 8. remove missing → idempotent no-op (204 either way)
    @Test
    @DisplayName("MEM-8: removing a missing membership is a successful no-op")
    void removeMissingIsNoop() {
        UUID contactId = UUID.randomUUID();
        when(memberRepository.deleteByContactGroupIdAndContactId(GROUP_A, contactId)).thenReturn(0L);

        service.removeMember(GROUP_A, contactId);

        verify(memberRepository).deleteByContactGroupIdAndContactId(GROUP_A, contactId);
    }

    // 9. batch mixed results → independent per-item outcomes
    @Test
    @DisplayName("MEM-9: batch add reports independent per-item outcomes and collapses duplicates")
    void batchAddReportsPerItemOutcomes() {
        UUID created = UUID.randomUUID();
        UUID exists = UUID.randomUUID();
        UUID missing = UUID.randomUUID();
        when(contactRepository.findByIdAndTenantIdAndDeletedAtIsNull(created, TENANT_A))
            .thenReturn(Optional.of(contact(created, TENANT_A)));
        when(memberRepository.existsByContactGroupIdAndContactId(GROUP_A, created)).thenReturn(false);
        when(memberRepository.saveAndFlush(any())).thenAnswer(inv -> {
            ContactGroupMemberEntity m = inv.getArgument(0);
            m.setId(UUID.randomUUID());
            m.setContact(contact(created, TENANT_A));
            return m;
        });
        when(contactRepository.findByIdAndTenantIdAndDeletedAtIsNull(exists, TENANT_A))
            .thenReturn(Optional.of(contact(exists, TENANT_A)));
        when(memberRepository.existsByContactGroupIdAndContactId(GROUP_A, exists)).thenReturn(true);
        when(memberRepository.findByContactGroupIdAndContactId(GROUP_A, exists))
            .thenReturn(Optional.of(member(UUID.randomUUID(), GROUP_A, exists, null)));
        // 'missing' has no stub → absent → NOT_FOUND_CONTACT

        var response = service.addMembers(GROUP_A,
            new BatchMemberRequest(List.of(created, exists, missing, missing)));

        var firstOutcome = response.results().stream()
            .collect(Collectors.toMap(BatchMemberResult::contactId,
                BatchMemberResult::status,
                (first, ignored) -> first)); // keep the first occurrence
        assertThat(firstOutcome.get(created)).isEqualTo(MemberBatchStatus.CREATED);
        assertThat(firstOutcome.get(exists)).isEqualTo(MemberBatchStatus.EXISTS);
        assertThat(firstOutcome.get(missing)).isEqualTo(MemberBatchStatus.NOT_FOUND_CONTACT);
        // One entry per requested item (4), duplicates deterministic: the
        // replayed 'missing' items stay NOT_FOUND_CONTACT, exactly one row written.
        assertThat(response.results()).hasSize(4);
        assertThat(response.failed()).isZero();
        verify(memberRepository, org.mockito.Mockito.times(1)).saveAndFlush(any());
    }

    // 10. roster pagination
    @Test
    @DisplayName("MEM-10: roster returns paged results with pagination metadata")
    void rosterReturnsPagedMetadata() {
        UUID c1 = UUID.randomUUID();
        stubRoster(GROUP_A, List.of(member(UUID.randomUUID(), GROUP_A, c1, null)), 1);

        ApiResponse<List<ContactGroupMemberResponse>> response =
            service.listMembers(GROUP_A, 0, 20, new String[0], null);

        assertThat(response.data()).hasSize(1);
        assertThat(response.data().get(0).contactId()).isEqualTo(c1);
        assertThat(response.data().get(0).contact().phoneNumber()).isEqualTo("+919876543210");
        assertThat(response.pagination().totalElements()).isEqualTo(1);
    }

    // 11. roster search passthrough
    @Test
    @DisplayName("MEM-11: roster search is passed through to the repository")
    void rosterSearchPassthrough() {
        stubRoster(GROUP_A, List.of(), 0);

        service.listMembers(GROUP_A, 0, 20, new String[0], "rahul");

        verify(memberRepository).findRosterPage(eq(GROUP_A), eq("rahul"), any());
    }

    // 12. sort whitelist/fallback
    @Test
    @DisplayName("MEM-12: roster sort whitelist falls back to createdAt,asc on an unknown field")
    void rosterSortWhitelistFallsBack() {
        stubRoster(GROUP_A, List.of(), 0);

        service.listMembers(GROUP_A, 0, 20, new String[] {"phoneNumber", "DESC"}, null);
        service.listMembers(GROUP_A, 0, 20, new String[] {"hackerField; DROP", "desc"}, null);

        ArgumentCaptor<PageRequest> captor = ArgumentCaptor.forClass(PageRequest.class);
        verify(memberRepository, org.mockito.Mockito.times(2))
            .findRosterPage(eq(GROUP_A), any(), captor.capture());
        // phoneNumber is whitelisted → DESC honored.
        assertThat(captor.getAllValues().get(0).getSort())
            .isEqualTo(Sort.by(Sort.Direction.DESC, "phoneNumber"));
        // Unknown field → default createdAt ASC.
        assertThat(captor.getAllValues().get(1).getSort())
            .isEqualTo(Sort.by(Sort.Direction.ASC, "createdAt"));
    }

    // 13. member count (single + grouped, no N+1)
    @Test
    @DisplayName("MEM-13: member counts come from one direct and one grouped query")
    void memberCountsUseSingleAndGroupedQueries() {
        UUID g1 = UUID.randomUUID();
        UUID g2 = UUID.randomUUID();
        when(memberRepository.countLiveByContactGroupId(GROUP_A)).thenReturn(7L);
        when(memberRepository.findCountsByGroupIds(List.of(g1, g2)))
            .thenReturn(List.of(new Object[] {g1, 3L}, new Object[] {g2, 0L}));

        assertThat(service.memberCount(GROUP_A)).isEqualTo(7L);
        var counts = service.memberCounts(List.of(g1, g2));
        assertThat(counts).containsEntry(g1, 3L).containsEntry(g2, 0L);
        verify(memberRepository, never()).findByContactGroupId(GROUP_A); // never materialize ids
    }

    @Test
    @DisplayName("MEM-14: getMember returns the membership; missing membership is 404-cloaked")
    void getMemberCloaksMissing() {
        UUID contactId = UUID.randomUUID();
        when(memberRepository.findByContactGroupIdAndContactId(GROUP_A, contactId))
            .thenReturn(Optional.of(member(UUID.randomUUID(), GROUP_A, contactId, USER_ID.toString())));

        var response = service.getMember(GROUP_A, contactId);
        assertThat(response.data().contactId()).isEqualTo(contactId);
        assertThat(response.data().createdBy()).isEqualTo(USER_ID);

        UUID stranger = UUID.randomUUID();
        when(memberRepository.findByContactGroupIdAndContactId(GROUP_A, stranger))
            .thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getMember(GROUP_A, stranger))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("MEM-15: batch remove is idempotent per item; group/contact cascades delegate")
    void batchRemoveAndCascades() {
        UUID removed = UUID.randomUUID();
        UUID absent = UUID.randomUUID();
        when(memberRepository.deleteByContactGroupIdAndContactId(GROUP_A, removed)).thenReturn(1L);
        when(memberRepository.deleteByContactGroupIdAndContactId(GROUP_A, absent)).thenReturn(0L);

        var response = service.removeMembers(GROUP_A, new BatchMemberRequest(List.of(removed, absent)));

        assertThat(response.results()).hasSize(2);
        assertThat(response.results().get(0).status()).isEqualTo(MemberBatchStatus.CREATED);
        assertThat(response.results().get(1).status()).isEqualTo(MemberBatchStatus.NOT_FOUND);
        assertThat(response.failed()).isZero();

        service.removeAllForGroup(GROUP_A);
        verify(memberRepository).deleteAll(memberRepository.findByContactGroupId(GROUP_A));
        service.removeAllForContact(removed);
        verify(memberRepository).deleteAll(memberRepository.findByContactId(removed));
    }
}

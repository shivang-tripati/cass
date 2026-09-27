package com.shivang.obd.contact;

import com.shivang.obd.common.api.response.ApiResponse;
import com.shivang.obd.common.api.response.PaginationMetadata;
import com.shivang.obd.common.api.response.ResponseFactory;
import com.shivang.obd.contact.dto.AddMemberRequest;
import com.shivang.obd.contact.dto.BatchMemberRequest;
import com.shivang.obd.contact.dto.BatchMemberResponse;
import com.shivang.obd.contact.dto.BatchMemberResult;
import com.shivang.obd.contact.dto.ContactGroupMemberResponse;
import com.shivang.obd.contact.dto.MemberBatchStatus;
import com.shivang.obd.security.CurrentUserProvider;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Canonical membership mutation/read service (VB-6B.2). Owns every
 * membership write — the single-add idempotent upsert (backed by the
 * {@code uq_cgm_group_contact} unique constraint), single/batch removal,
 * and the group/contact delete cascades — plus the paged member roster
 * and membership detail reads.
 *
 * <p>Boundary rules carried over from VB-6B.1:</p>
 * <ul>
 *   <li>Memberships are relationships only — this service NEVER creates,
 *       updates or deletes contacts; the contact identity flow
 *       ({@link ContactIdentityService} + {@link ContactGroupService})
 *       stays the only owner of contact rows.</li>
 *   <li>Tenant isolation is DB-enforced (composite FKs); the service
 *       resolves contacts strictly inside the group's tenant so foreign
 *       contacts are indistinguishable from missing (404).</li>
 *   <li>Group authorization is shared with {@link ContactGroupService}
 *       via {@link ContactGroupAccess} (404-cloaked).</li>
 * </ul>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ContactGroupMemberService {

    private static final List<String> MEMBER_SORTABLE_FIELDS =
        List.of("createdAt", "firstName", "phoneNumber");
    private static final Sort DEFAULT_MEMBER_SORT = Sort.by(Sort.Direction.ASC, "createdAt");
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_BATCH_ITEMS = 500;

    private final ContactGroupMemberRepository memberRepository;
    private final ContactRepository contactRepository;
    private final ContactGroupAccess groupAccess;
    private final CurrentUserProvider currentUserProvider;

    // === reads ===

    @Transactional(readOnly = true)
    public ApiResponse<List<ContactGroupMemberResponse>> listMembers(
        UUID groupId, int page, int size, String[] sortArr, String search
    ) {
        groupAccess.authorizedGroup(groupId, "CONTACT_VIEW");

        Page<ContactGroupMemberEntity> resultPage = memberRepository.findRosterPage(
            groupId, blankToNull(search),
            buildPageable(page, size, sortArr, DEFAULT_MEMBER_SORT, MEMBER_SORTABLE_FIELDS));
        List<ContactGroupMemberResponse> items = resultPage.getContent().stream()
            .map(ContactGroupMemberService::toResponse).toList();
        return ResponseFactory.page(items,
            PaginationMetadata.of(page, size, resultPage.getTotalElements()));
    }

    @Transactional(readOnly = true)
    public ApiResponse<ContactGroupMemberResponse> getMember(UUID groupId, UUID contactId) {
        groupAccess.authorizedGroup(groupId, "CONTACT_VIEW");
        ContactGroupMemberEntity member = memberRepository
            .findByContactGroupIdAndContactId(groupId, contactId)
            .orElseThrow(ContactGroupAccess::contactNotFound);
        return ResponseFactory.ok(toResponse(member));
    }

    // === single mutations ===

    /**
     * Idempotent membership add. The contact must be a live identity of
     * the group's tenant (never created here); an existing membership is
     * returned as-is. A concurrent duplicate loses to the unique
     * constraint and resolves to the same EXISTS outcome — never a 500.
     */
    @Transactional
    public AddOutcome addMember(UUID groupId, AddMemberRequest request) {
        ContactGroupEntity group = groupAccess.authorizedGroup(groupId, "CONTACT_MANAGE");
        UUID tenantId = group.getTenantId();
        UUID contactId = request.contactId();

        ContactEntity contact = contactRepository
            .findByIdAndTenantIdAndDeletedAtIsNull(contactId, tenantId)
            .orElseThrow(ContactGroupAccess::contactNotFound);

        UpsertOutcome outcome = insertMembership(tenantId, groupId, contactId);
        if (outcome.member().getContact() == null) {
            // A freshly inserted row has no loaded contact association (the
            // read-only join is not populated on insert), and a raced
            // duplicate must not query again (the transaction is aborted
            // server-side, PostgreSQL 25P02). Either way, embed the contact
            // we already resolved for validation.
            outcome.member().setContact(contact);
        }
        return new AddOutcome(toResponse(outcome.member()), outcome.created());
    }

    /**
     * Idempotent membership removal: an existing row is deleted, a
     * missing one is a successful no-op (204 either way). Contact
     * existence is deliberately irrelevant to a relationship delete.
     */
    @Transactional
    public void removeMember(UUID groupId, UUID contactId) {
        groupAccess.authorizedGroup(groupId, "CONTACT_MANAGE");
        memberRepository.deleteByContactGroupIdAndContactId(groupId, contactId);
    }

    // === batch mutations (per-item outcomes; never fail-fast) ===

    @Transactional
    public BatchMemberResponse addMembers(UUID groupId, BatchMemberRequest request) {
        ContactGroupEntity group = groupAccess.authorizedGroup(groupId, "CONTACT_MANAGE");
        UUID tenantId = group.getTenantId();

        List<BatchMemberResult> results = new ArrayList<>();
        Map<UUID, MemberBatchStatus> outcomes = new LinkedHashMap<>();
        for (UUID contactId : request.contactIds()) {
            if (outcomes.containsKey(contactId)) {
                // Duplicate id in the same request: deterministically replay
                // the first outcome under idempotent-add semantics — a
                // replayed CREATED becomes EXISTS (the pair is a member
                // after the first item), anything else repeats verbatim.
                // No second membership row can ever be created.
                MemberBatchStatus first = outcomes.get(contactId);
                results.add(BatchMemberResult.of(contactId,
                    first == MemberBatchStatus.CREATED ? MemberBatchStatus.EXISTS : first));
                continue;
            }
            BatchMemberResult result = addSingle(tenantId, groupId, contactId);
            outcomes.put(contactId, result.status());
            results.add(result);
        }
        return aggregate(results);
    }

    @Transactional
    public BatchMemberResponse removeMembers(UUID groupId, BatchMemberRequest request) {
        groupAccess.authorizedGroup(groupId, "CONTACT_MANAGE");

        List<BatchMemberResult> results = new ArrayList<>();
        Map<UUID, MemberBatchStatus> outcomes = new LinkedHashMap<>();
        for (UUID contactId : request.contactIds()) {
            if (outcomes.containsKey(contactId)) {
                // Idempotent replay: a repeated id after a successful
                // removal has nothing left to remove (NOT_FOUND); any other
                // first outcome repeats verbatim.
                MemberBatchStatus first = outcomes.get(contactId);
                results.add(BatchMemberResult.of(contactId,
                    first == MemberBatchStatus.CREATED ? MemberBatchStatus.NOT_FOUND : first));
                continue;
            }
            try {
                boolean removed = memberRepository
                    .deleteByContactGroupIdAndContactId(groupId, contactId) > 0;
                MemberBatchStatus status =
                    removed ? MemberBatchStatus.CREATED : MemberBatchStatus.NOT_FOUND;
                outcomes.put(contactId, status);
                results.add(BatchMemberResult.of(contactId, status));
            } catch (RuntimeException itemFailure) {
                log.warn("Batch member removal failed for contact {} in group {}",
                    contactId, groupId, itemFailure);
                outcomes.put(contactId, MemberBatchStatus.ERROR);
                results.add(BatchMemberResult.error(contactId,
                    "Removal failed; retry this item."));
            }
        }
        return aggregate(results);
    }

    private BatchMemberResult addSingle(UUID tenantId, UUID groupId, UUID contactId) {
        try {
            ContactEntity contact = contactRepository
                .findByIdAndTenantIdAndDeletedAtIsNull(contactId, tenantId)
                .orElse(null);
            if (contact == null) {
                return BatchMemberResult.of(contactId, MemberBatchStatus.NOT_FOUND_CONTACT);
            }
            UpsertOutcome outcome = insertMembership(tenantId, groupId, contactId);
            return BatchMemberResult.of(contactId,
                outcome.created() ? MemberBatchStatus.CREATED : MemberBatchStatus.EXISTS);
        } catch (RuntimeException itemFailure) {
            log.warn("Batch member add failed for contact {} in group {}",
                contactId, groupId, itemFailure);
            return BatchMemberResult.error(contactId, "Add failed; retry this item.");
        }
    }

    // === cascades (called by ContactGroupService; same-transaction) ===

    /** Removes every membership of a group (group soft-delete cascade). */
    @Transactional
    public void removeAllForGroup(UUID groupId) {
        memberRepository.deleteAll(memberRepository.findByContactGroupId(groupId));
    }

    /** Removes every membership of a contact (contact soft-delete cascade). */
    @Transactional
    public void removeAllForContact(UUID contactId) {
        memberRepository.deleteAll(memberRepository.findByContactId(contactId));
    }

    /** Live member contact ids of a group (legacy roster/export reader). */
    public List<UUID> memberContactIds(UUID groupId) {
        return memberRepository.findByContactGroupId(groupId).stream()
                .map(ContactGroupMemberEntity::getContactId)
                .toList();
    }

    // === internal ===

    /**
     * THE membership upsert path — the exact behavior of the pre-VB-6B.2
     * private {@code upsertMembership}: exists fast-path, flush inside the
     * guarded block so the unique-constraint race surfaces here, and a
     * raced duplicate resolves idempotently (constraint is the authority;
     * never a 500). Stamps {@code createdBy} from the authenticated user
     * (V46 reserved this audit surface).
     * ContactGroupService.createContact/importContacts delegate here.
     */
    UpsertOutcome insertMembership(UUID tenantId, UUID groupId, UUID contactId) {
        if (memberRepository.existsByContactGroupIdAndContactId(groupId, contactId)) {
            return new UpsertOutcome(
                memberRepository.findByContactGroupIdAndContactId(groupId, contactId)
                    .orElse(null),
                false);
        }
        ContactGroupMemberEntity member = new ContactGroupMemberEntity();
        member.setTenantId(tenantId);
        member.setContactGroupId(groupId);
        member.setContactId(contactId);
        member.setCreatedBy(currentUserProvider.current()
            .map(user -> user.userId().toString())
            .orElse(null));
        try {
            return new UpsertOutcome(memberRepository.saveAndFlush(member), true);
        } catch (DataIntegrityViolationException race) {
            // Concurrent duplicate membership: the unique constraint wins.
            // Deliberately NO follow-up lookup here — the transaction is
            // aborted server-side (PostgreSQL 25P02), so any further query
            // would fail; the idempotent outcome (exactly one row) is
            // guaranteed by the constraint itself. This is the exact
            // behavior of the pre-VB-6B.2 upsertMembership.
            log.info("Concurrent duplicate membership add resolved by unique constraint "
                + "(group={}, contact={})", groupId, contactId);
            return new UpsertOutcome(member, false);
        }
    }

    /** Result of the membership upsert: the row plus whether it was newly created. */
    record UpsertOutcome(ContactGroupMemberEntity member, boolean created) {
    }

    /** Live member counts for one page of groups in ONE grouped query. */
    Map<UUID, Long> memberCounts(List<UUID> groupIds) {
        if (groupIds.isEmpty()) {
            return Map.of();
        }
        return memberRepository.findCountsByGroupIds(groupIds).stream()
            .collect(java.util.stream.Collectors.toMap(
                row -> (UUID) row[0], row -> (Long) row[1]));
    }

    long memberCount(UUID groupId) {
        return memberRepository.countLiveByContactGroupId(groupId);
    }

    private static BatchMemberResponse aggregate(List<BatchMemberResult> results) {
        int failed = (int) results.stream()
            .filter(result -> result.status() == MemberBatchStatus.ERROR)
            .count();
        return new BatchMemberResponse(results, results.size() - failed, failed);
    }

    private static ContactGroupMemberResponse toResponse(ContactGroupMemberEntity member) {
        ContactEntity contact = member.getContact();
        return new ContactGroupMemberResponse(
            member.getId(),
            member.getContactGroupId(),
            member.getContactId(),
            member.getTenantId(),
            contact == null ? null : new com.shivang.obd.contact.dto.ContactResponse(
                contact.getId(),
                contact.getTenantId(),
                contact.getFirstName(),
                contact.getLastName(),
                contact.getPhoneNumber(),
                contact.getEmail(),
                contact.getAttributes(),
                contact.getCreatedAt(),
                contact.getUpdatedAt()),
            member.getCreatedAt(),
            parseUuidOrNull(member.getCreatedBy()));
    }

    private static UUID parseUuidOrNull(String createdBy) {
        if (createdBy == null || createdBy.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(createdBy);
        } catch (IllegalArgumentException unparsable) {
            return null; // legacy/non-UUID audit values stay unparsed
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private Pageable buildPageable(int page, int size, String[] sortArr, Sort defaultSort, List<String> allowedFields) {
        Sort sort = defaultSort;
        if (sortArr != null && sortArr.length > 0 && allowedFields.contains(sortArr[0])) {
            Sort.Direction direction = sortArr.length > 1 && "asc".equalsIgnoreCase(sortArr[1])
                ? Sort.Direction.ASC : Sort.Direction.DESC;
            sort = Sort.by(direction, sortArr[0]);
        }
        return PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE), sort);
    }

    /** Result of a single add: the membership plus whether it was newly created. */
    record AddOutcome(ContactGroupMemberResponse member, boolean created) {
    }
}

package com.shivang.obd.contact;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository for {@link ContactGroupMemberEntity} — the physical
 * (group, contact) relationship rows of V46. VB-6B.2 adds the
 * membership-API surface (count, idempotent delete, paged roster,
 * grouped counts) on top of the VB-6B.1 audience-selection queries.
 * Membership uniqueness and tenant isolation remain DB-enforced by
 * {@code uq_cgm_group_contact} and the composite FKs — never re-implemented here.
 */
public interface ContactGroupMemberRepository
        extends JpaRepository<ContactGroupMemberEntity, UUID> {

    /** Membership existence (idempotency pre-check for the add flow). */
    boolean existsByContactGroupIdAndContactId(UUID contactGroupId, UUID contactId);

    /** Live members of a group — the audience selection unit. */
    List<ContactGroupMemberEntity> findByContactGroupId(UUID contactGroupId);

    /** All groups containing a contact (reverse lookup / cascades). */
    List<ContactGroupMemberEntity> findByContactId(UUID contactId);

    /** The one membership row for a (group, contact) pair, when present. */
    Optional<ContactGroupMemberEntity> findByContactGroupIdAndContactId(UUID contactGroupId, UUID contactId);

    /** Group-scoped existence for delete-guards. */
    boolean existsByContactGroupId(UUID contactGroupId);

    /**
     * Live member count of one group (get-by-id / readiness checks).
     * Memberships of soft-deleted contacts are excluded so the direct
     * count agrees with the roster and the grouped live counts.
     */
    @Query("SELECT COUNT(m) FROM ContactGroupMemberEntity m JOIN m.contact c "
           + "WHERE m.contactGroupId = :groupId AND c.deletedAt IS NULL")
    long countLiveByContactGroupId(@Param("groupId") UUID groupId);

    /**
     * Idempotent single-membership removal. Returns whether a row was
     * actually deleted; a missing membership is a successful no-op.
     */
    long deleteByContactGroupIdAndContactId(UUID contactGroupId, UUID contactId);

    /**
     * Paged member roster: membership-led, joined to the live contact
     * payload. Soft-deleted contacts are excluded (the live identity is
     * the roster unit); a membership whose contact has been soft-deleted
     * is invisible here until the contact-delete flow removes the row.
     * {@code search} follows the application-wide case-insensitive
     * contains semantics over firstName/lastName/phoneNumber.
     */
    @Query("""
        SELECT m FROM ContactGroupMemberEntity m
        JOIN m.contact c
        WHERE m.contactGroupId = :groupId
          AND c.deletedAt IS NULL
          AND (COALESCE(:search, '') = ''
               OR LOWER(c.firstName) LIKE LOWER(CONCAT('%', :search, '%'))
               OR LOWER(COALESCE(c.lastName, '')) LIKE LOWER(CONCAT('%', :search, '%'))
               OR LOWER(c.phoneNumber) LIKE LOWER(CONCAT('%', :search, '%')))
        """)
    Page<ContactGroupMemberEntity> findRosterPage(
            @Param("groupId") UUID groupId,
            @Param("search") String search,
            Pageable pageable);

    /**
     * Live member counts for one page of groups in a single grouped
     * query (the list-page memberCount — never one count per group).
     * Element 0 is the group id, element 1 the live-member count.
     */
    @Query("""
        SELECT m.contactGroupId, COUNT(m)
        FROM ContactGroupMemberEntity m
        JOIN m.contact c
        WHERE m.contactGroupId IN :groupIds
          AND c.deletedAt IS NULL
        GROUP BY m.contactGroupId
        """)
    List<Object[]> findCountsByGroupIds(@Param("groupIds") Collection<UUID> groupIds);
}

package com.shivang.obd.contact;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository for the tenant-level {@link ContactEntity} identity
 * (VB-6B.1). Every lookup is tenant-scoped; the identity boundary is
 * {@code (tenantId, canonical phone, live)} and is DB-enforced by
 * {@code uq_contacts_tenant_phone_live}. Group participation is not part
 * of contact identity — membership queries live on
 * {@link ContactGroupMemberRepository}.
 */
public interface ContactRepository
        extends JpaRepository<ContactEntity, UUID>, JpaSpecificationExecutor<ContactEntity> {

    /** The contact identity within the tenant (404-cloaked scoping). */
    Optional<ContactEntity> findByIdAndTenantIdAndDeletedAtIsNull(UUID id, UUID tenantId);

    /** The live identity for a canonical phone within the tenant. */
    Optional<ContactEntity> findByTenantIdAndPhoneNumberAndDeletedAtIsNull(UUID tenantId, String phoneNumber);

    boolean existsByTenantIdAndPhoneNumberAndDeletedAtIsNull(UUID tenantId, String phoneNumber);

    boolean existsByTenantIdAndDeletedAtIsNull(UUID tenantId);

    /**
     * Canonical phone numbers of every live contact of the tenant. Backs
     * the bulk-import find-or-create with one batched lookup.
     */
    @Query("select c.phoneNumber from ContactEntity c " +
           "where c.tenantId = :tenantId and c.deletedAt is null")
    List<String> findLivePhoneNumbers(@Param("tenantId") UUID tenantId);

    /**
     * Live contact ids for the given canonical phone across the tenant's
     * groups — the logical identity resolution used by cross-campaign
     * consumers. Under identity uniqueness this is 0 or 1 live row.
     */
    @Query("select c.id from ContactEntity c " +
           "where c.tenantId = :tenantId and c.phoneNumber = :phoneNumber " +
           "and c.deletedAt is null")
    List<UUID> findIdsByTenantIdAndPhoneNumberAndDeletedAtIsNull(
            @Param("tenantId") UUID tenantId, @Param("phoneNumber") String phoneNumber);
}

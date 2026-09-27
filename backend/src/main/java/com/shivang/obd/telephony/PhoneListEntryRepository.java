package com.shivang.obd.telephony;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface PhoneListEntryRepository extends JpaRepository<PhoneListEntry, UUID>, JpaSpecificationExecutor<PhoneListEntry> {

    Optional<PhoneListEntry> findByIdAndDeletedAtIsNull(UUID id);

    // Platform scope (no scope ID)
    List<PhoneListEntry> findByTypeAndScopeTypeAndActiveTrueAndDeletedAtIsNull(PhoneListType type, ScopeType scopeType);

    // Reseller scope
    List<PhoneListEntry> findByTypeAndScopeTypeAndScopeResellerIdAndActiveTrueAndDeletedAtIsNull(
            PhoneListType type, ScopeType scopeType, UUID scopeResellerId);

    // Tenant scope
    List<PhoneListEntry> findByTypeAndScopeTypeAndScopeTenantIdAndActiveTrueAndDeletedAtIsNull(
            PhoneListType type, ScopeType scopeType, UUID scopeTenantId);

    List<PhoneListEntry> findByTypeAndActiveTrueAndDeletedAtIsNull(PhoneListType type);

    /**
     * Find any active platform block/protected entries for a number.
     */
    List<PhoneListEntry> findByNormalizedNumberAndTypeInAndActiveTrueAndDeletedAtIsNull(
            String normalizedNumber, List<PhoneListType> types);

    /**
     * Find any active tenant-specific blocklist/whitelist for a tenant.
     */
    List<PhoneListEntry> findByNormalizedNumberAndScopeTypeAndScopeTenantIdAndActiveTrueAndDeletedAtIsNull(
            String normalizedNumber, ScopeType scopeType, UUID scopeTenantId);

    /**
     * Find active entries for reseller scope.
     */
    List<PhoneListEntry> findByScopeTypeAndScopeResellerIdAndActiveTrueAndDeletedAtIsNull(
            ScopeType scopeType, UUID scopeResellerId);
}
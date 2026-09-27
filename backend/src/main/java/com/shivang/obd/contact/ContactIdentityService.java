package com.shivang.obd.contact;

import com.shivang.obd.common.api.error.CommonErrorCode;
import com.shivang.obd.common.exception.BusinessException;
import com.shivang.obd.common.exception.ConflictException;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Single boundary for Contact identity semantics (VB-6B.1).
 *
 * <p>Identity levels:</p>
 * <ul>
 *   <li><b>Contact identity</b> — {@code (tenant_id, canonical_phone_number)}
 *       for live rows, DB-enforced by {@code uq_contacts_tenant_phone_live}.
 *       One tenant + number = exactly one live Contact, regardless of how
 *       many groups contain it. No global phone uniqueness exists — the
 *       tenant is inside the key.</li>
 *   <li><b>Group participation</b> — a separate concern
 *       ({@link ContactGroupMemberEntity}); never part of identity
 *       validation.</li>
 * </ul>
 *
 * <p>This service canonicalizes every Contact-input phone value through
 * {@link ContactValidation#canonicalizePhoneNumber} (the single persistence
 * canonicalizer; the voice layer keeps {@code PhoneNumberNormalizer} for
 * dial strings), enforces identity deduplication with a typed 409, and
 * translates the database unique-constraint race into the same typed
 * conflict — the constraint remains the final concurrency authority.</p>
 */
@Service
@Slf4j
public class ContactIdentityService {

    private final ContactRepository contactRepository;

    public ContactIdentityService(ContactRepository contactRepository) {
        this.contactRepository = contactRepository;
    }

    /**
     * Canonicalizes and validates a Contact-input phone value.
     *
     * @return the canonical E.164 form
     * @throws BusinessException when the value cannot reach canonical form
     *         (API maps to 400 validation semantics)
     */
    public String canonicalPhoneNumber(String rawPhoneNumber) {
        String canonical = ContactValidation.canonicalizePhoneNumber(rawPhoneNumber);
        if (canonical == null) {
            throw new BusinessException(
                    CommonErrorCode.VALIDATION_ERROR,
                    "Phone number must be a valid E.164 number, e.g. +918012345678.");
        }
        return canonical;
    }

    /**
     * Asserts the canonical phone is not already a live contact of the
     * tenant. Identity is tenant-scoped — the same number under a
     * different tenant is a different Contact and remains allowed.
     *
     * @throws ConflictException when the tenant already holds this live
     *         identity
     */
    public void assertPhoneAvailableForTenant(UUID tenantId, String canonicalPhoneNumber) {
        if (contactRepository.existsByTenantIdAndPhoneNumberAndDeletedAtIsNull(
                tenantId, canonicalPhoneNumber)) {
            throw duplicateContactConflict(tenantId, canonicalPhoneNumber);
        }
    }

    /**
     * Translates the database unique-constraint race (concurrent creates
     * of the same tenant+phone) into the typed duplicate conflict. The
     * constraint stays the authoritative correctness mechanism; this only
     * replaces the raw {@link DataIntegrityViolationException} (500) with
     * the deterministic 409.
     */
    public ConflictException duplicateContactConflict(UUID tenantId, String canonicalPhoneNumber) {
        log.info("Duplicate contact identity detected (tenant={}, phoneHash={})",
                tenantId, Integer.toHexString(canonicalPhoneNumber.hashCode()));
        return new ConflictException(
                "A contact with this phone number already exists for this tenant.");
    }

    /**
     * The live Contact identity for a canonical tenant phone, when present.
     * The find half of find-or-create for import/inline consumers.
     */
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public List<UUID> findLiveContactIds(UUID tenantId, String canonicalPhoneNumber) {
        return contactRepository.findIdsByTenantIdAndPhoneNumberAndDeletedAtIsNull(
                tenantId, canonicalPhoneNumber);
    }
}

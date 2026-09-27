package com.shivang.obd.contact;

import com.shivang.obd.contact.dto.ContactResponse;
import com.shivang.obd.contact.dto.CreateContactRequest;
import com.shivang.obd.contact.dto.UpdateContactRequest;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Single DTO &lt;-&gt; entity conversion point for contacts (VB-6B.1).
 * Ownership is always derived from the authenticated tenant context, never
 * from the request; a Contact carries no group — group participation is a
 * membership concern (VB-6B.2).
 */
@Component
public class ContactMapper {

    public ContactEntity toEntity(CreateContactRequest request, UUID tenantId) {
        ContactEntity entity = new ContactEntity();
        applyCommon(entity, request.firstName(), request.lastName(), request.phoneNumber(),
            request.email(), request.attributes());
        entity.setTenantId(tenantId);
        return entity;
    }

    /**
     * Persistence with an already-canonicalized phone value — the
     * canonical form is both the dedup key and the stored value.
     */
    public ContactEntity toEntity(
            CreateContactRequest request, UUID tenantId, String canonicalPhoneNumber) {
        ContactEntity entity = new ContactEntity();
        applyCommon(entity, request.firstName(), request.lastName(), canonicalPhoneNumber,
            request.email(), request.attributes());
        entity.setTenantId(tenantId);
        return entity;
    }

    public void updateEntity(ContactEntity entity, UpdateContactRequest request) {
        updateEntity(entity, request, request.phoneNumber());
    }

    /** Update with an already-canonicalized phone value. */
    public void updateEntity(ContactEntity entity, UpdateContactRequest request, String canonicalPhoneNumber) {
        applyCommon(entity, request.firstName(), request.lastName(), canonicalPhoneNumber,
            request.email(), request.attributes());
    }

    public ContactResponse toResponse(ContactEntity entity) {
        return new ContactResponse(
            entity.getId(),
            entity.getTenantId(),
            entity.getFirstName(),
            entity.getLastName(),
            entity.getPhoneNumber(),
            entity.getEmail(),
            entity.getAttributes(),
            entity.getCreatedAt(),
            entity.getUpdatedAt()
        );
    }

    private void applyCommon(
        ContactEntity entity,
        String firstName,
        String lastName,
        String phoneNumber,
        String email,
        tools.jackson.databind.JsonNode attributes
    ) {
        entity.setFirstName(firstName == null ? null : firstName.trim());
        entity.setLastName(blankToNull(lastName));
        entity.setPhoneNumber(phoneNumber.trim());
        entity.setEmail(blankToNull(email));
        entity.setAttributes(attributes);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}

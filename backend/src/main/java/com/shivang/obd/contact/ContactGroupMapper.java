package com.shivang.obd.contact;

import com.shivang.obd.contact.dto.ContactGroupResponse;
import com.shivang.obd.contact.dto.CreateContactGroupRequest;
import com.shivang.obd.contact.dto.UpdateContactGroupRequest;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Single DTO &lt;-&gt; entity conversion point for contact groups. */
@Component
public class ContactGroupMapper {

    public ContactGroupEntity toEntity(CreateContactGroupRequest request, UUID tenantId) {
        ContactGroupEntity entity = new ContactGroupEntity();
        applyCommon(entity, request.name(), request.description());
        entity.setTenantId(tenantId);
        return entity;
    }

    public void updateEntity(ContactGroupEntity entity, UpdateContactGroupRequest request) {
        applyCommon(entity, request.name(), request.description());
    }

    public ContactGroupResponse toResponse(ContactGroupEntity entity) {
        return new ContactGroupResponse(
            entity.getId(),
            entity.getTenantId(),
            entity.getName(),
            entity.getDescription(),
            0L,
            entity.getCreatedAt(),
            entity.getUpdatedAt()
        );
    }

    private void applyCommon(ContactGroupEntity entity, String name, String description) {
        entity.setName(name);
        entity.setDescription(description);
    }
}

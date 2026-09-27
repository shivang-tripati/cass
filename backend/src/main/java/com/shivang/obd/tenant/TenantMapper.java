package com.shivang.obd.tenant;

import com.shivang.obd.tenant.dto.CreateTenantRequest;
import com.shivang.obd.tenant.dto.TenantResponse;
import com.shivang.obd.tenant.dto.UpdateTenantRequest;
import org.springframework.stereotype.Component;

@Component
public class TenantMapper {

    public TenantEntity toEntity(CreateTenantRequest request, java.util.UUID resellerId) {
        var entity = new TenantEntity();
        entity.setName(request.name().trim());
        entity.setSlug(request.slug());
        entity.setResellerId(resellerId);
        return entity;
    }

    public void updateEntity(TenantEntity entity, UpdateTenantRequest request) {
        if (request.name() != null && !request.name().isBlank()) {
            entity.setName(request.name().trim());
        }
    }

    public TenantResponse toResponse(TenantEntity entity) {
        return new TenantResponse(
            entity.getId(),
            entity.getName(),
            entity.getSlug(),
            entity.getStatus(),
            entity.getResellerId(),
            entity.getCreatedAt(),
            entity.getUpdatedAt()
        );
    }
}

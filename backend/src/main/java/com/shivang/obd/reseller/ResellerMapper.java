package com.shivang.obd.reseller;

import com.shivang.obd.reseller.dto.CreateResellerRequest;
import com.shivang.obd.reseller.dto.ResellerResponse;
import com.shivang.obd.reseller.dto.UpdateResellerRequest;
import org.springframework.stereotype.Component;

@Component
public class ResellerMapper {

    public ResellerEntity toEntity(CreateResellerRequest request) {
        var entity = new ResellerEntity();
        entity.setName(request.name().trim());
        entity.setSlug(request.slug());
        entity.setDisplayName(request.displayName());
        entity.setSupportEmail(request.supportEmail());
        entity.setCustomDomain(request.customDomain());
        entity.setLogoUrl(request.logoUrl());
        entity.setPrimaryColor(request.primaryColor());
        return entity;
    }

    public void updateEntity(ResellerEntity entity, UpdateResellerRequest request) {
        if (request.name() != null && !request.name().isBlank()) {
            entity.setName(request.name().trim());
        }
        if (request.displayName() != null) {
            entity.setDisplayName(request.displayName());
        }
        if (request.supportEmail() != null) {
            entity.setSupportEmail(request.supportEmail());
        }
        if (request.logoUrl() != null) {
            entity.setLogoUrl(request.logoUrl());
        }
        if (request.primaryColor() != null) {
            entity.setPrimaryColor(request.primaryColor());
        }
    }

    public ResellerResponse toResponse(ResellerEntity entity) {
        return new ResellerResponse(
            entity.getId(),
            entity.getName(),
            entity.getSlug(),
            entity.getDisplayName(),
            entity.getStatus(),
            entity.getSupportEmail(),
            entity.getLogoUrl(),
            entity.getPrimaryColor(),
            entity.getCreatedAt(),
            entity.getUpdatedAt()
        );
    }
}

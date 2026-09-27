package com.shivang.obd.did;

import com.shivang.obd.did.dto.CreateDidRequest;
import com.shivang.obd.did.dto.DidResponse;
import com.shivang.obd.did.dto.UpdateDidRequest;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Single DTO &lt;-&gt; entity conversion point for the DID module.
 * Ownership (tenantId/resellerId) is applied by DidService from
 * server-derived context, never from this mapper's request fields.
 */
@Component
public class DidMapper {

    public DidEntity toEntity(CreateDidRequest request) {
        DidEntity entity = new DidEntity();
        applyCommon(entity, request.countryCode(), request.areaCode(), request.circle(),
            request.numberType(), request.provider(), request.capabilities(),
            request.status(), request.allocationState());
        entity.setE164Number(request.e164Number());
        return entity;
    }

    public void updateEntity(DidEntity entity, UpdateDidRequest request) {
        applyCommon(entity, request.countryCode(), request.areaCode(), request.circle(),
            request.numberType(), request.provider(), request.capabilities(),
            request.status(), request.allocationState());
    }

    public DidResponse toResponse(DidEntity entity) {
        return new DidResponse(
            entity.getId(),
            entity.getTenantId(),
            entity.getResellerId(),
            entity.getE164Number(),
            entity.getCountryCode(),
            entity.getAreaCode(),
            entity.getCircle(),
            entity.getNumberType(),
            entity.getProvider(),
            entity.getStatus(),
            copyCapabilities(entity.getCapabilities()),
            entity.getAllocationState(),
            entity.getAllocationSource(),
            entity.getCreatedAt(),
            entity.getUpdatedAt()
        );
    }

    // === internal ===

    private void applyCommon(
        DidEntity entity,
        String countryCode,
        String areaCode,
        String circle,
        NumberType numberType,
        String provider,
        Set<DidCapability> capabilities,
        DidStatus status,
        AllocationState allocationState
    ) {
        entity.setCountryCode(countryCode);
        entity.setAreaCode(emptyToNull(areaCode));
        entity.setCircle(emptyToNull(circle));
        entity.setNumberType(numberType);
        entity.setProvider(provider);
        entity.setCapabilities(normalizeCapabilities(capabilities));
        entity.setStatus(status == null ? DidStatus.ACTIVE : status);
        entity.setAllocationState(allocationState == null ? AllocationState.AVAILABLE : allocationState);
    }

    private Set<DidCapability> normalizeCapabilities(Set<DidCapability> capabilities) {
        return capabilities == null || capabilities.isEmpty() ? null : new LinkedHashSet<>(capabilities);
    }

    private Set<DidCapability> copyCapabilities(Set<DidCapability> capabilities) {
        return capabilities == null ? null : new LinkedHashSet<>(capabilities);
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}

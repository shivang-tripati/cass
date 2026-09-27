package com.shivang.obd.telephony;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface SipGatewayRepository extends JpaRepository<SipGateway, UUID>, JpaSpecificationExecutor<SipGateway> {

    Optional<SipGateway> findByIdAndDeletedAtIsNull(UUID id);

    Optional<SipGateway> findByNameAndDeletedAtIsNull(String name);

    Optional<SipGateway> findByFreeSwitchGatewayNameAndDeletedAtIsNull(String freeSwitchGatewayName);

    boolean existsByNameAndDeletedAtIsNull(String name);

    boolean existsByFreeSwitchGatewayNameAndDeletedAtIsNull(String freeSwitchGatewayName);

    List<SipGateway> findByStatusAndEnabledTrueAndDeletedAtIsNull(SipGatewayStatus status);

    List<SipGateway> findByProviderAndStatusAndEnabledTrueAndDeletedAtIsNull(String provider, SipGatewayStatus status);
}

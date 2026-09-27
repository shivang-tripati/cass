package com.shivang.obd.voice.agent;

import com.shivang.obd.common.audit.AuditableEntity;
import com.shivang.obd.voice.call.EndpointType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The dial destination for an agent leg (VB-3). The application service
 * only ever sees "endpoint type + dial target"; FreeSWITCH-specific dial
 * string construction stays behind the telephony boundary.
 * <p>
 * VB-3 accepts SIP and EXTERNAL_FORWARD endpoints (dialable by the existing
 * FreeSWITCH setup). WEBRTC/MOBILE_APP/AI endpoints are modeled but
 * rejected by eligibility — documented limitation, not implemented.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "agent_endpoints", indexes = {
    @Index(name = "idx_agent_endpoints_agent", columnList = "agent_id,enabled,deleted_at"),
    @Index(name = "idx_agent_endpoints_tenant", columnList = "tenant_id,deleted_at")
})
public class AgentEndpointEntity extends AuditableEntity {

    /** Owning agent. */
    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    /** Owning tenant — enforced on every lookup (isolation). */
    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    /** Destination technology. */
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Enumerated(EnumType.STRING)
    @Column(name = "endpoint_type", nullable = false)
    private EndpointType endpointType = EndpointType.SIP;

    /** Dial string FreeSWITCH can resolve (SIP URI / E.164). */
    @Column(name = "dial_target", nullable = false, length = 255)
    private String dialTarget;

    /** Disabled endpoints make the agent ineligible. */
    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;
}

# OBD Platform - Architecture

## System Context
# System Architecture – OBD Platform

```text
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│  Browser    │◄──►│   Next.js    │◄──►│  Spring Boot │
│  (React)    │     │ (Frontend)  │     │   (API)     │
└─────────────┘     └─────────────┘     └──────┬──────┘
                                               │
                                      ┌────────▼────────┐
                                      │ PostgreSQL +    │
                                      │ Redis           │
                                      └────────┬────────┘
                                               │
                                      ┌────────▼────────┐
                                      │ Kamailio +      │
                                      │ Asterisk        │
                                      └─────────────────┘
```

## Layered Architecture

### 1. Presentation Layer
- **Frontend**: Next.js 14+ with App Router
- **Admin Portal**: Campaign management, reporting
- **Agent Portal**: WebRTC softphone, disposition logging
- **White-Label**: Dynamic branding via middleware

### 2. Application Layer (Spring Boot Modular Monolith)
- **Core Module**: Domain models, events, utilities
- **Tenant Module**: Hierarchy, RBAC, white-label config
- **Telephony Module**: ARI WebSocket, call control
- **Campaign Module**: Dialer engine, lead management
- **Agent Module**: State machine, softphone config
- **Number Module**: DID inventory, allocation
- **Audio Module**: Upload, approval, transcoding
- **Billing Module**: Credit management, usage tracking
- **Integration Module**: Webhooks, CRM connectors
- **Reporting Module**: CDR processing, analytics

### 3. Data Layer
- **PostgreSQL 16**: RLS, JSONB, partitioning, SKIP LOCKED
- **Redis**: Agent state, DND cache, rate limiting
- **MinIO/S3**: Audio files, call recordings
- **Kafka**: Async event streaming

### 4. Telephony Layer
- **Kamailio**: SIP proxy, NAT traversal, load balancing
- **Asterisk 20+**: Media engine, ARI control
- **WebRTC**: Browser-based softphone (SIP.js)

## Key Design Patterns

### 1. Repository Pattern (Spring Data JPA)
```java
@Repository
@Filter(name = "tenantFilter", condition = "tenantId = :tenantId")
public interface CampaignRepository extends JpaRepository<Campaign, Long> {
    @Query("SELECT c FROM Campaign c WHERE c.status = :status")
    List<Campaign> findByStatus(@Param("status") CampaignStatus status);
}
```
### 2. Event-Driven (Kafka)
```java
@Component
public class CallCompletedEventPublisher {
    @Autowired
    private KafkaTemplate<String, CallEvent> kafkaTemplate;
    
    public void publish(CallLog callLog) {
        CallEvent event = CallEvent.from(callLog);
        kafkaTemplate.send("call.events", event);
    }
}
```

### 3. State Machine (Agent)
```java
public enum AgentState {
    OFFLINE, AVAILABLE, DIALING, RINGING, 
    CONNECTED, WRAP_UP, BREAK
}

@Component
public class AgentStateManager {
    private final RedisTemplate<String, Object> redis;
    
    public void transition(Long agentId, AgentState newState) {
        // Update Redis state
        // Publish AGENT_STATE_CHANGED event
    }
}
```

### 4. Circuit Breaker (Dialer)
```java
@Component
public class AbandonmentCircuitBreaker {
    private static final double MAX_ABANDONMENT = 0.03;
    
    public boolean isTripped(Long campaignId, double currentRate) {
        if (currentRate > MAX_ABANDONMENT) {
            // Log incident
            // Publish alert
            return true;
        }
        return false;
    }
}
```

## Critical Flows
### 1. Call Origination Flow
1. User starts campaign
2. Dialer engine calculates pacing
3. Claim leads with FOR UPDATE SKIP LOCKED
4. Originate call via ARI
5. On answer → play audio / handle DTMF / bridge to agent
6. On hangup → publish CALL_COMPLETED event
7. Update lead status, campaign stats
8. Generate CDR, deduct billing
9. Send webhook (if configured)

### 2. Lead Import Flow

1. Upload CSV
2. Validate format
3. Check DND registry (Redis)
4. Check tenant block list
5. Remove duplicates
6. Batch insert (500 records/batch)
7. Update campaign stats

###  3. Agent State Flow
1. Agent logs in → state = AVAILABLE
2. Incoming call → state = DIALING
3. Call ringing → state = RINGING
4. Call answered → state = CONNECTED
5. Call ends → state = WRAP_UP
6. Disposition submitted → state = AVAILABLE

## Security Architecture

### 1. Authentication
JWT: Access token (15min) + refresh token (7d, HTTP-only cookie)
SIP Digest: Agent softphone login
API Key: Programmatic access (AccountSID:AuthToken)

### 2. Authorization (RBAC)
Roles (hierarchical):
  - super_admin: Platform-wide controls
  - reseller_admin: Reseller management
  - tenant_admin: Full tenant access
  - tenant_manager: Read+manage agents/campaigns
  - agent: Own calls only
  - report_viewer: Read-only reports

### 3. Data Isolation
RLS: PostgreSQL row-level security
JPA @Filter: Application-level filtering
Server-Side Enforcement: Never trust frontend for tenant_id

## Performance Optimizations
1. Database
Partitioning: call_logs by month
Indexes: Partial indexes for dialer queue
SKIP LOCKED: Race-free lead claiming
Denormalization: campaign_stats table for real-time metrics

## 2. Caching
Redis: Agent state, DND lookups, reseller branding
Caffeine: In-memory cache for frequent lookups

## 3. Async Processing
Kafka: CDR processing, webhooks, billing
@Async: CSV imports, audio transcoding

## 4. WebSocket
Java 21 Virtual Threads: Handle 10,000+ connections
Connection Pooling: Reuse ARI connections

## Deployment Architecture

## 1. Development
Docker Compose (local)
PostgreSQL, Redis, Kafka, MinIO
Asterisk + Kamailio in containers

## 2. Staging
Kubernetes (minikube or cloud)
1x Asterisk node, 1x Kamailio

## 3. Production
Kubernetes (managed)
Load-balanced Asterisk cluster
Kamailio with active-passive failover
PostgreSQL with replication

## 4. Monitoring Stack
Metrics: Prometheus + Grafana
Logs: ELK Stack (Elasticsearch, Logstash, Kibana)
Tracing: Jaeger
Alerts: PagerDuty/Opsgenie
APM: New Relic or Datadog

## 5. Compliance Requirements
DND Registry: Check before every dial
Call Recording Consent: Beep tone or IVR announcement
Data Retention: Configurable per tenant (30 days to 7 years)
GDPR/DPDP: Right to deletion, PII encryption
TCPA: <3% abandonment rate
Number KYC: Documents required for regulated numbers
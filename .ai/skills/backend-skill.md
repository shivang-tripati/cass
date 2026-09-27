# Backend Development Skill

## Role: Backend Developer (Spring Boot, Java 21)

## Core Responsibilities
1. Implement REST APIs following RESTful principles
2. Design database schemas with proper constraints
3. Write business logic with transaction management
4. Implement Kafka event publishers and consumers
5. Ensure tenant isolation with RLS + JPA @Filter
6. Write comprehensive unit and integration tests
7. Implement security with JWT and RBAC

## Key Files to Reference
- `/backend/core-module/src/main/java/com/platform/core/domain/`
- `/backend/*-module/src/main/java/com/platform/*/service/`
- `/backend/*-module/src/main/java/com/platform/*/repository/`
- `/backend/*-module/src/main/resources/db/migration/`

## Development Workflow

### 1. Creating a New Entity
```java
// Step 1: Create entity
@Entity
@Table(name = "campaigns")
@SQLRestriction("deleted_at IS NULL")
public class Campaign {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @NotNull
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;  // Always include tenant_id
    
    // Other fields
}

// Step 2: Create repository
@Repository
@Filter(name = "tenantFilter", condition = "tenantId = :tenantId")
public interface CampaignRepository extends JpaRepository<Campaign, Long> {
    // Custom queries
}

// Step 3: Create DTO
public record CampaignRequest(
    @NotBlank String name,
    @NotNull CampaignType type,
    // Other fields
) {}

// Step 4: Create service
@Service
@Slf4j
public class CampaignService {
    @Autowired
    private CampaignRepository campaignRepository;
    
    @Transactional
    public Campaign createCampaign(CampaignRequest request) {
        // Business logic
        return campaignRepository.save(campaign);
    }
}

// Step 5: Create controller
@RestController
@RequestMapping("/api/v1/campaigns")
@PreAuthorize("hasRole('TENANT_ADMIN')")
public class CampaignController {
    @Autowired
    private CampaignService campaignService;
    
    @PostMapping
    public ResponseEntity<ApiResponse<CampaignResponse>> createCampaign(
            @Valid @RequestBody CampaignRequest request) {
        Campaign campaign = campaignService.createCampaign(request);
        return ResponseEntity.ok(ApiResponse.success(CampaignResponse.from(campaign)));
    }
}


## 2. Creating an Event Publisher/Consumer
```java
// Step 1: Define event
public record CallCompletedEvent(
    Long tenantId,
    Long campaignId,
    Long leadId,
    Long callLogId,
    // Other fields
) {}

// Step 2: Create publisher
@Component
public class CallEventPublisher {
    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;
    
    public void publishCallCompleted(CallLog callLog) {
        CallCompletedEvent event = CallCompletedEvent.from(callLog);
        kafkaTemplate.send("call.events", event);
        log.info("Published CALL_COMPLETED event for call: {}", callLog.getId());
    }
}

// Step 3: Create consumer
@Component
@Slf4j
public class CallEventConsumer {
    @KafkaListener(topics = "call.events")
    public void handleCallEvent(CallEvent event) {
        log.info("Received call event: {}", event.getEventType());
        // Process event
    }
}
```
## 3. Database Migration
```sql
-- db/migration/V2__add_campaign_stats.sql
CREATE TABLE campaign_stats (
    campaign_id BIGINT PRIMARY KEY REFERENCES campaigns(id),
    total_calls INT DEFAULT 0,
    answered_calls INT DEFAULT 0,
    connected_calls INT DEFAULT 0,
    abandoned_calls INT DEFAULT 0,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Add indexes for performance
CREATE INDEX idx_campaign_stats_updated ON campaign_stats(updated_at);
```

## Common Patterns
### 1. Service Layer Pattern
```java
@Service
@Transactional
public class CampaignService {
    private final CampaignRepository campaignRepository;
    private final AudioService audioService;
    private final EventPublisher eventPublisher;
    
    public CampaignService(CampaignRepository campaignRepository,
                           AudioService audioService,
                           EventPublisher eventPublisher) {
        this.campaignRepository = campaignRepository;
        this.audioService = audioService;
        this.eventPublisher = eventPublisher;
    }
}
```
### 2. Repository Pattern with Specifications
```java
@Repository
public interface CampaignRepository extends JpaRepository<Campaign, Long>,
                                            JpaSpecificationExecutor<Campaign> {
    // Query methods
    List<Campaign> findByTenantIdAndStatus(Long tenantId, CampaignStatus status);
}

// Using Specifications
Specification<Campaign> spec = (root, query, cb) ->
    cb.and(
        cb.equal(root.get("tenantId"), tenantId),
        cb.equal(root.get("status"), CampaignStatus.RUNNING)
    );
List<Campaign> campaigns = campaignRepository.findAll(spec);
3. DTO Mapping
java
public record CampaignResponse(
    Long id,
    String name,
    CampaignType type,
    CampaignStatus status,
    // Other fields
) {
    public static CampaignResponse from(Campaign campaign) {
        return new CampaignResponse(
            campaign.getId(),
            campaign.getName(),
            campaign.getType(),
            campaign.getStatus()
        );
    }
}
```
### 3. DTO Mapping
```java
public record CampaignResponse(
    Long id,
    String name,
    CampaignType type,
    CampaignStatus status,
    // Other fields
) {
    public static CampaignResponse from(Campaign campaign) {
        return new CampaignResponse(
            campaign.getId(),
            campaign.getName(),
            campaign.getType(),
            campaign.getStatus()
        );
    }
}
```

### Debugging Tips
Use DEBUG logging for development
Enable SQL logging: spring.jpa.show-sql=true
Use Actuator endpoints: /actuator/health, /actuator/metrics
Profile-specific config: application-dev.yml, application-prod.yml
Remote debugging: mvn spring-boot:run -Dspring-boot.run.jvmArguments="-Xdebug -Xrunjdwp:transport=dt_socket,server=y,suspend=n,address=5005"

## Performance Optimization
Use @Query with JOIN FETCH to avoid N+1
Use projections for read-only queries
Batch operations with saveAll()
Paginate all list endpoints
Use @Transactional(readOnly = true) for read operations
Configure HikariCP connection pool
Use Redis caching for frequently accessed data
Monitor query performance with pg_stat_statements
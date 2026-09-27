# OBD Platform - Development Rules

## ⚠️ Critical Rules (NEVER BREAK)

### 1. Tenant Isolation
```java
// ✅ DO: Extract tenant from JWT
@GetMapping("/campaigns")
public ResponseEntity<?> getCampaigns(@AuthenticationPrincipal UserPrincipal user) {
    Long tenantId = user.getTenantId();  // From JWT
    // Use tenantId in all queries
}

// ❌ NEVER: Trust frontend for tenant_id
@GetMapping("/campaigns")
public ResponseEntity<?> getCampaigns(@RequestParam Long tenantId) {
    // NEVER use this approach
}
```

### 2. DND Enforcement
```java
// ✅ DO: Check DND before every dial
public boolean isDnd(String phoneNumber) {
    String cached = redis.get("dnd:" + phoneNumber);
    if (cached != null) return Boolean.parseBoolean(cached);
    // Check database if not in cache
    return dncRepository.existsByPhoneNumber(phoneNumber);
}

// ❌ NEVER: Skip DND check for "urgent" campaigns
public void dialLead(Lead lead) {
    // If isDnd(lead.getPhoneNumber()) return; // Must check!
}
```

### 3. Async Processing
```java
// ✅ DO: Use Kafka for CDR processing
@Component
public class CallEventProcessor {
    @KafkaListener(topics = "call.events")
    public void processCallEvent(CallEvent event) {
        // Process CDR asynchronously
    }
}

// ❌ NEVER: Process CDR synchronously in ARI WebSocket thread
public void onCallEnded(Channel channel) {
    // Save CDR directly - will block WebSocket!
}
```
### 4. Lead Claiming
// ✅ DO: Use FOR UPDATE SKIP LOCKED
```sql
@Query(value = """
    UPDATE leads 
    SET status = 'dialing', attempt_count = attempt_count + 1
    WHERE id IN (
        SELECT id FROM leads 
        WHERE tenant_id = :tenantId 
        AND campaign_id = :campaignId
        AND status IN ('new', 'queued')
        LIMIT :batchSize
        FOR UPDATE SKIP LOCKED
    )
    RETURNING id, phone_number
    """, nativeQuery = true)
List<Object[]> claimLeads(@Param("tenantId") Long tenantId, 
                          @Param("campaignId") Long campaignId,
                          @Param("batchSize") int batchSize);
```
// ❌ NEVER: Use simple SELECT ... FOR UPDATE (will lock whole table)
// ❌ NEVER: Use SELECT without SKIP LOCKED in multi-threaded environment

### 5. Caller ID Validation
```java
// ✅ DO: Validate caller_id from number_inventory
@PreAuthorize("hasPermission(#campaign.callerId, 'number_inventory', 'OWN')")
public Campaign createCampaign(Campaign campaign) {
    // Validate caller_id is owned by tenant
    return campaignRepository.save(campaign);
}

// ❌ NEVER: Allow free-text caller_id
public Campaign createCampaign(String callerId) {
    // NEVER use callerId directly - must be validated
}
```

### 6. Audio Approval
```java
// ✅ DO: Check audio approval before campaign start
public void startCampaign(Long campaignId) {
    Campaign campaign = campaignRepository.findById(campaignId).orElseThrow();
    if (campaign.getAudioFile() != null && 
        campaign.getAudioFile().getStatus() != AudioStatus.APPROVED) {
        throw new BusinessException("AUDIO_NOT_APPROVED");
    }
    // Start campaign
}

// ❌ NEVER: Start campaign with unapproved audio
```
### 7. RBAC Enforcement
```java
// ✅ DO: Use @PreAuthorize at controller level
@PreAuthorize("hasRole('TENANT_ADMIN')")
@PostMapping("/campaigns")
public ResponseEntity<Campaign> createCampaign(@Valid @RequestBody CampaignRequest request) {
    // Only tenant_admin+ can create campaigns
}

// ❌ NEVER: Trust frontend to enforce permissions
// ❌ NEVER: Skip authorization checks
```

## Best Practices

### 1. Code Organization
backend/
├── module-name/
│   ├── controller/          # API endpoints
│   ├── service/             # Business logic
│   ├── repository/          # Data access
│   ├── model/               # Entities, DTOs
│   ├── event/               # Event publishers/consumers
│   ├── config/              # Module configuration
│   └── exception/           # Module-specific exceptions

### 2. Naming Conventions
Classes: PascalCase (e.g., CampaignService)
Interfaces: PascalCase (e.g., ICampaignRepository)
Methods: camelCase (e.g., getCampaignById)
Constants: UPPER_SNAKE_CASE (e.g., MAX_RETRY_ATTEMPTS)
Packages: lowercase (e.g., com.platform.campaign)
DTOs: Suffix Request/Response (e.g., CampaignRequest)

### 3. Logging
```java
// ✅ DO: Log at appropriate levels
@Slf4j
@Component
public class CampaignService {
    public void createCampaign(Campaign campaign) {
        log.info("Creating campaign: {}", campaign.getName());
        // Business logic
        log.debug("Campaign created with ID: {}", saved.getId());
        return saved;
    }
}

// ❌ NEVER: Log sensitive data (PII, passwords, tokens)
// ❌ NEVER: Use System.out.println()
```

### 4. Exception Handling
```java
// ✅ DO: Use custom exceptions
public class BusinessException extends RuntimeException {
    private final String code;
    private final String message;
    // Constructor, getters
}

@ControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusinessException(BusinessException ex) {
        return ResponseEntity.badRequest()
            .body(new ErrorResponse(ex.getCode(), ex.getMessage()));
    }
}
// ❌ NEVER: Catch and ignore exceptions
// ❌ NEVER: Return stack traces in API responses
```

### 5. Test (skip for now)

### 6. API Design
```java
// ✅ DO: Use RESTful principles
@RestController
@RequestMapping("/api/v1/campaigns")
public class CampaignController {
    @GetMapping("/{id}")
    public ResponseEntity<CampaignResponse> getCampaign(@PathVariable Long id) {
        // ...
    }
    
    @PostMapping
    public ResponseEntity<CampaignResponse> createCampaign(@Valid @RequestBody CampaignRequest request) {
        // ...
    }
}

// ✅ DO: Use consistent response format
public class ApiResponse<T> {
    private boolean success;
    private T data;
    private ApiError error;
    private Map<String, Object> meta;
    private String timestamp;
}

// ❌ NEVER: Return raw entities (use DTOs)
// ❌ NEVER: Expose internal IDs in responses
```

### 7. Database Operations
```java
// ✅ DO: Use batch operations for performance
@Transactional
public void importLeads(List<Lead> leads) {
    leadRepository.saveAll(leads);  // Batch insert
}

// ✅ DO: Use projections for read-only queries
@Query("SELECT c.id, c.name FROM Campaign c WHERE c.status = 'RUNNING'")
List<CampaignSummary> findRunningCampaigns();

// ❌ NEVER: Load full entities when only few fields needed
// ❌ NEVER: N+1 queries (use JOIN FETCH)
```

### 8. Concurrency
```java
// ✅ DO: Use Virtual Threads for I/O operations
@Bean
public ExecutorService virtualThreadExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor();
}

// ✅ DO: Use @Async with proper executor
@Async("virtualThreadExecutor")
public CompletableFuture<Void> processCampaign(Campaign campaign) {
    // Long-running operation
    return CompletableFuture.completedFuture(null);
}

// ❌ NEVER: Block on I/O in WebSocket threads
// ❌ NEVER: Use synchronized for performance-critical code
```

## ❌ Anti-Patterns to Avoid
God Classes: Break large classes into focused components
Dependency Injection Anti-Patterns: Never use new for services
Magic Numbers: Use constants or enums
Deep Nesting: Extract nested logic into methods
Duplication: Use DRY principle
Hardcoding: Configuration files or environment variables
Ignoring Performance: Always consider Big-O complexity

## Security Best Practices
Never log passwords, tokens, or PII
Always validate input (use @Valid)
Never trust client-side validation
Use prepared statements (JPA handles this)
Implement CSRF protection (for non-API endpoints)
Use HTTPS in production
Implement rate limiting (Redis sliding window)
Audit all admin actions

## Performance Checklist
Use pagination for all list endpoints
Cache frequently accessed data (Redis)
Use @Async for long-running operations
Implement connection pooling (HikariCP)
Use indexes for query-heavy columns
Partition large tables (call_logs)
Use projections for read-only queries
Batch database operations
Monitor query performance (pg_stat_statements)
Use WebFlux for reactive endpoints

## Code Review Checklist
Tests pass and coverage >80%
No SQL injection vulnerabilities
Tenant isolation enforced
Error handling implemented
Logging at appropriate levels
API documented (Swagger/OpenAPI)
No TODOs or FIXMEs
Code follows naming conventions
No debug logging in production
Performance considerations addressed
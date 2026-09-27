# Reporting & Analytics Skill

## Role: Reporting Developer (Analytics, CDR, BI)

## Core Responsibilities
1. Design and implement CDR (Call Detail Record) processing
2. Build real-time and historical dashboards
3. Implement denormalized stats for performance
4. Create export functionality (CSV, PDF)
5. Build agent performance reports
6. Implement campaign conversion tracking
7. Design billing and usage reports
8. Ensure GDPR/DPDP compliance for data retention

## Key Files to Reference
- `/backend/reporting-module/` - Reporting logic
- `/backend/billing-module/` - Billing integration
- `/backend/campaign-module/` - Campaign stats
- `/backend/reporting-module/src/main/resources/db/migration/` - DB migrations

## CDR Processing Pipeline

### 1. CDR Generation
```java
@Component
public class CdrService {
    @KafkaListener(topics = "call.events", groupId = "cdr-processor")
    public void processCallEvent(CallEvent event) {
        if (event.getEventType() != "CALL_COMPLETED") return;
        
        // Create CDR
        CallLog callLog = new CallLog();
        callLog.setTenantId(event.getTenantId());
        callLog.setCampaignId(event.getCampaignId());
        callLog.setLeadId(event.getLeadId());
        callLog.setAgentId(event.getAgentId());
        callLog.setFromNumber(event.getFromNumber());
        callLog.setToNumber(event.getToNumber());
        callLog.setInitiatedAt(event.getInitiatedAt());
        callLog.setAnsweredAt(event.getAnsweredAt());
        callLog.setEndedAt(event.getEndedAt());
        callLog.setDurationSec(event.getDurationSec());
        callLog.setBillableSec(event.getBillableSec());
        callLog.setStatus(event.getStatus());
        callLog.setDisposition(event.getDisposition());
        callLog.setCostCents(event.getCostCents());
        
        // Save CDR
        callLogRepository.save(callLog);
        
        // Update campaign stats
        campaignStatsService.updateStats(event);
        
        // Update lead status
        leadService.updateLeadStatus(event);
        
        // Trigger billing
        billingService.processBilling(event);
        
        // Trigger webhooks
        webhookService.sendWebhook(event);
    }
}
2. Denormalized Stats (campaign_stats)
java
@Service
public class CampaignStatsService {
    private final CampaignStatsRepository statsRepository;
    
    @Transactional
    public void updateStats(CallEvent event) {
        CampaignStats stats = statsRepository.findByCampaignId(event.getCampaignId())
            .orElse(new CampaignStats());
        stats.setCampaignId(event.getCampaignId());
        stats.setTenantId(event.getTenantId());
        
        // Update counters (thread-safe with database locks)
        stats.setTotalCalls(stats.getTotalCalls() + 1);
        
        if (event.getStatus() == CallStatus.ANSWERED) {
            stats.setAnsweredCalls(stats.getAnsweredCalls() + 1);
            
            // Update AHT rolling average
            double oldAht = stats.getAvgHandleTime();
            double newAht = (oldAht * (stats.getAnsweredCalls() - 1) + 
                           (event.getTalkTimeSec() + event.getWrapUpTimeSec())) / 
                           stats.getAnsweredCalls();
            stats.setAvgHandleTime(newAht);
        }
        
        if (event.getStatus() == CallStatus.ABANDONED) {
            stats.setAbandonedCalls(stats.getAbandonedCalls() + 1);
        }
        
        // Calculate rates
        if (stats.getAnsweredCalls() > 0) {
            stats.setAbandonmentRate(
                (double) stats.getAbandonedCalls() / stats.getAnsweredCalls()
            );
        }
        
        stats.setUpdatedAt(LocalDateTime.now());
        statsRepository.save(stats);
    }
}
Reporting Queries
1. Real-time Campaign Dashboard
sql
-- Get real-time campaign metrics
SELECT 
    c.id,
    c.name,
    c.type,
    c.status,
    COUNT(l.id) as total_leads,
    SUM(CASE WHEN l.status = 'CONNECTED' THEN 1 ELSE 0 END) as connected,
    SUM(CASE WHEN l.status = 'NO_ANSWER' THEN 1 ELSE 0 END) as no_answer,
    SUM(CASE WHEN l.status = 'FAILED' THEN 1 ELSE 0 END) as failed,
    COALESCE(s.abandonment_rate, 0) as abandonment_rate,
    COALESCE(s.avg_handle_time, 0) as avg_handle_time,
    c.created_at
FROM campaigns c
LEFT JOIN leads l ON c.id = l.campaign_id AND l.deleted_at IS NULL
LEFT JOIN campaign_stats s ON c.id = s.campaign_id
WHERE c.tenant_id = :tenantId
  AND c.status IN ('RUNNING', 'PAUSED')
GROUP BY c.id, s.id
ORDER BY c.created_at DESC;
2. Historical Campaign Performance
sql
-- Get historical campaign performance (daily aggregates)
SELECT 
    DATE(c.initiated_at) as date,
    COUNT(*) as total_calls,
    SUM(CASE WHEN c.status = 'ANSWERED' THEN 1 ELSE 0 END) as answered,
    SUM(CASE WHEN c.status = 'COMPLETED' THEN 1 ELSE 0 END) as completed,
    SUM(CASE WHEN c.status = 'ABANDONED' THEN 1 ELSE 0 END) as abandoned,
    AVG(c.talk_time_sec) as avg_talk_time,
    AVG(c.wrap_up_time_sec) as avg_wrap_time,
    SUM(c.cost_cents) / 100.0 as total_cost
FROM call_logs c
WHERE c.tenant_id = :tenantId
  AND c.campaign_id = :campaignId
  AND c.initiated_at >= :fromDate
  AND c.initiated_at <= :toDate
GROUP BY DATE(c.initiated_at)
ORDER BY date DESC;
3. Agent Performance Report
sql
-- Agent performance metrics
SELECT 
    u.id as agent_id,
    u.full_name as agent_name,
    COUNT(cl.id) as total_calls,
    SUM(CASE WHEN cl.status = 'ANSWERED' THEN 1 ELSE 0 END) as answered_calls,
    SUM(CASE WHEN cl.disposition = 'SALE' THEN 1 ELSE 0 END) as conversions,
    AVG(cl.talk_time_sec) as avg_talk_time,
    AVG(cl.wrap_up_time_sec) as avg_wrap_time,
    SUM(cl.cost_cents) / 100.0 as total_cost,
    (SUM(CASE WHEN cl.disposition = 'SALE' THEN 1 ELSE 0 END) * 100.0 / 
     NULLIF(SUM(CASE WHEN cl.status = 'ANSWERED' THEN 1 ELSE 0 END), 0)) as conversion_rate,
    SUM(EXTRACT(EPOCH FROM (cl.ended_at - cl.initiated_at))) / 3600 as total_hours
FROM users u
JOIN call_logs cl ON u.id = cl.agent_id
WHERE u.tenant_id = :tenantId
  AND cl.initiated_at >= :fromDate
  AND cl.initiated_at <= :toDate
  AND u.role = 'AGENT'
GROUP BY u.id, u.full_name
ORDER BY conversion_rate DESC;
Analytics & Metrics
1. Key Performance Indicators (KPIs)
java
@Service
public class AnalyticsService {
    public Map<String, Double> calculateKPIs(Long campaignId, LocalDate from, LocalDate to) {
        Map<String, Double> kpis = new HashMap<>();
        
        // Connection Rate
        kpis.put("connection_rate", getConnectionRate(campaignId, from, to));
        
        // Abandonment Rate
        kpis.put("abandonment_rate", getAbandonmentRate(campaignId, from, to));
        
        // Average Handle Time (AHT)
        kpis.put("avg_handle_time", getAHT(campaignId, from, to));
        
        // Agent Utilization
        kpis.put("agent_utilization", getAgentUtilization(campaignId, from, to));
        
        // Cost Per Connected Call
        kpis.put("cost_per_connected", getCostPerConnected(campaignId, from, to));
        
        // Lead Conversion Rate
        kpis.put("conversion_rate", getConversionRate(campaignId, from, to));
        
        return kpis;
    }
    
    private double getConnectionRate(Long campaignId, LocalDate from, LocalDate to) {
        Long total = callLogRepository.countByCampaignIdAndInitiatedAtBetween(
            campaignId, from.atStartOfDay(), to.atStartOfDay()
        );
        Long connected = callLogRepository.countByCampaignIdAndStatusAndInitiatedAtBetween(
            campaignId, CallStatus.COMPLETED, from.atStartOfDay(), to.atStartOfDay()
        );
        return total > 0 ? (connected * 100.0 / total) : 0.0;
    }
}
2. Real-time Monitoring
java
@Component
public class RealtimeMonitor {
    private final SimpMessagingTemplate messagingTemplate;
    private final CampaignStatsService statsService;
    
    @Scheduled(fixedDelay = 5000)
    public void pushRealTimeStats() {
        List<Campaign> activeCampaigns = campaignService.getActiveCampaigns();
        
        for (Campaign campaign : activeCampaigns) {
            CampaignStats stats = statsService.getStats(campaign.getId());
            
            // Push to WebSocket
            messagingTemplate.convertAndSend(
                "/topic/campaign/" + campaign.getId(),
                RealTimeStats.from(campaign, stats)
            );
        }
    }
}
Export Functionality
1. CSV Export
java
@Service
public class ReportExportService {
    public byte[] exportCsv(Long campaignId, LocalDate from, LocalDate to) {
        List<CallLog> callLogs = callLogRepository.findByCampaignIdAndInitiatedAtBetween(
            campaignId, from.atStartOfDay(), to.atStartOfDay()
        );
        
        StringWriter writer = new StringWriter();
        CSVPrinter csvPrinter = new CSVPrinter(writer, CSVFormat.DEFAULT
            .withHeader("ID", "Phone Number", "Status", "Duration", "Cost", "Disposition"));
        
        for (CallLog log : callLogs) {
            csvPrinter.printRecord(
                log.getId(),
                log.getToNumber(),
                log.getStatus(),
                log.getDurationSec(),
                log.getCostCents() / 100.0,
                log.getDisposition()
            );
        }
        
        csvPrinter.flush();
        return writer.toString().getBytes(StandardCharsets.UTF_8);
    }
}
2. PDF Export
java
@Service
public class PdfExportService {
    public byte[] exportPdf(Long campaignId, LocalDate from, LocalDate to) {
        try (PdfDocument pdf = new PdfDocument(new PdfWriter(new ByteArrayOutputStream()))) {
            Document document = new Document(pdf);
            
            // Add title
            Paragraph title = new Paragraph("Campaign Report")
                .setFontSize(20)
                .setBold();
            document.add(title);
            
            // Add summary
            CampaignStats stats = statsService.getStats(campaignId);
            document.add(new Paragraph("Total Calls: " + stats.getTotalCalls()));
            document.add(new Paragraph("Connected: " + stats.getConnectedCalls()));
            document.add(new Paragraph("Abandonment Rate: " + stats.getAbandonmentRate() + "%"));
            
            // Add table
            Table table = new Table(UnitValue.createPercentArray(5));
            table.addHeaderCell("Date");
            table.addHeaderCell("Calls");
            table.addHeaderCell("Connected");
            table.addHeaderCell("Avg Duration");
            table.addHeaderCell("Cost");
            
            List<DailyStats> dailyStats = getDailyStats(campaignId, from, to);
            for (DailyStats stats : dailyStats) {
                table.addCell(stats.getDate().toString());
                table.addCell(String.valueOf(stats.getTotalCalls()));
                table.addCell(String.valueOf(stats.getConnectedCalls()));
                table.addCell(String.valueOf(stats.getAvgDuration()));
                table.addCell("$" + stats.getTotalCost());
            }
            document.add(table);
            
            document.close();
            return ((ByteArrayOutputStream) pdf.getOutputStream()).toByteArray();
        }
    }
}
Data Retention & Compliance
1. Auto-Deletion Job
java
@Component
public class DataRetentionJob {
    private final CallLogRepository callLogRepository;
    private final RecordingService recordingService;
    
    @Scheduled(cron = "0 0 2 * * ?") // Daily at 2 AM
    public void cleanupOldData() {
        List<Tenant> tenants = tenantService.getAllTenants();
        
        for (Tenant tenant : tenants) {
            int retentionDays = tenant.getRetentionDays();
            LocalDateTime cutoffDate = LocalDateTime.now().minusDays(retentionDays);
            
            // Delete old call logs
            int deletedLogs = callLogRepository.deleteByTenantIdAndInitiatedAtBefore(
                tenant.getId(), cutoffDate
            );
            
            // Delete old recordings
            recordingService.deleteOldRecordings(tenant.getId(), cutoffDate);
            
            log.info("Deleted {} old call logs for tenant {}", deletedLogs, tenant.getId());
        }
    }
}
2. GDPR Compliance
java
@RestController
@RequestMapping("/api/v1/privacy")
public class PrivacyController {
    private final PiiRedactionService redactionService;
    
    @DeleteMapping("/right-to-deletion")
    public ResponseEntity<Void> rightToDeletion(@RequestParam String phoneNumber) {
        // Redact all PII for this phone number
        redactionService.redactPII(phoneNumber);
        
        // Log the deletion request
        auditService.logPiiDeletion(phoneNumber);
        
        return ResponseEntity.noContent().build();
    }
}

@Service
public class PiiRedactionService {
    public void redactPII(String phoneNumber) {
        // Redact in leads
        leadService.redactPhoneNumber(phoneNumber);
        
        // Redact in call logs
        callLogService.redactPhoneNumber(phoneNumber);
        
        // Redact in recordings (metadata only)
        recordingService.redactMetadata(phoneNumber);
        
        // Remove from cache
        redisTemplate.delete("dnd:" + phoneNumber);
    }
}
Dashboard APIs
java
@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {
    @GetMapping("/summary")
    public DashboardSummary getSummary(@RequestParam Long tenantId) {
        return new DashboardSummary(
            campaignService.countActive(tenantId),
            campaignService.countCompleted(tenantId),
            agentService.countAvailable(tenantId),
            callLogService.countToday(tenantId),
            billingService.getBalance(tenantId)
        );
    }
    
    @GetMapping("/calls/hourly")
    public List<HourlyStats> getHourlyStats(@RequestParam Long tenantId) {
        return callLogService.getHourlyStats(tenantId, LocalDate.now());
    }
}
Performance Optimizations
Materialized Views for heavy aggregations

Partition Pruning for date-based queries

Denormalized Stats table for real-time metrics

Redis Caching for frequently accessed stats

Batch Processing for CDR inserts

Async Processing via Kafka for reporting updates

Query Optimizations with proper indexes

Export Streaming for large reports (avoid OOM)

Monitoring
yaml
# Reporting metrics to track
- CDR processing latency (Kafka consumer lag)
- Report generation time
- Export success/failure rate
- Database query performance
- Cache hit rate for stats
- Data retention job success
Common Issues & Solutions
Issue	Cause	Solution
Slow report queries	Missing indexes	Add composite indexes
CDR consumer lag	High call volume	Increase partitions, consumers
Stats not updating	Kafka consumer failure	Monitor, retry with dead letter
Export OOM	Large dataset	Stream export, paginate
GDPR deletion failures	Dependencies	Cascade delete, audit trail
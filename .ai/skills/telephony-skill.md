# Telephony Development Skill

## Role: Telephony Engineer (Asterisk ARI, Kamailio, SIP)

## Core Responsibilities
1. Implement call control logic via Asterisk ARI (WebSocket)
2. Configure Kamailio for SIP routing and load balancing
3. Implement DTMF collection and processing
4. Handle call bridging (customer to agent)
5. Implement call recording with MinIO storage
6. Design predictive dialer pacing algorithm
7. Ensure NAT traversal and WebRTC support

## Key Files to Reference
- `/backend/telephony-module/src/main/java/com/platform/telephony/ari/`
- `/backend/telephony-module/src/main/java/com/platform/telephony/call/`
- `/backend/telephony-module/src/main/java/com/platform/telephony/events/`
- `/infrastructure/asterisk/` - Asterisk configuration
- `/infrastructure/kamailio/` - Kamailio configuration

## Asterisk ARI Development

### 1. ARI WebSocket Connection
```java
@Component
public class AriWebSocketClient {
    private final WebSocketClient webSocketClient;
    private final ObjectMapper objectMapper;
    private final EventPublisher eventPublisher;
    
    @PostConstruct
    public void connect() {
        String url = String.format("ws://%s:%s/ari/events?api_key=%s:%s",
            ariHost, ariPort, ariUsername, ariPassword);
            
        webSocketClient.doHandshake(url, new WebSocketHandler() {
            @Override
            public void onTextMessage(String message) {
                handleAriEvent(message);
            }
            
            @Override
            public void onClose(int code, String reason) {
                log.warn("ARI WebSocket closed: {} - {}", code, reason);
                reconnect();
            }
        });
    }
    
    private void handleAriEvent(String message) {
        try {
            JsonNode event = objectMapper.readTree(message);
            String type = event.get("type").asText();
            
            switch (type) {
                case "StasisStart":
                    handleStasisStart(event);
                    break;
                case "ChannelStateChange":
                    handleChannelStateChange(event);
                    break;
                case "ChannelDestroyed":
                    handleChannelDestroyed(event);
                    break;
                case "ChannelDtmfReceived":
                    handleDtmfReceived(event);
                    break;
                // More event types
            }
        } catch (Exception e) {
            log.error("Error processing ARI event", e);
        }
    }
}
```

2. Call Origination
```java
@Service
public class CallOriginationService {
    private final AriClient ariClient;
    private final AgentStateManager agentStateManager;
    private final EventPublisher eventPublisher;
    
    public OriginateResponse originateCall(CallRequest request) {
        // Build originate request
        OriginateRequest originateRequest = new OriginateRequest()
            .endpoint(request.getEndpoint()) // SIP/trunk/number
            .extension(request.getExtension())
            .context("from-internal")
            .priority(1)
            .app("dialer-app")
            .appArgs(JSON.stringify(Map.of(
                "campaignId", request.getCampaignId(),
                "leadId", request.getLeadId(),
                "callerId", request.getCallerId()
            )))
            .channelId(request.getChannelId())
            .timeout(30);
            
        // Send to Asterisk
        Channel channel = ariClient.channels().originate(originateRequest);
        
        log.info("Originated call: {}", channel.getId());
        return new OriginateResponse(channel.getId());
    }
}
```

3. Play Audio to Channel
```java
public void playAudio(String channelId, String audioFileUri) {
    PlayRequest playRequest = new PlayRequest()
        .media(audioFileUri) // sound:filename or file:///path/to/file.wav
        .language("en");
        
    ariClient.channels().play(channelId, playRequest);
}
```
4. DTMF Collection
```java
public CompletableFuture<String> collectDtmf(String channelId, int maxDigits, int timeoutSeconds) {
    CompletableFuture<String> future = new CompletableFuture<>();
    
    // Create a DTMF collector
    String collectorId = UUID.randomUUID().toString();
    dtmfCollectors.put(collectorId, new DtmfCollector(future, maxDigits, timeoutSeconds));
    
    // Wait for DTMF events
    ariClient.channels().sendDTMF(channelId, null, null, null);
    
    return future;
}

private void handleDtmfReceived(JsonNode event) {
    String channelId = event.get("channel").get("id").asText();
    String digit = event.get("digit").asText();
    
    // Find matching collector
    DtmfCollector collector = dtmfCollectors.values().stream()
        .filter(c -> c.getChannelId().equals(channelId))
        .findFirst()
        .orElse(null);
        
    if (collector != null) {
        collector.addDigit(digit);
    }
}
```
5. Call Bridging (Agent Connect)
```java
public void bridgeCall(String customerChannelId, String agentChannelId) {
    // Create bridge
    Bridge bridge = ariClient.bridges().create(
        new BridgeRequest()
            .type("mixing")
            .bridgeId(UUID.randomUUID().toString())
    );
    
    // Add channels to bridge
    ariClient.bridges().addChannel(bridge.getId(), customerChannelId, null);
    ariClient.bridges().addChannel(bridge.getId(), agentChannelId, null);
    
    // Monitor bridge
    bridgeMonitor.startMonitoring(bridge.getId());
    
    log.info("Bridged {} and {}", customerChannelId, agentChannelId);
}
```
6. Call Recording
```java
public void startRecording(String channelId, String recordingName) {
    RecordingRequest recordingRequest = new RecordingRequest()
        .name(recordingName)
        .format("wav")
        .maxDurationSeconds(3600)
        .maxSilenceSeconds(5)
        .beep(true);
        
    LiveRecording recording = ariClient.channels().record(channelId, recordingRequest);
    
    log.info("Recording started: {}", recording.getId());
}

public void handleRecordingCompleted(LiveRecording recording) {
    // Recording completed
    String recordingUrl = minioService.uploadRecording(recording);
    
    // Store in database
    recordingService.saveRecording(callLogId, recordingUrl);
}
```

## Predictive Dialer Engine

1. Pacing Algorithm
```java
@Component
public class PacingCalculator {
    private final AgentStateService agentStateService;
    private final CampaignStatsService campaignStatsService;
    
    public int calculateTargetActiveCalls(Long campaignId) {
        // Step 1: Available agents
        int availableAgents = agentStateService.getAvailableAgentCount(campaignId);
        int wrapUpAgents = agentStateService.getWrapUpAgentCount(campaignId);
        int totalAvailable = availableAgents + wrapUpAgents;
        
        // Step 2: Average Handle Time
        double aht = campaignStatsService.getAverageHandleTime(campaignId);
        
        // Step 3: Pacing ratio (from config)
        double pacingRatio = campaignService.getPacingRatio(campaignId);
        
        // Step 4: Abandonment rate check
        double abandonmentRate = campaignStatsService.getAbandonmentRate(campaignId);
        if (abandonmentRate > 0.03) {
            pacingRatio = 1.0; // Circuit breaker
            alertService.sendAlert(campaignId, "HIGH_ABANDONMENT", abandonmentRate);
        }
        
        // Step 5: Target calls
        int targetCalls = (int) (totalAvailable * pacingRatio);
        int activeCalls = campaignStatsService.getActiveCalls(campaignId);
        
        return Math.max(0, targetCalls - activeCalls);
    }
}
```
2. Lead Claiming
```java
@Repository
public interface LeadRepository extends JpaRepository<Lead, Long> {
    @Query(value = """
        UPDATE leads 
        SET status = 'DIALING', 
            attempt_count = attempt_count + 1,
            last_dialed_at = NOW()
        WHERE id IN (
            SELECT id FROM leads 
            WHERE tenant_id = :tenantId 
              AND campaign_id = :campaignId
              AND status IN ('NEW', 'QUEUED')
              AND is_dnc = FALSE
              AND (scheduled_at IS NULL OR scheduled_at <= NOW())
            ORDER BY priority DESC, created_at ASC
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
        )
        RETURNING id, phone_number, custom_fields
        """, nativeQuery = true)
    List<Object[]> claimLeads(
        @Param("tenantId") Long tenantId,
        @Param("campaignId") Long campaignId,
        @Param("batchSize") int batchSize
    );
}
```

### Kamailio Configuration

## 1. SIP Routing
```conf
# /etc/kamailio/kamailio.cfg

route {
    # SIP request routing
    if (!mf_process_maxfwd_header("10")) {
        sl_send_reply("483", "Too Many Hops");
        exit;
    }
    
    # NAT traversal
    if (nat_uac_test("19")) {
        setbflag(FLT_NATS);
        fix_nated_contact();
        fix_nated_sdp("3");
    }
    
    # Load balancing
    if (is_method("INVITE")) {
        if (isflagset(FLT_NATS)) {
            add_rr_param(";nat=yes");
        }
        
        # Route to Asterisk
        route_to_asterisk();
    }
}

route_to_asterisk {
    # Load balance across Asterisk nodes
    if (!ds_select_domain("asterisk", "4")) {
        sl_send_reply("503", "Service Unavailable");
        exit;
    }
    t_relay();
}
```
## NAT Traversal
```lua
// Handle NAT in Kamailio
// Use STUN/TURN for WebRTC
// Configure Asterisk for NAT

// Asterisk sip.conf
[general]
nat=yes
externip=your-public-ip
localnet=192.168.0.0/16

// WebRTC configuration
public:
    type=peer
    host=dynamic
    context=public
    disallow=all
    allow=ulaw
    ice_support=yes
    dtlsenable=yes
    dtlsverify=no
    dtlssetup=actpass
    websocket_enabled=yes

```

### WebRTC Agent Softphone
```ts
1. SIP.js Integration
// lib/softphone/SIPClient.ts
import { UserAgent, UserAgentOptions, Session } from 'sip.js';

export class SIPClient {
    private userAgent: UserAgent | null = null;
    private currentSession: Session | null = null;
    
    constructor(private config: {
        uri: string;
        password: string;
        wsServer: string;
    }) {}
    
    connect() {
        const options: UserAgentOptions = {
            uri: new URI(this.config.uri),
            password: this.config.password,
            transportOptions: {
                wsServers: [this.config.wsServer],
            },
            displayName: 'Agent',
        };
        
        this.userAgent = new UserAgent(options);
        this.userAgent.start();
    }
    
    makeCall(targetNumber: string) {
        if (!this.userAgent) throw new Error('Not connected');
        
        const target = new URI('sip', targetNumber);
        this.currentSession = this.userAgent.invite(target, {
            sessionDescriptionHandlerOptions: {
                constraints: {
                    audio: true,
                    video: false,
                },
            },
        });
        
        return this.currentSession;
    }
    
    hangup() {
        if (this.currentSession) {
            this.currentSession.bye();
            this.currentSession = null;
        }
    }
}
```
## Monitoring & Debugging
### 1. Asterisk CLI Commands
```bash
# Show active channels
asterisk -rx "core show channels"

# Show active calls
asterisk -rx "core show calls"

# Show ARI statistics
asterisk -rx "module show like ari"

# Show SIP peers
asterisk -rx "pjsip show endpoints"

# Real-time call monitoring
asterisk -rx "core show channels verbose"
```

### 2. Kamailio Statistics
```bash
# Show load balancer stats
kamailio -x "ds_list"

# Show active transactions
kamailio -x "t_stats"

# Show memory usage
kamailio -x "mod stats"
```  
### 3. ARI Debugging
```bash
# Enable ARI debug logging
asterisk -rx "logger set debug level 1"

# Watch ARI events
tail -f /var/log/asterisk/full | grep ARI

# Test ARI connection
curl -X GET "http://localhost:8088/ari/api-docs" -u asterisk:password
```

Common Issues & Solutions
1. Call Setup Failed
Check SIP registration

Verify NAT settings

Check firewall (ports 5060/udp, 5060/tcp, 10000-20000/udp)

Verify Kamailio routing

2. Audio Issues (One-Way or No Audio)
NAT traversal misconfiguration
Incorrect SDP
RTP port range blocked
Codec mismatch

3. ARI WebSocket Disconnects
Heartbeat timeout
Memory pressure
Network issues
Long-running operations blocking

4. High Abandonment Rate
Pacing ratio too high
Not enough agents
Long ring time
Poor call quality

## Performance Tuning
```ini
Asterisk
# /etc/asterisk/asterisk.conf
[options]
maxcalls = 500
maxfilehandles = 10000

# /etc/asterisk/rtp.conf
[general]
rtpstart = 10000
rtpend = 20000

# /etc/asterisk/logger.conf
[files]
full => debug,notice,verbose
```

Kamailio
# Increase memory
# /etc/default/kamailio
SHM_MEMORY=512
PKG_MEMORY=64

# Load balancing
# Reuse TCP connections
enable_tcp = yes
tcp_connection_lifetime = 300

Java Application
yaml
# application.yml
ari:
  connection-pool-size: 50
  reconnect-delay: 5000
  max-retries: 5
  
websocket:
  max-connections: 10000
  virtual-threads: true


## Security Considerations
SRTP for media encryption
TLS for SIP signaling
Whitelist IPs for Kamailio
Rate limiting on SIP REGISTER
Anti-spoofing on Caller ID
Recording consent (beep tone)
Encrypted recordings (AES-256)
Audit all call actions



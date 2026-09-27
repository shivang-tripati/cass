# Troubleshooting Workflow

## Issue Identification
1. **Detect issue**: Alert, user report, monitoring
2. **Verify issue**: Confirm it's not false positive
3. **Classify severity**: Critical/High/Medium/Low
4. **Notify**: Inform stakeholders

## Investigation

### Backend Issues
```bash
# Check logs
kubectl logs -f -l app=obd-backend -n obd-platform

# Check metrics
kubectl top pods -n obd-platform

# Check database
kubectl exec -it postgres-xxx -n obd-platform -- psql -U obd_user

Frontend Issues
```bash
# Check logs
kubectl logs -f -l app=obd-frontend -n obd-platform

# Check browser console
# Check network tab
```

Telephony Issues
```bash
# Check Asterisk
asterisk -rx "core show channels"

# Check Kamailio
kamailio -x "ds_list"

# Check SIP traces
tcpdump -i any -s 0 -w sip.pcap port 5060
```

Infrastructure Issues
```bash
# Check nodes
kubectl get nodes

# Check pods
kubectl get pods -n obd-platform

# Check services
kubectl get services -n obd-platform

# Check events
kubectl get events -n obd-platform --sort-by='.lastTimestamp'
```

Common Issues & Solutions

Database Connection Issues
```sql
-- Check connections
SELECT count(*) FROM pg_stat_activity;

-- Kill idle connections
SELECT pg_terminate_backend(pid) 
FROM pg_stat_activity 
WHERE state = 'idle' 
AND age(now(), state_change) > interval '5 minutes';
```
High Memory Usage
```bash
# Check JVM heap
jcmd <pid> GC.heap_info

# Check pod memory
kubectl top pod -n obd-platform

# Increase memory limit
kubectl edit deployment obd-backend -n obd-platform
```

High API Latency
```bash
# Check database query performance
SELECT query, mean_exec_time, calls 
FROM pg_stat_statements 
ORDER BY mean_exec_time DESC 
LIMIT 20;

# Check CPU usage
kubectl top nodes

# Scale replicas
kubectl scale deployment obd-backend --replicas=5 -n obd-platform
```

Call Setup Failures
```bash
# Check Asterisk logs
tail -f /var/log/asterisk/full | grep ERROR

# Check Kamailio logs
tail -f /var/log/kamailio/kamailio.log

# Verify SIP registration
asterisk -rx "pjsip show endpoints"
```

Escalation
Level 1: Check logs, restart services
Level 2: Debug application code
Level 3: Investigate infrastructure
Level 4: Vendor support (if needed)

Resolution
Implement fix: Code change or configuration
Test fix: Verify in staging
Deploy fix: Apply to production
Verify: Confirm issue resolved
Document: Add to knowledge base
Post-Mortem
Timeline: When did it happen?
Root cause: What caused it?
Impact: What was affected?
Resolution: How was it fixed?
Prevention: How to prevent recurrence?
Action items: What needs to be done?

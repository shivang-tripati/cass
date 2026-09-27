# Deployment Workflow

## Pre-Deployment Checklist

### Code Ready
- [ ] All PRs merged
- [ ] All tests passing
- [ ] Version tagged: `git tag v1.2.3`
- [ ] CHANGELOG updated
- [ ] Documentation updated

### Infrastructure Ready
- [ ] Database backups completed
- [ ] Resources provisioned
- [ ] SSL certificates valid
- [ ] Monitoring configured

### Configuration Ready
- [ ] Environment variables set
- [ ] Feature flags configured
- [ ] Rate limits adjusted
- [ ] Backup schedule confirmed

## Deployment Steps

### 1. Build
```bash
# Build Docker images
docker build -t obd-platform/backend:v1.2.3 ./backend
docker build -t obd-platform/frontend:v1.2.3 ./frontend

# Push to registry
docker push obd-platform/backend:v1.2.3
docker push obd-platform/frontend:v1.2.3
```
2. Database Migrations
```bash
# Apply migrations
kubectl exec -it obd-backend-xxx -n obd-platform -- \
  java -jar app.jar --spring.flyway.enabled=true
```

3. Deploy Backend
```bash
# Update Kubernetes
kubectl set image deployment/obd-backend \
  backend=obd-platform/backend:v1.2.3 \
  -n obd-platform

# Wait for rollout
kubectl rollout status deployment/obd-backend -n obd-platform
```

### 4. Deploy Frontend
```bash
# Update Kubernetes
kubectl set image deployment/obd-frontend \
  frontend=obd-platform/frontend:v1.2.3 \
  -n obd-platform

# Wait for rollout
kubectl rollout status deployment/obd-frontend -n obd-platform
```

### 5. Verify Deployment
```bash
# Health check
curl https://api.obd-platform.com/actuator/health

# Verify functionality
# Run smoke tests
```

6. Post-Deployment
```bash
# Clear caches
kubectl exec -it obd-backend-xxx -n obd-platform -- \
  redis-cli FLUSHALL

# Update monitoring
# Configure new dashboards

# Notify team
# Send deployment notification
```

## Rollback Plan
Rollback Steps
```bash
# Rollback backend
kubectl rollout undo deployment/obd-backend -n obd-platform

# Rollback frontend
kubectl rollout undo deployment/obd-frontend -n obd-platform

# Rollback database (if needed)
# Restore from backup
```

Post-Deployment Monitoring
Error rates

Response times

Resource usage

Business metrics

User feedback

Success Criteria
All health checks pass

Smoke tests pass

No error spike

Performance within targets

User feedback positive

Communication
Notify stakeholders: [who]

Update status dashboard

Document any issues

Schedule post-mortem if needed
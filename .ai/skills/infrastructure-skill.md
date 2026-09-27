# Infrastructure & DevOps Skill

## Role: DevOps Engineer (Kubernetes, Docker, Monitoring)

## Core Responsibilities
1. Design and maintain Kubernetes infrastructure
2. Configure Docker containers for all services
3. Implement CI/CD pipelines (GitHub Actions)
4. Set up monitoring (Prometheus, Grafana, ELK)
5. Manage database backups and disaster recovery
6. Implement auto-scaling for telephony workloads
7. Configure SSL certificates (Let's Encrypt)
8. Ensure high availability (99.9% uptime)

## Key Files to Reference
- `/infrastructure/docker/` - Docker Compose files
- `/infrastructure/kubernetes/` - K8s manifests
- `/infrastructure/monitoring/` - Prometheus, Grafana config
- `/.github/workflows/` - CI/CD pipelines

## Docker Configuration

### 1. Dockerfile for Backend
```dockerfile
# backend/Dockerfile
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app
COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .
RUN ./mvnw dependency:go-offline
COPY src src
RUN ./mvnw package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=builder /app/app-bootstrap/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

### 2. Dockerfile for Frontend

```dockerfile
# frontend/Dockerfile
FROM node:20-alpine AS builder
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM node:20-alpine AS runner
WORKDIR /app
COPY --from=builder /app/.next ./.next
COPY --from=builder /app/public ./public
COPY --from=builder /app/package*.json ./
RUN npm ci --only=production
EXPOSE 3000
CMD ["npm", "start"]
```

### 3. Docker Compose (Development)

```dockerfile

# docker-compose.yml
version: '3.8'

services:
  postgres:
    image: postgres:16
    environment:
      POSTGRES_DB: obd
      POSTGRES_USER: obd_user
      POSTGRES_PASSWORD: obd_password
    ports:
      - "5432:5432"
    volumes:
      - postgres_data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U obd_user"]
      interval: 10s
      timeout: 5s
      retries: 5

  redis:
    image: redis:7.2-alpine
    ports:
      - "6379:6379"
    volumes:
      - redis_data:/data
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 10s
      timeout: 5s
      retries: 5

  kafka:
    image: bitnami/kafka:3.6
    environment:
      KAFKA_CFG_NODE_ID: 0
      KAFKA_CFG_PROCESS_ROLES: controller,broker
      KAFKA_CFG_CONTROLLER_QUORUM_VOTERS: 0@kafka:9093
      KAFKA_CFG_LISTENERS: PLAINTEXT://:9092,CONTROLLER://:9093
      KAFKA_CFG_ADVERTISED_LISTENERS: PLAINTEXT://localhost:9092
      KAFKA_CFG_LISTENER_SECURITY_PROTOCOL_MAP: PLAINTEXT:PLAINTEXT,CONTROLLER:PLAINTEXT
      KAFKA_CFG_CONTROLLER_LISTENER_NAMES: CONTROLLER
    ports:
      - "9092:9092"
    volumes:
      - kafka_data:/bitnami/kafka
    healthcheck:
      test: ["CMD", "kafka-topics.sh", "--list", "--bootstrap-server", "localhost:9092"]
      interval: 30s
      timeout: 10s
      retries: 5

  minio:
    image: minio/minio:latest
    command: server /data --console-address ":9001"
    environment:
      MINIO_ROOT_USER: minioadmin
      MINIO_ROOT_PASSWORD: minioadmin
    ports:
      - "9000:9000"
      - "9001:9001"
    volumes:
      - minio_data:/data
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:9000/minio/health/live"]
      interval: 30s
      timeout: 10s
      retries: 5

  asterisk:
    image: asterisk/asterisk:20-alpine
    ports:
      - "5060:5060/udp"
      - "5060:5060/tcp"
      - "8088:8088"
    volumes:
      - ./asterisk/config:/etc/asterisk
      - asterisk_logs:/var/log/asterisk
    healthcheck:
      test: ["CMD", "asterisk", "-rx", "core show version"]
      interval: 30s
      timeout: 10s
      retries: 5

  kamailio:
    image: kamailio/kamailio:5.8-alpine
    ports:
      - "5060:5060/udp"
      - "5060:5060/tcp"
    volumes:
      - ./kamailio/config:/etc/kamailio
      - kamailio_logs:/var/log/kamailio
    healthcheck:
      test: ["CMD", "kamailio", "-h"]
      interval: 30s
      timeout: 10s
      retries: 5

  backend:
    build: ./backend
    ports:
      - "8080:8080"
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/obd
      SPRING_DATASOURCE_USERNAME: obd_user
      SPRING_DATASOURCE_PASSWORD: obd_password
      SPRING_REDIS_HOST: redis
      SPRING_REDIS_PORT: 6379
      SPRING_KAFKA_BOOTSTRAP_SERVERS: kafka:9092
      MINIO_ENDPOINT: http://minio:9000
      MINIO_ACCESS_KEY: minioadmin
      MINIO_SECRET_KEY: minioadmin
      ARI_HOST: asterisk
      ARI_PORT: 8088
      ARI_USERNAME: asterisk
      ARI_PASSWORD: asterisk123
    depends_on:
      postgres:
        condition: service_healthy
      redis:
        condition: service_healthy
      kafka:
        condition: service_healthy
      minio:
        condition: service_healthy
      asterisk:
        condition: service_healthy

  frontend:
    build: ./frontend
    ports:
      - "3000:3000"
    environment:
      NEXT_PUBLIC_API_URL: http://localhost:8080
      NEXT_PUBLIC_WS_URL: ws://localhost:8080
    depends_on:
      - backend

volumes:
  postgres_data:
  redis_data:
  kafka_data:
  minio_data:
  asterisk_logs:
  kamailio_logs:

```

### Kubernetes Deployment

1. Backend Deployment
```dockerfile
# k8s/backend/deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: obd-backend
  namespace: obd-platform
spec:
  replicas: 3
  selector:
    matchLabels:
      app: obd-backend
  template:
    metadata:
      labels:
        app: obd-backend
    spec:
      containers:
        - name: backend
          image: obd-platform/backend:latest
          ports:
            - containerPort: 8080
          env:
            - name: SPRING_DATASOURCE_URL
              valueFrom:
                secretKeyRef:
                  name: db-secret
                  key: url
            - name: SPRING_DATASOURCE_USERNAME
              valueFrom:
                secretKeyRef:
                  name: db-secret
                  key: username
            - name: SPRING_DATASOURCE_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: db-secret
                  key: password
            - name: SPRING_REDIS_HOST
              value: redis-service
            - name: SPRING_KAFKA_BOOTSTRAP_SERVERS
              value: kafka-service:9092
            - name: MINIO_ENDPOINT
              value: http://minio-service:9000
            - name: MINIO_ACCESS_KEY
              valueFrom:
                secretKeyRef:
                  name: minio-secret
                  key: access-key
            - name: MINIO_SECRET_KEY
              valueFrom:
                secretKeyRef:
                  name: minio-secret
                  key: secret-key
          resources:
            requests:
              memory: "512Mi"
              cpu: "250m"
            limits:
              memory: "1Gi"
              cpu: "500m"
          livenessProbe:
            httpGet:
              path: /actuator/health/liveness
              port: 8080
            initialDelaySeconds: 30
            periodSeconds: 10
          readinessProbe:
            httpGet:
              path: /actuator/health/readiness
              port: 8080
            initialDelaySeconds: 30
            periodSeconds: 10
---
# k8s/backend/service.yaml
apiVersion: v1
kind: Service
metadata:
  name: backend-service
  namespace: obd-platform
spec:
  selector:
    app: obd-backend
  ports:
    - port: 8080
      targetPort: 8080
  type: ClusterIP

```
2. Frontend Deployment
```dockerfile
# k8s/frontend/deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: obd-frontend
  namespace: obd-platform
spec:
  replicas: 2
  selector:
    matchLabels:
      app: obd-frontend
  template:
    metadata:
      labels:
        app: obd-frontend
    spec:
      containers:
        - name: frontend
          image: obd-platform/frontend:latest
          ports:
            - containerPort: 3000
          env:
            - name: NEXT_PUBLIC_API_URL
              value: https://api.obd-platform.com
            - name: NEXT_PUBLIC_WS_URL
              value: wss://api.obd-platform.com
          resources:
            requests:
              memory: "256Mi"
              cpu: "100m"
            limits:
              memory: "512Mi"
              cpu: "250m"
---
# k8s/frontend/service.yaml
apiVersion: v1
kind: Service
metadata:
  name: frontend-service
  namespace: obd-platform
spec:
  selector:
    app: obd-frontend
  ports:
    - port: 3000
      targetPort: 3000
  type: ClusterIP
```

3. Ingress Configuration
```yml
# k8s/ingress.yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: obd-ingress
  namespace: obd-platform
  annotations:
    nginx.ingress.kubernetes.io/rewrite-target: /
    nginx.ingress.kubernetes.io/ssl-redirect: "true"
    nginx.ingress.kubernetes.io/websocket-services: "backend-service"
    nginx.ingress.kubernetes.io/proxy-read-timeout: "3600"
    nginx.ingress.kubernetes.io/proxy-send-timeout: "3600"
    cert-manager.io/cluster-issuer: "letsencrypt-prod"
spec:
  tls:
    - hosts:
        - obd-platform.com
        - api.obd-platform.com
      secretName: obd-tls
  rules:
    - host: obd-platform.com
      http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: frontend-service
                port:
                  number: 3000
    - host: api.obd-platform.com
      http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: backend-service
                port:
                  number: 8080

```
CI/CD Pipeline (GitHub Actions)

# .github/workflows/ci.yml
```yml
name: CI

on:
  push:
    branches: [main, develop]
  pull_request:
    branches: [main]

jobs:
  test-backend:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - name: Set up JDK 21
        uses: actions/setup-java@v3
        with:
          java-version: '21'
          distribution: 'temurin'
      - name: Cache Maven packages
        uses: actions/cache@v3
        with:
          path: ~/.m2
          key: ${{ runner.os }}-m2-${{ hashFiles('**/pom.xml') }}
      - name: Run tests
        run: mvn test
      - name: Upload coverage
        uses: codecov/codecov-action@v3
        with:
          file: ./**/target/site/jacoco/jacoco.xml

  test-frontend:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - name: Use Node.js 20
        uses: actions/setup-node@v3
        with:
          node-version: '20'
          cache: 'npm'
      - name: Install dependencies
        run: npm ci
      - name: Lint
        run: npm run lint
      - name: Run tests
        run: npm run test
      - name: Build
        run: npm run build

  security-scan:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - name: Run Trivy vulnerability scanner
        uses: aquasecurity/trivy-action@master
        with:
          scan-type: 'fs'
          scan-ref: '.'
          format: 'sarif'
          output: 'trivy-results.sarif'
      - name: Upload Trivy results
        uses: github/codeql-action/upload-sarif@v2
        with:
          sarif_file: 'trivy-results.sarif'
```

2. Build and Deploy
```yml
# .github/workflows/cd.yml
name: CD

on:
  push:
    branches: [main]
  workflow_dispatch:

jobs:
  build-and-push:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      
      - name: Set up Docker Buildx
        uses: docker/setup-buildx-action@v2
        
      - name: Login to DockerHub
        uses: docker/login-action@v2
        with:
          username: ${{ secrets.DOCKER_USERNAME }}
          password: ${{ secrets.DOCKER_PASSWORD }}
          
      - name: Build and push backend
        uses: docker/build-push-action@v4
        with:
          context: ./backend
          file: ./backend/Dockerfile
          push: true
          tags: obd-platform/backend:latest,${{ github.sha }}
          
      - name: Build and push frontend
        uses: docker/build-push-action@v4
        with:
          context: ./frontend
          file: ./frontend/Dockerfile
          push: true
          tags: obd-platform/frontend:latest,${{ github.sha }}

  deploy:
    runs-on: ubuntu-latest
    needs: build-and-push
    steps:
      - uses: actions/checkout@v3
      
      - name: Configure kubectl
        uses: azure/setup-kubectl@v3
        with:
          version: 'v1.28.0'
          
      - name: Set up Kubeconfig
        run: |
          echo "${{ secrets.KUBECONFIG }}" > kubeconfig
          export KUBECONFIG=kubeconfig
          
      - name: Deploy to Kubernetes
        run: |
          kubectl set image deployment/obd-backend backend=obd-platform/backend:${{ github.sha }} -n obd-platform
          kubectl set image deployment/obd-frontend frontend=obd-platform/frontend:${{ github.sha }} -n obd-platform
          kubectl rollout status deployment/obd-backend -n obd-platform
          kubectl rollout status deployment/obd-frontend -n obd-platform
```

## Monitoring Stack

### 1. Prometheus Configuration
```yml
# prometheus/prometheus.yml
global:
  scrape_interval: 15s
  evaluation_interval: 15s

scrape_configs:
  - job_name: 'kubernetes-pods'
    kubernetes_sd_configs:
      - role: pod
    relabel_configs:
      - source_labels: [__meta_kubernetes_pod_annotation_prometheus_io_scrape]
        action: keep
        regex: true
      - source_labels: [__meta_kubernetes_pod_annotation_prometheus_io_path]
        action: replace
        target_label: __metrics_path__
        regex: (.+)
      - source_labels: [__address__, __meta_kubernetes_pod_annotation_prometheus_io_port]
        action: replace
        regex: ([^:]+)(?::\d+)?;(\d+)
        replacement: $1:$2
        target_label: __address__

  - job_name: 'asterisk'
    static_configs:
      - targets: ['asterisk-service:8088']
  
  - job_name: 'kamailio'
    static_configs:
      - targets: ['kamailio-service:5060']
  
  - job_name: 'kafka'
    static_configs:
      - targets: ['kafka-service:9092']
```
### 2. Grafana Dashboards
```yml
{
  "dashboard": {
    "title": "OBD Platform Monitoring",
    "panels": [
      {
        "title": "Active Calls",
        "type": "graph",
        "targets": [
          {
            "expr": "sum(asterisk_channels_active)",
            "legendFormat": "Active Calls"
          }
        ],
        "gridPos": {"h": 8, "w": 12, "x": 0, "y": 0}
      },
      {
        "title": "Abandonment Rate",
        "type": "graph",
        "targets": [
          {
            "expr": "abandonment_rate{tenant!=\"\"}",
            "legendFormat": "{{tenant}}"
          }
        ],
        "gridPos": {"h": 8, "w": 12, "x": 12, "y": 0}
      },
      {
        "title": "API Request Rate",
        "type": "graph",
        "targets": [
          {
            "expr": "rate(http_requests_total[5m])",
            "legendFormat": "{{method}} {{path}}"
          }
        ],
        "gridPos": {"h": 8, "w": 12, "x": 0, "y": 8}
      },
      {
        "title": "Agent Status",
        "type": "stat",
        "targets": [
          {
            "expr": "agent_online",
            "legendFormat": "Online"
          }
        ],
        "gridPos": {"h": 8, "w": 12, "x": 12, "y": 8}
      }
    ]
  }
}
```

### 3. Alerts Configuration
```yml
# prometheus/alerts.yml
groups:
  - name: obd_alerts
    rules:
      - alert: HighAbandonmentRate
        expr: abandonment_rate > 3
        for: 5m
        labels:
          severity: critical
        annotations:
          summary: "High abandonment rate for tenant {{ $labels.tenant }}"
          
      - alert: AsteriskDown
        expr: up{job="asterisk"} == 0
        for: 2m
        labels:
          severity: critical
        annotations:
          summary: "Asterisk is down"
          
      - alert: HighAPIErrorRate
        expr: rate(http_requests_total{status=~"5.."}[5m]) > 0.05
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "High API error rate"
          
      - alert: DatabaseConnectionPoolFull
        expr: hikaricp_connections_active / hikaricp_connections_max > 0.9
        for: 2m
        labels:
          severity: warning
        annotations:
          summary: "Database connection pool is almost full"
```

## Database Backup & Recovery

### 1. Automated Backup Script
```sh
#!/bin/bash
# backup.sh

BACKUP_DIR="/backups/postgres"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
BACKUP_FILE="$BACKUP_DIR/obd_$TIMESTAMP.sql"

# Create backup
pg_dump -h postgres-service -U obd_user -d obd > $BACKUP_FILE

# Compress
gzip $BACKUP_FILE

# Upload to S3
aws s3 cp $BACKUP_FILE.gz s3://obd-backups/$(date +%Y/%m/%d)/

# Keep last 30 days of backups
find $BACKUP_DIR -name "*.sql.gz" -mtime +30 -delete

# Verify backup
pg_restore --list $BACKUP_FILE.gz | head -5
```

### 2. Recovery Procedure
```sh 
#!/bin/bash
# restore.sh

BACKUP_FILE=$1

# Download from S3
aws s3 cp s3://obd-backups/$BACKUP_FILE /tmp/backup.sql.gz

# Uncompress
gunzip -f /tmp/backup.sql.gz

# Restore
psql -h postgres-service -U obd_user -d obd < /tmp/backup.sql

# Clean up
rm /tmp/backup.sql*
```

## Auto-Scaling Configuration

### 1. Horizontal Pod Autoscaler
```yml
# k8s/hpa.yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: backend-hpa
  namespace: obd-platform
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: obd-backend
  minReplicas: 3
  maxReplicas: 10
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 70
    - type: Resource
      resource:
        name: memory
        target:
          type: Utilization
          averageUtilization: 80
    - type: Pods
      pods:
        metric:
          name: active_calls
        target:
          type: AverageValue
          averageValue: "50"
```

### 2. Vertical Pod Autoscaler
```yml
# k8s/vpa.yaml
apiVersion: autoscaling.k8s.io/v1
kind: VerticalPodAutoscaler
metadata:
  name: backend-vpa
  namespace: obd-platform
spec:
  targetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: obd-backend
  updatePolicy:
    updateMode: "Auto"
  resourcePolicy:
    containerPolicies:
      - containerName: backend
        minAllowed:
          cpu: "250m"
          memory: "512Mi"
        maxAllowed:
          cpu: "2"
          memory: "4Gi"

```

## SSL Certificate Management

### 1. Cert-Manager Configuration
```yml
# k8s/cert-manager/cluster-issuer.yaml
apiVersion: cert-manager.io/v1
kind: ClusterIssuer
metadata:
  name: letsencrypt-prod
spec:
  acme:
    server: https://acme-v02.api.letsencrypt.org/directory
    email: admin@obd-platform.com
    privateKeySecretRef:
      name: letsencrypt-prod
    solvers:
      - http01:
          ingress:
            class: nginx
```

2. Automated Certificate Renewal
```yml
# k8s/cert-manager/certificate.yaml
apiVersion: cert-manager.io/v1
kind: Certificate
metadata:
  name: obd-tls
  namespace: obd-platform
spec:
  secretName: obd-tls
  duration: 2160h # 90 days
  renewBefore: 360h # 15 days
  subject:
    organizations:
      - OBD Platform
  commonName: obd-platform.com
  dnsNames:
    - obd-platform.com
    - api.obd-platform.com
  issuerRef:
    name: letsencrypt-prod
    kind: ClusterIssuer
```

Disaster Recovery Plan
1. RPO/RTO Targets
RPO (Recovery Point Objective): 15 minutes

RTO (Recovery Time Objective): 1 hour

2. Failover Procedure
```sh
# 1. Check primary cluster status
kubectl get nodes -n obd-platform

# 2. If primary failed, switch to secondary
kubectl config use-context secondary-cluster

# 3. Restore database from latest backup
./restore.sh latest

# 4. Update DNS to point to secondary
aws route53 change-resource-record-sets ...

# 5. Verify services
kubectl get pods -n obd-platform

# 6. Resume operations
```

## Security Hardening

### 1. Network Policies
```yml
# k8s/network-policy.yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: backend-network-policy
  namespace: obd-platform
spec:
  podSelector:
    matchLabels:
      app: obd-backend
  policyTypes:
    - Ingress
    - Egress
  ingress:
    - from:
        - podSelector:
            matchLabels:
              app: obd-frontend
        - podSelector:
            matchLabels:
              app: obd-agent-portal
    - from:
        - namespaceSelector:
            matchLabels:
              name: monitoring
  egress:
    - to:
        - podSelector:
            matchLabels:
              app: postgres
        - podSelector:
            matchLabels:
              app: redis
        - podSelector:
            matchLabels:
              app: kafka
        - podSelector:
            matchLabels:
              app: minio
```

### 2. Secrets Management
```yml
# k8s/secrets.yaml
apiVersion: v1
kind: Secret
metadata:
  name: db-secret
  namespace: obd-platform
type: Opaque
data:
  url: amRiYzpwb3N0Z3Jlc3FsOi8vcG9zdGdyZXM6NTQzMi9vYmQ=
  username: b2JkX3VzZXI=
  password: b2JkX3Bhc3N3b3Jk
---
# Use sealed-secrets for Git
apiVersion: bitnami.com/v1alpha1
kind: SealedSecret
metadata:
  name: db-secret
  namespace: obd-platform
spec:
  encryptedData:
    url: AgBy3i4OJswK9k...
```

Cost Optimization
Right-size resources based on monitoring data

Use spot instances for non-critical workloads

Implement auto-scaling to match demand

Use reserved instances for predictable workloads

Optimize storage with lifecycle policies

Clean up unused resources (old images, volumes)

## Troubleshooting Guide
Issue	Check	Action
Pods not starting	Events	kubectl describe pod
Service not accessible	Endpoints	kubectl get endpoints
High memory usage	Metrics	kubectl top pods
Database connection errors	Connection pool	Check max_connections
Slow response times	Metrics	Check CPU, memory, network
Certificate expired	Cert-manager	kubectl get certificate
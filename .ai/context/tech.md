# OBD Platform - Technology Stack

## Backend Technologies

### Core Framework
| Technology | Version | Purpose |
|------------|---------|---------|
| Java | 25 LTS | Virtual threads for WebSocket scale |
| Spring Boot | 4.x.x | Modular monolith framework |
| Spring WebFlux | 3.2.x | Reactive programming |
| Spring Security | 6.2.x | JWT, RBAC, OAuth2 |
| Spring Data JPA | 3.2.x | ORM with RLS support |
| Hibernate | 6.3.x | JPA implementation |

### Build & Testing
| Technology | Version | Purpose |
|------------|---------|---------|
| Maven | 3.9+ | Build tool, multi-module |
| JUnit 5 | 5.10 | Unit testing |
| Mockito | 5.6 | Mocking framework |
| Testcontainers | 1.19 | Integration testing |
| Jacoco | 0.8.11 | Code coverage |

### Data Layer
| Technology | Version | Purpose |
|------------|---------|---------|
| PostgreSQL | 16 | Primary database |
| Redis | 7.2 | Cache, session, state |
| Apache Kafka | 3.6 | Event streaming |
| MinIO | Latest | Object storage (S3-compatible) |

### Telephony
| Technology | Version | Purpose |
|------------|---------|---------|
| Asterisk | 20+ | Media engine, ARI |
| Kamailio | 5.8 | SIP proxy |
| SIP.js | Latest | WebRTC client library |

### Integration
| Technology | Version | Purpose |
|------------|---------|---------|
| Spring WebClient | 3.2.x | HTTP client for webhooks |
| Resilience4j | 2.1 | Retry, circuit breaker |
| OpenCSV | 5.8 | CSV parsing |

### Monitoring
| Technology | Version | Purpose |
|------------|---------|---------|
| Micrometer | 1.12 | Metrics |
| Prometheus | Latest | Metrics collection |
| Grafana | Latest | Dashboards |
| ELK Stack | Latest | Log aggregation |
| Jaeger | Latest | Distributed tracing |

## Frontend Technologies

### Core Framework
| Technology | Version | Purpose |
|------------|---------|---------|
| Next.js | 14+ | SSR, middleware |
| React | 18+ | UI library |
| TypeScript | 5+ | Type safety |
| Tailwind CSS | 3+ | Utility-first CSS |
| Shadcn UI | Latest | Component library |

### State Management
| Technology | Version | Purpose |
|------------|---------|---------|
| TanStack Query | 5+ | Server-state caching |
| Zustand | 4+ | Client-state management |

### Real-Time
| Technology | Version | Purpose |
|------------|---------|---------|
| Socket.io-client | 4+ | WebSocket client |
| Server-Sent Events | Native | Real-time updates |

### Testing
| Technology | Version | Purpose |
|------------|---------|---------|
| Jest | 29+ | Unit testing |
| React Testing Library | 14+ | Component testing |
| Cypress | 13+ | E2E testing |

## Infrastructure Technologies

### Containerization
| Technology | Version | Purpose |
|------------|---------|---------|
| Docker | 24+ | Containerization |
| Docker Compose | 2.20 | Local orchestration |
| Kubernetes | 1.28+ | Production orchestration |
| Helm | 3.13+ | Kubernetes packaging |

### CI/CD
| Technology | Version | Purpose |
|------------|---------|---------|
| GitHub Actions | - | CI/CD pipeline |
| ArgoCD | 2.10 | GitOps deployment |
| SonarQube | 10+ | Code quality |

### Cloud Providers (Flexible)
| Provider | Services Used |
|----------|---------------|
| AWS | EC2, RDS, S3, EKS, MSK |
| GCP | Compute Engine, Cloud SQL, Cloud Storage |
| Azure | Virtual Machines, SQL Database, Blob Storage |

## Development Tools

### IDE & Extensions
- IntelliJ IDEA Ultimate (recommended)
- VS Code (with Java extensions)
- DBeaver (database client)
- Postman (API testing)
- TablePlus (database GUI)

### Required VS Code Extensions
```json
{
  "recommendations": [
    "vscjava.vscode-java-pack",
    "vmware.vscode-spring-boot",
    "ms-azuretools.vscode-docker",
    "redhat.vscode-yaml",
    "bradlc.vscode-tailwindcss",
    "esbenp.prettier-vscode",
    "dbaeumer.vscode-eslint",
    "eamodio.gitlens"
  ]
}
```

## Environment Variables
### Backend
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/obd
SPRING_DATASOURCE_USERNAME=obd_user
SPRING_DATASOURCE_PASSWORD=obd_pass
SPRING_REDIS_HOST=localhost
SPRING_REDIS_PORT=6379
SPRING_KAFKA_BOOTSTRAP_SERVERS=localhost:9092
MINIO_ENDPOINT=http://localhost:9000
MINIO_ACCESS_KEY=minioadmin
MINIO_SECRET_KEY=minioadmin
ARI_HOST=localhost
ARI_PORT=8088
ARI_USERNAME=asterisk
ARI_PASSWORD=asterisk123
JWT_SECRET=your-256-bit-secret-key
JWT_REFRESH_SECRET=your-refresh-secret

### Frontend
NEXT_PUBLIC_API_URL=http://localhost:8080
NEXT_PUBLIC_WS_URL=ws://localhost:8080/ws
NEXT_PUBLIC_ARI_URL=http://localhost:8088

### Version Strategy
Major.Minor.Patch (Semantic Versioning)
Breaking Changes: Major version increment
New Features: Minor version increment
Bug Fixes: Patch version increment
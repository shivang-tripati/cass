# F0 Artifact — Repository Map

Canonical source: [`../F0-FRONTEND-AUDIT.md`](../F0-FRONTEND-AUDIT.md) §2, §3, §4, §17.
Evidence root: `D:\work\agile\obd-platform`, branch `main`, HEAD `3a89e5c`.

## Monorepo layout

| Path | Responsibility | State |
|---|---|---|
| `backend/` | Spring Boot 4.1.0 API. 549 main Java files, 55 Flyway migrations (`V1`–`V55`, **`V43` absent**), 179 test files. | `mvnw` + `mvnw.cmd` present |
| `frontend/` | Next.js 16.2.10 App Router app, ~185 files under `src/` | `node_modules/` + `.next/` present |
| `docs/` | `00_`–`06_` project context, `VB-*` sprint reports, `LIVE-FREESWITCH-*`, `campaign-readiness.md` | **`docs/api/` exists but is EMPTY — no committed OpenAPI** |
| `infra/` | `docker-compose.yml`, `docker-compose.freeswitch.yml`, `docker-compose.freeswitch-endpoints.yml` | FreeSWITCH files untracked |
| `infrastructure/` | `docker/`, `kubernetes/`, `monitoring/` | Deployment shape |
| `mock/` | `contacts.csv`, `contacts_test_india.json` | Backend import fixtures, **not** frontend mocks |
| `module/` | Design notes: AUTH, TENANT, USER, LEAD, CAMPAIGN, AUDIO, DIALER | Documentation |
| `progress/` | `CURRENT_SPRINT.md`, `DECISIONS.md`, `DEVELOPMENT_LOG.md`, `KNOWN_ISSUES.md` | Planning |
| `tools/` | `freeswitch-harness/` | Untracked |
| `.ai/` | `context/`, `skills/`, `prompts/`, `workflows/` (per `.cursorrules`) | Agent guidance |
| `.github/` | Present | **EMPTY — no CI** |

**No shared packages.** No generated client, no OpenAPI-derived types. The TypeScript
contracts in `frontend/src/lib/api/contracts.ts` are hand-maintained and claim a "mirrored
1:1" relationship (`types.ts:1-6`) that this audit shows is only **partially true**.

## Frontend stack (actual versions)

| Concern | Implementation |
|---|---|
| Framework | Next.js **16.2.10**, App Router, route groups `(auth)` / `(platform)` |
| UI runtime | React **19.2.4** / react-dom 19.2.4 |
| Language | TypeScript ^5 |
| Build tool | Next's own bundler (`next build`) |
| Styling | Tailwind CSS **4** (`@tailwindcss/postcss`), CSS-first, CSS variables, `src/app/globals.css` |
| Component system | shadcn/ui, style `radix-nova`, baseColor `neutral`, on `radix-ui` ^1.6.7, `lucide-react`, CVA, `clsx`, `tailwind-merge` |
| Server state | **TanStack Query ^5.101.2** (single shared client) |
| Client state | **None.** `zustand` ^5.0.14 is declared but has **zero imports** |
| Tables | TanStack Table ^8.21.3 |
| API client | `axios` ^1.18.1, `baseURL: "/api/v1"`, 20 s timeout, `withCredentials` |
| Transport | Next rewrite `/api/:path*` → `${API_ORIGIN}/api/:path*` (`next.config.ts`); dev fallback `http://localhost:8081`; `frontend/.env` sets the same |
| Forms | react-hook-form ^7.80 + `@hookform/resolvers` ^5.4 |
| Validation | **zod ^4.4.3** (11 schema modules under `lib/schemas/`) |
| Notifications | `sonner` ^2.0.7 (32 call sites) |
| Theming | `next-themes` ^0.4.6 consumed by `components/ui/sonner.tsx:3` but **no `ThemeProvider` is mounted** |
| Testing | **NONE** — no runner, no test files, no `test` script |
| E2E | **NONE** |
| Lint / typecheck | `eslint` 9 + `eslint-config-next` 16.2.10; `tsc --noEmit` |

## Backend stack (actual versions)

| Concern | Implementation |
|---|---|
| Framework | Spring Boot **4.1.0**, Java **17** |
| Modularity | Spring Modulith **2.1.0** (+ `ArchitectureTest` enforcing boundaries) |
| API style | REST under `/api/v1`, uniform `ApiResponse<T>` envelope |
| AuthN | Spring Security, **stateless**, OAuth2 resource server (JWT) |
| AuthZ | **Custom capability engine** in `authz/`. `@EnableMethodSecurity` is enabled but **unused**; `@PreAuthorize`/`@Secured`/`@RolesAllowed` appear **zero** times |
| Persistence | Spring Data JPA + **PostgreSQL**; soft delete via `AuditableEntity` |
| Migrations | **Flyway** (`spring-boot-starter-flyway` + `flyway-maven-plugin`), 55 migrations |
| Cache / limiter | **Redis** (`spring-boot-starter-data-redis`), login rate limiting |
| HTTP client | `spring-boot-starter-restclient` |
| Validation | `spring-boot-starter-validation` (Jakarta Bean Validation) |
| OpenAPI | `springdoc-openapi-starter-webmvc-ui` **2.8.5** at `/v3/api-docs` + `/swagger-ui`, both `permitAll`. **No committed spec** |
| Storage | `AudioStorage` abstraction; `LocalAudioStorage` / `NoOpAudioStorage` |
| Test stack | `spring-boot-starter-*-test` suites, **Testcontainers 2.0.2** (PostgreSQL), Spring Modulith test, ~25 `*PostgresIntegrationTest` |

## Validation commands (executed in F0)

| Project | Command | Result |
|---|---|---|
| Frontend | `npm run typecheck` | **PASS** (exit 0) |
| Frontend | `npm run lint` | **PASS** (exit 0) — 0 errors, 5 `react-hooks/incompatible-library` warnings |
| Frontend | `npm run build` | Not run in F0 (mutates `.next/`) |
| Frontend | tests | Not available |
| Backend | `./mvnw test` | Not run in F0 (needs PostgreSQL + Redis) |

No project configuration was changed to make any command pass.

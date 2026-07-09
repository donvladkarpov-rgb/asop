# ASOP Platform Architecture

**Version:** 1.0  
**Date:** 2026-07-09  
**Status:** MVP in development

---

## Table of Contents

1. [Technology Stack](#1-technology-stack)
2. [Module Structure](#2-module-structure)
3. [Gateway Architecture](#3-gateway-architecture)
4. [Authentication & Authorization](#4-authentication--authorization)
5. [Pass-Through Identity](#5-pass-through-identity)
6. [Keycloak Integration](#6-keycloak-integration)
7. [Kafka Messaging](#7-kafka-messaging)
8. [Crypto & PKI](#8-crypto--pki)
9. [Database](#9-database)
10. [Frontend](#10-frontend)
11. [Build & Deploy](#11-build--deploy)
12. [Key Decisions & Rationale](#12-key-decisions--rationale)

---

## 1. Technology Stack

| Component | Version | Purpose |
|-----------|---------|---------|
| Kotlin | 2.0.21 | Primary language |
| Spring Boot | 3.3.5 (WebFlux) | Reactive framework |
| PostgreSQL | 14 + PostGIS | Database |
| Kafka | 3.7.1 | Async messaging |
| Keycloak | 25.0.4 | OIDC provider |
| Bouncy Castle | 1.78.1 | Crypto (ECC P-256) |
| Gradle | 8.10.2 | Build system |
| React | 18 + Vite | Admin frontend |
| oidc-client-ts | — | OIDC client library |

### JVM Limits (critical)
- Gradle daemon: `-Xmx4g`
- Kotlin daemon: `-Xmx2g`
- Without these, build OOMs on 22 modules.
- `org.gradle.configuration-cache=false` — breaks Kotlin compilation (ClasspathSnapshotProperties).

---

## 2. Module Structure

22 modules organized in a layered hierarchy:

```
:backend:shared:asop-common           # BaseEntity, DomainEvent, ErrorCode, KafkaTopic, util (InnValidator, UuidUtils)
:backend:shared:asop-dto              # Empty — DTOs moved to API modules
:backend:shared:asop-kafka-contracts  # Kafka event class definitions
:backend:shared:api:{domain}-api      # 10 modules: controller interfaces + DTOs (no implementation)
:backend:{domain}-service             # 10 Spring Boot apps with implementation
```

### Dependency graph
```
Service → API (implementation) → asop-common (api platform)
```

### API module pattern
Package: `ru.asop.api.{domain}`
```
backend/shared/api/{name}-api/
├── build.gradle.kts
└── src/main/kotlin/ru/asop/api/{package}/
    ├── controller/          # Controller interfaces (@RequestMapping)
    ├── dto/request/         # Request DTOs
    ├── dto/response/        # Response DTOs
    └── exception/           # API exceptions
```

### Service module pattern
Package: `ru.asop.{domain}` (e.g. `ru.asop.gateway`, `ru.asop.crypto`)

### Service ports

| Service | Port | Role |
|---------|------|------|
| gateway-service | 8080 | API Gateway: JWT + mTLS, Kafka producer |
| crypto-service | 8081 | Root CA, X.509 cert issuance |
| user-service | 8082 | Users + Keycloak bootstrap |
| terminal-service | 8084 | Terminal management |
| session-service | 8085 | Sessions/shifts (tree hierarchy) |
| card-service | 8086 | Cards (MIFARE, bank) |
| carrier-service | 8087 | Carriers, contracts, vehicles (R2DBC) |
| debt-service | 8088 | Card debts |
| audit-service | 8089 | Inspections |
| fiscal-service | 8090 | Fiscalization (OFD) |

---

## 3. Gateway Architecture

**Core principle:** Gateway does NOT write to the database. It validates auth, pushes commands to Kafka, returns `202 Accepted`.

### Routing patterns

#### 1. Async writes (POST/PUT/DELETE with explicit controller)
- Command sent to Kafka
- Gateway returns `202 Accepted` + `X-Event-Id` header + `AcceptedResponse` body
- `keycloakId` passed in Kafka headers (`X-Keycloak-Id`)
- Example: `CarrierController` / `CarrierCommandService`

#### 2. Sync proxy (GET + unhandled requests)
- `ProxyController` forwards to backend services via `WebClient`
- Resource → base URL mapping in `ServiceRegistry` (`ServiceRegistry.kt`):
  - `ASOP_ENV=local` (default) → `localhost:{port}`
  - `ASOP_ENV=docker` → Docker service hostnames
- Gateway adds `X-Keycloak-Id` header, removes `Authorization`

#### 3. Event tracking
- After Kafka send, `EventService` stores status `PENDING` (in-memory `ConcurrentHashMap` with 30-min TTL)
- Frontend polls `GET /api/v1/events/{eventId}` until `COMPLETED` or `FAILED`
- Status stays PENDING forever unless services publish events to `.events` topics

### Key gateway components

| File | Purpose |
|------|---------|
| `config/ServiceRegistry.kt` | Resource → service URL mapping |
| `config/WebClientConfig.kt` | WebClient bean for proxy |
| `controller/ProxyController.kt` | Catch-all sync proxy |
| `controller/EventController.kt` | Event status endpoint |
| `service/EventService.kt` | In-memory event store |
| `model/EventStatus.kt` | EventState (PENDING, COMPLETED, FAILED) |

---

## 4. Authentication & Authorization

### Dual auth in Gateway

**Chain 1** (`@Order(1)`): mTLS for terminal endpoints
- Paths: `/api/v1/terminals/**`, `/api/v1/sync/**`
- Principal = `CN` from X.509 certificate
- `X509PrincipalExtractor` from `org.springframework.security.web.authentication.preauth.x509` (synchronous, returns `Any`)

**Chain 2** (`@Order(2)`): JWT (Keycloak) for everything else
- JWKS cached locally, verified every 60s
- No network calls to Keycloak per-request

### JWT Issuer workaround

In Docker, Keycloak (`KC_HOSTNAME=localhost`) issues tokens with `iss: http://localhost:8180/realms/asop`, but services reach Keycloak at `http://keycloak:8080`. Standard Spring Security validator rejects mismatched issuer.

**Solution:** Custom `ReactiveJwtDecoder` (`JwtDecoderConfig.kt`) that:
- Uses JWKS (`jwk-set-uri`) for signature verification
- Does NOT validate `iss` (accepts any issuer)
- Validates `exp` and `nbf` manually

Currently applied in: gateway-service, user-service.

---

## 5. Pass-Through Identity

Gateway validates JWT, extracts `sub` (keycloakId), passes it to backend services **without** forwarding the original JWT.

### Flow
```
Client                     Gateway                         Service
  │                         │                                │
  │ POST /password/change   │                                │
  │ Authorization: JWT      │                                │
  │────────────────────────>│                                │
  │                         │ JWT validation (JWKS, exp/nbf) │
  │                         │ extract sub (keycloakId)       │
  │                         │                                │
  │                         │ POST /password/change          │
  │                         │ X-Keycloak-Id: <sub>          │
  │                         │ (no Authorization)             │
  │                         │──────────────────────────────>│
  │                         │                                │
  │                         │ resolve keycloakId → userId   │
  │                         │ process request                │
  │                         │<──────────────────────────────│
  │<────────────────────────│                                │
  │ 204 / 202 / 4xx / 5xx   │                                │
```

### Implementation
- **Gateway**: `ProxyController.extractIdentity()` uses `ReactiveSecurityContextHolder` to get JWT, extracts `sub`
- **Kafka async**: `X-Keycloak-Id` in Kafka headers
- **Backend services**: do NOT validate JWT (except user-service which has `permitAll`)
- Backend services trust `X-Keycloak-Id` from gateway (internal network trust)

### Future improvement
Create a shared `UserResolver` in `asop-common` to resolve `keycloakId` → `userId` + roles from database.

---

## 6. Keycloak Integration

### Keycloak 25.0.4 bugs

Three bugs found when creating realm/users via Admin API:

1. **Realm creation**
   - Bare `{realm: "asop", enabled: true}` breaks Direct Access Grant
   - Required: `resetPasswordAllowed=true`, `directGrantFlow="direct grant"`, `registrationAllowed=false`, `verifyEmail=false`, `loginWithEmailAllowed=true`

2. **User credentials**
   - `POST /users` without credentials + separate `PUT /reset-password` → "Account is not fully set up"
   - Fix: pass credentials **inline** in `UserRepresentation`

3. **User names**
   - Missing `firstName` or `lastName` → "Account is not fully set up"
   - Both fields are mandatory

### Bootstrap service

On `ApplicationReadyEvent`, if `ASOP_USERS` table is empty:
1. Creates Keycloak realm `asop` with full configuration
2. Creates roles: `SUPER_ADMIN`, `CARRIER_ADMIN`, `DISPATCHER`, etc.
3. Creates admin user `admin@asop.local` with inline credentials
4. Records user in `ASOP_USERS` + `ASOP_USER_ROLES`

### Auth flows

| Environment | Flow | Status |
|------------|------|--------|
| MVP/dev | Direct Access Grant (password grant) | Active |
| Production | OIDC Auth Code + PKCE (via oidc-client-ts) | Planned |

### Endpoints

| Method | Path | Service | Type |
|--------|------|---------|------|
| POST | `/api/v1/users/password/change` | user-service | sync (pass-through) |

---

## 7. Kafka Messaging

### Topic naming
Pattern: `asop.{domain}.{commands|events}`

Defined in `KafkaTopic` object in `asop-common`:
- `asop.carrier.commands` — carrier write commands
- `asop.session.events` — session domain events

### Async command flow
1. Gateway validates request, extracts identity
2. Gateway sends command to `asop.{domain}.commands` topic
3. Gateway returns `202 Accepted` + `X-Event-Id`
4. Backend service consumes command, processes, publishes event to `asop.{domain}.events`
5. Event consumer updates event status (future: persistence)

### Headers
- `X-Keycloak-Id`: authenticated user's keycloakId (identity tracing)

---

## 8. Crypto & PKI

### Certificate hierarchy
```
Root CA (self-signed, ECC P-256, 10 years)
└── Intermediate CA (signed by Root CA, 5 years)
    ├── Terminal Certificates (1 year)
    ├── Smart Card Certificates (1 year)
    └── Driver Certificates (1 year)
```

### Implementation
- Root CA in PKCS#12 (`./data/root-ca.p12`), auto-generated on first start
- ECC P-256 via Bouncy Castle
- All signing via Intermediate CA (regenerated on each restart in MVP)
- `MediaType.APPLICATION_PEM_CERTIFICATE_VALUE` not available in Spring 6.1 — use `"application/x-pem-file"`

### Crypto-service endpoints
| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/v1/terminals/register` | Terminal cert issuance |
| POST | `/api/v1/smart-cards/issue` | Smart card cert issuance |
| GET | `/api/v1/terminals/root-ca(/{format})` | Root CA in PEM/DER |

### Smart card roles
`PASSENGER_ANONYMOUS`, `PASSENGER_BENEFIT`, `DRIVER`, `CONTROLLER`, `DISPATCHER`, `CARRIER_ADMIN`, `REGION_ADMIN`, `SUPER_ADMIN`, `DISTRIBUTOR_ADMIN`, `DISTRIBUTOR_TERMINAL`, `SERVICE`

### DN templates
```yaml
DRIVER: "CN={cardId}, OU=DRIVER:{carrierId}, O=ASOP"
CONTROLLER: "CN={cardId}, OU=CONTROLLER:{carrierId}, O=ASOP"
```

---

## 9. Database

### Key tables
- `ASOP_USERS`, `ASOP_USER_ROLES`, `ASOP_USER_CARRIERS` — users
- `ASOP_CARRIERS`, `ASOP_CONTRACTS`, `ASOP_VEHICLES` — carriers
- `ASOP_CARDS`, `ASOP_CARD_MIFARES`, `ASOP_CARD_TARIFFS`, `ASOP_CARD_BANKS` — cards
- `ASOP_TERMINALS`, `ASOP_DISTRIBUTOR_TERMINALS`, `ASOP_TIDS` — terminals
- `ASOP_SESSIONS`, `ASOP_TRANSACTIONS`, `ASOP_CARD_DEBTS` — transactions
- `ASOP_AUDIT_TASKS`, `ASOP_AUDIT_BRIGADES`, `ASOP_AUDIT_INSPECTIONS` — inspections

### Conventions
- **All IDs**: UUID v7 via `UuidCreator.getTimeOrderedEpoch()` (`UuidUtils.newId()`)
- **Migrations**: Liquibase, stored in `infrastructure/db-migrations/{service}/`
- Currently only user-service has active migrations; others have `liquibase.enabled=false`

---

## 10. Frontend

### Stack
- Vite + React 18 + TypeScript
- React Router (client-side routing)
- TanStack Query (server state)
- oidc-client-ts (OIDC Auth Code + PKCE)
- Axios (API client)

### Architecture
- Proxy `/api` → `http://localhost:8080` (gateway) in Vite dev mode
- API client: `BASE=/api/v1`, Bearer token from oidc-client-ts
- `useCommand` hook: 202 + polling pattern for write commands

### Pages
`Login`, `Callback` (OIDC), `Dashboard`, `Users`, `Terminals`, `Cards`

---

## 11. Build & Deploy

### Docker
```bash
./gradlew bootJar
docker compose -f infrastructure/docker/docker-compose.yml up -d --build
```

Each service has a `Dockerfile` (eclipse-temurin:21-jre). Liquibase migrations mounted from `infrastructure/db-migrations/` into `/db-migrations/` inside containers.

### Docker Compose
All 10 services + PostgreSQL + Kafka + Keycloak on shared network `asop-net`.

### Key environment variables
| Variable | Purpose |
|----------|---------|
| `BOOTSTRAP_ENABLED` | Enable bootstrap on startup |
| `BOOTSTRAP_ADMIN_PASSWORD` | Initial admin password |
| `KEYCLOAK_URL` | Keycloak internal URL |
| `KEYCLOAK_ADMIN_PASSWORD` | Keycloak admin password |
| `ROOT_CA_KEYSTORE_PASSWORD` | Root CA keystore password |
| `ASOP_ENV` | `local` (default) or `docker` |
| `KC_HOSTNAME` | Keycloak hostname (localhost in Docker) |

---

## 12. Key Decisions & Rationale

| Decision | Rationale |
|----------|-----------|
| **Gateway → Kafka → Services** (no DB writes in gateway) | CQRS-like separation; gateway is just a router/validator |
| **Pass-through identity** | Backend services don't need to validate JWT; trust internal network |
| **Custom JWT decoder (no issuer validation)** | Keycloak issuer URL differs between Docker network and external access |
| **API modules as separate Gradle subprojects** | Clean contract separation; service and client share same DTOs/interfaces |
| **UUID v7** | Time-ordered → better B-tree index performance than UUID v4 |
| **ECC P-256 for certs** | Smaller keys than RSA; hardware support in Android StrongBox, DESFire EV3 |
| **In-memory event store (TTL 30 min)** | MVP simplicity; events are ephemeral (tracking only until service confirms) |
| **mTLS for terminals** | Offline-capable device auth; no dependency on Keycloak availability |
| **Chain 1 + Chain 2 in SecurityConfig** | Clean separation of terminal (mTLS) and web (JWT) auth flows |
| **Liquibase in separate directory** | Centralized migration management; not embedded in service JARs |

# ASOP Platform (asop-platform)

**Стек:** Kotlin 2.0.21, Spring Boot 3.3.5 (WebFlux), PostgreSQL 14 + PostGIS, Kafka 3.7.1, Keycloak 25.0.4, Bouncy Castle 1.78.1, Gradle 8.10.2.

## Build

```bash
./gradlew build          # полная сборка
./gradlew :backend:{module}:build  # сборка одного модуля
./gradlew clean build    # с очисткой
```

## Critical config

- **JVM memory**: Gradle needs `-Xmx4g`, Kotlin daemon `-Xmx2g` (already in `gradle.properties`). Without this, build OOMs on 22 modules.
- **`org.gradle.configuration-cache=false`** — breaks Kotlin compilation (ClasspathSnapshotProperties). Do not enable.
- **No tests exist** — `./gradlew test` runs zero tests. Don't assume coverage requirements.
- **No CI workflow** — GitHub Actions not configured.

## Architecture

**Gateway → Kafka → Services** (async, CQRS-подобная). Gateway не пишет в БД, только валидирует и пушит команды в Kafka, возвращает `202 Accepted`.

### Gateway routing patterns

Gateway обрабатывает запросы двумя способами:

1. **Async writes (POST/PUT/DELETE с явным контроллером)** — команда уходит в Kafka, gateway возвращает `202 Accepted` + `X-Event-Id`. Пример: `CarrierController` / `CarrierCommandService`. keycloakId передаётся в Kafka headers (`X-Keycloak-Id`).

2. **Sync proxy (GET + остальные запросы)** — `ProxyController` пересылает запросы в backend-сервисы через `WebClient`. Маппинг ресурсов (`users`, `carriers`, `cards`, etc.) → base URL сервиса определён в `ServiceRegistry`. Для local dev `ASOP_ENV=local` (default → `localhost`), для Docker `ASOP_ENV=docker` (→ Docker hostnames).

3. **Event tracking** — после отправки команды в Kafka `EventService` сохраняет статус `PENDING`. Фронт поллит `GET /api/v1/events/{eventId}` до COMPLETED/FAILED. Пока сервисы не публикуют события в `.events` topics — статус навсегда PENDING. `EventController` отдаёт статус.

### Gateway files

- `config/ServiceRegistry.kt` — маппинг `resource` → `http://service:port/api/v1/{resource}`
- `config/WebClientConfig.kt` — `WebClient` bean для proxy
- `controller/ProxyController.kt` — catch-all `/api/v1/{resource}/**` для GET + необработанных запросов
- `controller/EventController.kt` — `GET /api/v1/events/{eventId}`
- `service/EventService.kt` — in-memory `ConcurrentHashMap<UUID, EventStatus>` с TTL-очисткой
- `model/EventStatus.kt` — `EventState` (PENDING, COMPLETED, FAILED) + `EventStatus`

### JWT issuer

В Docker Keycloak (`KC_HOSTNAME=localhost`) выдаёт токены с `iss: http://localhost:8180/realms/asop`, но сервисы внутри Docker обращаются к Keycloak по `http://keycloak:8080`. Из-за этого стандартный валидатор Spring Security отвергает токены.

**Решение**: кастомный `ReactiveJwtDecoder` (см. `JwtDecoderConfig.kt` в gateway-service и user-service), который:
- Использует JWKS с `jwk-set-uri` для проверки подписи
- Не проверяет `iss` (accept any issuer)
- Проверяет `exp` и `nbf` вручную

Применён в gateway-service и user-service. Для других сервисов нужно добавить при запуске.

### Module structure (22 modules in `settings.gradle.kts`)

```
:backend:shared:asop-common          # BaseEntity, DomainEvent, ErrorCode, KafkaTopic, util
:backend:shared:asop-dto             # пусто (DTO перенесены в API-модули)
:backend:shared:asop-kafka-contracts # Kafka event classes
:backend:shared:api:{domain}-api     # Controller interfaces + DTO (10 модулей)
:backend:{domain}-service            # Spring Boot apps (10 сервисов)
```

Service → API dependency: `implementation(project(":backend:shared:api:{domain}-api"))`.
API → asop-common dependency via `api(platform(...))` pattern.

### Key services

| Path | Port | Role |
|------|------|------|
| `gateway-service` | 8080 | API Gateway: JWT + mTLS, Kafka producer |
| `crypto-service` | 8081 | Root CA, X.509 cert issuance |
| `user-service` | 8082 | Users + Keycloak bootstrap |
| `terminal-service` | 8084 | Terminal management |
| `session-service` | 8085 | Sessions/shifts (tree hierarchy) |
| `card-service` | 8086 | Cards (MIFARE, bank) |
| `carrier-service` | 8087 | Carriers, contracts, vehicles (R2DBC) |
| `debt-service` | 8088 | Card debts |
| `audit-service` | 8089 | Inspections (КРС) |
| `fiscal-service` | 8090 | Fiscalization (OFD) |

### Gateway dual auth

- **Chain 1** (`@Order(1)`): mTLS for `/api/v1/terminals/**`, `/api/v1/sync/**`. Principal = `CN` from X.509 cert.
- **Chain 2** (`@Order(2)`): JWT (Keycloak) for everything else. JWKS cached locally via кастомный `ReactiveJwtDecoder` (см. `JwtDecoderConfig`).
- `X509PrincipalExtractor` is from `org.springframework.security.web.authentication.preauth.x509`, not `web.server.authentication`. It's a synchronous interface (returns `Any`, not `Mono<Any>`).

### Frontend

- **`frontend/web-admin/`**: Vite + React + TypeScript + react-router + TanStack Query + oidc-client-ts
- В Vite dev mode (`npm run dev`) проксирует `/api` → `http://localhost:8080` (gateway)
- `useCommand` hook — паттерн 202 + polling для команд записи
- API-клиент через axios, BASE=`/api/v1`, авторизация через Bearer token из oidc-client-ts
- Страницы: Login, Callback (OIDC), Dashboard, Users, Terminals, Cards
- Язык UI: русский (для переключения на английский нужен i18n — react-intl/i18next)

### Kafka topic naming

Pattern: `asop.{domain}.{commands|events}` — see `KafkaTopic` object in `asop-common`. For example: `asop.carrier.commands`, `asop.session.events`.

## Key conventions

- **All IDs** = UUID v7 via `UuidCreator.getTimeOrderedEpoch()` (`UuidUtils.newId()`)
- **REST prefix**: `/api/v1/{resource}`
- **Gateway returns**: `202 Accepted` + `X-Event-Id` header + `AcceptedResponse` body (with `eventId`, `topic`, `acceptedAt`, `locationHint`)
- **API modules** contain only interfaces + DTOs, no implementation. Package: `ru.asop.api.{domain}`.
- **Service packages**: `ru.asop.{domain}` (e.g. `ru.asop.gateway`, `ru.asop.crypto`)
- **Liquibase migrations**: all in `infrastructure/db-migrations/{service}/` (e.g. `infrastructure/db-migrations/user/`). Only `user-service` has migrations now; others have `liquibase.enabled=false` until changelogs are added.
- **InnValidator** lives in `asop-common`, used in gateway for carrier creation

## Endpoints (реализовано)

### Gateway (порт 8080, HTTPS)
| Метод | Путь | Описание | Тип |
|-------|------|----------|-----|
| GET/POST/PUT/DELETE | `/api/v1/{resource}/**` | Proxy в backend-сервисы (кроме явных обработчиков) | sync |
| POST | `/api/v1/carriers` | Создание перевозчика (Kafka) | async |
| GET | `/api/v1/events/{eventId}` | Статус async-команды | sync |

### User-service (порт 8082, только через gateway)
| Метод | Путь | Описание |
|-------|------|----------|
| POST | `/api/v1/users/password/change` | Смена пароля (pass-through identity) |

## Auth workflow (pass-through identity)

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
  │                         │ (без Authorization)            │
  │                         │──────────────────────────────>│
  │                         │                                │
  │                         │ resolve keycloakId → userId   │
  │                         │ process request                │
  │                         │<──────────────────────────────│
  │<────────────────────────│                                │
  │ 204 / 202 / 4xx / 5xx   │                                │
```

## Keycloak 25.0.4 bugs

При создании realm/users через Admin API:
1. **Realm**: нужно явно включать `resetPasswordAllowed=true`, `directGrantFlow="direct grant"`, `registrationAllowed=false`
2. **User**: credentials передавать **inline** в `UserRepresentation`, не через `resetPassword` API
3. **User**: обязательны **оба** поля `firstName` + `lastName`, иначе Direct Access Grant выдаёт "Account is not fully set up"

## Infrastructure

- **Docker Compose** in `infrastructure/docker/docker-compose.yml` — все 10 сервисов + Postgres + Kafka + Keycloak на общей сети `asop-net`
- **PostgreSQL 14** with PostGIS
- **Keycloak 25.0.4** on port 8180, realm `asop`
- **Bootstrap** (`BootstrapService` in `user-service`): on `ApplicationReadyEvent`, checks `ASOP_USERS` — if empty, creates Keycloak realm + roles + admin user (`admin@asop.local`, temporary password from `BOOTSTRAP_ADMIN_PASSWORD`). Records in `ASOP_USERS` + `ASOP_USER_ROLES`. Env vars: `BOOTSTRAP_ENABLED`, `BOOTSTRAP_ADMIN_PASSWORD`, `KEYCLOAK_URL`, `KEYCLOAK_ADMIN_PASSWORD`
  - **Keycloak 25.0.4 bug**: realm creation with bare `{realm: "asop", enabled: true}` breaks Direct Access Grant. Must include `resetPasswordAllowed: true`, `directGrantFlow: "direct grant"`, `registrationAllowed: false`, etc.
  - **Keycloak 25.0.4 bug**: `POST /users` без credentials + `PUT /reset-password` → "Account is not fully set up". Фикс: передавать credentials inline в `UserRepresentation`.
  - **Keycloak 25.0.4 bug**: пользователь без BOTH `firstName` и `lastName` → "Account is not fully set up". Оба поля обязательны.
- **Password change**: `POST /api/v1/users/password/change` через gateway → pass-through identity. Gateway извлекает `sub` из JWT, передаёт `X-Keycloak-Id` header в user-service. User-service не валидирует JWT (permitAll), читает `X-Keycloak-Id` из header, обновляет пароль в Keycloak Admin API. Запросы напрямую к user-service тоже разрешены (без JWT).

### Docker deploy

```bash
# 1. Build JARs
./gradlew bootJar

# 2. Build & start all containers
docker compose -f infrastructure/docker/docker-compose.yml up -d --build

# 3. Drop specific service
docker compose -f infrastructure/docker/docker-compose.yml up -d --build user-service
```

Each service has its own `Dockerfile` in `backend/{service}/Dockerfile` (eclipse-temurin:21-jre). Liquibase migrations for Docker mounted from `infrastructure/db-migrations/` into `/db-migrations/` inside containers.

## Crypto (crypto-service)

- Root CA in PKCS#12 (`./data/root-ca.p12`), auto-generated on first start
- ECC P-256 via Bouncy Castle, all signing via Intermediate CA (regenerated on each restart in MVP)
- `MediaType.APPLICATION_PEM_CERTIFICATE_VALUE` not available in Spring 6.1 — use `"application/x-pem-file"`
- Endpoints: `POST /api/v1/terminals/register`, `POST /api/v1/smart-cards/issue`, `GET /api/v1/terminals/root-ca(/{format})`

## Reference docs

- `doc/context.md` — full project guide
- `doc/auth.md` — auth architecture
- `infrastructure/db-migrations/asop_schema.sql` — database schema

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

3. **Event tracking** — после отправки команды в Kafka `EventService` сохраняет статус `PENDING` (in-memory `ConcurrentHashMap<UUID, EventStatus>`, TTL 30 мин). Фронт поллит `GET /api/v1/events/{eventId}`:
   - `202 Accepted` пока PENDING
   - `200 OK` с `resultData` когда COMPLETED (cert saga — JSON с PEM)
   - `422 Unprocessable Entity` с `errorMessage` когда FAILED
   - `404 Not Found` если eventId неизвестен

   Для cert-sign saga (`asop.terminal.cert.events`) Gateway-consumer обновляет EventService (`COMPLETED`/`FAILED`). Для остальных команд пока сервисы не публикуют события — статус навсегда PENDING.

### Cert signing saga (choreographed, 4 hops)

Первая регистрация терминала — **открытый HTTPS endpoint без JWT/mTLS** (chicken-and-egg). Поток:

```
Android → POST /api/v1/terminals/cert-sign (HTTPS plain)
       → 202 + X-Event-Id
       ↓
Gateway → Kafka asop.terminal.cert.commands (X-Event-Id header)
       ↓
crypto-service @KafkaListener → выпускает X.509 через Intermediate CA
                            → Kafka asop.terminal.cert.issued (X-Event-Id пробрасывается)
       ↓
terminal-service @KafkaListener → ensureTerminal (findByTerminalSerial → insert если новый)
                              → TransactionalOperator.transactional:
                                markAllAsNotCurrent(terminalId)
                                R2dbcEntityTemplate.insert(TerminalCertEntity IS_CURRENT=true)
                              → Kafka asop.terminal.cert.events (CertStored | CertSignFailed)
       ↓
Gateway CertEventConsumer → EventService.complete(eventId, resultData=CertStoredResult JSON)
                          или fail(eventId, reason)
       ↓
Android polling GET /api/v1/events/{eventId} → 200 + resultData → MtlsManager.storeCertificateChain()
```

Подробности — `doc/context.md` раздел 9.

### Gateway files

- `config/ServiceRegistry.kt` — маппинг `resource` → `https://service:port/api/v1/{resource}`
- `config/WebClientConfig.kt` — `WebClient` bean для proxy (SSL truststore из `/tmp/certs/truststore.p12`, hostname verification отключён)
- `controller/ProxyController.kt` — catch-all `/api/v1/{resource}/**` для GET + необработанных запросов
- `controller/EventController.kt` — `GET /api/v1/events/{eventId}`
- `controller/CertCommandController.kt` — `POST /api/v1/terminals/cert-sign` (open HTTPS, async)
- `service/CertCommandService.kt` — producer `CertSignRequested` в `asop.terminal.cert.commands`
- `kafka/CertEventConsumer.kt` — consumer `asop.terminal.cert.events` → `EventService.complete/fail`
- `kafka/CommandEventConsumer.kt` — consumer всех 7 domain event topics → `EventService.complete/fail`
- `service/EventService.kt` — in-memory `ConcurrentHashMap<UUID, EventStatus>` с TTL-очисткой
- `model/EventStatus.kt` — `EventState` (PENDING, COMPLETED, FAILED) + `EventStatus` (с `resultData: String?`)

**Terminal async command controllers** (все под mTLS `/api/v1/sync/**`):
- `controller/SessionCommandController.kt` — open/close session → Kafka
- `controller/TransactionCommandController.kt` — complete transaction → Kafka
- `controller/CardCommandController.kt` — register/block card → Kafka
- `controller/DebtCommandController.kt` — create/recover debt → Kafka
- `controller/FiscalCommandController.kt` — request fiscal receipt → Kafka
- `controller/AuditCommandController.kt` — create audit task → Kafka
- `controller/GpsCommandController.kt` — report GPS position → Kafka

**Terminal command services** (gateway → Kafka producers):
- `service/SessionCommandService.kt` — SessionOpenedEvent/SessionClosedEvent → `asop.session.commands`
- `service/TransactionCommandService.kt` — TransactionCompletedEvent → `asop.transaction.commands`
- `service/CardCommandService.kt` — CardRegisteredEvent/CardBlockedEvent → `asop.card.commands`
- `service/DebtCommandService.kt` — DebtCreatedEvent/DebtRecoveredEvent → `asop.debt.commands`
- `service/FiscalCommandService.kt` — FiscalReceiptRequestedEvent → `asop.fiscal.commands`
- `service/AuditCommandService.kt` — AuditTaskCreatedEvent → `asop.audit.commands`
- `service/GpsCommandService.kt` — GpsPositionReported → `asop.gps.commands`

### JWT issuer

В Docker Keycloak (`KC_HOSTNAME=localhost`) выдаёт токены с `iss: http://localhost:8180/realms/asop`, но сервисы внутри Docker обращаются к Keycloak по `http://keycloak:8080`. Из-за этого стандартный валидатор Spring Security отвергает токены.

**Решение**: кастомный `ReactiveJwtDecoder` (см. `JwtDecoderConfig.kt` в gateway-service и user-service), который:
- Использует JWKS с `jwk-set-uri` для проверки подписи
- Не проверяет `iss` (accept any issuer)
- Проверяет `exp` и `nbf` вручную

Применён в gateway-service и user-service. Для других сервисов нужно добавить при запуске.

### Module structure (27 modules in `settings.gradle.kts`)

```
:backend:shared:asop-common          # BaseEntity, DomainEvent, ErrorCode, KafkaTopic, util
:backend:shared:asop-dto             # пусто (DTO перенесены в API-модули)
:backend:shared:asop-kafka-contracts # Kafka event classes
:backend:shared:api:{domain}-api     # Controller interfaces + DTO (12 модулей)
:backend:{domain}-service            # Spring Boot apps (12 сервисов)
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
| `carrier-service` | 8087 | Carriers, contracts (R2DBC) |
| `debt-service` | 8088 | Card debts |
| `audit-service` | 8089 | Inspections (КРС) |
| `fiscal-service` | 8090 | Fiscalization (OFD) |
| `admin-service` | 8091 | Справочники (Regions, Territories, Organizers) |
| `route-service` | 8092 | Routes, fare zones, transport stops, vehicles, paths, schedule (R2DBC) |

### Gateway dual auth

- **Chain 1** (`@Order(1)`): mTLS for `/api/v1/terminals/**`, `/api/v1/sync/**`. Principal = `CN` from X.509 cert. Исключение: `POST /api/v1/terminals/cert-sign` → `permitAll` (open HTTPS, без JWT и mTLS — chicken-and-egg при первой регистрации терминала).
- **Chain 2** (`@Order(2)`): JWT (Keycloak) for everything else. JWKS cached locally via кастомный `ReactiveJwtDecoder` (см. `JwtDecoderConfig`).
- `X509PrincipalExtractor` is from `org.springframework.security.web.authentication.preauth.x509`, not `web.server.authentication`. It's a synchronous interface (returns `Any`, not `Mono<Any>`).

### Frontend

- **`frontend/web-admin/`**: Vite + React + TypeScript + react-router + TanStack Query + oidc-client-ts
- **`frontend/android-terminal/`**: Android (Kotlin + Jetpack Compose + Hilt + Room + WorkManager) — приложение для терминала. mTLS auth через X.509 сертификат crypto-service.

  **Офлайн-буферизация:** Все write-команды (session open/close, transaction, card register/block, debt create/recover, fiscal receipt, audit task, GPS position) сначала сохраняются в Room (`PendingEventEntity`, статус `PENDING`). Фоновые `WorkManager` workers (`SyncWorker` каждые 15 мин, `EventPollWorker` каждые 5 мин) отправляют их на gateway через `SyncApi` (mTLS). После получения `202 + X-Event-Id` статус меняется на `SENDING`. Polling `GET /api/v1/events/{eventId}` через `EventPollWorker` отслеживает COMPLETED/FAILED.

  **Компоненты:**
  - `AppDatabase` (Room): 3 сущности — `PendingEventEntity`, `SessionEntity`, `TransactionEntity` + 3 DAOs
  - `SyncPreferences` (DataStore): terminalId, sessionId, lastSyncTime
  - `SyncApi` (Retrofit): 10 async endpoints под `/api/v1/sync/**` (mTLS)
  - `GatewayApi` (Retrofit): terminal CRUD + `GET /api/v1/events/{eventId}`
  - `SyncWorker`: отправка PENDING событий на gateway (15 min periodic, one-shot on network restore)
  - `EventPollWorker`: polling SENDING событий (5 min periodic, `retryCount >= 20` → FAILED)
  - `GpsTrackingService`: foreground service, `FusedLocationProviderClient`, 30s interval, batch threshold 10 → trigger sync
  - `NetworkMonitor`: `ConnectivityManager.NetworkCallback` → one-shot sync on network restore
  - `CertificateService`: ECC P-256 keypair generation, `POST /cert-sign`, event polling, PEM store
  - `SyncViewModel` + обновлённый `MainScreen`: sync status card, pending badge, GPS toggle, manual sync button

  **Permissions:** `INTERNET`, `ACCESS_FINE_LOCATION`, `ACCESS_BACKGROUND_LOCATION`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE_DATA_SYNC`, `FOREGROUND_SERVICE_LOCATION`, `NFC`

  **Sync flow:**
  ```
  Offline:  UI → Room (PendingEvent PENDING)
            GPS → Room (PendingEvent PENDING)
  Online:   NetworkCallback → SyncWorker → POST /sync/** → 202 + eventId → SENDING
            EventPollWorker → GET /events/{eventId} → 200 COMPLETED / 422 FAILED
  ```
- В Vite dev mode (`npm run dev`) проксирует `/api` → `http://localhost:8080` (gateway)
- `useCommand` hook — паттерн 202 + polling для команд записи
- API-клиент через axios, BASE=`/api/v1`, авторизация через Bearer token из oidc-client-ts
- Страницы: Login, Callback (OIDC), Dashboard, Users, Terminals, Cards, Regions, Territories, Organizers, Routes, FareZones, TransportStops, Vehicles, Paths, Schedule
- Язык UI: русский (для переключения на английский нужен i18n — react-intl/i18next)

### Kafka topic naming

Pattern: `asop.{domain}.{commands|events}` — see `KafkaTopic` object in `asop-common`. For example: `asop.carrier.commands`, `asop.session.events`, `asop.terminal.cert.commands` (gateway→crypto), `asop.terminal.cert.issued` (crypto→terminal), `asop.terminal.cert.events` (terminal→gateway).

## Key conventions

- **All IDs** = UUID v7 via `UuidCreator.getTimeOrderedEpoch()` (`UuidUtils.newId()`)
- **REST prefix**: `/api/v1/{resource}`
- **Gateway returns**: `202 Accepted` + `X-Event-Id` header + `AcceptedResponse` body (with `eventId`, `topic`, `acceptedAt`, `locationHint`)
- **API modules** contain only interfaces + DTOs, no implementation. Package: `ru.asop.api.{domain}`.
- **Service packages**: `ru.asop.{domain}` (e.g. `ru.asop.gateway`, `ru.asop.crypto`)
- **Liquibase migrations**: единый changelog в `infrastructure/db-migrations/` → `migrations/v001-init.yaml` → `v001-init.sql` (67 таблиц + функции + seed roles). Выполняется отдельным Docker-контейнером `liquibase:4.27` после `postgres:healthy`. Сервисы НЕ содержат Liquibase/DataSource/JDBC (только R2DBC).
- **asop_schema.sql** — справочная копия v001-init.sql, не монтируется в init скрипты.
- **idempotent FK**: `ALTER TABLE ... ADD CONSTRAINT IF NOT EXISTS ... DEFERRABLE INITIALLY DEFERRED`.
- **Liquibase quirks**: `$$` → `$body$` (dollar quoting), `splitStatements: false` для sqlFile (JDBC сам разбивает), `relativeToChangelogFile: true` во всех include.
- **v002-terminal-certs влит в v001**: DDL для ASOP_TERMINAL_CERTS перенесён из v002 в v001-init.sql.
  При обновлении с версии, где v002 был отдельным changeset, Liquibase упадёт с checksum mismatch.
  Решение — docker compose down -v (полное пересоздание БД).
- **Save bug**: `ReactiveCrudRepository.save()` с не-null UUID делает UPDATE. Использовать `R2dbcEntityTemplate.insert()`.
- **TransactionalOperator** для реактивных транзакций (Spring `@Transactional` НЕ работает в WebFlux). `transactionalOperator.transactional(mono)` оборачивает цепочку в R2DBC-транзакцию. Использовать когда несколько R2DBC-операций должны быть атомарны (например, `markAllAsNotCurrent` + `insert` в cert saga).
- **Partial UNIQUE index** для "не более одного активного сертификата на терминал": `CREATE UNIQUE INDEX uq_tc_current_per_terminal ON ASOP_TERMINAL_CERTS (TERMINAL_ID) WHERE IS_CURRENT = true`. Защищает от race condition при параллельной ротации сертификатов.
- **InnValidator** lives in `asop-common`, used in gateway for carrier creation
- **CertSignRequest** DTO для endpoint'а: `{ terminalSerial, terminalNumber?, terminalModel?, carrierId?, terminalId?, publicKeyBase64 }`. Обязательные: `terminalSerial`, `publicKeyBase64`. Все остальные — optional, при первом запуске терминал регистрируется автоматически в `ensureTerminal` с `carrierId=null` если не передан.
- **Crypto DN bug**: `X500Name(cert.subjectX500Principal.name)` в `RootCaService.signCertificate()` переупорядочивает DN компоненты (через RFC2253), что ломает PKIX chain validation на byte-level сравнении. Фикс: `X500Name.getInstance(ASN1Sequence.getInstance(cert.subjectX500Principal.encoded))`.
- **SAN missing bug**: `provision.sh` не передавал `dnsNames` в JSON если `$DNS_NAMES == $SERVICE_NAME`, из-за чего сертификаты выпускались без SAN. Java 17+ требует SAN для hostname verification. Фикс: всегда передавать `dnsNames` в JSON.
- **Hostname verification**: В WebClient gateway отключена (`SslProvider.DefaultConfigurationType.NONE`) из-за сертификатов без SAN. Для production нужно исправить — выпускать корректные сертификаты с SAN.
- **route-service ExceptionHandler**: `@RestControllerAdvice` в `route-service/config/ExceptionHandler.kt` мапит `IllegalArgumentException` → 400 (невалидный UUID в path variable) и `IllegalStateException` → 400 (missing required fields в `fromRequest`). Все 10 service используют `parseId()` хелпер для безопасного UUID parsing.
- **provision.sh SIGTERM**: bash как PID 1 контейнера не пересылает SIGTERM дочернему процессу. Фикс: `run_jvm()` функция с `trap 'kill -TERM $child' TERM` + `wait $child` на промежуточных попытках, `exec "$@"` на финальной.
- **Keystore fallback paths**: Все `application.yml` используют `/tmp/certs/{service-name}.p12` как fallback для `KEYSTORE_PATH` (не `/certs/service.p12`). crypto-service — edge case (`./data/server.p12`).
- **admin-service mem_limit**: 256m (128m недостаточно — OOM-killer на 14 R2DBC repositories).
- **web-admin Dockerfile**: `npm ci --legacy-peer-deps` (конфликт typescript 6.x vs openapi-typescript 7.x peer dep).
- **start.ps1**: PowerShell-скрипт для wave-based запуска Docker из Windows (Git Bash не видит Docker Desktop — unix socket). `--build` обязателен после пересборки JARs.
- **CertCommandConsumer subscribe**: `.subscribe(onNext, onError)` с error handler — без него ошибка публикации в Kafka проглатывалась, терминал зависал в PENDING навсегда.

## Endpoints (реализовано)

### Gateway (порт 8080, HTTPS)
| Метод | Путь | Описание | Тип |
|-------|------|----------|-----|
| GET/POST/PUT/DELETE | `/api/v1/{resource}/**` | Proxy в backend-сервисы (кроме явных обработчиков) | sync |
| POST | `/api/v1/carriers` | Создание перевозчика (Kafka) | async |
| POST | `/api/v1/terminals/cert-sign` | Подписать X.509 сертификат терминала (open HTTPS, kafka, 4-hop saga) | async |
| GET | `/api/v1/events/{eventId}` | Статус async-команды (PENDING 202 / COMPLETED 200 / FAILED 422) | sync |
| POST | `/api/v1/sync/sessions/open` | Открыть сессию (mTLS terminal) | async |
| PUT | `/api/v1/sync/sessions/{id}/close` | Закрыть сессию (mTLS terminal) | async |
| POST | `/api/v1/sync/transactions` | Завершить транзакцию (mTLS terminal) | async |
| POST | `/api/v1/sync/cards/register` | Зарегистрировать карту (mTLS terminal) | async |
| POST | `/api/v1/sync/cards/{id}/block` | Блокировать карту (mTLS terminal) | async |
| POST | `/api/v1/sync/debts` | Создать долг (mTLS terminal) | async |
| PUT | `/api/v1/sync/debts/{id}/recover` | Погасить долг (mTLS terminal) | async |
| POST | `/api/v1/sync/fiscal/receipts` | Запросить фискальный чек (mTLS terminal) | async |
| POST | `/api/v1/sync/audit/tasks` | Создать задание КРС (mTLS terminal) | async |
| POST | `/api/v1/sync/gps/positions` | Отправить GPS-координату (mTLS terminal) | async |

**Terminal async flow:** все async endpoint'ы доступны только через mTLS (chain Order 1, `/api/v1/sync/**`). Gateway возвращает 202 + X-Event-Id. Android терминал поллит `GET /api/v1/events/{eventId}` до COMPLETED/FAILED.

### User-service (порт 8082, только через gateway)
| Метод | Путь | Описание |
|-------|------|----------|
| POST | `/api/v1/users/password/change` | Смена пароля (pass-through identity) |

### Terminal-service (порт 8084, mTLS через gateway)
| Метод | Путь | Описание |
|-------|------|----------|
| POST | `/api/v1/terminals/register` | Регистрация терминала в БД |
| GET | `/api/v1/terminals/{id}` | Получить терминал |
| PUT | `/api/v1/terminals/{id}/status` | Изменить статус терминала |

### Route-service (порт 8092, через gateway)
| Метод | Путь | Описание |
|-------|------|----------|
| GET/POST/PUT/DELETE | `/api/v1/fare-zones/**` | Тарифные зоны (с GeoJSON полигонами) |
| GET/POST/PUT/DELETE | `/api/v1/transport-stops/**` | Остановки транспорта |
| GET/POST/PUT/DELETE | `/api/v1/routes/**` | Маршруты |
| GET/POST/PUT/DELETE | `/api/v1/paths/**` | Маршруты следования |
| GET/POST/PUT/DELETE | `/api/v1/vehicles/**` | Транспортные средства |
| GET/POST/PUT/DELETE | `/api/v1/schedule/**` | Расписание |
| GET/POST/PUT/DELETE | `/api/v1/path-transport-stops/**` | Остановки на маршруте |
| GET/POST/PUT/DELETE | `/api/v1/path-services/**` | Услуги на маршруте |
| GET/POST/PUT/DELETE | `/api/v1/path-discounts/**` | Скидки на маршруте |
| GET/POST/PUT/DELETE | `/api/v1/path-benefits/**` | Льготы на маршруте |

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

- **Docker Compose** in `infrastructure/docker/docker-compose.yml` — все 11 сервисов + Postgres + Kafka + Keycloak + Liquibase на общей сети `asop-net`
- **PostgreSQL 14** with PostGIS
- **Keycloak 25.0.4** on port 8180, realm `asop`
- **Bootstrap** (`BootstrapService` in `user-service`): on `ApplicationReadyEvent`, checks `ASOP_USERS` — if empty, creates Keycloak realm + roles + admin user (`admin@asop.local`, temporary password from `BOOTSTRAP_ADMIN_PASSWORD`). Also creates public OIDC client `asop-admin` via `ensureOidcClient()` (redirectUris: `http://localhost:3000/*`). Records in `ASOP_USERS` + `ASOP_USER_ROLES`. Env vars: `BOOTSTRAP_ENABLED`, `BOOTSTRAP_ADMIN_PASSWORD`, `KEYCLOAK_URL`, `KEYCLOAK_ADMIN_PASSWORD`
  - **Keycloak 25.0.4 bug**: realm creation with bare `{realm: "asop", enabled: true}` breaks Direct Access Grant. Must include `resetPasswordAllowed: true`, `directGrantFlow: "direct grant"`, `registrationAllowed: false`, etc.
  - **Keycloak 25.0.4 bug**: `POST /users` без credentials + `PUT /reset-password` → "Account is not fully set up". Фикс: передавать credentials inline в `UserRepresentation`.
  - **Keycloak 25.0.4 bug**: пользователь без BOTH `firstName` и `lastName` → "Account is not fully set up". Оба поля обязательны.
- **Password change**: `POST /api/v1/users/password/change` через gateway → pass-through identity. Gateway извлекает `sub` из JWT, передаёт `X-Keycloak-Id` header в user-service. User-service не валидирует JWT (permitAll), читает `X-Keycloak-Id` из header, обновляет пароль в Keycloak Admin API. Запросы напрямую к user-service тоже разрешены (без JWT).

### Docker deploy (fresh start)

**Договорённость:** Docker стартует с нуля каждый раз. Все volumes удаляются между запусками.

```bash
# 1. Build JARs
./gradlew bootJar

# 2. Полный перезапуск с очисткой всех данных
docker compose -f infrastructure/docker/docker-compose.yml down -v
docker compose -f infrastructure/docker/docker-compose.yml up -d --build

# 3. Пересобрать и запустить конкретный сервис
docker compose -f infrastructure/docker/docker-compose.yml up -d --build user-service

# Просмотр логов
docker compose -f infrastructure/docker/docker-compose.yml logs -f gateway-service
```

**Что происходит при старте:**
1. `crypto-service` стартует, не находит Root CA / Intermediate CA → генерирует новые
2. `certs-init` запускается, ждёт crypto-service, генерирует EC P-256 keypair для kafka + keycloak, запрашивает сертификаты, собирает truststore
3. Каждый сервис через `provision.sh` генерирует свой keypair, получает сертификат от crypto-service, собирает PKCS#12 keystore
4. Liquibase накатывает миграции (67 таблиц + функции + seed roles)
5. user-service при пустой `ASOP_USERS` создаёт realm + admin в Keycloak

**Важно:** `down -v` удаляет `crypto_data`, `certs_data`, `postgres_data` — всё пересоздаётся с нуля. Без `-v` старые CA и сертификаты остаются, и новые сервисы не смогут подключиться (старый truststore не совпадает с новыми сертификатами).

### Docker single-service rebuild

```bash
# Пересобрать образ и перезапустить один сервис
docker compose -f infrastructure/docker/docker-compose.yml up -d --build user-service

# При этом зависимости (kafka, postgres, keycloak, crypto) не перезапускаются
```

Each service has its own `Dockerfile` in `backend/{service}/Dockerfile` (eclipse-temurin:21-jre). Liquibase migrations for Docker mounted from `infrastructure/db-migrations/` into `/db-migrations/` inside the liquibase container.

### Тестовые данные

Пока скриптов нет. Будут заполняться специальными скриптами после успешного запуска всех сервисов. Следить за `infrastructure/docker/todo.md`.

### Windows запуск

Git Bash не видит Docker Desktop (unix socket `/var/run/docker.sock` не существует на Windows). Для запуска из PowerShell используйте `infrastructure/docker/start.ps1` — wave-based скрипт, аналог `start.sh`. `--build` обязателен после пересборки JARs (стартовые волны 6-9 включают `--build` для application-сервисов).

## Crypto (crypto-service)

- Root CA in PKCS#12 (`./data/root-ca.p12`), auto-generated on first start
- Intermediate CA in PKCS#12 (`./data/intermediate-ca.p12`), persisted (not regenerated on restart)
- ECC P-256 via Bouncy Castle, all signing via Intermediate CA
- `MediaType.APPLICATION_PEM_CERTIFICATE_VALUE` not available in Spring 6.1 — use `"application/x-pem-file"`
- Endpoints: `POST /api/v1/terminals/register`, `POST /api/v1/smart-cards/issue`, `GET /api/v1/terminals/root-ca(/{format})`, `POST /api/v1/certificates/server`, `GET /api/v1/certificates/ca-chain`
- `ServerCertRequest.dnsNames` — список DNS-имён для SAN (Subject Alternative Name) в серверном сертификате
- **Kafka consumer**: `@KafkaListener("asop.terminal.cert.commands")` в `CertCommandConsumer.kt` — выпускает X.509 через `TerminalCertService.issueTerminalCertificate()` и публикует `CertIssued` в `asop.terminal.cert.issued` с пробросом `X-Event-Id` header. CA chain (intermediate + root PEM bundle) включается в `CertIssued.caChain` для последующего сохранения рядом с сертификатом на стороне terminal-service.

## Full TLS setup

Весь трафик между gateway и внутренними сервисами шифруется. Схема сертификатов:

```
Root CA (self-signed, persisted)
└── Intermediate CA (signed by Root CA, persisted)
    ├── gateway.p12, user-service.p12, card-service.p12, ...
    ├── keycloak.p12 (HTTPS на порту 8443)
    └── kafka.p12 (SSL listener на порту 9093)
```

### Генерация сертификатов

В Docker сертификаты генерируются автоматически при каждом `docker compose up`:
1. `certs-init` — для инфраструктурных сервисов (kafka, keycloak)
2. `provision.sh` (entrypoint каждого сервиса) — для application-сервисов

Оба скрипта:
- Ждут crypto-service
- Запрашивают CA chain, собирают truststore
- Генерируют EC P-256 keypair
- Запрашивают подписанный сертификат у crypto-service (с SAN)
- Собирают PKCS#12 keystore

For local dev (without Docker) there's `infrastructure/docker/certs/generate-certs.sh`.

### Сертификация всегда включена

SSL включён всегда, переменная `SSL_ENABLED` больше не используется. Все сервисы слушают HTTPS, Kafka использует SSL (9093), Keycloak использует HTTPS (8443).

### Gateway mTLS для терминалов

Gateway настроен с `client-auth: want` — запрашивает, но не требует клиентский сертификат.
- Если терминал предъявляет сертификат → аутентификация через X509 (SecurityConfig, Order 1)
- Если нет (браузер) → аутентификация через JWT (Order 2)
- Truststore gateway содержит Root CA crypto-service — валидация терминальных сертификатов через цепочку до Intermediate CA

### Kafka SSL

- Kafka слушает SSL (9093) — PLAINTEXT отключён
- Сертификат Kafka подписан Intermediate CA, SAN: kafka
- Endpoint identification algorithm отключён для внутренней сети
- Каждый сервис конфигурируется через `spring.kafka.ssl.*`

### Keycloak proxy через gateway

Keycloak проксируется через gateway, чтобы браузер всегда обращался к одному origin (избежать CORS/origin errors):

```
Browser → nginx/vite → Gateway (/realms/**) → Keycloak (internal)
```

- nginx `location /realms/` → `proxy_pass https://gateway-service:8080` (with `Host $http_host`, `X-Forwarded-Proto $scheme`)
- nginx `location /resources/` → `proxy_pass https://keycloak:8443` (статический контент темы логина Keycloak)
- Gateway: `KeycloakProxyController` catch-all `/realms/**` → forward to Keycloak (`http://keycloak:8080`)
  - `X-Forwarded-Host` берётся из `X-Forwarded-Host` header → `Host` header → `request.uri.host` (preserves port)
  - `X-Forwarded-Proto` аналогично
  - Issuer в OIDC ответах = `https://{forwarded-host}/realms/asop` (например `https://localhost:3443/realms/asop`)
- Gateway: `SecurityConfig` → `pathMatchers("/realms/**").permitAll()`
- Keycloak: `KC_PROXY=edge`, `KC_HOSTNAME=localhost`, `KC_HTTPS_PORT=8443`

## Reference docs

- `doc/context.md` — full project guide
- `doc/auth.md` — auth architecture
- `infrastructure/db-migrations/asop_schema.sql` — database schema

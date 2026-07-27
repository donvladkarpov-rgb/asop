# Контекст проекта ASOP — Полный гайд

**Дата создания:** 08 июля 2026
**Версия:** 0.3.0-SNAPSHOT
**Статус:** MVP в разработке

---

## 📖 Оглавление

1. [Архитектура проекта](#1-архитектура-проекта)
2. [Структура модулей](#2-структура-модулей)
3. [Gateway](#3-gateway)
4. [Безопасность и аутентификация](#4-безопасность-и-аутентификация)
5. [Pass-Through Identity](#5-pass-through-identity)
6. [Keycloak 25.0.4 Баги](#6-keycloak-2504-баги)
7. [Криптография и PKI](#7-криптография-и-pki)
8. [База данных](#8-база-данных)
9. [Frontend](#9-frontend)
10. [Gradle конфигурация](#10-gradle-конфигурация)
11. [Git и .gitignore](#11-git-и-gitignore)
12. [Bootstrap](#12-bootstrap)
13. [Docker deploy](#13-docker-deploy)
14. [Ключевые уроки](#14-ключевые-уроки)

---

## 1. Архитектура проекта

### Технологический стек
- **Язык:** Kotlin 2.0.21
- **Фреймворк:** Spring Boot 3.3.5 (WebFlux, реактивный)
- **БД:** PostgreSQL 14+ с PostGIS
- **Очереди:** Kafka 3.7.1
- **Event store:** Redis 7 (alpine) — `gateway-service` хранит статусы async-команд в Redis, TTL 24 ч
- **Аутентификация:** Keycloak 25.0.4 (JWT для веба)
- **Криптография:** Bouncy Castle 1.78.1, ECC P-256
- **Сборка:** Gradle 8.10.2
- **UUID:** v7 (Time-Ordered, RFC 9562)
- **Фронтенд:** Vite + React 18 + TypeScript + oidc-client-ts

### Микросервисы
```
backend/
├── shared/                    # Общие библиотеки
│   ├── asop-common/           # BaseEntity, DomainEvent, ErrorCode, KafkaTopic, UuidUtils
│   ├── asop-dto/              # пусто (DTO перенесены в API-модули)
│   ├── asop-kafka-contracts/  # Kafka события
│   └── api/                   # API-контракты (11 модулей)
├── gateway-service/           # API Gateway (WebFlux + двойная аутентификация)
├── crypto-service/            # Root CA, выпуск сертификатов
├── carrier-service/           # Управление перевозчиками (R2DBC)
├── terminal-service/          # Управление терминалами
├── session-service/           # Сессии и смены
├── card-service/              # Управление картами
├── user-service/              # Пользователи + Keycloak bootstrap
├── debt-service/              # Долги по картам
├── fiscal-service/            # Фискализация (ОФД)
├── audit-service/             # КРС (контролёры)
├── admin-service/             # Справочники (Regions, Territories, Organizers)
└── route-service/             # Маршруты, тарифные зоны, остановки, ТС (R2DBC)
```

---

## 2. Структура модулей

### Разделение API и реализации

**Проблема:** Контроллеры и DTO были в сервисах, что мешало переиспользованию.

**Решение:** Вынести API-контракты в `backend/shared/api/`

**Структура API-модуля:**
```
backend/shared/api/{name}-api/
├── build.gradle.kts
└── src/main/kotlin/ru/asop/api/{package}/
    ├── controller/          # Интерфейсы контроллеров
    ├── dto/request/         # Request DTO
    ├── dto/response/        # Response DTO
    └── exception/           # Исключения API
```

**Service → API dependency:** `implementation(project(":backend:shared:api:{domain}-api"))`

### Все модули (27)
```
:backend:shared:asop-common
:backend:shared:asop-dto
:backend:shared:asop-kafka-contracts
:backend:shared:api:gateway-api
:backend:shared:api:crypto-api
:backend:shared:api:carrier-api
:backend:shared:api:session-api
:backend:shared:api:terminal-api
:backend:shared:api:card-api
:backend:shared:api:user-api
:backend:shared:api:debt-api
:backend:shared:api:fiscal-api
:backend:shared:api:audit-api
:backend:shared:api:admin-api
:backend:shared:api:route-api
:backend:gateway-service
:backend:carrier-service
:backend:crypto-service
:backend:terminal-service
:backend:session-service
:backend:card-service
:backend:user-service
:backend:debt-service
:backend:fiscal-service
:backend:audit-service
:backend:admin-service
:backend:route-service
```

### Сервисы и порты

| Путь | Порт | Роль |
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

---

## 3. Gateway

**Принцип:** Gateway не пишет в БД. Только валидирует аутентификацию и пушит команды в Kafka.

### Маршрутизация

#### 1. Async writes (POST/PUT/DELETE с явным контроллером)
- Команда уходит в Kafka, gateway возвращает `202 Accepted` + `X-Event-Id`
- `keycloakId` передаётся в Kafka headers (`X-Keycloak-Id`)
- Пример: `CarrierController` / `CarrierCommandService`, `CertCommandController` для cert-sign saga

#### 2. Sync proxy (GET + остальные запросы)
- `ProxyController` пересылает запросы в backend-сервисы через `WebClient`
- Маппинг ресурсов (`users`, `carriers`, `cards`, etc.) → base URL сервиса в `ServiceRegistry`
- `ASOP_ENV=local` (default → `localhost`), `ASOP_ENV=docker` → Docker hostnames
- Gateway добавляет `X-Keycloak-Id`, **убирает** `Authorization`

#### 3. Event tracking
- После отправки команды в Kafka `EventService` сохраняет статус `PENDING` в **Redis** (key `asop:event:{eventId}`, TTL 24 ч). Состояние переживает рестарт gateway — оффлайн-терминал успеет добрать результат в течение суток.
- Раньше хранилище было `ConcurrentHashMap` в памяти gateway (TTL 30 мин) — теперь реактивный `ReactiveStringRedisTemplate` + Jackson-сериализация `EventStatus`. Старый `Executors`-cleaner удалён (TTL встроен в Redis).
- Фронт поллит `GET /api/v1/events/{eventId}`:
  - `202 Accepted` пока PENDING
  - `200 OK` с `resultData` когда COMPLETED (cert saga — JSON с PEM)
  - `422 Unprocessable Entity` с `errorMessage` когда FAILED
  - `404 Not Found` если eventId неизвестен
- Для cert-sign saga (`asop.terminal.cert.events`) Gateway-consumer обновляет EventService (`COMPLETED`/`FAILED`). Для остальных команд пока сервисы не публикуют события — статус остаётся PENDING в течение TTL 24 ч.

#### 4. Cert signing saga (choreographed, 4 hops)
Первая регистрация терминала — **открытый HTTPS endpoint без JWT/mTLS** (chicken-and-egg):

```
Android → POST /api/v1/terminals/cert-sign (HTTPS plain)
       → 202 + X-Event-Id
       ↓
Gateway → Kafka asop.terminal.cert.commands (X-Event-Id header)
       ↓
crypto-service @KafkaListener → выпускает X.509 через Intermediate CA
                            → Kafka asop.terminal.cert.issued (X-Event-Id пробрасывается)
       ↓
terminal-service @KafkaListener → ensureTerminal:
                                    findByTerminalSerial → insert если новый
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

**XA-гарантии через UNIQUE partial index:** `CREATE UNIQUE INDEX uq_tc_current_per_terminal ON ASOP_TERMINAL_CERTS (TERMINAL_ID) WHERE IS_CURRENT = true` — ловит гонку при параллельной ротации сертификатов. `TransactionalOperator` обеспечивает атомарность mark+insert в terminal-service.

### Gateway files

| Файл | Назначение |
|------|------------|
| `config/ServiceRegistry.kt` | Маппинг resource → service URL |
| `config/WebClientConfig.kt` | WebClient bean для proxy (SSL truststore, hostname verification отключён) |
| `config/JwtDecoderConfig.kt` | Кастомный ReactiveJwtDecoder (без проверки issuer) |
| `config/KeycloakProxyController.kt` | Проксирование `/realms/**` в Keycloak с корректными `X-Forwarded-*` |
| `controller/ProxyController.kt` | Catch-all sync proxy |
| `controller/EventController.kt` | GET /api/v1/events/{eventId} |
| `controller/CarrierController.kt` | POST /api/v1/carriers (async) |
| `controller/CertCommandController.kt` | POST /api/v1/terminals/cert-sign (open HTTPS, async через Kafka) |
| `service/EventService.kt` | Redis-backed event store (key `asop:event:{eventId}`, TTL 24 ч, reactive) |
| `service/CarrierCommandService.kt` | Kafka producer с X-Keycloak-Id header |
| `service/CertCommandService.kt` | CertSignRequested producer в asop.terminal.cert.commands |
| `kafka/CertEventConsumer.kt` | Listener asop.terminal.cert.events → EventService.complete/fail |
| `model/EventStatus.kt` | EventState (PENDING, COMPLETED, FAILED) + `resultData: String?` |

---

## 4. Безопасность и аутентификация

### Двойная аутентификация в Gateway

**Chain 1** (`@Order(1)`): mTLS для терминалов
- Пути: `/api/v1/terminals/**`, `/api/v1/sync/**`
- Principal = `CN` из X.509 сертификата
- Использует `X509PrincipalExtractor` из `org.springframework.security.web.authentication.preauth.x509` (синхронный, возвращает `Any`)

**Chain 2** (`@Order(2)`): JWT (Keycloak) для всего остального
- JWKS кэшируется локально, обновляется каждые 60 сек
- Нет сетевых вызовов к Keycloak на каждый запрос

### JWT issuer (важно!)

В Docker Keycloak (`KC_HOSTNAME=localhost`) выдаёт токены с `iss: http://localhost:8180/realms/asop`, но сервисы внутри Docker обращаются к Keycloak по `http://keycloak:8080`. Стандартный валидатор Spring Security отвергает токены.

**Решение:** Кастомный `ReactiveJwtDecoder` (`JwtDecoderConfig.kt`):
- Использует JWKS с `jwk-set-uri` для проверки подписи
- Не проверяет `iss` (accept any issuer)
- Проверяет `exp` и `nbf` вручную

Применён в gateway-service и user-service. Для других сервисов нужно добавить.

---

## 5. Pass-Through Identity

Gateway проверяет JWT, извлекает `sub` (keycloakId), передаёт в backend-сервисы **без** оригинального JWT.

### Схема
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

### Реализация
- **Gateway**: `ProxyController.extractIdentity()` использует `ReactiveSecurityContextHolder` для получения JWT, извлекает `sub`
- **Kafka async**: `X-Keycloak-Id` в Kafka headers
- **Backend сервисы**: не валидируют JWT (user-service имеет `permitAll`)
- Backend доверяет заголовку `X-Keycloak-Id` от gateway (внутренняя сеть)

### User-service
- `SecurityConfig.kt`: `permitAll`, JWT не проверяется
- `UserController`: читает `X-Keycloak-Id` из request headers (через `ServerWebExchange`)

---

## 6. Keycloak 25.0.4 Баги

Три бага при создании realm/users через Admin API:

### 1. Создание realm
```kotlin
// НЕ РАБОТАЕТ (ломает Direct Access Grant):
val realmRep = RealmRepresentation().apply {
    realm = "asop"
    enabled = true
}

// РАБОТАЕТ:
val realmRep = RealmRepresentation().apply {
    realm = "asop"
    enabled = true
    resetPasswordAllowed = true
    directGrantFlow = "direct grant"
    registrationAllowed = false
    verifyEmail = false
    loginWithEmailAllowed = true
    bruteForceProtected = false
}
```

### 2. Создание пользователя
```kotlin
// НЕ РАБОТАЕТ:
val userRep = UserRepresentation().apply {
    username = "admin@asop.local"
    enabled = true
}
// + отдельный POST /users/{id}/reset-password
// → "Account is not fully set up"

// РАБОТАЕТ: credentials inline
val userRep = UserRepresentation().apply {
    username = "admin@asop.local"
    enabled = true
    credentials = listOf(CredentialRepresentation().apply {
        type = CredentialRepresentation.PASSWORD
        value = password
        temporary = true
    })
}
```

### 3. Обязательные поля
```kotlin
// НЕ РАБОТАЕТ (даже с одним полем):
firstName = "Admin"
// или
lastName = "Admin"

// РАБОТАЕТ: оба поля обязательны
firstName = "Admin"
lastName = "ASOP"
```

---

## 7. Криптография и PKI

### Иерархия CA
```
Root CA (self-signed, ECC P-256, 10 лет)
└── Intermediate CA (подписан Root CA, 5 лет)
    ├── Terminal Certificates (1 год)
    ├── Smart Card Certificates (1 год)
    └── Driver Certificates (1 год)
```

### Хранение
- Root CA в PKCS#12 (`./data/root-ca.p12`), авто-генерация при первом старте
- ECC P-256 через Bouncy Castle
- Все подписи через Intermediate CA (пересоздаётся при каждом рестарте в MVP)

### Важные замечания
- `MediaType.APPLICATION_PEM_CERTIFICATE_VALUE` нет в Spring 6.1 — использовать `"application/x-pem-file"`
- `X509PrincipalExtractor` из `preauth.x509`, не из `web.server.authentication`
- Интерфейс синхронный — возвращает `Any`, не `Mono<Any>`

### Endpoint'ы crypto-service
| Метод | Путь | Описание |
|-------|------|----------|
| POST | `/api/v1/terminals/register` | Выпуск сертификата терминала |
| POST | `/api/v1/smart-cards/issue` | Выпуск сертификата карты |
| GET | `/api/v1/terminals/root-ca(/{format})` | Root CA в PEM/DER |

### Роли смарт-карт
`PASSENGER_ANONYMOUS`, `PASSENGER_BENEFIT`, `DRIVER`, `CONTROLLER`, `DISPATCHER`, `CARRIER_ADMIN`, `REGION_ADMIN`, `SUPER_ADMIN`, `DISTRIBUTOR_ADMIN`, `DISTRIBUTOR_TERMINAL`, `SERVICE`

---

## 8. База данных

### Ключевые таблицы

**Пользователи:**
- `ASOP_USERS` — пользователи
- `ASOP_USER_ROLES` — роли
- `ASOP_USER_CARRIERS` — привязка к перевозчикам
- `ASOP_USER_REGIONS` — привязка к регионам

**Перевозчики:**
- `ASOP_CARRIERS` — перевозчики
- `ASOP_CARDS_DISTRIBUTORS` — дистрибьюторы карт (юридические лица, пополняющие MIFARE-карты через свои платёжные терминалы)
- `ASOP_CONTRACTS` — договоры (общий для перевозчиков и дистрибьюторов)
  - `CONTRACTOR_TYPE` VARCHAR(20) — `CARRIER` | `CARDS_DISTRIBUTOR` (nullable)
  - `CARRIER_ID`, `CARDS_DISTRIBUTOR_ID` — оба nullable; CHECK `chk_contracts_contractor` разрешает оба NULL, но запрещает оба NOT NULL
  - `ATTRIBUTES JSONB` — произвольная абстрактная информация по договору (nullable)
  - `COMMISSION_PERCENT` NUMERIC(5,2) — 0-100, nullable

**Маршруты (route-service):**
- `ASOP_ROUTES` — справочник маршрутов
- `ASOP_FARE_ZONES` — тарифные зоны (с `ZONE_POLYGON GEOGRAPHY(POLYGON, 4326)`)
- `ASOP_TRANSPORT_STOPS` — остановки (с `ZONE_POLYGON GEOGRAPHY(POLYGON, 4326)`)
- `ASOP_PATHS` — маршруты следования
- `ASOP_VEHICLES` — транспортные средства (перенесены из carrier-service)
- `ASOP_SCHEDULE`, `ASOP_PATH_TRANSPORT_STOPS`, `ASOP_PATH_SERVICES`, `ASOP_PATH_DISCOUNTS`, `ASOP_PATH_BENEFITS`

**Карты:**
- `ASOP_CARDS` — все карты
- `ASOP_CARD_MIFARES` — MIFARE-карты (с PKI полями)
- `ASOP_CARD_TARIFFS` — тарифы
- `ASOP_CARD_BANKS` — банковские карты

**Терминалы:**
- `ASOP_TERMINALS` — терминалы (с `UNIQUE` constraint на `TERMINAL_SERIAL`). `TERMINAL_NUMBER` теперь nullable (инвентарный номер вводится вручную на Registration-экране Android). Добавлены `CREATED_AT` / `UPDATED_AT TIMESTAMPTZ` (`DEFAULT now()`), маппятся на `TerminalEntity.createdAt`/`updatedAt`.
- `ASOP_DISTRIBUTOR_TERMINALS` — терминалы дистрибьюторов
- `ASOP_TIDS` — пул TID
- `ASOP_TERMINAL_CERTS` — история X.509 сертификатов терминалов (DDL влит в v001-init.sql, ранее v002)
  - `CERT_ID`, `TERMINAL_ID`, `CERT_SERIAL`, `ISSUED_AT` (TIMESTAMPTZ), `EXPIRES_AT` (TIMESTAMPTZ)
  - `REVOKED_AT`, `REVOCATION_REASON`, `IS_CURRENT`, `CERT_DATA` (PEM), `CA_CHAIN`, `CREATED_AT`
  - **UNIQUE partial index** `uq_tc_current_per_terminal ON (TERMINAL_ID) WHERE IS_CURRENT = true` — не более одного активного сертификата
  - `uq_tc_cert_serial` UNIQUE на `CERT_SERIAL`
  - CHECK: `expires_at > issued_at`, не более одного `IS_CURRENT=true AND REVOKED_AT IS NOT NULL`

**Terminal registration (`POST /api/v1/terminals/register`, terminal-service)** — синхронный upsert:
- Запрос `TerminalRegisterRequest { terminalSerial, terminalNumber?, terminalModel?, carrierId?, terminalId? }`. `terminalSerial` = `ANDROID_ID` устройства.
- Логика `TerminalService.resolveTerminal`:
  1. Если `terminalId != null` → `findById(terminalId)`. Если найден — обновить `terminalSerial`/`terminalNumber`/`terminalModel`/`carrierId`/`updatedAt`, вернуть тот же `terminalId`. Если не найден — fallback к шагу 2.
  2. `findByTerminalSerial(serial)` — это «обычный кейс» после cert-sign saga (cert-saga уже создала терминал по serial + сохранила cert). Обновить атрибуты, вернуть существующий `terminalId`.
  3. Если по serial тоже нет — создать новый `TerminalEntity` через `R2dbcEntityTemplate.insert()` (не `save()`, см. AGENTS.md save-bug), `terminalId = UuidUtils.newId()` (UUIDv7), `status = "WAREHOUSE"`.
- Ответ: `TerminalRegisterResponse { terminal: TerminalResponse, operationStatus: "SUCCESS", errorMessage? }`. Сертификат при регистрации НЕ пересохраняется — он уже лежит в `ASOP_TERMINAL_CERTS` после cert-saga (с `IS_CURRENT=true` и атомарной ротацией старых через `markAllAsNotCurrent` в `CertCommandService`).

**Транзакции:**
- `ASOP_SESSIONS` — сессии (иерархические)
- `ASOP_TRANSACTIONS` — финансовые проводки
- `ASOP_CARD_DEBTS` — долги

**КРС:**
- `ASOP_AUDIT_TASKS` — задания
- `ASOP_AUDIT_BRIGADES` — бригады
- `ASOP_AUDIT_INSPECTIONS` — акты

**Справочники:**
- `ASOP_REGIONS` — регионы
- `ASOP_TERRITORIES` — территории
- `ASOP_ORGANIZERS` — организаторы
- `ASOP_ORGANIZER_TERRITORIES` — привязка организаторов к территориям

### UUID v7
Генерируется на уровне приложения через `UuidCreator.getTimeOrderedEpoch()` (`UuidUtils.newId()`).
В БД есть fallback-функция `gen_uuid_v7()`.

### Миграции

Liquibase запускается **отдельным Docker-контейнером** (`liquibase:4.27`) после `postgres:healthy` и завершается после наката миграций.

**Структура:**
- `db.changelog-master.yaml` → `migrations/v001-init.yaml` → `v001-init.sql` (единый SQL, включая ASOP_TERMINAL_CERTS — ранее в v002)
- Все 67+ таблиц, функции (gen_uuid_v7, set_timestamps, update_timestamps), seed roles
- Все FK idempotent: `ADD CONSTRAINT IF NOT EXISTS ... DEFERRABLE INITIALLY DEFERRED`
- **v002-terminal-certs влит в v001**: при обновлении с версии, где v002 был отдельным changeset — Liquibase checksum mismatch. Решение: `docker compose down -v`.

**Liquibase quirks:**
- `$$` dollar quotes не работают — использовать `$body$`
- `splitStatements: false` для sqlFile (JDBC сам разбивает многосоставные скрипты)
- `relativeToChangelogFile: true` во всех include

**Сервисы НЕ содержат Liquibase/DataSource/JDBC** — только R2DBC.

**`asop_schema.sql`** — справочная копия `v001-init.sql`, не исполняется.

---

## 9. Frontend

### Стек
- Vite + React 18 + TypeScript
- React Router (клиентская маршрутизация)
- TanStack Query (серверное состояние)
- oidc-client-ts (OIDC Auth Code + PKCE)
- Axios (HTTP-клиент)

### Архитектура
- Vite dev mode проксирует `/api` → `http://localhost:8080` (gateway)
- API-клиент: `BASE=/api/v1`, Bearer token из oidc-client-ts
- `useCommand` hook: паттерн 202 + polling для write-команд

### Android (frontend/android-terminal)
- Kotlin + Jetpack Compose + Hilt + Room + WorkManager + Retrofit/OkHttp + Moshi
- mTLS-auth через X.509 сертификат, выпущенный crypto-service через 4-хопную choreographed saga
- Корневой сертификат (Root CA) и Intermediate CA встроены в truststore
- `CertificateService` — генерация ключевой пары в AndroidKeyStore (опционально StrongBox), отправка CSR через Gateway, polling результата, сохранение PEM-цепочки через `MtlsManager.storeCertificateChain()`
- `CertSignApi` — использует plain (без mTLS) HTTPS-клиент для endpoint'а `/api/v1/terminals/cert-sign` (chicken-and-egg при первой регистрации)
- `GatewayApi` — использует mTLS-клиент для остальных защищённых endpoint'ов

**Трёхэкранный флоу терминала** (без навигационных меню, последовательный `provisioning → registration → main`):
1. **Provisioning** — генерация ECC P-256 ключевой пары в AndroidKeyStore, `POST /api/v1/terminals/cert-sign` (plain HTTPS, без mTLS — chicken-and-egg), polling `GET /api/v1/events/{eventId}` каждые 2 сек до 5 мин, сохранение PEM-цепочки в SharedPreferences (`asop_terminal_cert`).
2. **Registration** — `POST /api/v1/terminals/register` через mTLS. Серийный номер = `Settings.Secure.ANDROID_ID` (read-only, не редактируется). Пользователь вводит только:
   - **Модель** (опционально) — `terminalModel`
   - **Инвентарный номер** (обязательно) — `terminalNumber`
   В теле запроса также передаётся сохранённый ранее `terminalId` (UUID, ПК терминала в БД), если он есть в DataStore, иначе `null`. После успешной регистрации сберегается возвращённый `id` через `SyncPreferences.setTerminalId()`.
3. **Main** (Dashboard) — `LazyColumn` с картами: инфо терминала, синхронизация (badge PENDING-событий + кнопка «Синхронизировать сейчас», тоггл синхронизации в TopBar), GPS-трекинг. Фоновая работа: `SyncWorker` (15 мин), `EventPollWorker` (5 мин, до 20 ретраев → FAILED), `NetworkMonitor` (одноразовый sync на восстановлении сети), `GpsTrackingService` (foreground, `FusedLocationProviderClient`, 30 сек, batch ≥ 10 → trigger sync).

**Navhost skip-логика (`TerminalNavHost.kt`):** при старте приложения, если `certificateReady && terminalId != null` → `loadTerminal(id)` и сразу экран `main`; если только `certificateReady` → экран `registration`. Смена `ANDROID_ID` (factory reset / смена signing-key) даёт новый serial → cert-sign saga через `findByTerminalSerial` создаст новый терминал → регистрация сохранит новый `terminalId`.

**Офлайн-буферизация:** Все write-команды сначала сохраняются в Room (`PendingEventEntity`, статус `PENDING`). Фоновые `WorkManager` workers (`SyncWorker` каждые 15 мин, `EventPollWorker` каждые 5 мин) отправляют их на gateway через `SyncApi` (mTLS). После получения `202 + X-Event-Id` статус меняется на `SENDING`. Polling `GET /api/v1/events/{eventId}` через `EventPollWorker` отслеживает COMPLETED/FAILED (теперь статус живёт в Redis, TTL 24 ч).

См. подробнее в `doc/smoke-tests.md` (7 сценариев интеграционного тестирования).

### Страницы
`Login`, `Callback` (OIDC), `Dashboard`, `Users`, `Terminals`, `Cards`, `Carriers`, `CardsDistributors`, `Contracts`, `Regions`, `Territories`, `Organizers`, `Routes`, `FareZones`, `TransportStops`, `Vehicles`, `Paths`, `Schedule`

Раздел **"Справочники"** в Sidebar: Regions, Territories, Organizers.

Отдельные пункты в Sidebar:
- **Перевозчики** (`/carriers`) — список перевозчиков, редактирование (БЕЗ создания — создание идёт через async Kafka). Поля: name, INN, region.
- **Дистрибьюторы карт** (`/cards-distributors`) — полный CRUD + выбиралка договоров (привязка/отвязка через `PUT /api/v1/contracts/{id}`).
- **Договоры** (`/contracts`) — полный CRUD. Форма валидирует "только одно поле" (carrierId XOR cardsDistributorId). Поле `attributes` — textarea для JSON.

Экран Android-приложения: **"Подписать новый сертификат"** — генерация ключевой пары, отправка публичного ключа через Gateway, polling `GET /api/v1/events/{eventId}`, сохранение сертификата и CA-цепочки в AndroidKeyStore.

---

## 10. Gradle конфигурация

### build.gradle.kts для API-модулей
```kotlin
plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.spring.dependency.management)
}

dependencies {
    api(platform(libs.spring.boot.dependencies))
    api(libs.spring.boot.starter.webflux)
    api(libs.spring.boot.starter.validation)
    api(libs.jakarta.validation.api)
    api(project(":backend:shared:asop-common"))
}
```

### gradle.properties
```properties
org.gradle.parallel=true
org.gradle.caching=true
org.gradle.configuration-cache=false
org.gradle.daemon=true
org.gradle.jvmargs=-Xmx4g -XX:+UseG1GC -XX:MaxMetaspaceSize=512m -Dfile.encoding=UTF-8
kotlin.code.style=official
kotlin.incremental=true
kotlin.daemon.jvmargs=-Xmx2g -XX:+UseG1GC -Dfile.encoding=UTF-8
```

### ⚠️ Важно
- Без `-Xmx4g` Gradle падает с OOM при 20+ модулях
- `configuration-cache=false` — ломает компиляцию Kotlin (ClasspathSnapshotProperties)

---

## 11. Git и .gitignore

```gitignore
# Gradle
**/build/
.gradle/
gradle/wrapper/gradle-wrapper.jar

# IntelliJ IDEA
.idea/
*.iml
*.ipr
*.iws
out/

# Kotlin
.kotlin/

# OS
.DS_Store
Thumbs.db
desktop.ini

# Environment
.env
.env.local

# Secrets
*.p12
*.jks
*.keystore
*.pem
*.key
*.crt
*.der
```

**⚠️ Важно:** Паттерн `.*/` игнорирует ВСЕ точечные директории, включая `.git`. Использовать явные правила.

---

## 12. Bootstrap

На `ApplicationReadyEvent` (в user-service), если таблица `ASOP_USERS` пуста:
1. Создаёт realm `asop` в Keycloak (с полными настройками — см. баг №1)
2. Создаёт роли: `SUPER_ADMIN`, `CARRIER_ADMIN`, `DISPATCHER` и т.д.
3. Создаёт администратора `admin@asop.local` (credentials inline — см. баг №2)
4. Записывает в `ASOP_USERS` + `ASOP_USER_ROLES`
5. Создаёт публичный OIDC-клиент `asop-admin` (redirectUris: `http://localhost:3000/*`) через `ensureOidcClient()`

### Переменные окружения
- `BOOTSTRAP_ENABLED` — включить bootstrap
- `BOOTSTRAP_ADMIN_PASSWORD` — пароль администратора
- `KEYCLOAK_URL` — URL Keycloak
- `KEYCLOAK_ADMIN_PASSWORD` — пароль admin Keycloak

---

## 13. Docker deploy

**Договорённость:** Docker стартует с нуля каждый раз. Все volumes удаляются между запусками.

```bash
# 1. Build JARs
./gradlew bootJar --no-daemon

# 2. Полный перезапуск с очисткой всех данных
docker compose -f infrastructure/docker/docker-compose.yml down -v
docker compose -f infrastructure/docker/docker-compose.yml up -d --build

# 3. Пересобрать и запустить конкретный сервис
docker compose -f infrastructure/docker/docker-compose.yml up -d --build user-service

# 4. Wave-based запуск (Windows PowerShell)
.\infrastructure\docker\start.ps1
```

Каждый сервис имеет свой `Dockerfile` (`eclipse-temurin:21-jre`).
Liquibase запускается отдельным контейнером (image: `liquibase:4.27`), который монтирует `infrastructure/db-migrations/` в `/db-migrations/`, выполняет миграции и завершается.
Docker-compose включает 19 контейнеров (11 application services + route-service + web-admin + postgres + kafka + keycloak + zookeeper + liquibase + certs-init).

**Важно:**
- `down -v` удаляет `crypto_data`, `certs_data`, `postgres_data` — всё пересоздаётся с нуля
- `--build` обязателен после пересборки JARs — иначе Docker запустит старые образы
- `admin-service` имеет `mem_limit: 256m` (128m недостаточно — OOM-killer на 14 R2DBC repositories)
- `web-admin` Dockerfile использует `npm ci --legacy-peer-deps` (конфликт typescript 6.x vs openapi-typescript 7.x)
- `provision.sh` корректно пересылает SIGTERM в JVM (trap handler) для graceful shutdown
- `start.ps1` — PowerShell-аналог `start.sh` для запуска из Windows (Git Bash не видит Docker Desktop)

---

## 14. Ключевые уроки

### ❌ Что НЕ работает
1. **PowerShell + regex для рефакторинга кода** — хрупко, ломает форматирование
2. **`MediaType.APPLICATION_PEM_CERTIFICATE_VALUE`** — нет в Spring 6.1, использовать `"application/x-pem-file"`
3. **`X509PrincipalExtractor` из `web.server.authentication`** — неправильный пакет, правильный: `preauth.x509`
4. **`extractPrincipal` возвращает `Mono<Any>`** — интерфейс синхронный, возвращает `Any`
5. **`BOOT_DEPENDENCIES`** — не существует как публичная константа, использовать `platform(libs.spring.boot.dependencies)`
6. **Мало памяти для Gradle** — OOM при 20+ модулях, нужно `-Xmx4g`. Также используется `--no-parallel --max-workers=1` при локальной сборке под Windows (OOM при параллельной компиляции Kotlin daemon'ов).
7. **Паттерн `.*/` в .gitignore** — игнорирует `.git`, использовать явные правила
8. **Keycloak bare-minimum realm** — без `resetPasswordAllowed` и `directGrantFlow` ломает Direct Access Grant
9. **Keycloak `POST /users` без credentials** — раздельный resetPassword выдаёт "Account is not fully set up"
10. **Keycloak без firstName/lastName** — "Account is not fully set up"
11. **Keycloak issuer mismatch в Docker** — `localhost:8180` vs `keycloak:8080`
12. **Liquibase внутри Spring Boot + R2DBC** — конфликт DataSource (JDBC) и R2DBC. Liquibase должен быть отдельным контейнером.
13. **`$$` dollar quotes в Liquibase sqlFile** — ломают парсинг. Использовать `$body$`.
14. **`splitStatements: true` (default) для sqlFile** — разбивает CREATE FUNCTION на части. Использовать `splitStatements: false`.
15. **`ReactiveCrudRepository.save()` с не-null UUID** — делает UPDATE вместо INSERT. Использовать `R2dbcEntityTemplate.insert()`.
16. **`@Transactional` в WebFlux не работает** — Spring AOP-прокси не может обернуть реактивную цепочку. Использовать `TransactionalOperator.transactional(mono)` для реактивных транзакций.
17. **`X500Name(cert.subjectX500Principal.name)` в crypto-service** — Java переупорядочивает DN в RFC2253, ломает PKIX на byte-level сравнении
18. **`provision.sh` без dnsNames при DNS_NAMES == SERVICE_NAME** — сертификаты без SAN, Java 17+ отклоняет hostname verification
19. **Gateway service URL scheme `http://`** — все сервисы слушают только HTTPS, `http://` вызывал PrematureCloseException
20. **Дублирование имён импортов в Kotlin** — при импорте `io.netty.handler.ssl.SslProvider` и `reactor.netty.tcp.SslProvider` в один файл — конфликт имён, не скомпилируется

### ✅ Что работает
1. **Python для миграций** — надёжнее PowerShell, точное сравнение строк
2. **API-модули в `backend/shared/api/`** — правильное место для контрактов
3. **`platform(libs.spring.boot.dependencies)`** — правильный способ импорта BOM
4. **Разделение на Chain 1 (mTLS) и Chain 2 (JWT)** — чистая архитектура
5. **Cert signing choreographed saga** (gateway → crypto-service → terminal-service → gateway через Kafka) — Asynchronous Request-Reply с polling pattern
6. **UNIQUE partial index на `IS_CURRENT=true`** — ловит race condition при параллельной ротации сертификатов
7. **`TransactionalOperator`** для атомарности `markAllAsNotCurrent` + `R2dbcEntityTemplate.insert()` в cert saga
8. **Root CA генерится автоматически** при первом старте crypto-service
9. **UUID v7** — time-ordered, лучше для индексации чем v4
10. **Pass-through identity** — backend сервисы не валидируют JWT, доверяют gateway
11. **Кастомный JWT decoder** — решает проблему issuer URL в Docker
12. **Inline credentials в Keycloak** — единственный рабочий способ для 25.x
13. **Liquibase отдельным контейнером** — решает проблему R2DBC ↔ JDBC в сервисах
14. **Единый v001-init.sql + миграции v002+** — проще поддерживать, чем множество changelog'ов
15. **Gateway sync proxy для CRUD-справочников** — не требует Kafka для простых операций
16. **Keycloak proxy через gateway** — единый origin, без CORS, issuer адаптируется под `X-Forwarded-*` заголовки
17. **`X500Name.getInstance(ASN1Sequence.getInstance(encoded))`** — фикс DN байтового сравнения при PKIX chain validation
18. **`X-Event-Id` через Kafka headers** — корреляция request-response в асинхронной saga без сохранения state в продюсере
19. **`start.sh` с wave-based запуском** — последовательный запуск зависимостей через healthcheck
20. **`start.ps1` для Windows** — Git Bash не видит Docker Desktop (unix socket); PowerShell-скрипт для wave-based запуска
21. **`provision.sh` SIGTERM forwarding** — bash как PID 1 не пересылает SIGTERM дочернему процессу; нужен `trap 'kill -TERM $child' TERM` + `wait $child`
22. **route-service ExceptionHandler** — `@RestControllerAdvice` для `IllegalArgumentException` → 400 (невалидный UUID в path variable) и `IllegalStateException` → 400 (missing required fields в fromRequest)
23. **TransportStopEntity `created_at` bug** — `toDbMap()` использовал `Instant.now()` вместо поля `createdAt`, теряя timestamp на UPDATE
24. **CertCommandConsumer `.subscribe()` без error handler** — ошибка публикации в Kafka проглатывалась, терминал зависал в PENDING навсегда; фикс — `.subscribe(onNext, onError)` с логированием
25. **Keystore fallback paths** — все `application.yml` должны использовать `/tmp/certs/{service-name}.p12` (не `/certs/service.p12`) для local dev
26. **admin-service OOM** — 128m недостаточно для Spring Boot с 14 R2DBC repositories; нужно 256m
27. **Async terminal writes vs sync admin writes** — осознанный CQRS-lite split. Admin operations (route-service CRUD, admin справочники, carrier, card, audit, fiscal, debt) — sync через ProxyController (online, low latency, HTTP cache). Terminal operations (sessions, transactions, GPS, cards register/block, debts, fiscal, audit tasks) — async через Kafka (offline-capable, eventual consistency, 202 + polling).
28. **CommandResult generic pattern** — единый контракт для подтверждения async команд. Backend consumer после DB write публикует `CommandResult(eventId, "COMPLETED" | "FAILED", resultData, errorMessage)` в `{domain}.events`. Gateway `CommandEventConsumer` обновляет `EventService`. Android `EventPollWorker` получает результат через polling `GET /api/v1/events/{eventId}`.
29. **Android offline buffering** — Room DB (PendingEventEntity) + WorkManager (SyncWorker 15 min, EventPollWorker 5 min) + GpsTrackingService foreground service. Offline → enqueue в Room. Online → flush через SyncWorker → 202 + eventId → polling COMPLETED/FAILED.
30. **EventService переехал в Redis (TTL 24 ч)** — раньше был `ConcurrentHashMap` в памяти gateway (терял статусы при рестарте, всего 30 мин окно для polling). Теперь `ReactiveStringRedisTemplate`, Jackson-сериализация `EventStatus`, key `asop:event:{eventId}`, TTL встроен в Redis (`set(key, value, Duration.ofHours(24))`). Состояние переживает рестарт gateway; оффлайн-терминал успеет забрать результат в течение суток. Все `createPending`/`complete`/`fail` — реактивные (`Mono<Void>`), продюсеры и консьюмеры Kafka переключены на `.then()`/`.subscribe()`. Docker-сервис `redis:7-alpine` добавлен в `docker-compose.yml` (volume `redis_data`, healthcheck `redis-cli ping`), gateway зависит от `redis: service_healthy`, env `REDIS_HOST=redis`.

### 📋 Чеклист для новых модулей
- [ ] Создать API-модуль в `backend/shared/api/{name}-api/`
- [ ] Добавить в `settings.gradle.kts`
- [ ] Создать build.gradle.kts с `platform(libs.spring.boot.dependencies)`
- [ ] Создать API-интерфейс в `controller/`
- [ ] Создать DTO в `dto/request/` и `dto/response/`
- [ ] Добавить SQL-таблицы в `infrastructure/db-migrations/migrations/v001-init.sql`
- [ ] Добавить маппинг в `ServiceRegistry.kt` gateway (для sync CRUD)
- [ ] Добавить зависимость в сервис: `implementation(project(":backend:shared:api:{name}-api"))`
- [ ] Реализовать интерфейс в контроллере сервиса
- [ ] Добавить JwtDecoderConfig (или permitAll) для JWT issuer workaround
- [ ] Создать Dockerfile для сервиса
- [ ] Добавить сервис в docker-compose.yml
- [ ] Проверить сборку: `./gradlew clean build`

---

## 📎 Ссылки

- `doc/architecture.md` — архитектурные решения (English)
- `doc/auth.md` — детальная архитектура аутентификации
- `infrastructure/db-migrations/asop_schema.sql` — схема БД
- `backend/shared/asop-common/` — общие утилиты

---

**Конец документа.**
*Версия: 0.3.0 — обновлено 16 июля 2026*

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
- **Целевое устройство:** Feitian F20 (FTSafe) — Android 14, Quad-core 2.0 GHz, 2 GB RAM / 32 GB ROM, 5.5" экран, NFC (ISO/IEC 14443-4 — DESFire EV1+), 4G/Wi-Fi/BT/GPS, термопринтер 58 мм. PCI PTS 5.1. Подробнее: `doc/architecture.md` раздел «Целевое устройство».

### Микросервисы
```
backend/
├── shared/                    # Общие библиотеки
│   ├── asop-common/           # BaseEntity, DomainEvent, ErrorCode, KafkaTopic, UuidUtils
│   ├── asop-dto/              # пусто (DTO перенесены в API-модули)
│   ├── asop-kafka-contracts/  # Kafka события
│   └── api/                   # API-контракты (13 модулей, включая tid-api)
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
├── route-service/             # Маршруты, тарифные зоны, остановки, ТС (R2DBC)
└── orchestrator-service/      # Delta/full-синхронизация справочников (порт 8094)
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

### Все модули (31)
```
:backend:shared:asop-common
:backend:shared:asop-dto
:backend:shared:asop-kafka-contracts
:backend:shared:asop-proto            # Protobuf схемы delta/full-sync (DeltaChunk, XxxFile)
:backend:shared:watermark-processor   # per-terminal seq watermark
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
:backend:shared:api:reference-api
:backend:shared:api:route-api
:backend:shared:api:tid-api
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
:backend:orchestrator-service         # Delta/full-sync справочников (порт 8094)
```

### Сервисы и порты

| Путь | Порт | Роль |
|------|------|------|
| `gateway-service` | 8080 | API Gateway: JWT + mTLS, Kafka producer, proxy для регионов/перевозчиков/TIDs |
| `crypto-service` | 8081 | Root CA, X.509 cert issuance |
| `user-service` | 8082 | Users + Keycloak bootstrap |
| `terminal-service` | 8084 | Terminal management |
| `session-service` | 8085 | Sessions/shifts (tree hierarchy) |
| `card-service` | 8086 | Cards (MIFARE, bank) |
| `carrier-service` | 8087 | Carriers, contracts, TIDs (R2DBC) |
| `debt-service` | 8088 | Card debts |
| `audit-service` | 8089 | Inspections (КРС) |
| `fiscal-service` | 8090 | Fiscalization (OFD) |
| `admin-service` | 8091 | Справочники (Regions, Territories, Organizers) |
| `route-service` | 8092 | Routes, fare zones, transport stops, vehicles, paths, schedule (R2DBC) |

---

## 3. Gateway

**Принцип:** Gateway не пишет в БД. Выполняет только авторизацию/аутентификацию/проксирование — бизнес-логики в gateway нет. Терминал сам передаёт business context (`carrierId`, `regionId`, `timezone`) в теле запроса, gateway пробрасывает его в Kafka headers (`X-Carrier-Id`, `X-Region-Id`, `X-Timezone`) — по аналогии с `X-Event-Id`/`X-Keycloak-Id`. Event DTOs в `asop-kafka-contracts` не меняются — контекст идёт только в headers, консьюмеры могут читать (через `@Header`) или игнорировать.

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
- Для cert-sign saga (`asop.terminal.cert.events`) Gateway-consumer обновляет EventService (`COMPLETED`/`FAILED`). Доменные сервисы публикуют `CommandResult` (COMPLETED/FAILED/PENDING_WATERMARK) в `{domain}.events`; gateway `CommandEventConsumer` обновляет EventService.

#### 4. Cert signing saga (choreographed, 4 hops)
Первая регистрация терминала — **открытый HTTPS endpoint без JWT/mTLS** (chicken-and-egg). Доступ ограничен **HMAC-SHA256** (см. ниже):

```
Android → POST /api/v1/terminals/cert-sign (HTTPS plain, headers X-API-Key/X-Timestamp/X-Signature)
       → 202 + X-Event-Id
       ↓
Gateway CertSignHmacFilter → валидация HMAC → Kafka asop.terminal.cert.commands (X-Event-Id header)
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

**HMAC на cert-sign:** `config/CertSignHmacFilter.kt` (gateway) — WebFilter на `POST /api/v1/terminals/cert-sign` валидирует `X-API-Key` + `X-Timestamp` + `X-Signature` (HMAC-SHA256(hex) по timestamp; body не подписывается из-за WebFlux body consumption в WebFilter). Ключи/секреты/rate-limit из `asop.cert-sign-api-keys` (env `ASOP_CERT_SIGN_API_KEYS`, формат `key:hmac_secret:rate_limit`, default `asop-terminal-cert-key:9f8e...3210:10`). `|now-ts|>300с` → 401, неверный key → 403, sliding-window rate-limit (60с) → 429. Android: `network/CertSignHmacInterceptor.kt` на plain OkHttpClient добавляет заголовки; ключи из `CERT_SIGN_API_KEY`/`CERT_SIGN_HMAC_SECRET` BuildConfig (`local.properties` `cert.sign.api.key`/`cert.sign.hmac.secret`).

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
| `config/CertSignHmacFilter.kt` | WebFilter на POST /api/v1/terminals/cert-sign: HMAC (X-API-Key/X-Timestamp/X-Signature) + rate-limit, ключи из `asop.cert-sign-api-keys` |
| `ru.asop.common.event.EventService` (asop-common) | Redis-backed event store (key `asop:event:{eventId}`, TTL 24 ч, reactive); bean через `EventServiceConfig` (gateway + orchestrator) |
| `service/CarrierCommandService.kt` | Kafka producer с X-Keycloak-Id header |
| `service/CertCommandService.kt` | CertSignRequested producer в asop.terminal.cert.commands |
| `kafka/CertEventConsumer.kt` | Listener asop.terminal.cert.events → EventService.complete/fail |
| `model/EventStatus.kt` | EventState (PENDING, COMPLETED, FAILED) + `resultData: String?` |
| `controller/DeltaReferenceController.kt` | POST /sync/references/delta|full (async), GET .../{eventId}/meta|chunks/{n}|download (sync, Redis + MinIO-прокси) |
| `service/DeltaCommandService.kt` | Producer DeltaSyncCommand/FullSyncCommand → asop.delta.commands/full.commands |
| `service/TerminalResolver.kt` | terminalId → carrierId → regionId (WebClient к terminal-service + carrier-service) |
| `config/GatewayByteArrayRedisConfig.kt` | ReactiveRedisTemplate<String, ByteArray> для chunk storage |

---

## 3.1. Delta Sync (инкрементальная дельта-синхронизация справочников)

### Поток

```
Android → POST /api/v1/sync/references/delta (mTLS, body {terminalId, lastVersion}) → 202 + X-Event-Id
       ↓
Gateway → TerminalResolver (terminalId → carrierId → regionId)
        → DeltaCommandService → Kafka asop.delta.commands (X-Event-Id header)
       ↓
orchestrator-service @KafkaListener → опрашивает мастер-сервисы GET /api/v1/{resource}/delta?carrierId&regionId&versionSince&includeDeleted&limit (keyset-пагинация)
                                  → Protobuf rows (asop-proto) → чанки 50 КБ (serializedSize > 50000)
                                  → Redis asop:event:{eventId}:chunk:{n} + asop:event:{eventId}:meta (TTL 24 ч)
                                  → EventService.complete(eventId, {totalChunks, totalBytes})
       ↓
Android поллит GET /api/v1/events/{eventId} → 200 COMPLETED
       → GET /api/v1/sync/references/{eventId}/meta (totalChunks)
       → GET /api/v1/sync/references/{eventId}/chunks/{n} (application/x-protobuf)
       → ReferenceSyncStore.applyChunk → атомарный накат в Room reference_rows + sync_meta
```

Полная выгрузка: `POST /api/v1/sync/references/full` → `asop.delta.full.commands` → orchestrator собирает ZIP из `.pb` файлов (по таблице на файл) → MinIO `asop-sync` bucket → `EventService.complete(eventId, {s3Url})` → Android качает ZIP через `GET /api/v1/sync/references/{eventId}/download` (gateway проксирует стрим из MinIO).

### Оркестратор (orchestrator-service, порт 8094)

Spring Boot 3.3.5 WebFlux. **БЕЗ R2DBC** (кроме purge-job). Читает Kafka `asop.delta.commands`/`asop.delta.full.commands`, опрашивает мастер-сервисы через WebClient (TLS, truststore), чанкует и пишет в Redis, заливает ZIP в MinIO (AWS S3 SDK). `EventService` — reactive Redis, bean через `EventServiceConfig` (EventService без `@Service` — иначе все сервисы, сканирующие `ru.asop`, падали бы без Redis-зависимости).

**Purge-job**: `@Scheduled(fixedRate = 3600000, initialDelay = 60000)` — раз в час. `SET LOCAL session_replication_role = 'replica'` (отключает BEFORE DELETE триггеры) → физическое удаление soft-deleted строк старше 6 месяцев. Требует `asop` SUPERUSER в PostgreSQL.

### Master-service `/delta` endpoints

Каждый мастер-сервис имеет `@GetMapping("/delta")` с query-параметрами:
- `versionSince` — фильтр `VERSION > versionSince` (инкрементальная дельта по глобальному sequence `asop_delta_version_seq`). Используется как keyset-курсор: мастер возвращает до `limit` строк, оркестратор переспрашивает с `versionSince` = последний `VERSION` предыдущей страницы, пока не получит пустую страницу.
- `includeDeleted` — включать soft-deleted (для синхронизации удалений).
- `limit` (default 10000).
- `carrierId` / `regionId` — где применимо (FK-привязка справочника к перевозчику/региону).
- `userIdsIn` — для user/card таблиц (оркестратор сначала получает userIds, потом фильтрует карточные по ним). URL с `userIdsIn` ограничен ~4 КБ (Reactor Netty `max-initial-line-length`) — оркестратор шлёт userIds батчами по 80.

Маппинг таблиц к сервисам (фактический):
- admin-service: regions, territories, organizers, organizer-territories, roles, card-types, tariff-types, session-types, event-types, transaction-types, transaction-results, services, benefits, benefit-steps (через `DeltaSupport` + `R2dbcEntityTemplate`)
- carrier-service: carriers, tids, contracts, cards-distributors (`DeltaSupport`)
- route-service: 13 ресурсов (fare-zones, transport-stops, routes, paths, path-transport-stops, schedule, path-services, path-discounts, path-benefits, vehicles, vehicle-types, vehicle-models, contract-routes) через `GenericRouteRepository.findDelta`
- user-service: admin-users (UNION user_carriers ∪ user_regions), user-roles, user-carriers, user-regions (camelCase алиасы через DatabaseClient)
- card-service: cards, card-mifares, card-banks, card-tariffs, blacklists, user-benefits, tariff-rates. Для mifares/banks/tariffs/blacklists — JOIN ASOP_CARDS на user_id (фильтр `userIdsIn`). Tariff-rates — без user-фильтра (carrierId FK).

**Region/carrier фильтрация в `/delta` (промпт 010):**

Параметр `regionId` (и реже `carrierId`) принимают все мастер-сервисы и применяют SQL-фильтрацию ПЕРЕД возвратом:

| DB table | Region filter | Реализация |
|----------|---------------|-----------|
| `asop_territories` | `region_id` direct | `Criteria.where("region_id")` в `TerritoryController` |
| `asop_organizers` | через `organizer_territories → territories.region_id` | `OrganizerDeltaQuery` (EXISTS subquery) |
| `asop_organizer_territories` | через `territories.region_id` | `OrganizerTerritoryDeltaQuery` (JOIN) |
| `asop_benefit_steps` | через `benefits.region_id` | `BenefitStepDeltaQuery` (JOIN) |
| `asop_carriers` | direct `region_id` | existing |
| `asop_contracts` | direct `carrier_id`/`cards_distributor_id` | existing |
| `asop_users` | UNION (carriers/regions/admin-no-region) | existing |
| `asop_user_regions` / `asop_user_carriers` | direct | existing |
| `asop_fare_zones`/`transport_stops`/`routes`/`paths`/`path_transport_stops`/`schedule` | direct `region_id` | `REGION_ID_TABLES` set |
| `asop_vehicles`/`path_services`/`path_discounts` | direct `carrier_id` | `CARRIER_ID_TABLES` set |
| `asop_contract_routes` | через `routes.region_id` | `ResourceInfo.regionJoinClause` в `GenericRouteRepository.findDelta` |
| `asop_path_benefits` | через `paths → routes.region_id` | `ResourceInfo.regionJoinClause` в `GenericRouteRepository.findDelta` |
| `asop_user_roles` | через `users + EXISTS ASOP_USER_REGIONS` | explicit JOIN в `UserRoleController` |
| `asop_services`/`benefits` | direct `region_id` | existing |

Если `regionId` в запросе = NULL (full dump — orchestrator не знает, какой регион нужен), SQL возвращает все строки (`null` означает «global» для `where (:p IS NULL OR ...)`). Это позволяет `FullSyncService` прокачать всю базу для нового терминала при первой регистрации.

**Тех. детали промпт 010:**
- Spring Data R2DBC плохо мапит custom `@Query` возвращающие `Flux<Entity>` для сложных JOIN — использован прямой `DatabaseClient` + `.map { row -> entity }` с явным row mapping.
- R2DBC PostgreSQL driver возвращает UUID как `UUID.class`, не `String`; хелпер `asUuid(value)` оборачивает оба варианта.
- `.bind("name", nullable_value)` падает (`Any` non-null) — нужен `if (v != null) spec.bind(v) else spec.bindNull(name, Class::javaObjectType)`.
- Все WHERE-условия с префиксом таблицы (`ASOP_T.x`) во избежание ambiguity при JOIN.
- В `GenericRouteRepository.findDelta` добавлено поле `ResourceInfo.regionJoinClause: String?` — если задано и `regionId != null`, используется вместо стандартного `region_id = :regionId`. Это общий механизм для произвольных JOIN-фильтров.
- `MasterRegistry.kt` НЕ изменён — orchestrator` уже передаёт `regionId`/`carrierId` в URL; правки сделаны на master-стороне.

### Soft-delete и VERSION-курсор

- `DELETED_AT TIMESTAMPTZ` добавлен во все 42 справочные таблицы через DO-блок в `v001-init.sql`.
- `BEFORE DELETE` триггер: generic `trg_fn_soft_delete()` (single-PK) + `trg_fn_soft_delete_2col()` (composite-PK: organizer-territories, contract-routes, user-roles, user-carriers, user-regions). Превращает DELETE в `UPDATE DELETED_AT = NOW(), UPDATED_AT = NOW()` и возвращает NULL.
- `trg_fn_touch_updated()` — авто-pristine `UPDATED_AT` на UPDATE (приложение не обязано проставлять вручную).
- Индексы: `ix_<table>_updated_deleted ON (UPDATED_AT, DELETED_AT)` + `ix_<table>_deleted ON (DELETED_AT) WHERE DELETED_AT IS NOT NULL`.
- **VERSION-курсор**: глобальный sequence `asop_delta_version_seq`; `trg_fn_delta_version()` присваивает `VERSION = nextval(...)` на INSERT/UPDATE/DELETE. Все 42 справочные таблицы имеют `VERSION BIGINT`. `UPDATED_AT` остаётся для аудита, но **дельта-курсор — это `VERSION`** (монотонный глобальный sequence, не зависит от часовых поясов и обновлений несправочных таблиц). Мастера фильтруют `VERSION > versionSince` и сортируют `ORDER BY VERSION ASC` для keyset-пагинации.

### Protobuf

- `:backend:shared:asop-proto` — новый модуль, `schema.proto` с ~41 row messages + `DeltaChunk` (40 repeated-полей) + `XxxFile` messages для full-dump.
- Генерация через `protobuf-gradle-plugin:0.9.4`, `protobuf-java:3.25.5` (backend) + `protobuf-java-util` для `JsonFormat`.
- Android: `protobuf-java` + `protobuf-java-util` (НЕ lite — `ReferenceSyncStore` использует `JsonFormat.printer()` + descriptor reflection, которого нет в lite).

### MinIO

- `minio` (9000 API / 9001 console) + `minio-init` (создаёт bucket `asop-sync`, `mc anonymous set download`) в docker-compose.
- Образы: `minio/minio:RELEASE.2024-10-13T13-34-11Z` + `minio/mc:latest`. minio-init ретраит до 30 раз.
- Оркестратор грузит `full_{eventId}.zip` в `asop-sync`; gateway `GET .../download` проксирует стрим по `s3Url` из resultData. Терминал качает ZIP через gateway mTLS, наружу MinIO не выставляется.
- Ключи: `S3_ENDPOINT`, `S3_ACCESS_KEY`, `S3_SECRET_KEY`, `S3_BUCKET` (defaults `http://minio:9000`, `asop`, `asop-secret`, `asop-sync`).

---

## 4. Безопасность и аутентификация

### Двойная аутентификация в Gateway

**Chain 1** (`@Order(1)`): mTLS для терминалов
- Пути: `/api/v1/terminals/**`, `/api/v1/sync/**`
- Principal = `CN` из X.509 сертификата
- Использует `X509PrincipalExtractor` из `org.springframework.security.web.authentication.preauth.x509` (синхронный, возвращает `Any`)
- Исключение: `POST /api/v1/terminals/cert-sign` → `permitAll` (chicken-and-egg: mTLS ещё нет в момент первой регистрации).
- **`GET /api/v1/terminals` и `GET /api/v1/terminals/{id}` — `authenticated()` (mTLS)**, НЕ permitAll. Анонимный доступ к списку терминалов запрещён (device-id, leakage carrier↔terminal недопустим). Терминал читает только свой terminal по id через mTLS; web-admin — список через JWT.

**Chain 2** (`@Order(2)`): JWT (Keycloak) для всего остального
- JWKS кэшируется локально, обновляется каждые 60 сек
- Нет сетевых вызовов к Keycloak на каждый запрос
- `GET /api/v1/regions/**` и `GET /api/v1/carriers/**` — `permitAll` (public справочники, нужны терминалу под mTLS).

### terminal-service SecurityConfig

terminal-service — `permitAll` для `/api/v1/terminals/**`, **БЕЗ `.oauth2ResourceServer { oauth2.jwt {} }`** (ранее leftover — удалён в коммите review-fix). Сервис внутри Docker доверяет gateway; JWT-валидация не выполняется, terminal-service не имеет ключей/ключей JWKS.

defense-in-depth через gateway: терминалы достаются из внешнего мира только через gateway (chain-1 mTLS / chain-2 JWT). Терминал(client) → gateway (mTLS principal X.509 CN=serial) → terminal-service (trust-gateway, прокси по TLS).

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
- Все подписи через Intermediate CA (persisted в `./data/intermediate-ca.p12`, НЕ пересоздаётся при рестарте; цепочку Root→Intermediate→leaf строит `provision.sh` до старта JVM)

### Важные замечания
- `MediaType.APPLICATION_PEM_CERTIFICATE_VALUE` нет в Spring 6.1 — использовать `"application/x-pem-file"`
- `X509PrincipalExtractor` из `preauth.x509`, не из `web.server.authentication`
- Интерфейс синхронный — возвращает `Any`, не `Mono<Any>`

### Endpoint'ы crypto-service
| Метод | Путь | Описание |
|-------|------|----------|
| Kafka | `asop.terminal.cert.commands` | Consumer `CertCommandConsumer` выпускает сертификат (открытый HTTPS endpoint — на gateway) |
| POST | `/api/v1/smart-cards/issue` | Выпуск сертификата карты |
| GET | `/api/v1/terminals/root-ca(/{format})` | Root CA в PEM/DER |

### Роли смарт-карт
14 ролей (`ASOP_CARD_MIFARES.CARD_ROLE`): `SUPER_ADMIN`, `REGION_ADMIN`, `ORGANIZER_ADMIN`, `CARRIER_ADMIN`, `CARRIER_DISPATCHER`, `DISTRIBUTOR_ADMIN`, `DISTRIBUTOR_DISPATCHER`, `KRS_ADMIN`, `KRS_DISPATCHER`, `KRS_FOREMAN`, `KRS_CONTROLLER`, `DRIVER`, `PASSENGER`, `PASSENGER_ANONYMOUS`

### Ключи ASOP_KEYS (промпт 006)

**Доставка — вариант Б** (по mTLS в составе дельты/полной выкачки, БЕЗ ECIES). ECIES отклонён из-за непортативности `PURPOSE_AGREE` между OEM-реализациями Android Keystore. Plaintext ключа приходит на терминал по mTLS, терминал перешифровывает его локальным Keystore-AES-ключом. Сейчас генерируются 24-байтные 3K3DES для DESFire; MIFARE Classic использует первые 6 байт (Key A) и байты 6-11 (Key B).

**Серверный ключ шифрования** (`crypto-service`):
- RSA-2048 PKCS12 (`./data/server-key.p12`), отдельный от CA/TLS
- `POST /api/v1/keys/decrypt` (cipher → plaintext base64), `POST /api/v1/keys/generate` (keyId+cipher), `GET /api/v1/keys/public`
- RSA/ECB/OAEPWithSHA-256AndMGF1Padding
- **Dev-режим**: `asop.crypto.server-key.dev-mode-enabled` (env `DEV_ASOP_KEY_MODE_ENABLED`) + фиксированный dev-ключ `DEV_ASOP_KEY_BASE64` (base64 `AAECAwQFBgcICQoLDA0ODxAREhMUFRYX`)

**Таблицы:**
- `ASOP_KEYS` — глобальный пул ротируемых ключей (admin-service). `KEY_ID UUIDv7`, `KEY_MATERIAL TEXT` (ключ произвольной длины, зашифрован публичным ключом сервера). Soft-delete only, `PurgeJob` исключает из purge.
- `ASOP_CONFIG_PARAMS` — иерархия перекрытия параметров: base (scope=NULL) → region → organizer → carrier → distributor → krs. `PARAMS JSONB`. Серверная, на терминалы НЕ синкается.

**Оркестратор:**
- `MasterRegistry.GLOBAL_TABLES["asop_keys"]` → admin `asop-keys`
- `KeyService` — decrypt-трансформ + серверный фильтр «N лет» (default 5)
- `KeyRotationScheduler` — `SchedulingConfigurer` + динамический CronTrigger, timeout 10 сек на чтение base-конфига, fallback на дефолтный cron

**Android:**
- `TerminalKeyEntity` в `AppDatabase` v5, таблица `terminal_keys` (не `reference_rows`)
- `TerminalKeyCryptor` — AndroidKeyStore AES-GCM, неэкспортируемый, алиас `asop_terminal_keys_aes`
  - PURPOSE: `ENCRYPT | DECRYPT` (без `SIGN | VERIFY` — отдельный ключ, не связанный с mTLS)
  - Алгоритм: AES-256, GCM/NoPadding, IV 12 байт, tag 128 бит
  - Генерация: **lazy** — при первом вызове `encrypt()`/`decrypt()`, т.е. когда приходит первый чанк/файл с `asop_3des_keys` через дельту или полную выкачку
  - Хранилище: AndroidKeyStore (TEE/StrongBox), ключ **неэкспортируемый** — извлечь невозможно даже с root
  - Жизненный цикл: создаётся один раз и живёт в Keystore навсегда. **Не зависит** от mTLS-сертификата терминала (разные PURPOSE, разные алиасы). Переживает: перевыпуск сертификата, переустановку приложения (тот же signing key), `fallbackToDestructiveMigration()` Room. Удаляется только при очистке данных приложения или factory reset
  - Инъекция: `@Singleton` через Hilt, потокобезопасность — `synchronized` не требуется (AndroidKeyStore атомарен на уровне KeyGenParameterSpec)
  - Ротация: не предусмотрена в текущей версии. При потере Keystore-ключа существующие `KEY_MATERIAL_ENC` в `terminal_keys` становятся нерасшифровываемыми
- Записи с DELETED_AT физически удаляются, порядок KEY_ID DESC

**web-admin:**
- Страницы `AsopKeys` (`/asop-keys`) и `ConfigParams` (`/config-params`)
- Раздел «Ключи и параметры» в Sidebar

---

### Активация карт на терминале (промпт 005)

14 типов карт АСОП (персонал/пассажиры), иерархия root → region → organizer → carrier/distributor/krs → dispatcher → driver/foreman/controller. Матрица авторизации 14×14. Self-авторизация разрешена (кроме root).

**Роли**: 9 новых в `ASOP_ROLES` (REGION_ADMIN, ORGANIZER_ADMIN, KRS_ADMIN, CARRIER_DISPATCHER, DISTRIBUTOR_DISPATCHER, KRS_DISPATCHER, KRS_FOREMAN, KRS_CONTROLLER, PASSENGER_ANONYMOUS). `CARD_ROLE` CHECK = 14 значений.

**cardIdentity** (canonical JSON): cardId (UUIDv7), uid (hex), regionId/organizerId/carrierId/cardsDistributorId/auditServiceId/userId, roles (JSON-массив). Сохраняется на карту в ASOP-приложении (AID 0xA05A01): File 0 = protobuf binary (CardIdentity message), File 1 = RSA-PSS-SHA256 подпись canonical JSON.

**Верификация подписи на терминале:**
- При регистрации терминала (CertificateService.provision) загружается публичный RSA ключ сервера (`GET /api/v1/keys/public`) → хранится в SyncPreferences (DataStore)
- При чтении зарегистрированной карты (CardReadScreen) терминал парсит proto из File 0, строит canonical JSON, верифицирует подпись из File 1 через `SignatureVerifier`
- Результат (`signatureValid`) отображается в UI
- Не выполняется при активации новых карт (write-операция)

**Server endpoints:**
- crypto-service: `POST /api/v1/smart-cards/sign` (RSA-PSS-SHA256, server-key)
- card-service: `POST /api/v1/cards/activate` (проверка авторизации + подписи + upsert)
- gateway: `POST /api/v1/sync/smart-cards/sign`, `POST /api/v1/sync/cards/activate`, `POST /api/v1/sync/auth/root` (Keycloak password grant)
- audit-service: CRUD + `/delta` для `ASOP_AUDIT_SERVICES`

**Android:** `DesfireCardWriter` (ChangeKey 0xC4, CreateApplication 0xCA, CreateStdDataFile 0x6D, WriteData 0x8D, ReadData 0xBD), `CardActivationScreen`/`ViewModel`, пункт «Активация карт» в drawer. Порядок: сервер → карта.

**Known limitation:** `ChangeKey` без session key encryption — работает на клоне, не пройдёт на оригинальной NXP.

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
- `ASOP_CONTRACTS` — договоры (общий для контрагентов)
  - `CONTRACTOR_TYPE VARCHAR(20) NOT NULL` — `ORGANIZER` (перевозочный) | `BANK` (эквайринг — на такие договоры вешаются TID) | `CARDS_DISTRIBUTOR`
  - CHECK `chk_contracts_contractor`: ORGANIZER/BANK требуют `CARRIER_ID NOT NULL`; CARDS_DISTRIBUTOR требует `CARDS_DISTRIBUTOR_ID NOT NULL`. Валидация дублируется в `ContractService.create/update` (IllegalArgumentException → 400). Актуальность = `STATUS='ACTIVE'` + даты действия; триггер `trg_contracts_bump_tids` бампает VERSION его TID-ов → дельта привозит обновлённый `isValid`
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
- Запрос `TerminalRegisterRequest { terminalSerial, terminalNumber?, terminalModel?, carrierId?, terminalId?, timezone? }`. `terminalSerial` = `ANDROID_ID` устройства. `timezone` (VARCHAR(50), опц.) — таймзона терминала (Android передаёт `TimeZone.getDefault().id`); сервер хранит как есть, все timestamps в БД в UTC.
- Логика `TerminalService.resolveTerminal`:
  1. Если `terminalId != null` → `findById(terminalId)`. Если найден — обновить `terminalSerial`/`terminalNumber`/`terminalModel`/`carrierId`/`timezone`/`updatedAt`, вернуть тот же `terminalId`. Если не найден — fallback к шагу 2.
  2. `findByTerminalSerial(serial)` — это «обычный кейс» после cert-sign saga (cert-saga уже создала терминал по serial + сохранила cert). Обновить атрибуты, вернуть существующий `terminalId`.
  3. Если по serial тоже нет — создать новый `TerminalEntity` через `R2dbcEntityTemplate.insert()` (не `save()`, см. AGENTS.md save-bug), `terminalId = UuidUtils.newId()` (UUIDv7), `status = "WAREHOUSE"`.
- Ответ: `TerminalRegisterResponse { terminal: TerminalResponse, operationStatus: "SUCCESS", errorMessage? }`. `TerminalResponse` включает `timezone: String?` и `carrierId: UUID?`. Сертификат при регистрации НЕ пересохраняется — он уже лежит в `ASOP_TERMINAL_CERTS` после cert-saga (с `IS_CURRENT=true` и атомарной ротацией старых через `markAllAsNotCurrent` в `CertCommandService`).

**Terminal assign carrier (`PUT /api/v1/terminals/{id}/carrier`, terminal-service)** — синхронная привязка терминала к перевозчику. Запрос `TerminalCarrierAssignRequest { carrierId: UUID? }` (null = отвязать), ответ `TerminalResponse`. Используется Android-экраном "Привязать перевозчика" из drawer-меню.

**TID CRUD (carrier-service, sync-proxy)** — `GET/POST/PUT/DELETE /api/v1/tids[/{id}]`:
- **TID привязан к ДОГОВОРУ, не к перевозчику**: `ASOP_TIDS.CONTRACT_ID` → `ASOP_CONTRACTS` (FK `fk_tids_contract`); договор обязан быть `CONTRACTOR_TYPE='BANK'` и актуальным — валидация в `TidService.create/update` (IllegalArgumentException → 400).
- `GET /api/v1/tids?carrierId=UUID&regionId=UUID` — список с фильтрами через JOIN договоров.
- `POST /api/v1/tids` — создать (`TidCreateRequest { contractId: UUID (NotNull), tidValue: String (NotBlank, Size 20) }`, status всегда `UNUSED`).
- `PUT /api/v1/tids/{id}` — обновить (`TidUpdateRequest { contractId?, tidValue?, status?, terminalId? }`; смена статуса проставляет `ASSIGNED_AT`/`UNASSIGNED_AT`).
- `DELETE /api/v1/tids/{id}` — удалить (soft-delete триггером). Дельта `/api/v1/tids/delta` включает `contractId` и `isValid`.
- Sync-CRUD (R2DBC, `R2dbcEntityTemplate.insert()` для новых), без Kafka. Gateway `ServiceRegistry` маппит `tids` → `carrier-service:8087`, `ProxyController` пересылает.

**Транзакции:**
- `ASOP_SESSIONS` — сессии (иерархические, parent=shift, child=TRIP, parent=NULL=SHIFT)
- `ASOP_TRANSACTIONS` — финансовые проводки (промпт 011: VALIDATION_ONLY result, amount=0)
- `ASOP_CARD_DEBTS` — долги

### Сессии водителя (промпт 011)

Иерархия `3 уровня`: SHIFT → TRIP → TRANSACTIONS. `SESSION_TYPE_CODE` принимает значения:
`SHIFT` (`…0601`), `BREAK` (`…0602`), `TRIP` (`…0603`).
`TRANSACTION_TYPE_CODE='VALIDATION'` (`…0803`) +
`TRANSACTION_RESULT_CODE='VALIDATION_ONLY'` (`…0903`) для MVP без списания.

Идемпотентность: client генерирует UUIDv7 для sessionId/tripPaymentId → server
`INSERT … ON CONFLICT (SESSION_ID) DO NOTHING` → нет дублей при offline retry.

**Сессия водителя — anonymous ACL `canClose()` matrix (промпт 011 §4)** реализуется в
`session-service/.../SessionService.kt::canClose(sessionId, requesterUserId)`:
1. Любой DRIVER (открыватель или другой driver_of_same_carrier) → OK.
2. CARRIER_DISPATCHER / KRS_DISPATCHER / CARRIER_ADMIN → OK если scope matches carrier_id.
3. ORGANIZER_ADMIN → OK cascade на все carriers организатора (JOIN `ASOP_ORGANIZER_TERRITORIES`).
4. KRS_ADMIN → OK cascade по auditServiceId.
5. REGION_ADMIN → OK cascade на всех организаторов → carriers региона.
6. `ADMIN` / `SUPER_ADMIN` → OK всегда.

Реализация — два запроса (промпт-фикс E2E):
- **Root**: отдельный `SELECT 1 … ASOP_USER_ROLES JOIN ASOP_ROLES WHERE role_name IN ('ADMIN','SUPER_ADMIN')`
  — НЕ зависит от `requester_scope`/линков. Роль читается из **`ASOP_ROLES.ROLE_NAME`** (колонки `role_code` в
  схеме нет — с ней SQL падал `column r.role_code does not exist`).
- **Скоуп** (только для non-root): `requester_scope` CTE (UNION `ASOP_USER_CARRIERS` × `ASOP_USER_REGIONS`) +
  EXISTS на роль из carrier/region-множества. Root-админ без линков давал бы пустой CTE → «not authorized»,
  поэтому root ветка выделена в отдельный запрос. Детальная формула — см. AGENTS.md раздел
`Сессии водителя (промпт 011)`.

**Race condition guard** (server, трехуровневый):
- `POST /sync/sessions/open` с `sessionTypeId=TRIP` → проверка
  `EXISTS (SELECT 1 FROM ASOP_SESSIONS WHERE PARENT_SESSION_ID=:shiftId AND STATUS='IN_PROGRESS')`.
  Если есть другая IN_PROGRESS TRIP → **409 Conflict** + `IllegalStateException`.
- Client check перед отправкой: `sessionDao.getCurrentOpenTrip(parentId) != null` → блокирует кнопку «Открыть рейс».
- DB UNIQUE-индекс невозможен (много TRIP-ов по разным shift), поэтому проверка на уровне consumer+service.

**GPS-привязка**: `ASOP_GPS_TRACKING.SESSION_ID = shift.id` (НЕ trip.id) — отчёты согласованы
непрерывно от открытия смены до её закрытия, независимо от TRIP boundaries. `vehicleId/pathId`
в GPS-отчёте = `trip?.vehicleId ?: shift?.vehicleId` (fallback на смену); если оба null — точка
молча отбрасывается. Полная live-мапа и snap-to-route — см. `doc/gps.md`.

**TID selectors**: выбор TID при открытии TRIP — водопад (cascade) region→carrier→terminal.
TID pool (`ASOP_TIDS.STATUS='UNUSED'`) → admin назначает через `PUT /api/v1/tids/{id}` (carrier-service).
На устройстве водопад ещё не реализован на UI (hint-card stub в `OpenTripScreen.kt`),
TODO Phase 4.2.b.

**Известные ограничения MVP**:
1. `OpenTripScreen.kt` — TID/Vehicle/Route/Path pickers показаны как hint-card. Полный cascade-picker —
   отдельный flow (Phase 4.2.b).
2. Trip payments пассажиров идут через `transaction-service` (`amount=0`, `transaction_result_id='VALIDATION_ONLY'`)
   при parent_session_id=trip.id. Пассажирская карта НЕ регистрируется в `ASOP_CARDS` как Driver card.
3. Поскольку `ASOP_SESSIONS.OPENED_AT_LOCAL` хранится в device timezone (Europe/Moscow),
рекомендуется UTC на сервере — нет timezone-conflict, но явная конверсия не выполняется (в TODO).
4. `EXPIRATION_TIME` = startedAt + 8h hardcoded — фоновое обнуление не выполняется (водитель
отвечает за явное CLOSE в конце смены, иначе события зависают как PENDING).
Матрица авторизации (prompt 011 §4 закрытие смены):
- DRIVER (открыватель — он же)
- DRIVER_B (любой водитель carrier_id == session.carrier_id)
- CARRIER_DISPATCHER/KRS_DISPATCHER/CARRIER_ADMIN (того же carrier_id)
- ORGANIZER_ADMIN (cascade через organizторов)
- KRS_ADMIN (cascade через auditServiceId)
- REGION_ADMIN/ADMIN/SUPER_ADMIN (глобальные)

`ASOP_SESSIONS.ATTRIBUTES JSONB` хранит `carrierId/regionId/timezone`.

**КРС:**
- `ASOP_AUDIT_TASKS` — задания
- `ASOP_AUDIT_BRIGADES` — бригады
- `ASOP_AUDIT_INSPECTIONS` — акты

**Справочники:**
- `ASOP_REGIONS` — регионы
- `ASOP_TERRITORIES` — территории
- `ASOP_ORGANIZERS` — организаторы
- `ASOP_ORGANIZER_TERRITORIES` — привязка организаторов к территориям

### GPS-трекинг и live-карта (промпт 014/015)

`ASOP_GPS_TRACKING` — трекинг ТС; `SESSION_ID = shift.id`, `GPS_COORD GEOGRAPHY(POINT,4326)`,
`VEHICLE_ID/PATH_ID NOT NULL`, партиционируется по `RECORDED_AT`. Сглаживание (экспоненциальное,
alpha 0.6), watermark-ordering (`X-Terminal-Seq`) и snap-to-route — в session-service (`GpsCommandConsumer`,
`gps/GpsRouteSnapper.kt`). Live-API: `GET /tracking/live` (последняя точка на ТС, `DISTINCT ON`,
при необходимости снапнута) и `GET /tracking/vehicle/{id}/track`. Публичный контур пассажира —
`/api/v1/public/**` с API-ключом (`ApiKeyHmacFilter`). Потребители: web-admin «Карта ТС» (`/live-map`,
Leaflet) + «Редактор маршрута» (`/route-editor`, `ROUTE_OBJECT` GeoJSON LineString) и пассажирское
Android-приложение (`frontend/passenger-app`, osmdroid). Подробно — `doc/gps.md` и `doc/gps_maps.md`.

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
2. **Registration** — `POST /api/v1/terminals/register` через mTLS. Серийный номер = `Settings.Secure.ANDROID_ID` (read-only, не редактируется). Пользователь вводит:
   - **Регион** (обязательно) — dropdown из `GET /api/v1/regions` (sync-proxy через gateway `permitAll` GET)
   - **Перевозчик** (обязательно) — dropdown из `GET /api/v1/carriers?regionId=...` (фильтр по региону; carrier dropdown доступен только после выбора региона)
   - **Часовой пояс** (обязательно, read-only) — `TimeZone.getDefault().id`, передаётся в `TerminalRegisterRequest.timezone`
   - **Модель** (опционально) — `terminalModel`
   - **Инвентарный номер** (обязательно) — `terminalNumber`
   В теле запроса также передаётся сохранённый ранее `terminalId` (UUID, ПК терминала в БД), если он есть в DataStore, иначе `null`. После успешной регистрации сберегается возвращённый `id` через `SyncPreferences.setTerminalId()`.
3. **Main** (Dashboard) — `LazyColumn` с картами: инфо терминала, синхронизация (badge PENDING-событий + кнопка «Синхронизировать сейчас», тоггл синхронизации в TopBar), GPS-трекинг. Фоновая работа: `SyncWorker` (15 мин), `EventPollWorker` (5 мин, до 20 ретраев → FAILED), `NetworkMonitor` (одноразовый sync на восстановлении сети), `GpsTrackingService` (foreground, `LOCATION_INTERVAL_MS=5_000` / fastest 3 c, batch ≥10 → trigger sync; debug — `MockRoutePlayer` по маршруту 301).

**Drawer-меню (`ModalNavigationDrawer`, hamburger-иконка в TopAppBar):** экраны терминала доступны перманентно через drawer (а не только через линейный provisioning → registration → main flow):
- **"Сертификат"** — диалог подтверждения перевыпуска → `MtlsManager.resetKeyAndCert()` (чистит alias AndroidKeyStore + SharedPreferences) → `CertificateService.provision(androidId)` (новый cert-saga).
- **"Регистрация"** — **доступна всегда** (даже после успешной регистрации). Если `terminalId == null` — навигация на `provisioning` (cert-sign, далее автоматом на `registration`); если `terminalId != null` — сразу на `registration` (update существующего).
- **"Привязать перевозчика"** — переход на `AssignCarrierScreen`: dropdown регион → dropdown перевозчик (фильтр по `regionId`) → кнопка "Сохранить" → `PUT /api/v1/terminals/{id}/carrier` с `TerminalCarrierAssignRequest { carrierId }`. Текущий перевозчик пред-выбран, отображается на экране. Требует предварительно сохранённый `terminalId` в DataStore.
- **Stub-пункты** (placeholder, TODO, `onClick` только закрывает drawer): "Загрузить справочники", "Зарегистрировать карту водителя", "Открыть смену", "Закрыть смену", "Открыть рейс", "Закрыть рейс". Оставлены как «заглушки» до реализации.

**Navhost skip-логика (`TerminalNavHost.kt`):** `startDestination = "welcome"` — при старте приложения всегда сначала показывается `WelcomeScreen` (без auto-навигации). Если `terminalId != null` → кнопка «Войти» → `main`; иначе → «Настроить сертификат» → `provisioning`. `LaunchedEffect` молча вызывает `loadTerminal(tid)` (если `certificateReady && tid != null`), но НЕ навигирует сам. Смена `ANDROID_ID` (factory reset / смена signing-key) даёт новый serial → cert-sign saga через `findByTerminalSerial` создаст новый терминал → регистрация сохранит новый `terminalId`.

**Registration guard (`ui/TerminalRegistrationGuard.kt` — `RequireTerminalRegistration`):** full-screen warning с кнопкой (без auto-навигации), если `isRegistered=false`; иначе рендерит `content`. Применяется к `open-shift` (два branch: `terminalId==null` → warning+кнопка к сертификату; зарегистрирован но нет интернета по `ConnectivityManager` → warning без кнопки), `card-activation`, `top-up`. НЕ применяется к `open-trip`/`close-shift`/`close-trip` (offline OK). `TerminalViewModel.loadTerminal` при 404/пустом ответе сервера делает `clearTerminalId()` — WelcomeScreen показывает «Терминал не зарегистрирован».

**Офлайн-буферизация:** Все write-команды сначала сохраняются в Room (`PendingEventEntity`, статус `PENDING`). Фоновые `WorkManager` workers (`SyncWorker` каждые 15 мин, `EventPollWorker` каждые 5 мин) отправляют их на gateway через `SyncApi` (mTLS). После получения `202 + X-Event-Id` статус меняется на `SENDING`. Polling `GET /api/v1/events/{eventId}` через `EventPollWorker` отслеживает COMPLETED/FAILED (теперь статус живёт в Redis, TTL 24 ч).

См. подробнее в `doc/smoke-tests.md` (7 сценариев интеграционного тестирования).

**Целевое устройство:** Feitian F20 (см. `doc/architecture.md` раздел «Целевое устройство»).

### Страницы
`Login`, `Callback` (OIDC), `Dashboard`, `Users`, `Terminals`, `Cards`, `Carriers`, `Tids`, `CardsDistributors`, `Contracts`, `Regions`, `Territories`, `Organizers`, `Routes`, `FareZones`, `TransportStops`, `Vehicles`, `Paths`, `Schedule`

Раздел **"Справочники"** в Sidebar: Regions, Territories, Organizers.

Отдельные пункты в Sidebar:
- **Перевозчики** (`/carriers`) — список перевозчиков, редактирование (БЕЗ создания — создание идёт через async Kafka). Поля: name, INN, region.
- **TID (пулы)** (`/tids`) — полный sync-CRUD TIDs перевозчиков. Filter по carrierId dropdown. Форма: `carrierId` (обязательно), `tidValue` (VARCHAR(20), обязательно), `status` (UNUSED/ASSIGNED/REVOKED, только для редактирования), `terminalId` (optional, для привязки TID к терминалу). Endpoint: `GET/POST/PUT/DELETE /api/v1/tids` (sync-proxy через gateway в carrier-service, без Kafka — Read/Write-CRUD).
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
Docker-compose определяет 24 сервиса: 11 application-сервисов + orchestrator-service + web-admin + инфраструктура (postgres, liquibase, certs-init, zookeeper, kafka, redis, minio + minio-init, kcat).

**Важно:**
- `down -v` удаляет `crypto_data`, `certs_data`, `postgres_data` — всё пересоздаётся с нуля
- `--build` обязателен после пересборки JARs — иначе Docker запустит старые образы
- `admin-service` и `route-service` имеют `mem_limit: 512m` (128m недостаточно — OOM-killer на 14/10 R2DBC repositories), `orchestrator-service` — 1g
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
- `doc/gps.md` — GPS-трекинг, live-карта, пассажирское приложение (фактическое состояние)
- `doc/gps_maps.md` — источник тайлов, лицензии OSM, офлайн-режим
- `infrastructure/db-migrations/asop_schema.sql` — схема БД
- `backend/shared/asop-common/` — общие утилиты

---

**Конец документа.**
*Версия: 0.3.0 — обновлено 16 июля 2026*

# Архитектура платформы ASOP

**Версия:** 1.1
**Дата:** 2026-07-16
**Статус:** MVP в разработке

---

## Содержание

1. [Технологический стек](#1-технологический-стек)
2. [Структура модулей](#2-структура-модулей)
3. [Архитектура Gateway](#3-архитектура-gateway)
4. [Аутентификация и авторизация](#4-аутентификация-и-авторизация)
5. [Pass-Through Identity](#5-pass-through-identity)
6. [Интеграция с Keycloak](#6-интеграция-с-keycloak)
7. [Kafka-сообщения](#7-kafka-сообщения)
8. [Криптография и PKI](#8-криптография-и-pki)
9. [База данных](#9-база-данных)
10. [Фронтенд](#10-фронтенд)
11. [Сборка и деплой](#11-сборка-и-деплой)
12. [Ключевые решения и обоснование](#12-ключевые-решения-и-обоснование)

---

## 1. Технологический стек

| Компонент | Версия | Назначение |
|-----------|--------|------------|
| Kotlin | 2.0.21 | Основной язык |
| Spring Boot | 3.3.5 (WebFlux) | Реактивный фреймворк |
| PostgreSQL | 14 + PostGIS | База данных |
| Kafka | 3.7.1 | Асинхронная шина |
| Redis | 7 (alpine) | Event store gateway: статусы async-команд (TTL 24 ч) + delta-sync chunks (`asop:event:{id}:chunk:{n}`, TTL 24 ч) |
| MinIO | RELEASE.2024-10-13 | S3-совместимое хранилище ZIP полной выгрузки справочников (bucket `asop-sync`) |
| Protobuf | 3.25.5 | Сериализация dull-reference-данных в чанках 50 КБ (модуль `:backend:shared:asop-proto`, `protobuf-java` на backend + Android) |
| Keycloak | 25.0.4 | OIDC-провайдер |
| Bouncy Castle | 1.78.1 | Криптография (ECC P-256) |
| Gradle | 8.10.2 | Система сборки |
| React | 18 + Vite | Админка |
| oidc-client-ts | — | OIDC-клиент |
| **Целевое устройство** | Feitian F20 | Android-POS терминал для транспорта (перевозчики, дистрибьюторы, диспетчеры, водители) |

### Ограничения JVM (критично)
- Gradle daemon: `-Xmx4g`
- Kotlin daemon: `-Xmx2g`
- Без этих настроек сборка падает с OOM на 22 модулях
- `org.gradle.configuration-cache=false` — ломает компиляцию Kotlin (ClasspathSnapshotProperties)

---

## 2. Структура модулей

28 модулей в иерархии:

```
:backend:shared:asop-common           # BaseEntity, DomainEvent, ErrorCode, KafkaTopic, утилиты
:backend:shared:asop-dto              # Пусто — DTO перенесены в API-модули
:backend:shared:asop-kafka-contracts  # Классы Kafka-событий
:backend:shared:api:{domain}-api      # 13 модулей: интерфейсы контроллеров + DTO (без реализации), включая tid-api
:backend:{domain}-service             # 12 Spring Boot приложений с реализацией
```

### Граф зависимостей
```
Сервис → API (implementation) → asop-common (api platform)
```

### Структура API-модуля
Пакет: `ru.asop.api.{domain}`
```
backend/shared/api/{name}-api/
├── build.gradle.kts
└── src/main/kotlin/ru/asop/api/{package}/
    ├── controller/          # Интерфейсы контроллеров (@RequestMapping)
    ├── dto/request/         # Request DTO
    ├── dto/response/        # Response DTO
    └── exception/           # Исключения API
```

### Структура сервисного модуля
Пакет: `ru.asop.{domain}` (например `ru.asop.gateway`, `ru.asop.crypto`)

### Порты сервисов

| Сервис | Порт | Роль |
|--------|------|------|
| gateway-service | 8080 | API Gateway: JWT + mTLS, Kafka producer |
| crypto-service | 8081 | Root CA, выпуск X.509 сертификатов |
| user-service | 8082 | Пользователи + Keycloak bootstrap |
| terminal-service | 8084 | Управление терминалами |
| session-service | 8085 | Сессии/смены (иерархия) |
| card-service | 8086 | Карты (MIFARE, банковские) |
| carrier-service | 8087 | Перевозчики, договоры, дистрибьюторы карт (R2DBC) |
| debt-service | 8088 | Долги по картам |
| audit-service | 8089 | КРС (проверки) |
| fiscal-service | 8090 | Фискализация (ОФД) |
| admin-service | 8091 | Справочники (Regions, Territories, Organizers) |
| route-service | 8092 | Маршруты, тарифные зоны, остановки, ТС, расписание (R2DBC) |

---

## 3. Архитектура Gateway

**Основной принцип:** Gateway не пишет в БД. Выполняет только авторизацию/аутентификацию/проксирование — бизнес-логики в gateway нет. Терминал сам передаёт business context (`carrierId`, `regionId`, `timezone`) в теле запроса, gateway пробрасывает его в Kafka headers (`X-Carrier-Id`, `X-Region-Id`, `X-Timezone`) — по аналогии с `X-Event-Id`/`X-Keycloak-Id`.

### Паттерны маршрутизации

#### 1. Async writes (POST/PUT/DELETE с явным контроллером)
- Команда отправляется в Kafka
- Gateway возвращает `202 Accepted` + заголовок `X-Event-Id` + тело `AcceptedResponse`
- `keycloakId` передаётся в Kafka headers (`X-Keycloak-Id`)
- Пример: `CarrierController` / `CarrierCommandService`

#### 2. Sync proxy (GET + необработанные запросы)
- `ProxyController` пересылает запросы в backend-сервисы через `WebClient`
- Маппинг ресурса → base URL в `ServiceRegistry`:
  - `ASOP_ENV=local` (по умолчанию) → `localhost:{port}`
  - `ASOP_ENV=docker` → Docker hostname сервиса
- Gateway добавляет заголовок `X-Keycloak-Id`, **удаляет** `Authorization`

#### 3. Event tracking
- После отправки команды в Kafka `EventService` сохраняет статус `PENDING` в **Redis** (key `asop:event:{eventId}`, TTL 24 ч, реактивный `ReactiveStringRedisTemplate` + Jackson-сериализация `EventStatus`). Состояние переживает рестарт gateway — оффлайн-терминал успеет добрать результат в течение суток. До переезда в Redis хранилище было `ConcurrentHashMap` в памяти (TTL 30 мин, терялось при рестарте).
- Фронт поллит `GET /api/v1/events/{eventId}`:
  - `202 Accepted` пока PENDING
  - `200 OK` с `resultData` (JSON) когда COMPLETED (cert saga — PEM)
  - `422 Unprocessable Entity` с `errorMessage` когда FAILED
  - `404 Not Found` если eventId неизвестен
- Для cert-sign saga (`asop.terminal.cert.events`) Gateway-consumer обновляет EventService (`COMPLETED`/`FAILED`). Для остальных команд пока сервисы не публикуют события — статус остаётся PENDING в течение TTL 24 ч.

#### 4. Cert signing flow (choreographed saga, 4 hops)
Первая регистрация терминала — **открытый HTTPS endpoint без JWT/mTLS** (chicken-and-egg при первой регистрации):

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

XA-гарантии: UNIQUE partial index `uq_tc_current_per_terminal ON ASOP_TERMINAL_CERTS (TERMINAL_ID) WHERE IS_CURRENT = true` + `TransactionalOperator` для атомарности.

### Ключевые компоненты Gateway

| Файл | Назначение |
|------|------------|
| `config/ServiceRegistry.kt` | Маппинг resource → URL сервиса |
| `config/WebClientConfig.kt` | WebClient bean для proxy |
| `controller/ProxyController.kt` | Sync proxy (catch-all) |
| `controller/CertCommandController.kt` | POST /api/v1/terminals/cert-sign (async через Kafka) |
| `controller/SessionCommandController.kt` | POST /sync/sessions/open, PUT /sync/sessions/{id}/close (mTLS async) |
| `controller/TransactionCommandController.kt` | POST /sync/transactions (mTLS async) |
| `controller/CardCommandController.kt` | POST /sync/cards/register, POST /sync/cards/{id}/block (mTLS async) |
| `controller/DebtCommandController.kt` | POST /sync/debts, PUT /sync/debts/{id}/recover (mTLS async) |
| `controller/FiscalCommandController.kt` | POST /sync/fiscal/receipts (mTLS async) |
| `controller/AuditCommandController.kt` | POST /sync/audit/tasks (mTLS async) |
| `controller/GpsCommandController.kt` | POST /sync/gps/positions (mTLS async) |
| `controller/EventController.kt` | Эндпоинт статуса события |
| `service/EventService.kt` | Redis-backed event store (key `asop:event:{eventId}`, TTL 24 ч, reactive) |
| `service/CertCommandService.kt` | Producer CertSignRequested в `asop.terminal.cert.commands` |
| `service/SessionCommandService.kt` | Производитель SessionOpenedEvent/SessionClosedEvent |
| `service/TransactionCommandService.kt` | Производитель TransactionCompletedEvent |
| `service/CardCommandService.kt` | Производитель CardRegisteredEvent/CardBlockedEvent |
| `service/DebtCommandService.kt` | Производитель DebtCreatedEvent/DebtRecoveredEvent |
| `service/FiscalCommandService.kt` | Производитель FiscalReceiptRequestedEvent |
| `service/AuditCommandService.kt` | Производитель AuditTaskCreatedEvent |
| `service/GpsCommandService.kt` | Производитель GpsPositionReported |
| `kafka/CertEventConsumer.kt` | Listener `asop.terminal.cert.events` → EventService.complete/fail |
| `kafka/CommandEventConsumer.kt` | Listener всех 7 domain event topics → EventService.complete/fail |
| `model/EventStatus.kt` | EventState (PENDING, COMPLETED, FAILED) + `resultData: String?` |

---

## 4. Аутентификация и авторизация

### Двойная аутентификация в Gateway

**Chain 1** (`@Order(1)`): mTLS для терминалов
- Пути: `/api/v1/terminals/**`, `/api/v1/sync/**`
- Principal = `CN` из X.509 сертификата
- `X509PrincipalExtractor` из `org.springframework.security.web.authentication.preauth.x509` (синхронный, возвращает `Any`)
- **Исключение**: `POST /api/v1/terminals/cert-sign` → `permitAll` (open HTTPS, без JWT и mTLS — chicken-and-egg при первой регистрации терминала). **GET/PUT к `/api/v1/terminals` и `/api/v1/terminals/{id}` — `authenticated()` (mTLS)**; анонимный доступ к списку терминалов/деталим запрещён (device-id leaks).

**Chain 2** (`@Order(2)`): JWT (Keycloak) для всего остального
- JWKS кэшируется локально, проверка каждые 60 сек
- Нет сетевых вызовов к Keycloak на каждый запрос
- `GET /api/v1/regions/**` и `GET /api/v1/carriers/**` — `permitAll` (public справочники).

### terminal-service SecurityConfig

`permitAll` для `/api/v1/terminals/**`, **БЕЗ `.oauth2ResourceServer`** (ранее leftover — удалён). Сервис внутри Docker доверяет gateway по TLS. defense-in-depth — gateway mTLS chain-1 / JWT chain-2.

### Проблема JWT issuer

В Docker Keycloak (`KC_HOSTNAME=localhost`) выдаёт токены с `iss: http://localhost:8180/realms/asop`, но сервисы внутри Docker обращаются к Keycloak по `http://keycloak:8080`. Стандартный валидатор Spring Security отвергает несовпадающий issuer.

**Решение:** Кастомный `ReactiveJwtDecoder` (`JwtDecoderConfig.kt`):
- Использует JWKS (`jwk-set-uri`) для проверки подписи
- Не проверяет `iss` (принимает любой issuer)
- Проверяет `exp` и `nbf` вручную

Применён в: gateway-service, user-service.

---

## 5. Pass-Through Identity

Gateway проверяет JWT, извлекает `sub` (keycloakId), передаёт backend-сервисам **без** оригинального JWT.

### Схема
```
Клиент                      Gateway                         Сервис
  │                         │                                │
  │ POST /password/change   │                                │
  │ Authorization: JWT      │                                │
  │────────────────────────>│                                │
  │                         │ Проверка JWT (JWKS, exp/nbf)   │
  │                         │ Извлечение sub (keycloakId)    │
  │                         │                                │
  │                         │ POST /password/change          │
  │                         │ X-Keycloak-Id: <sub>          │
  │                         │ (без Authorization)            │
  │                         │──────────────────────────────>│
  │                         │                                │
  │                         │ keycloakId → userId            │
  │                         │ обработка запроса              │
  │                         │<──────────────────────────────│
  │<────────────────────────│                                │
  │ 204 / 202 / 4xx / 5xx   │                                │
```

### Реализация
- **Gateway**: `ProxyController.extractIdentity()` использует `ReactiveSecurityContextHolder` для получения JWT, извлекает `sub`
- **Kafka async**: `X-Keycloak-Id` в Kafka headers
- **Backend сервисы**: не валидируют JWT (user-service с `permitAll`)
- Backend доверяет `X-Keycloak-Id` от gateway (внутренняя сеть)

### Улучшение в будущем
Создать общий `UserResolver` в `asop-common` для resolve keycloakId → userId + роли из БД.

---

## 6. Интеграция с Keycloak

### Баги Keycloak 25.0.4

Три бага при создании realm/users через Admin API:

1. **Создание realm**
   - Голый `{realm: "asop", enabled: true}` ломает Direct Access Grant
   - Требуется: `resetPasswordAllowed=true`, `directGrantFlow="direct grant"`, `registrationAllowed=false`, `verifyEmail=false`, `loginWithEmailAllowed=true`

2. **Учётные данные пользователя**
   - `POST /users` без credentials + отдельный `PUT /reset-password` → "Account is not fully set up"
   - Фикс: credentials **inline** в `UserRepresentation`

3. **Имена пользователя**
   - Отсутствие `firstName` или `lastName` → "Account is not fully set up"
   - Оба поля обязательны

### Bootstrap

На `ApplicationReadyEvent`, если таблица `ASOP_USERS` пуста:
1. Создаёт realm `asop` с полной конфигурацией
2. Создаёт роли: `SUPER_ADMIN`, `CARRIER_ADMIN`, `DISPATCHER` и т.д.
3. Создаёт администратора `admin@asop.local` с inline credentials
4. Записывает в `ASOP_USERS` + `ASOP_USER_ROLES`

### Потоки аутентификации

| Окружение | Поток | Статус |
|-----------|-------|--------|
| MVP/dev | Direct Access Grant (password grant) | Активен |
| Production | OIDC Auth Code + PKCE (через oidc-client-ts) | Запланирован |

### Эндпоинты

| Метод | Путь | Сервис | Тип |
|-------|------|--------|-----|
| POST | `/api/v1/users/password/change` | user-service | sync (pass-through) |

---

## 7. Kafka-сообщения

### Именование топиков
Шаблон: `asop.{domain}.{commands|events}`

Определён в `KafkaTopic` в `asop-common`:

**Command topics (gateway → сервисы):**
- `asop.carrier.commands` — команды записи перевозчиков
- `asop.session.commands` — open/close session
- `asop.transaction.commands` — complete transaction
- `asop.card.commands` — register/block card
- `asop.debt.commands` — create/recover debt
- `asop.fiscal.commands` — request fiscal receipt
- `asop.audit.commands` — create audit task
- `asop.gps.commands` — report GPS position
- `asop.terminal.cert.commands` — команды выпуска X.509 (gateway → crypto-service)

**Event topics (сервисы → gateway):**
- `asop.session.events` — доменные события сессий
- `asop.transaction.events` — события транзакций
- `asop.card.events` — события карт
- `asop.debt.events` — события долгов
- `asop.fiscal.events` — события фискализации
- `asop.audit.events` — события КРС
- `asop.gps.events` — события GPS
- `asop.terminal.cert.issued` — выпущенные сертификаты (crypto-service → terminal-service)
- `asop.terminal.cert.events` — сохранённые сертификаты + ошибки (terminal-service → gateway)

### Поток асинхронной команды
1. Gateway проверяет запрос, извлекает identity
2. Gateway отправляет команду в `asop.{domain}.commands`
3. Gateway возвращает `202 Accepted` + `X-Event-Id`
4. Backend-сервис потребляет команду, обрабатывает, публикует событие в `asop.{domain}.events`
5. **CommandEventConsumer** (gateway) слушает все 7 domain event topics, извлекает `X-Event-Id` из Kafka headers, вызывает `EventService.complete/fail`
6. Клиент получает `200 OK` с `resultData` при polling `GET /api/v1/events/{eventId}`

Для cert-sign saga поток расширен: gateway → crypto-service → terminal-service → gateway через 3 топика (`commands`, `issued`, `events`) и проброс `X-Event-Id` через Kafka headers для корреляции.

### Заголовки
- `X-Keycloak-Id`: keycloakId аутентифицированного пользователя (трассировка)
- `X-Event-Id`: идентификатор события для корреляции команд (используется `CommandEventConsumer` для вызова `EventService.complete/fail`)

---

## 8. Криптография и PKI

### Иерархия сертификатов
```
Root CA (self-signed, ECC P-256, 10 лет)
└── Intermediate CA (подписан Root CA, 5 лет)
    ├── Сертификаты терминалов (1 год)
    ├── Сертификаты смарт-карт (1 год)
    └── Сертификаты водителей (1 год)
```

### Реализация
- Root CA в PKCS#12 (`./data/root-ca.p12`), авто-генерация при первом старте
- ECC P-256 через Bouncy Castle
- Все подписи через Intermediate CA (пересоздаётся при каждом рестарте в MVP)
- `MediaType.APPLICATION_PEM_CERTIFICATE_VALUE` нет в Spring 6.1 — использовать `"application/x-pem-file"`

### Эндпоинты crypto-service
| Метод | Путь | Описание |
|-------|------|----------|
| POST | `/api/v1/terminals/cert-sign` | Выпуск сертификата терминала (open HTTPS, 202 + X-Event-Id, 4-hop Kafka saga) |
| POST | `/api/v1/smart-cards/issue` | Выпуск сертификата смарт-карты |
| GET | `/api/v1/terminals/root-ca(/{format})` | Root CA в PEM/DER |
| POST | `/api/v1/certificates/server` | Выпуск серверного сертификата |
| GET | `/api/v1/certificates/ca-chain` | CA chain bundle (Root + Intermediate) |

**Kafka consumer**: `@KafkaListener("asop.terminal.cert.commands")` в `CertCommandConsumer.kt` выпускает сертификат через `TerminalCertService.issueTerminalCertificate()` и публикует `CertIssued` в `asop.terminal.cert.issued` с пробросом `X-Event-Id` header. CA chain включается в `CertIssued.caChain`.

### Роли смарт-карт
`PASSENGER_ANONYMOUS`, `PASSENGER_BENEFIT`, `DRIVER`, `CONTROLLER`, `DISPATCHER`, `CARRIER_ADMIN`, `REGION_ADMIN`, `SUPER_ADMIN`, `DISTRIBUTOR_ADMIN`, `DISTRIBUTOR_TERMINAL`, `SERVICE`

### DN-шаблоны
```yaml
DRIVER: "CN={cardId}, OU=DRIVER:{carrierId}, O=ASOP"
CONTROLLER: "CN={cardId}, OU=CONTROLLER:{carrierId}, O=ASOP"
```

### Верификация подписей карт на терминале

При регистрации терминала (cert-sign saga) терминал загружает публичный RSA-PSS ключ сервера:

```
CertificateService.provision()
  → mtlsManager.generateKeyPair()
  → POST /api/v1/terminals/cert-sign → polling → store cert
  → GET /api/v1/keys/public → store PEM в SyncPreferences (DataStore)
```

Ключ хранится в DataStore (синхронное/preferences хранилище), загружается при старте.
Формат: base64-encoded DER (X.509 SubjectPublicKeyInfo) в PEM-обёртке.
Endpoint `GET /api/v1/keys/public` проксируется gateway → crypto-service (ServiceRegistry: keys).

При чтении зарегистрированной карты (CardReadScreen) терминал:

1. Читает File 0 (proto CardIdentity) и File 1 (RSA-PSS-SHA256 подпись)
2. Строит canonical JSON из proto (тот же порядок ключей, что у сервера)
3. Загружает публичный ключ из SyncPreferences
4. Верифицирует подпись: `Signature.getInstance("RSASSA-PSS")` с `PSSParameterSpec("SHA-256","MGF1",MGF1ParameterSpec.SHA256,32,1)`
5. Результат (`signatureValid: Boolean?`) отображается в UI

Верификация НЕ выполняется при активации/регистрации карты (write-операция).

### Ключи ASOP_KEYS на терминале (локальное AES-шифрование)

Ключи доставляются на терминал через дельту/полную выкачку (поле `asop_keys = 43` в `DeltaChunk`). На устройстве они **перешифровываются** локальным AES-ключом, чтобы не хранить plaintext материал в Room:

- `TerminalKeyCryptor` (алиас `asop_terminal_keys_aes`, AndroidKeyStore AES-256/GCM, `PURPOSE_ENCRYPT|DECRYPT`)
- Генерация: **lazy** — при первом прибытии `asop_keys` через дельту
- Ключ **неэкспортируемый**, живёт в TEE/StrongBox, **не зависит от mTLS-сертификата** (разные алиасы и PURPOSE)
- Переживает: перевыпуск mTLS-серта, переустановку приложения (тот же signing key), `fallbackToDestructiveMigration()` Room
- Удаляется только при очистке данных приложения или factory reset

---

## 8.1. Delta Sync (инкрементальная дельта-синхронизация справочников)

Оркестратор `orchestrator-service` (порт 8094) — новый микросервис: читает Kafka `asop.delta.commands` / `asop.delta.full.commands`, опрашивает мастер-сервисы через REST `/delta` (keyset-пагинация по `versionSince`), чанкует по 50 КБ (Protobuf `serializedSize`), пишет чанки в Redis, заливает ZIP в MinIO. `PurgeJob` физически удаляет soft-deleted строки старше 6 месяцев (раз в час, `SET session_replication_role='replica'`). Полный поток:

```
Android → POST /sync/references/delta (body: terminalId, lastVersion) → 202 + X-Event-Id
       → Gateway → Kafka asop.delta.commands
       → orchestrator → REST /delta к мастер-сервисам (versionSince, keyset) → Protobuf → чанки 50КБ → Redis
       → EventService.complete(eventId, {totalChunks,totalBytes})
       → Android → GET /events/{eventId} → COMPLETED → GET /sync/references/{eventId}/chunks/{n}
       → ReferenceSyncStore.applyChunk → Room reference_rows (атомарно) + sync_meta watermark
```

**Region/carrier фильтрация в `master /delta` (промпт 010):**

Все мастер-сервисы принимают `regionId` и `carrierId` query-параметры, которые orchestrator пробрасывает на основе `terminalId → carrierId → regionId` (`TerminalResolver`). Реальная SQL-фильтрация по региону делается в самих мастерах:

- **Прямая фильтрация** (таблица имеет `region_id` колонку): `asop_territories`, `asop_carriers`, `asop_fare_zones`, `asop_transport_stops`, `asop_routes`, `asop_paths`, `asop_path_transport_stops`, `asop_schedule`, `asop_services`, `asop_benefits`.
- **JOIN-фильтрация** (через FK-цепочку): `asop_benefit_steps` → `benefits.region_id`; `asop_contract_routes` → `routes.region_id`; `asop_path_benefits` → `paths→routes.region_id`; `asop_organizer_territories` → `territories.region_id`.
- **EXISTS-фильтрация** (через many-to-many): `asop_organizers` → `organizer_territories→territories.region_id`; `asop_user_roles` → `users + ASOP_USER_REGIONS.region_id`.

Если `regionId = null` (full dump), SQL возвращает все строки (`where (:p IS NULL OR ...)`). Это необходимо для `FullSyncService` при первом включении терминала.

**Тех. детали промпт 010 (для разработчиков):**
- Spring Data R2DBC плохо мапит custom `@Query` возвращающие `Flux<Entity>` — для JOIN использован прямой `DatabaseClient + .map { row -> entity }`.
- R2DBC PostgreSQL driver возвращает UUID как `UUID.class` — helper `asUuid(value)` оборачивает оба варианта.
- `.bind("name", null)` падает (`Any` non-null) — `if (v != null) spec.bind(v) else spec.bindNull(name, Class::javaObjectType)`.
- Все WHERE-условия с префиксом таблицы (`ASOP_FOO.x`) во избежание ambiguity при JOIN.
- В `GenericRouteRepository.findDelta` добавлено поле `ResourceInfo.regionJoinClause: String?` — если задано и `regionId != null`, используется вместо стандартного `region_id = :regionId`. Это generic механизм для произвольных JOIN-фильтров.
- `MasterRegistry.kt` НЕ изменён — orchestrator уже передаёт `regionId`/`carrierId`; правки сделаны только на master-стороне.

**VERSION-курсор**: все ~42 справочные таблицы имеют `VERSION BIGINT` — глобальный монотонный sequence `asop_delta_version_seq` (`trg_fn_assign_version()` присваивает `nextval(...)` на INSERT/UPDATE/DELETE). Дельта-фильтр — `VERSION > versionSince`, сортировка `ORDER BY VERSION ASC` (стабильная keyset-пагинация, не зависит от таймзоны/изменения часов, в отличие от `UPDATED_AT`). `UPDATED_AT` остаётся для аудита и soft-delete.

`userIdsIn`-фильтр для user/card таблиц: URL ограничен ~4 КБ (Reactor Netty `max-initial-line-length`) → оркестратор шлёт userIds батчами по 80 (`USER_IDS_BATCH`) и объединяет результат.

Soft-delete: все ~42 справочные таблицы имеют `DELETED_AT TIMESTAMPTZ` + `BEFORE DELETE` триггер (generic `trg_fn_soft_delete()` для single-PK, `trg_fn_soft_delete_2col()` для composite-PK). DELETE превращается в `UPDATE DELETED_AT = NOW(), UPDATED_AT = NOW()` и возвращает NULL. `trg_fn_touch_updated()` авто-pristine проставляет `UPDATED_AT` на UPDATE.

---

## 9. База данных

### Ключевые таблицы
- `ASOP_USERS`, `ASOP_USER_ROLES`, `ASOP_USER_CARRIERS` — пользователи
- `ASOP_CARRIERS`, `ASOP_CARDS_DISTRIBUTORS`, `ASOP_CONTRACTS`, `ASOP_VEHICLES` — перевозчики, дистрибьюторы карт, договоры, ТС
- `ASOP_CONTRACTS` — общий справочник договоров: `CONTRACTOR_TYPE` (CARRIER | CARDS_DISTRIBUTOR, nullable), `CARRIER_ID`/`CARDS_DISTRIBUTOR_ID` (оба nullable, CHECK запрещает оба NOT NULL), `ATTRIBUTES JSONB` (абстрактная информация)
- `ASOP_CARDS`, `ASOP_CARD_MIFARES`, `ASOP_CARD_TARIFFS`, `ASOP_CARD_BANKS` — карты
- `ASOP_TERMINALS` (с `UNIQUE` constraint на `TERMINAL_SERIAL`), `ASOP_DISTRIBUTOR_TERMINALS`, `ASOP_TIDS` — терминалы. `TERMINAL_NUMBER` теперь nullable (инвентарный номер вводится вручную); добавлены `CREATED_AT`/`UPDATED_AT TIMESTAMPTZ DEFAULT now()`.
- **Register endpoint** (`terminal-service`): синхронный upsert в `TerminalService.resolveTerminal` — см. `doc/context.md` раздел 8 «Terminal registration».
- `ASOP_TERMINAL_CERTS` — история X.509 сертификатов терминалов (v002):
  - `IS_CURRENT` boolean, `CA_CHAIN` PEM
  - **UNIQUE partial index** `uq_tc_current_per_terminal ON (TERMINAL_ID) WHERE IS_CURRENT = true` — не более одного активного
- `ASOP_SESSIONS`, `ASOP_TRANSACTIONS`, `ASOP_CARD_DEBTS` — транзакции
- `ASOP_AUDIT_TASKS`, `ASOP_AUDIT_BRIGADES`, `ASOP_AUDIT_INSPECTIONS` — КРС

### Соглашения
- **Все ID**: UUID v7 через `UuidCreator.getTimeOrderedEpoch()` (`UuidUtils.newId()`)
- **Миграции**: Liquibase, в `infrastructure/db-migrations/{service}/`
- Активные миграции только в user-service; у остальных `liquibase.enabled=false`

---

## 10. Фронтенд

### Web Admin (`frontend/web-admin/`)
- Vite + React 18 + TypeScript + react-router + TanStack Query + oidc-client-ts
- Vite dev mode проксирует `/api` → `http://localhost:8080` (gateway)
- API-клиент: `BASE=/api/v1`, Bearer token из oidc-client-ts
- `useCommand` hook: паттерн 202 + polling для write-команд
- Страницы: `Login`, `Callback` (OIDC), `Dashboard`, `Users`, `Terminals`, `Cards`, `Carriers`, `Tids`, `CardsDistributors`, `Contracts`, `Regions`, `Territories`, `Organizers`, `Routes`, `FareZones`, `TransportStops`, `Vehicles`, `Paths`, `Schedule`
- Язык UI: русский (для английского нужен i18n)

### Android Terminal (`frontend/android-terminal/`)
- Kotlin + Jetpack Compose + Hilt + Room + WorkManager
- mTLS auth через X.509 сертификат crypto-service

**Офлайн-буферизация:** Все write-команды сначала сохраняются в Room (`PendingEventEntity`, статус `PENDING`). Фоновые `WorkManager` workers (`SyncWorker` каждые 15 мин, `EventPollWorker` каждые 5 мин) отправляют их на gateway через `SyncApi` (mTLS). После получения `202 + X-Event-Id` статус меняется на `SENDING`. Polling `GET /api/v1/events/{eventId}` через `EventPollWorker` отслеживает COMPLETED/FAILED.

**Компоненты:**
- `AppDatabase` (Room, version 3): 6 сущностей — `PendingEventEntity`, `SessionEntity`, `TransactionEntity` (write-команды) + `SyncMetaEntity`, `DeltaSyncJobEntity`, `ReferenceRowEntity` (справочники). `ReferenceRowEntity` — generic-таблица `reference_rows` (tableName, rowId, payloadJson, updatedAt, deletedAt), composite PK `(table_name, row_id)`. `fallbackToDestructiveMigration()`.
- `SyncPreferences` (DataStore): terminalId, sessionId, lastSyncTime, lastVersion (VERSION-курсор)
- `SyncApi` (Retrofit): 10 async endpoints под `/api/v1/sync/**` (mTLS)
- `GatewayApi` (Retrofit): terminal CRUD + `GET /api/v1/events/{eventId}` + `GET /api/v1/regions` + `GET /api/v1/carriers?regionId=...` (sync-proxy через gateway, `permitAll` для mTLS-терминала) + `PUT /api/v1/terminals/{id}/carrier`
- `SyncWorker`: отправка PENDING событий на gateway (15 min periodic, one-shot on network restore)
- `EventPollWorker`: polling SENDING событий (5 min periodic, `retryCount >= 20` → FAILED)
- `DeltaSyncWorker`: дельта-запрос справочников (1 час periodic, one-shot) → `POST /sync/references/delta` (body: terminalId, lastVersion) → `DeltaSyncJobEntity` PENDING
- `DeltaChunkPollWorker`: polling COMPLETED дельта-заданий (5 min periodic), качает чанки → `ReferenceSyncStore.applyChunk` (атомарный накат + MAX VERSION в sync_meta)
- `FullDumpDownloadWorker`: полная выкачка (one-shot), поллит событие → ZIP по `s3Url` → `.pb` → `applyFile`
- `ReferenceSyncStore`: парсинг protobuf (`ru.asop.proto.v1.*File`), `applyChunk`/`applyFile`, `db.withTransaction` + sync_meta
- `WorkScheduler`: периодические DeltaSync 60м + DeltaChunkPoll 5м, one-shot delta, `enqueueFullDump`
- `GpsTrackingService`: foreground service, `FusedLocationProviderClient`, 30s interval, batch threshold 10 → trigger sync
- `NetworkMonitor`: `ConnectivityManager.NetworkCallback` → one-shot sync on network restore
- `CertificateService`: ECC P-256 keypair generation, `POST /cert-sign`, event polling, PEM store. `terminalSerial` = `Settings.Secure.ANDROID_ID`.
- `MtlsManager.resetKeyAndCert()`: чистит alias AndroidKeyStore + SharedPreferences — для принудительного перевыпуска сертификата через drawer-меню "Сертификат".
- `SyncViewModel` + обновлённый `MainScreen`: sync status card, pending badge, GPS toggle, manual sync button
- `ContentProvider` (`ru.asop.terminal.provider`): экспортирует `reference_rows`/`sync_meta` наружу (permission `ru.asop.terminal.provider.READ`) — читается приложением `android-test` для сверки дельта-синка.

**Drawer-меню (`ModalNavigationDrawer`)** — hamburger-иконка в TopAppBar, открывает панель с пунктами: "Сертификат" (диалог перевыпуска), "Регистрация" (`RegistrationScreen`, доступна всегда), "Привязать перевозчика" (`AssignCarrierScreen`), "Загрузить справочники" (AlertDialog с числом строк в `reference_rows` и активных дельта-заданий, кнопки «Дельта сейчас» и «Полная выкачка»), stub-пункты (зарегистрировать карту водителя, открыть/закрыть смену, открыть/закрыть рейс — placeholder, TODO). `TerminalNavHost` обёрнут в `ModalNavigationDrawer`+`Scaffold`, добавлены routes `assign-carrier`, `provisioning`, `registration`, `main`.

**Sync flow:**
```
Offline:  UI → Room (PendingEvent PENDING)
          GPS → Room (PendingEvent PENDING)
Online:   NetworkCallback → SyncWorker → POST /sync/** → 202 + eventId → SENDING
          EventPollWorker → GET /events/{eventId} → 200 COMPLETED / 422 FAILED

Delta:    DeltaSyncWorker (60m) → POST /sync/references/delta (lastVersion) → DeltaSyncJob PENDING
          DeltaChunkPollWorker (5m) → GET .../{eventId}/meta + /chunks/{n} → applyChunk → reference_rows + sync_meta
Full:     FullDumpDownloadWorker → GET /events/{eventId} (10с×60) → ZIP по s3Url → applyFile
```

### Целевое устройство

**Feitian F20** (бренд FTSafe) — мобильный Android-POS терминал, целевое устройство для установки `android-terminal`.

| Характеристика | Значение |
|----------------|----------|
| Модель | Feitian F20 Smart Mobile POS |
| SoC | Quad-core 4×A53@2.0GHz |
| RAM/ROM | 2 GB / 32 GB |
| ОС | Android 14 (опц. Android 10) |
| NFC | 13.56 МГц, ISO/IEC 14443 (Type A&B), ISO 18092, Felica, Mifare — поддерживает DESFire EV1/EV2/EV3 (ISO 14443-4) |
| Экран | 5.5" HD IPS LCD 720×1440, multi-touch |
| Связь | 4G, Wi-Fi 5 (2.4/5 GHz), BT 5.0, GPS (GPS+BDS+GLONASS+Galileo) |
| Принтер | Термо 58 мм, 80 мм/с |
| Дополнительно | MSR (магнитная полоса), IC-карты (ISO 7816), PSAM, камера 8 MP, сканер отпечатков |
| Сертификаты | PCI PTS 5.1, EMV L1/L2, EMV Contactless L1 |
| Сайт | https://www.ftsafe.com/payment/f20/ |

**Важно:** F20 — PCI PTS-сертифицированный финансовый терминал. NFC-контроллер может быть залочен на платёжное ядро и не пробрасываться в стандартный Android `android.nfc.tech.IsoDep` / `NfcAdapter` для сторонних приложений. Доступ к NFC для `android-terminal` необходимо проверить на конкретной партии; при необходимости — подключить Feitian SDK.

### Android Test (`frontend/android-test/`)
- Отдельное Android-приложение (Kotlin + Jetpack Compose, подписано тем же debug-ключом), НЕ содержит mTLS/SyncApi — только проверка результата синка через ContentProvider `android-terminal`.
- Ethalon JSON (SQL-generated): `assets/expected-1.json` (коммитится, ~746 КБ), `expected-2.json` (~40 МБ) и `expected-all.json` (~47 МБ) — **gitignored**, регенерируются через `infrastructure/docker/generate-ethalon.sh` после каждого дельта-состояния БД.
- Кнопки: «Тест дельта инкремента 1/2» (сверка `reference_rows` с expected-{1,2}.json), «Получить все данные» (сверка с expected-all.json). Сравнение в `EthalonChecker`: все поля кроме `created_at`/`updated_at`, канонизация (camelCase→snake_case, timestamps→epoch, proto3-дефолты).

**Регистрация (RegistrationScreen)** — пользователь выбирает: регион (dropdown из `GET /api/v1/regions`) → перевозчика (dropdown из `GET /api/v1/carriers?regionId=...`, фильтр по региону) → timezone (read-only, `TimeZone.getDefault().id`) → модель (опц.) → инвентарный номер (обяз.). Запрос `TerminalRegisterRequest` содержит `terminalSerial` (ANDROID_ID), `carrierId`, `timezone`, `terminalId` (если уже зарегистрирован). Кнопка дизейблится пока не выбраны region/carrier/inventory.

**Привязка перевозчика (AssignCarrierScreen)** — dropdown регион → dropdown перевозчик (filter по regionId, текущий пред-выбран) → кнопка "Сохранить" → `PUT /api/v1/terminals/{id}/carrier` (`TerminalCarrierAssignRequest { carrierId }`, null = отвязать). Отображает timezone устройства read-only.

**Permissions:** `INTERNET`, `ACCESS_FINE_LOCATION`, `ACCESS_BACKGROUND_LOCATION`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE_DATA_SYNC`, `FOREGROUND_SERVICE_LOCATION`, `NFC`

---

## 11. Сборка и деплой

### Docker
```bash
./gradlew bootJar
docker compose -f infrastructure/docker/docker-compose.yml down -v
docker compose -f infrastructure/docker/docker-compose.yml up -d --build
```

`--build` обязателен после пересборки JARs. `down -v` удаляет volumes (fresh-start конвенция).

Каждый сервис имеет `Dockerfile` (eclipse-temurin:21-jre). Liquibase миграции монтируются из `infrastructure/db-migrations/` в `/db-migrations/` внутри контейнера.

### Docker Compose
Все 12 сервисов + route-service + PostgreSQL + Kafka + Keycloak + Zookeeper + Liquibase + web-admin на общей сети `asop-net`.

**Запуск из Windows:** `.\infrastructure\docker\start.ps1` (PowerShell wave-based скрипт). Git Bash не видит Docker Desktop.

### Ключевые переменные окружения
| Переменная | Назначение |
|-----------|------------|
| `BOOTSTRAP_ENABLED` | Включить bootstrap при старте |
| `BOOTSTRAP_ADMIN_PASSWORD` | Начальный пароль администратора |
| `KEYCLOAK_URL` | Внутренний URL Keycloak |
| `KEYCLOAK_ADMIN_PASSWORD` | Пароль admin Keycloak |
| `ROOT_CA_KEYSTORE_PASSWORD` | Пароль хранилища Root CA |
| `ASOP_ENV` | `local` (по умолчанию) или `docker` |
| `KC_HOSTNAME` | Hostname Keycloak (localhost в Docker) |

---

## 12. Ключевые решения и обоснование

| Решение | Обоснование |
|---------|-------------|
| **Gateway → Kafka → Сервисы** (без записи в БД из gateway) | CQRS-подобное разделение; gateway — только роутер/валидатор |
| **Pass-through identity** | Backend-сервисам не нужно валидировать JWT; доверие во внутренней сети |
| **Кастомный JWT decoder (без проверки issuer)** | URL issuer Keycloak отличается внутри Docker и снаружи |
| **API-модули как отдельные Gradle-подпроекты** | Чистое разделение контрактов; сервис и клиент используют одни DTO/интерфейсы |
| **UUID v7** | Time-ordered → лучшая производительность B-tree индексов чем UUID v4 |
| **ECC P-256 для сертификатов** | Ключи меньше RSA; аппаратная поддержка в Android StrongBox, DESFire EV3 |
| **Redis-backed event store (TTL 24 ч)** | Statусы async-команд живут в Redis (`asop:event:{eventId}`), состояние переживает рестарт gateway; оффлайн-терминал успеет забрать результат в течение суток |
| **mTLS для терминалов** | Аутентификация устройств офлайн; независимость от Keycloak |
| **Chain 1 + Chain 2 в SecurityConfig** | Чистое разделение mTLS (терминалы) и JWT (веб) потоков |
| **Cert signing choreographed saga** (4 hops через Kafka) | Хореография через топики `asop.terminal.cert.{commands,issued,events}` с пробросом `X-Event-Id` через headers — нет single point of failure, каждая стадия независимо ретраится |
| **UNIQUE partial index** на `IS_CURRENT=true` | DB-уровневая защита от race condition при параллельной ротации сертификатов (`uq_tc_current_per_terminal`) |
| **Open HTTPS endpoint для cert-sign** (без JWT/mTLS) | Chicken-and-egg: первая регистрация терминала невозможна при строгой аутентификации; mTLS появляется после выпуска первого сертификата |
| **Liquibase в отдельной директории** | Централизованное управление миграциями; не встроено в JAR сервисов |

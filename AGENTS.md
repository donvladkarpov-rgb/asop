# ASOP Platform (asop-platform)

**Стек:** Kotlin 2.0.21, Spring Boot 3.3.5 (WebFlux), PostgreSQL 14 + PostGIS, Kafka 3.7.1, Redis 7 (event store), Keycloak 25.0.4, Bouncy Castle 1.78.1, Gradle 8.10.2.

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

1. **Async writes (POST/PUT/DELETE с явным контроллером)** — команда уходит в Kafka, gateway возвращает `202 Accepted` + `X-Event-Id`. Пример: `CarrierController` / `CarrierCommandService`. keycloakId передаётся в Kafka headers (`X-Keycloak-Id`). **Gateway не содержит бизнес-логики** — только авторизация/аутентификация/проксирование. Терминал сам передаёт business context (`carrierId`, `regionId`, `timezone`) в теле sync-командных запросов, gateway пробрасывает их в Kafka headers `X-Carrier-Id`/`X-Region-Id`/`X-Timezone` (Event DTOs в `asop-kafka-contracts` не меняются — контекст идёт только в headers).

2. **Sync proxy (GET + остальные запросы)** — `ProxyController` пересылает запросы в backend-сервисы через `WebClient`. Маппинг ресурсов (`users`, `carriers`, `cards`, etc.) → base URL сервиса определён в `ServiceRegistry`. Для local dev `ASOP_ENV=local` (default → `localhost`), для Docker `ASOP_ENV=docker` (→ Docker hostnames).

3. **Event tracking** — после отправки команды в Kafka `EventService` сохраняет статус `PENDING` в **Redis** (key `asop:event:{eventId}`, TTL 24 ч, реактивный `ReactiveStringRedisTemplate` + Jackson-сериализация `EventStatus`). Состояние переживает рестарт gateway. Фронт поллит `GET /api/v1/events/{eventId}`:
  - `202 Accepted` пока PENDING
  - `200 OK` с `resultData` когда COMPLETED (cert saga — JSON с PEM)
  - `422 Unprocessable Entity` с `errorMessage` когда FAILED
  - `404 Not Found` если eventId неизвестен

  Для cert-sign saga (`asop.terminal.cert.events`) Gateway-consumer обновляет EventService (`COMPLETED`/`FAILED`). Для остальных команд пока сервисы не публикуют события — статус остаётся PENDING в течение TTL 24 ч. Раньше хранилище было `ConcurrentHashMap` в памяти (TTL 30 мин, терялось при рестарте). Все `createPending`/`complete`/`fail` теперь реактивные (`Mono<Void>`); продюсеры/консьюмеры Kafka переключены на `.then()`/`.subscribe()`.

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

- `config/ServiceRegistry.kt` — маппинг `resource` → `https://service:port/api/v1/{resource}`. Включает все `/delta` ресурсы: organizer-territories → admin-service, card-mifares/card-banks/card-tariffs/blacklists/user-benefits/tariff-rates → card-service.
- `config/WebClientConfig.kt` — `WebClient` bean для proxy (SSL truststore из `/tmp/certs/truststore.p12`, hostname verification отключён)
- `controller/ProxyController.kt` — catch-all `/api/v1/{resource}/**` для GET + необработанных запросов
- `controller/EventController.kt` — `GET /api/v1/events/{eventId}`
- `controller/CertCommandController.kt` — `POST /api/v1/terminals/cert-sign` (open HTTPS, async)
- `service/CertCommandService.kt` — producer `CertSignRequested` в `asop.terminal.cert.commands`
- `kafka/CertEventConsumer.kt` — consumer `asop.terminal.cert.events` → `EventService.complete/fail`
- `kafka/CommandEventConsumer.kt` — consumer всех 7 domain event topics → `EventService.complete/fail`
- `service/EventService.kt` — Redis-backed event store (`ReactiveStringRedisTemplate`, key `asop:event:{eventId}`, TTL 24 ч, реактивные `Mono<Void>` для createPending/complete/fail). **Без `@Service`**: bean объявляется через `EventServiceConfig` (`@Bean`) в gateway-service и orchestrator-service — единственных сервисах с `spring-boot-starter-data-redis-reactive`. Иначе любой сервис, сканирующий `ru.asop`, падал бы при старте (`No qualifying bean of type ReactiveStringRedisTemplate`).
- `model/EventStatus.kt` — `EventState` (PENDING, COMPLETED, FAILED) + `EventStatus` (с `resultData: String?`)

**Terminal async command controllers** (все под mTLS `/api/v1/sync/**`):
- `controller/SessionCommandController.kt` — open/close session → Kafka
- `controller/TransactionCommandController.kt` — complete transaction → Kafka
- `controller/CardCommandController.kt` — register/block card → Kafka
- `controller/DebtCommandController.kt` — create/recover debt → Kafka
- `controller/FiscalCommandController.kt` — request fiscal receipt → Kafka
- `controller/AuditCommandController.kt` — create audit task → Kafka
- `controller/GpsCommandController.kt` — report GPS position → Kafka
- `controller/DeltaReferenceController.kt` — `POST /api/v1/sync/references/delta` + `/full` (async), `GET .../{eventId}/meta` + `.../chunks/{n}` + `.../download` (sync, Redis + MinIO-прокси)
- `service/DeltaCommandService.kt` — producer `DeltaSyncCommand`/`FullSyncCommand` в `asop.delta.commands`/`asop.delta.full.commands` (carrierId/regionId/lastVersion — из request терминала, без резолва на gateway)

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

### Module structure (28 modules in `settings.gradle.kts`)

```
:backend:shared:asop-common          # BaseEntity, DomainEvent, ErrorCode, KafkaTopic, util
:backend:shared:asop-dto             # пусто (DTO перенесены в API-модули)
:backend:shared:asop-kafka-contracts # Kafka event classes
:backend:shared:api:{domain}-api     # Controller interfaces + DTO (13 модулей, включая tid-api)
:backend:{domain}-service            # Spring Boot apps (12 сервисов)
```

TID-стек (новый `tid-api` + реализация в `carrier-service` — `TidEntity/TidRepository/TidService/TidController`): sync-CRUD для пулов TID перевозчиков; `GET /api/v1/tids?carrierId=UUID` для фильтра. Без Kafka — только R2DBC. Gateway `ServiceRegistry` маппит `tids` → carrier-service:8087 (sync-proxy).

Service → API dependency: `implementation(project(":backend:shared:api:{domain}-api"))`.
API → asop-common dependency via `api(platform(...))` pattern.

### Key services

| Path | Port | Role |
|------|------|------|
| `gateway-service` | 8080 | API Gateway: JWT + mTLS, Kafka producer, proxy для регионов/перевозчиков (GET permitAll) |
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
| `orchestrator-service` | 8094 | Delta/full-синхронизация справочников: Kafka consumer `asop.delta.commands`/`asop.delta.full.commands`, опрос мастер-сервисов `/delta`, Protobuf-чанки 50 КБ в Redis, ZIP в MinIO, purge-job (soft-delete старше 6 мес) |

### Gateway dual auth

- **Chain 1** (`@Order(1)`): mTLS for `/api/v1/terminals/**`, `/api/v1/sync/**`. Principal = `CN` from X.509 cert. Исключение: `POST /api/v1/terminals/cert-sign` → `permitAll` (open HTTPS, без JWT и mTLS — chicken-and-egg при первой регистрации терминала). **GET/PUT/POST к `/api/v1/terminals` и `/api/v1/terminals/{id}` — `authenticated()` (mTLS)**: любой анонимный доступ к списку терминалов/деталим/WRITE запрещён (устройство device-id' leaks). Только cert-sign (первичная подпись ключа) — open HTTPS.
- **Chain 2** (`@Order(2)`): JWT (Keycloak) for everything else. JWKS cached locally via кастомный `ReactiveJwtDecoder` (см. `JwtDecoderConfig`). `GET /api/v1/regions/**` и `GET /api/v1/carriers/**` — `permitAll` (терминал запрашивает справочники через mTLS-соединение, gateway не валидирует JWT для публичных GET-справочников). **`GET /api/v1/terminals/**` — НЕ permitAll** (терминалы — приватный справочник, device-id, leakage недопустим; web-admin читает через JWT, терминал через mTLS по своему id).
- **terminal-service SecurityConfig**: `permitAll` для `/api/v1/terminals/**`, БЕЗ `.oauth2ResourceServer` (terminal-service внутри Docker доверяет gateway, JWT не валидирует). defense-in-depth через gateway mTLS/JWT — терминалы достаются из внешнего мира только через gateway (chain-1 mTLS / chain-2 JWT).
- **card-service SecurityConfig**: `permitAll` (внутреннее доверие как у admin/route/user/carrier). Без этого orchestrator получал бы 401 при прямом опросе `/delta` (у него нет JWT). Внешний доступ — только через gateway (chain-2 JWT).
- `X509PrincipalExtractor` is from `org.springframework.security.web.authentication.preauth.x509`, not `web.server.authentication`. It's a synchronous interface (returns `Any`, not `Mono<Any>`).

### Frontend

- **`frontend/web-admin/`**: Vite + React + TypeScript + react-router + TanStack Query + oidc-client-ts
  - В Docker контейнере — nginx, HTTPS (порт 3443, сертификат от crypto-service). **Только HTTPS**, HTTP наружу не экспонируется.
  - В dev mode (`npm run dev`) — Vite dev server на `http://localhost:5173`, проксирует `/api` → `http://localhost:8080`.
  - **`frontend/android-terminal/`**: Android (Kotlin + Jetpack Compose + Hilt + Room + WorkManager) — приложение для терминала. mTLS auth через X.509 сертификат crypto-service.
  
  **Навигация (drawer):** `ModalNavigationDrawer` с пунктами, открывается через hamburger-иконку в TopAppBar:
  - "Сертификат" — диалог подтверждения перевыпуска → `MtlsManager.resetKeyAndCert()` + `CertificateService.provision(androidId)`.
  - "Регистрация" — **доступна всегда** (даже после успешной регистрации). Если `terminalId == null` — навигация на `provisioning` (cert-sign, далее автоматом на `registration`); если `terminalId != null` — сразу на `registration` (update существующего).
  - "Привязать перевозчика" — `AssignCarrierScreen` через `PUT /api/v1/terminals/{id}/carrier`.
  - "Загрузить справочники" — `AlertDialog` с числом строк в `reference_rows` и активных дельта-заданий; кнопки "Дельта сейчас" (`WorkScheduler.requestDeltaSync`) и "Полная выкачка" (`enqueueFullDump`).
  - Stub-пункты (placeholder, TODO, `onClick` только закрывает drawer): "Зарегистрировать карту водителя", "Открыть смену", "Закрыть смену", "Открыть рейс", "Закрыть рейс". Оставлены как «заглушки» до реализации.
  
  **Экран регистрации (обновлён):** После cert-sign пользователь выбирает регион (dropdown из `GET /api/v1/regions`), перевозчика (dropdown из `GET /api/v1/carriers?regionId=...`), часовой пояс (device default), модель (опц.), инвентарный номер (обяз.). Все поля передаются в `TerminalRegisterRequest.timezone`/`carrierId`.
  
  **Экран привязки перевозчика:** `AssignCarrierScreen` — выбор региона → выбор перевозчика → сохранение через `PUT /api/v1/terminals/{id}/carrier`.

  **Офлайн-буферизация:** Все write-команды (session open/close, transaction, card register/block, debt create/recover, fiscal receipt, audit task, GPS position) сначала сохраняются в Room (`PendingEventEntity`, статус `PENDING`). Фоновые `WorkManager` workers (`SyncWorker` каждые 15 мин, `EventPollWorker` каждые 5 мин) отправляют их на gateway через `SyncApi` (mTLS). После получения `202 + X-Event-Id` статус меняется на `SENDING`. Polling `GET /api/v1/events/{eventId}` через `EventPollWorker` отслеживает COMPLETED/FAILED.

  **Компоненты:**
  - `AppDatabase` (Room, version 3): 6 сущностей — `PendingEventEntity`, `SessionEntity`, `TransactionEntity` (write-команды) + `SyncMetaEntity`, `DeltaSyncJobEntity`, `ReferenceRowEntity` (справочники)
  - `ReferenceRowEntity` — **generic-таблица справочников** `reference_rows` (tableName, rowId, payloadJson, updatedAt, deletedAt), composite PK `(table_name, row_id)`. Вместо ~40 отдельных entities — одна таблица, JSON payload. Индексы: table_name, updated_at, deleted_at. `fallbackToDestructiveMigration()`.
  - `SyncMetaEntity` — `sync_meta` (id=0, lastVersion, lastSyncAt) — глобальный VERSION-водяной знак дельта-синка на терминале (lastVersion = обработанный `asop_delta_version_seq`).
  - `DeltaSyncJobEntity` — `delta_sync_jobs` (eventId PK, status PENDING/COMPLETED/FAILED, totalChunks, errorMessage, completedAt).
  - `SyncPreferences` (DataStore): terminalId, sessionId, lastSyncTime
  - `SyncApi` (Retrofit): 10 async endpoints под `/api/v1/sync/**` (mTLS)
  - `GatewayApi` (Retrofit): terminal CRUD + reference data (regions/carriers) + `GET /api/v1/events/{eventId}`
  - `SyncWorker`: отправка PENDING событий на gateway (15 min periodic, one-shot on network restore)
  - `EventPollWorker`: polling SENDING событий (5 min periodic, `retryCount >= 20` → FAILED)
  - `DeltaSyncWorker`: дельта-запрос справочников (1 час periodic, one-shot через `WorkScheduler.requestDeltaSync`) → `POST /api/v1/sync/references/delta` → `DeltaSyncJobEntity` PENDING
  - `DeltaChunkPollWorker`: polling COMPLETED дельта-заданий (5 min periodic), скачивает чанки `GET /api/v1/sync/references/{eventId}/chunks/{n}` (application/x-protobuf), `ReferenceSyncStore.applyChunk` — атомарный накат в `reference_rows` + обновление `sync_meta` (MAX updated_at). JOB_TTL 24ч (просроченные удаляются).
  - `FullDumpDownloadWorker`: полная выкачка (one-shot), поллит `GET /api/v1/events/{eventId}` (10с×60), качает ZIP по `s3Url` → `ZipInputStream` → `.pb` файлы → `applyFile`
  - `ReferenceSyncStore`: парсинг protobuf (`ru.asop.proto.v1.*File`), `applyChunk(DeltaChunk)` + `applyFile(fileName, byte[])`, `db.withTransaction` + sync_meta. **Protobuf НЕ lite**: `JsonFormat.printer()` + descriptor reflection (`Message`/`Descriptors`) отсутствуют в `protobuf-javalite`, поэтому в `app/build.gradle.kts` оставлены `protobuf-java` + `protobuf-java-util` (не трогать).
  - `WorkScheduler`: периодические DeltaSync 60м + DeltaChunkPoll 5м, one-shot delta, `enqueueFullDump`
  - `ReferenceSyncViewModel`: pendingDeltaCount, activeReferenceCount, `requestDeltaSync`/`requestFullSync`
  - `GpsTrackingService`: foreground service, `FusedLocationProviderClient`, 30s interval, batch threshold 10 → trigger sync
  - `NetworkMonitor`: `ConnectivityManager.NetworkCallback` → one-shot sync on network restore
  - `CertificateService`: ECC P-256 keypair generation, `POST /cert-sign`, event polling, PEM store. `terminalSerial` = `Settings.Secure.ANDROID_ID` (через `TerminalViewModel.getAndroidId(application)`). Смена ANDROID_ID = новый терминал.
  - `SyncViewModel` + обновлённый `MainScreen`: sync status card, pending badge, GPS toggle, manual sync button
  - `TerminalNavHost`: при старте, если `certificateReady && terminalId != null` → экран `main` (`loadTerminal(id)`); если только `certificateReady` → экран `registration`. Иначе — `provisioning`.
  - `RegistrationScreen`: серийный номер (`ANDROID_ID`) — read-only; пользователь вводит регион (dropdown), перевозчика (dropdown), часовой пояс (device default), модель (опц.), инвентарный номер (обяз.). После успешной регистрации `TerminalViewModel.registerTerminal` сохраняет `response.terminal.id` через `SyncPreferences.setTerminalId(...)`. В запросе передаются `carrierId`, `timezone`, `terminalId`.

  **Permissions:** `INTERNET`, `ACCESS_FINE_LOCATION`, `ACCESS_BACKGROUND_LOCATION`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE_DATA_SYNC`, `FOREGROUND_SERVICE_LOCATION`, `NFC`

  **NFC DESFire-зонды** (`nfc/DesfireCardReader.kt` + `nfc/DesfireAuthProbe.kt`, экран «Прочитать карту» в drawer):
  - Только **read-only** команды — ни одной пишущей (нет ChangeKey/CreateApplication/WriteData/FormatPICC). Не портит проверяемые карты, включая боевые с реальными ключами. Неудачная auth ничего не «сжигает» — состояние сессии исчезает при снятии карты с поля.
  - **Native опкоды DESFire** (ВАЖНО: ранее DeepSeek использовал неверные опкоды и сделал неверные выводы о карте-клоне — см. ниже): `GetVersion` 0x60, `GetFreeMemory` 0x6E, `GetApplicationIDs` 0x6A, `GetCardUID` 0x51, `SelectApplication` **0x5A** (не 0x6C = GetValue!), `GetKeySettings` **0x45** (не 0x6F = GetFileIDs!), `GetKeyVersion` 0x64, `AuthenticateAES` **0xAA** (не 0x1A = AuthenticateISO/3K3DES!), `AuthenticateISO` 0x1A, `GetCardCertificate` 0x65 (EV2/EV3), `Read_Sig` 0x3C (EV2/EV3), `GetMoreFrames` 0xAF, статус-байты 0x00=OK / 0xAF=more / 0x1C=IllegalCommandCode / 0x40=NoSuchKey / 0xAE=AuthError / 0x7E=LengthError / 0x9D=PermissionDenied/NoSuchApp / 0xC1=FileNotFound / 0x0C=NoChanges / 0x9E=OutOfEeprom / 0xFD=AuthRequired.
  - **Genuinity-эвристика** (`genuinityReasons`): оригинал NXP = vendor 0x04, subtype 0x01-0x03 (EV1/EV2/EV3), hwMajor ≤ 9, protocolCode 0x01 (ISO14443-A), на неизвестную команду (0x77) отвечает 0x1C (Illegal Command Code). **Код памяти НЕ признак клона** — `0x1A` = `2^(0x1A>>1)` = 8 КБ (корректное значение); утверждения про «64 МБ» были следствием ошибки декодера (`2^code` вместо `2^(code>>1)`). Клон-признаки: hwMajor=51, protocolCode=0x05, 0x77 → 0x7E (Length Error вместо Illegal Command Code), GetVersion(60) ≠ GetVersion(60 00) по фреймингу, hwSubtype ≠ swSubtype (EV1 HW + EV3 SW — клон склеил поля).
  - **ECC-тест** (окончательный): `GetCardCertificate (0x65) → 0x1C` = карта не EV2/EV3, ECC-ядра нет → GenerateKeyNew (0xC4 ECC P-256) и асимметричная аутентификация невозможны. `Read_Sig (0x3C) → 0x0C` подтверждает.
  - **AES-зонд**: `AuthenticateAES(0xAA, keyNo)` для keyNo 0..3. 0x1C = AES-ядра нет; 0xAE = команда известна, но auth не пройдена (ключ не того типа / не существует). Если key 0 = 3K3DES (подтверждено рукопожатием 0x1A), то 0xAE для key 0 — ожидаемо (ключ не AES-типа), не признак отсутствия AES. Для key 1..3: `0xAF + 16 байт` = **AES-ядро есть**, ключ существует (16 байт = AES-блок); step2 → 0xAE = ключ не дефолтный нулевой. **Полный handshake**: step1 `AA KeyNo → AF <16>=E_K(RndB)`, step2 `AF <32>=E_K(RndA‖RotL(RndB))`, card → `E_K(RotL(RndA))`. Использовать `unwrapFrame` (не `readResponse`!) — 0xAF здесь = «auth in progress», не «fetch more frames».
  - **Урок DeepSeek**: первая фактура «SelectApplication → 0x7E / GetKeySettings → 0x9D / AuthenticateAES → 8 байт мусора» была основана на неверных опкодах (0x6C/0x6F/0x1A вместо 0x5A/0x45/0xAA). Реальные результаты: SelectApplication → 0x00, GetKeySettings → 00 0F 01, 0x1A → 0xAF + 8 байт (корректный 3K3DES challenge, рукопожатие пройдено). Финальный вывод «карта не подходит для ECC» остаётся верным (GetCardCertificate → 0x1C), но 4 из 5 промежуточных доказательств были ошибочными. Утверждение «в карте нет AES» **также неверно** — AES-ядро есть (0xAA на key1/2/3 → 0xAF + 16 байт challenge), но ключи не дефолтные нулевые (step2 → 0xAE).
  - **Тестовая карта-клон** (куплена в интернете, UID 04421332A02290): Non-genui по NXP TagInfo, клон под EV3 8K. **3K3DES работает** (рукопожатие 0x1A проходит, key0=0x00). **AES-ядро есть** (0xAA на key1/2/3 → 0xAF + 16 байт challenge), но ключи не дефолтные (handshake step2 → 0xAE). SelectApplication/GetKeySettings/GetKeyVersion работают. ECC **нет** (GetCardCertificate → 0x1C, Read_Sig → 0x0C). Для ASOP ECC-MVP **не подходит**; для AES-CMAC MVP — **возможно**, если узнать ключи key1/2/3 (установлены производителем клона); для 3DES MVP — **работает** на key0. Риск: нестабильность клона (поле теряется при долгой сессии transceive). Рекомендация: оригинальные NXP DESFire EV3 у авторизованного дистрибьютора.

  **Sync flow:**
  ```
  Offline:  UI → Room (PendingEvent PENDING)
            GPS → Room (PendingEvent PENDING)
  Online:   NetworkCallback → SyncWorker → POST /sync/** → 202 + eventId → SENDING
            EventPollWorker → GET /events/{eventId} → 200 COMPLETED / 422 FAILED

  Delta:    DeltaSyncWorker (60m) → POST /sync/references/delta → DeltaSyncJob PENDING
            DeltaChunkPollWorker (5m) → GET .../{eventId}/meta + /chunks/{n} → applyChunk → reference_rows + sync_meta
  Full:     FullDumpDownloadWorker → GET /events/{eventId} (10с×60) → ZIP по s3Url → applyFile
  ```

- **`frontend/android-test/`**: проверочное Android-приложение (Kotlin + Compose, тот же debug-ключ), НЕ содержит mTLS/SyncApi — только сверка `reference_rows` через ContentProvider `android-terminal` (permission `ru.asop.terminal.provider.READ`). Кнопки: «Тест дельта инкремента 1/2», «Получить все данные».
  - **Ethalon JSON**: `assets/expected-1.json` (~746 КБ) — в git; `expected-2.json` (~40 МБ) и `expected-all.json` (~47 МБ) — **gitignored** (не пушить!).
  - **Регенерация ethalon** (после каждого дельта-состояния БД):
    ```bash
    # ожидаемое состояние БД после seed-data.sql + seed-data-delta-1.sql:
    docker exec -i -e PGPASSWORD=asop docker-postgres-1 bash < infrastructure/docker/generate-ethalon.sh \
      > frontend/android-test/app/src/main/assets/expected-1.json
    # после + seed-data-delta-2.sql → expected-2.json; после + seed-data-delta-3.sql → expected-all.json
    ```
  - Сборка: `cd frontend/android-test && ./gradlew :app:assembleDebug` (APK `app/build/outputs/apk/debug/app-debug.apk`). Чистый Kotlin-модуль — классы в `app/build/tmp/kotlin-classes/debug/`, директории `javac/` не будет.
- В Vite dev mode (`npm run dev`) проксирует `/api` → `http://localhost:8080` (gateway)
- `useCommand` hook — паттерн 202 + polling для команд записи
- API-клиент через axios, BASE=`/api/v1`, авторизация через Bearer token из oidc-client-ts
- Страницы: Login, Callback (OIDC), Dashboard, Users, Terminals, Cards, Carriers, TIDs, CardsDistributors, Contracts, Regions, Territories, Organizers, Routes, FareZones, TransportStops, Vehicles, Paths, Schedule
- Язык UI: русский (для переключения на английский нужен i18n — react-intl/i18next)

### Kafka topic naming

Pattern: `asop.{domain}.{commands|events}` — see `KafkaTopic` object in `asop-common`. For example: `asop.carrier.commands`, `asop.session.events`, `asop.terminal.cert.commands` (gateway→crypto), `asop.terminal.cert.issued` (crypto→terminal), `asop.terminal.cert.events` (terminal→gateway). Delta sync: `asop.delta.commands` (gateway→orchestrator, `DeltaSyncCommand`), `asop.delta.full.commands` (gateway→orchestrator, `FullSyncCommand`).

### Delta sync flow (incremental reference data)

```
Android → POST /api/v1/sync/references/delta (mTLS, body {terminalId, lastVersion}) → 202 + X-Event-Id
       ↓
Gateway DeltaReferenceController → TerminalResolver (terminalId → carrierId → regionId)
                                 → DeltaCommandService → Kafka asop.delta.commands
       ↓
orchestrator-service DeltaCommandConsumer → опрашивает мастер-сервисы GET /api/v1/{resource}/delta (keyset: versionSince)
                                         → Protobuf rows (asop-proto) → чанки 50 КБ в Redis asop:event:{id}:chunk:{n} + meta
                                         → EventService.complete(eventId, {totalChunks,totalBytes})
       ↓
Android поллит GET /api/v1/events/{eventId} → 200 → GET /api/v1/sync/references/{eventId}/meta (totalChunks)
       → GET /api/v1/sync/references/{eventId}/chunks/{n} (application/x-protobuf) → атомарный накат в Room

Full: POST /api/v1/sync/references/full → asop.delta.full.commands → orchestrator собирает ZIP .pb файлов
      → MinIO asop-sync bucket (anonymous download) → complete({s3Url}) → Android качает ZIP по s3Url.
```

**Master-service `/delta` endpoint'ы** (sync GET, поддерживают `versionSince`, `includeDeleted`, `limit`, фильтры `carrierId`/`regionId`/`userIdsIn`):
- admin-service (8091): regions, territories, organizers, organizer-territories, roles, card-types, tariff-types, session-types, event-types, transaction-types, transaction-results, services, benefits, benefit-steps (через `DeltaSupport` + `R2dbcEntityTemplate`)
- carrier-service (8087): carriers, tids, contracts, cards-distributors (`DeltaSupport`)
- route-service (8092): 13 ресурсов через `GenericRouteRepository.findDelta`
- user-service (8082): admin-users (с UNION-фильтром по user-carriers/user-regions), user-roles, user-carriers, user-regions (camelCase алиасы через DatabaseClient)
- card-service (8086): cards, card-mifares, card-banks, card-tariffs, blacklists, user-benefits, tariff-rates (фильтр `userIdsIn` через `@Query`; для mifares/banks/tariffs/blacklists — JOIN ASOP_CARDS на user_id; tariff-rates без user-фильтра)

### MinIO

- `minio` (9000 API / 9001 console) + `minio-init` (создаёт bucket `asop-sync`, `mc anonymous set download`) в docker-compose. Образы: `minio/minio:RELEASE.2024-10-13T13-34-11Z` (старый `2024-09-07` тег не существует) + `minio/mc:latest`. Healthcheck minio использует inline `mc alias set health ... && mc ready health`; minio-init ретраит коннект до 30 раз перед `mc mb`.
- Оркестратор грузит `full_{eventId}.zip` в `asop-sync`; gateway `GET /api/v1/sync/references/{eventId}/download` проксирует стрим из MinIO по `resultData.s3Url` (терминал качает ZIP через gateway mTLS, наружу MinIO не выставляется)
- Ключи: `S3_ENDPOINT`, `S3_ACCESS_KEY`, `S3_SECRET_KEY`, `S3_BUCKET` (defaults `http://minio:9000`, `asop`, `asop-secret`, `asop-sync`)

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
- **route-service columnExprs NULLIF**: Для PostGIS полей в `RouteTableRegistry.kt` используется `NULLIF(:param, '')` — фронт шлёт пустую строку вместо null, и `ST_GeomFromGeoJSON('')` падает с `unexpected end of data`. `NULLIF` превращает `''` в `NULL`, PostGIS функции это переживают.
- **provision.sh SIGTERM**: bash как PID 1 контейнера не пересылает SIGTERM дочернему процессу. Фикс: `run_jvm()` функция с `trap 'kill -TERM $child' TERM` + `wait $child` на промежуточных попытках, `exec "$@"` на финальной.
- **Keystore fallback paths**: Все `application.yml` используют `/tmp/certs/{service-name}.p12` как fallback для `KEYSTORE_PATH` (не `/certs/service.p12`). crypto-service — edge case (`./data/server.p12`).
- **admin-service/route-service mem_limit**: 256m (128m недостаточно — OOM-killer на 14 и 10 R2DBC repositories соответственно).
- **web-admin Dockerfile**: `npm ci --legacy-peer-deps` (конфликт typescript 6.x vs openapi-typescript 7.x peer dep).
- **start.ps1**: PowerShell-скрипт для wave-based запуска Docker из Windows (Git Bash не видит Docker Desktop — unix socket). `--build` обязателен после пересборки JARs.
- **CertCommandConsumer subscribe**: `.subscribe(onNext, onError)` с error handler — без него ошибка публикации в Kafka проглатывалась, терминал зависал в PENDING навсегда.
- **ASOP_CONTRACTS**: колонка `ATTRIBUTES JSONB` (nullable) — для произвольной абстрактной информации по договору. CHECK `chk_contracts_contractor` разрешает оба `carrierId`+`cardsDistributorId` = NULL, но запрещает оба NOT NULL (можно заполнить только одно поле). `ContractUpdateRequest` имеет флаги `clearCarrierId`/`clearCardsDistributorId` (Boolean) чтобы различить "не передано" (не менять) от "обнулить". Валидация BOTH-NOT-NULL дублируется в `ContractService.create/update` (IllegalArgumentException → 400) до удара по DB CHECK.
- **Carrier-service ExceptionHandler**: `@RestControllerAdvice` ловит `IllegalArgumentException`/`IllegalStateException` (валидация) и `DataIntegrityViolationException` (DB CHECK/FK нарушения) → 400.

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
| POST | `/api/v1/sync/references/delta` | Запросить дельта-синхронизацию справочников (mTLS terminal) | async |
| POST | `/api/v1/sync/references/full` | Запросить полную выгрузку справочников (mTLS terminal) | async |
| GET | `/api/v1/sync/references/{eventId}/meta` | Метаданные выгрузки (totalChunks/totalBytes/s3Url) из Redis (mTLS terminal) | sync |
| GET | `/api/v1/sync/references/{eventId}/chunks/{n}` | Protobuf-чанк из Redis `asop:event:{eventId}:chunk:{n}` (mTLS terminal) | sync |
| GET | `/api/v1/sync/references/{eventId}/download` | ZIP полной выгрузки (прокси MinIO по `s3Url`, `application/zip`) (mTLS terminal) | sync |

**Terminal async flow:** все async endpoint'ы доступны только через mTLS (chain Order 1, `/api/v1/sync/**`). Gateway возвращает 202 + X-Event-Id. Android терминал поллит `GET /api/v1/events/{eventId}` до COMPLETED/FAILED.

### User-service (порт 8082, только через gateway)
| Метод | Путь | Описание |
|-------|------|----------|
| POST | `/api/v1/users/password/change` | Смена пароля (pass-through identity) |

### Terminal-service (порт 8084, mTLS через gateway)
| Метод | Путь | Описание |
|-------|------|----------|
| POST | `/api/v1/terminals/register` | Регистрация/обновление терминала (sync upsert, `TerminalRegisterResponse { terminal, operationStatus, errorMessage? }`). `TerminalService.resolveTerminal`: если `terminalId != null` и найден — update; иначе `findByTerminalSerial` (обычный кейс после cert-saga) — update; иначе insert нового (`UuidUtils.newId()`, `status = WAREHOUSE`). `terminalSerial` = `ANDROID_ID` устройства. Сертификат при регистрации НЕ пересохраняется (он уже лежит в `ASOP_TERMINAL_CERTS` после cert-saga). Передаются `carrierId`, `timezone`. |
| GET | `/api/v1/terminals/{id}` | Получить терминал |
| PUT | `/api/v1/terminals/{id}/status` | Изменить статус терминала |
| PUT | `/api/v1/terminals/{id}/carrier` | Привязать/отвязать перевозчика (`TerminalCarrierAssignRequest { carrierId }`) |

### Carrier-service (порт 8087, через gateway sync proxy)
| Метод | Путь | Описание | Тип |
|-------|------|----------|-----|
| GET | `/api/v1/carriers?regionId=UUID` | Список перевозчиков (с фильтром по региону) | sync |
| POST | `/api/v1/carriers` | Создание перевозчика (Kafka async, `CarrierCommandService`) | async |
| GET | `/api/v1/carriers/{id}` | Получить перевозчика | sync |
| PUT | `/api/v1/carriers/{id}` | Редактировать перевозчика | sync |
| DELETE | `/api/v1/carriers/{id}` | Удалить перевозчика | sync |
| GET/POST/PUT/DELETE | `/api/v1/tids[/{id}]?carrierId=UUID` | CRUD TID (пулы), фильтр по перевозчику | sync |
| GET/POST/PUT/DELETE | `/api/v1/cards-distributors[/{id}]` | CRUD дистрибьюторов карт (R2DBC) | sync |
| GET/POST/PUT/DELETE | `/api/v1/contracts[/{id}]` | CRUD договоров (R2DBC, `ATTRIBUTES JSONB`, оба `carrierId`/`cardsDistributorId` могут быть null) | sync |

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
- **Postgres user SUPERUSER**: `infrastructure/docker/postgres-superuser.sql` монтируется в `/docker-entrypoint-initdb.d/01-superuser.sql` — поднимает `asop` до SUPERUSER при первом старте (пустой volume). Нужен PurgeJob orchestrator'а (`SET session_replication_role = 'replica'` для физического удаления soft-deleted строк). Проверка: `SELECT rolsuper FROM pg_roles WHERE rolname='asop';` → `t`. `ALTER USER ... WITH SUPERUSER` внутри v001-init.sql НЕ сработает (Liquibase подключается как asop).
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

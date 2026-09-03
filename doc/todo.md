# TODO — ASOP Platform

_См. также `infrastructure/docker/todo.md` — задачи по Docker инфраструктуре._

## 1. Certificate Architecture ✅

Всё реализовано: crypto-service — единый CA, сертификаты выпускаются автоматически
через `certs-init` и `provision.sh`. Root CA + Intermediate CA в PKCS#12.

- [x] **Cert signing saga (4 hops)** — реализована через Kafka:
      `Gateway → asop.terminal.cert.commands → crypto-service → asop.terminal.cert.issued → terminal-service → asop.terminal.cert.events → Gateway EventService`
- [x] **`ASOP_TERMINAL_CERTS` таблица** — DDL влит в v001-init.sql (ранее v002), UNIQUE partial index `uq_tc_current_per_terminal`
- [x] **`UNIQUE` constraint на `ASOP_TERMINALS.TERMINAL_SERIAL`** — защита от дублей терминалов
- [x] **Android rewrite** — `CertificateService.kt` теперь идёт через Gateway + polling `GET /api/v1/events/{eventId}`
- [x] **CertCommandConsumer error handlers** — `.subscribe(onNext, onError)` с логированием ошибок публикации в Kafka

Детали по компрометации ключей — см. `infrastructure/docker/todo.md`.

## 2. Android Terminal (Offline Buffering) ✅

- [x] **Room DB**: `AppDatabase` с 3 сущностями (`PendingEventEntity`, `SessionEntity`, `TransactionEntity`) + 3 DAO
- [x] **SyncPreferences**: DataStore для terminalId, sessionId, lastSyncTime
- [x] **SyncApi**: 10 Retrofit endpoint'ов под `/api/v1/sync/**` (mTLS)
- [x] **GatewayApi**: terminal CRUD + `GET /api/v1/events/{eventId}`
- [x] **SyncWorker**: отправка PENDING событий на gateway (15 min periodic, one-shot on network restore)
- [x] **EventPollWorker**: polling SENDING событий (5 min periodic, `retryCount >= 20` → FAILED)
- [x] **GpsTrackingService**: foreground service, FusedLocationProviderClient, 30s interval, batch threshold 10
- [x] **NetworkMonitor**: ConnectivityManager.NetworkCallback → one-shot sync on reconnect
- [x] **Hilt-Work**: AsopTerminalApp implements Configuration.Provider
- [x] **SyncViewModel + MainScreen**: sync status card, pending badge, GPS toggle, manual sync button
- [x] **Все DTO выровнены с backend API контрактами** (AcceptedResponse, SessionOpenRequest, TransactionCompleteRequest, CardRegisterRequest, CardBlockRequest, DebtCreateRequest, FiscalReceiptRequest, AuditTaskCreateRequest, GpsPositionReport)

## 3. Integration Wiring ✅

- [x] **CommandEventConsumer** в gateway-service — слушает все 7 domain event topics, извлекает X-Event-Id, вызывает EventService.complete/fail
- [x] **application.yml** gateway — consumer config + все 7 event topic properties
- [x] **EventPollWorker** — обработка 404 (→ FAILED), MAX_POLL_RETRIES=20
- [x] **GpsTrackingService** — триггерит one-shot sync при batch threshold
- [x] **WorkScheduler** — schedulePeriodicSync вызывается из AsopTerminalApp.onCreate
- [x] **doc/smoke-tests.md** — 7 end-to-end сценариев с HTTP-трассировкой
- [x] **EventService → Redis** — `ReactiveStringRedisTemplate`, TTL 24 ч (было in-memory ConcurrentHashMap, 30 мин). Docker-service `redis:7-alpine` добавлен в `docker-compose.yml`. Gateway `depends_on.redis: service_healthy`, env `REDIS_HOST=redis`.
- [x] **Terminal registration upsert** (`POST /api/v1/terminals/register`, terminal-service) — `TerminalService.resolveTerminal`: findById(terminalId) → findByTerminalSerial → insert new. `TerminalRegisterResponse { terminal, operationStatus, errorMessage? }`.
- [x] **Android registration flow** — serial=read-only `ANDROID_ID` (`Settings.Secure.ANDROID_ID`); пользователь вводит только модель (опц.) и инвентарный номер (обязательно). После регистрации `TerminalViewModel.registerTerminal` сберегает `response.terminal.id` через `SyncPreferences.setTerminalId`. `TerminalNavHost` skip registration если `terminalId != null` && certReady.
- [x] **Schema fixes** — `ASOP_TERMINALS`: `TERMINAL_NUMBER` теперь nullable (инвентарный вводится вручную), добавлены `CREATED_AT`/`UPDATED_AT TIMESTAMPTZ DEFAULT now()`.

## 3.1. TIDs + Android drawer (sync-proxy справочники) ✅

- [x] **TID-стек (backend)** — новый модуль `:backend:shared:api:tid-api` (DTO `TidCreateRequest/TidUpdateRequest/TidResponse`, интерфейс `TidApi` с `listTids(@RequestParam carrierId)`). Реализация в `carrier-service`: `TidEntity/TidRepository/TidService/TidController` (sync-CRUD, R2DBC, `R2dbcEntityTemplate.insert()` для новых). Gateway `ServiceRegistry` маппит `tids` → carrier-service:8087 (sync-proxy через `ProxyController`, без Kafka).
- [x] **TIDs в web-admin** — `src/pages/Tids.tsx` (sync `useMutation`, не async), `src/api/tids.ts`, type `Tid` в `types/reference.ts`. Sidebar: пункт "TID (пулы)" в `contractorItems` рядом с "Перевозчики". Filter dropdown по перевозчику. Форма создания/редактирования inline card (carrierId обязателен, tidValue обязательно, опционально terminalId для привязки).
- [x] **Android drawer** — `ModalNavigationDrawer` (hamburger-иконка в TopAppBar). Пункты: "Сертификат" (диалог перевыпуска → `MtlsManager.resetKeyAndCert()` + `provision(androidId)`), "Регистрация", "Привязать перевозчика". `TerminalNavHost` обёрнут в ModalNavigationDrawer + Scaffold, новый route `assign-carrier`.
- [x] **`MtlsManager.resetKeyAndCert()`** — чистит alias AndroidKeyStore + SharedPreferences `asop_terminal_cert`. Нужен для принудительного перевыпуска.
- [x] **`PUT /api/v1/terminals/{id}/carrier`** — `TerminalCarrierAssignRequest { carrierId: UUID? }` (null = отвязать), `TerminalService.assignCarrier(id, carrierId)`. Реализован по варианту 1 из промпта (отдельный endpoint, не `register`).
- [x] **Timezone в DTO** — `TerminalRegisterRequest.timezone: String?` и `TerminalResponse.timezone: String?`. `TerminalService.register` прокидывает в `entity.copy(timezone = request.timezone ?: entity.timezone)` и в insert нового. Android DTO зеркально.
- [x] **Carrier filter по regionId** — `CarrierApi.listCarriers(@RequestParam regionId: UUID?)`, `CarrierRepository.findByRegionId(regionId)`, `CarrierService.findAll(regionId)`.
- [x] **Gateway SecurityConfig** — `GET /api/v1/regions/**` и `GET /api/v1/carriers/**` → `permitAll` (терминал под mTLS читает справочники, **sync-proxy через ProxyController**, без Kafka/Redis — EventService зарезервирован для async write-команд).
- [x] **Android registration UI** — `RegistrationScreen`: dropdown регион → dropdown перевозчик (фильтр по regionId, доступен только после выбора региона) → read-only timezone (device default, `TimeZone.getDefault().id`) → модель (опц.) → инвентарный номер (обязательно). Кнопка дизейблится пока не выбраны region/carrier/inventory.
- [x] **`AssignCarrierScreen`** — dropdown регион → dropdown перевозчик (filter по regionId) → "Сохранить" → `PUT /api/v1/terminals/{id}/carrier`. Текущий carrier пред-выбран. Отображает timezone устройства (read-only) — передаётся на сервер при регистрации, но при assignCarrier не требуется.
- [x] **Reference data на Android** — `GatewayApi` дополнен `@GET listRegions()`, `@GET listCarriers(@Query("regionId") regionId: String?)`, `@PUT assignCarrier(...)`. Новые Moshi-модели `RegionResponse`/`CarrierResponse` в `network/models/ReferenceModels.kt`. `TerminalViewModel.loadReferenceData()` / `loadCarriersForRegion(regionId)` для подгрузки справочников.

## 4. Frontend: Admin UI

- [ ] **Страницы**:
  - [ ] `SessionsPage` — список смен
  - [ ] `CarrierDetailPage` — просмотр/редактирование перевозчика
  - [ ] `UserDetailPage` — просмотр пользователя

- [x] **Дистрибьюторы карт** (`CardsDistributorsPage`) — полный CRUD + выбиралка договоров ✅
- [x] **Договоры** (`ContractsPage`) — полный CRUD, форма с валидацией XOR (carrierId | cardsDistributorId), `ATTRIBUTES JSONB` ✅
- [x] **Перевозчики** (`CarriersPage`) — список + редактирование (создание через async Kafka) ✅
- [x] **3 пункта в Sidebar** — Перевозчики, Дистрибьюторы карт, Договоры (отдельными пунктами) ✅

- [ ] **CRUD формы**:
  - [ ] Создание пользователя
  - [ ] Редактирование терминала
  - [ ] Выпуск карты

- [x] **Смена пароля**:
  - [x] Форма смены пароля в профиле
  - [x] `POST /api/v1/users/password/change`

## 5. Keycloak 25 Совместимость

- [ ] **Prod: отключить Direct Access Grant**, оставить только OIDC Auth Code + PKCE
  - Сейчас в MVP используется Direct Access Grant для разработки
  - В production все клиенты должны идти через Authorization Code Flow

- [x] **Bootstrap фикс**:
  - Inline credentials в `UserRepresentation`
  - firstName + lastName обязательны
  - realm с полными настройками
  - ✅ Исправлено

## 6. Shared UserResolver

- [ ] **Создать shared UserResolver в `asop-common`**
  - Интерфейс: `fun resolveUserId(keycloakId: String): Mono<UUID>`
  - Возвращает `userId` (UUID) по keycloakId
  - Кеширование результата (Caffeine)
  - Используется всеми сервисами для resolve keycloakId → userId

## 7. JWT decoder для всех сервисов

- [ ] **Добавить JwtDecoderConfig или permitAll в сервисы:**
  - [ ] carrier-service
  - [x] terminal-service (SecurityConfig использует JWT ресурс-сервер, клиенты mTLS идут через Gateway)
  - [ ] session-service
  - [ ] card-service
  - [ ] debt-service
  - [ ] audit-service
  - [ ] fiscal-service

  Каждый сервис должен иметь либо `JwtDecoderConfig.kt` (как в gateway), либо `SecurityConfig` с `permitAll` (как в user-service).

## 8. Liquibase / Миграции

- [x] **user-service**:
  - Решено: Liquibase запускается отдельным контейнером, сервисы не содержат DataSource.
  - ✅ Не актуально

- [ ] **Другие сервисы** — включить Liquibase после добавления changelogs:
  - [ ] carrier-service
  - [ ] terminal-service
  - [ ] session-service
  - [ ] card-service
  - [ ] debt-service
  - [ ] audit-service
  - [ ] fiscal-service

## 9. PKI / Сертификаты

- [x] **DN encoding bug** — `X500Name(name)` в crypto-service переупорядочивает компоненты DN, PKIX chain validation падает. Фикс: `X500Name.getInstance(ASN1Sequence.getInstance(encoded))`.
- [x] **SAN in certs** — provision.sh всегда передаёт `dnsNames` в JSON при запросе сертификата.
- [ ] **Production SSL**: отключить `defaultConfiguration(NONE)` в gateway WebClient и включить hostname verification.
- [ ] **Production: выпускать сертификаты с SAN из provision.sh** — сейчас gateway работает с отключенной проверкой.

## 10. i18n / Многоязычность

- [ ] **Добавить i18n (react-intl или i18next)**
  - [ ] Создать файлы переводов: ru.json, en.json
  - [ ] Заменить все строки на вызовы `intl.formatMessage()`
  - [ ] Добавить переключатель языка в сайдбар/хедер
  - [ ] Перевести на английский (en.json)
  - [ ] Язык по умолчанию: русский
  - [ ] Сохранять выбор языка в localStorage

## 11. Прочее

- [x] **Dockerfile bug** ✅
- [x] **Gateway SSL всегда включён** ✅ (SSL_ENABLED больше не используется)
- [x] **cert-managed crypto-service** ✅ (через provision.sh entrypoint)
- [x] **route-service добавлен** ✅ (порт 8092, 10 контроллеров, GenericRouteRepository)
- [x] **vehicles перенесены в route-service** ✅ (из carrier-service)
- [x] **provision.sh SIGTERM fix** ✅ (trap handler для graceful shutdown)
- [x] **start.ps1** ✅ (PowerShell wave-based запуск для Windows)
- [x] **3 бага route-service/crypto-service** ✅ (created_at, subscribe error handler, UUID validation + ExceptionHandler)
- [x] **Keystore fallback paths** ✅ (унифицированы во всех application.yml)
- [ ] **Ключи/сертификаты: план на production**
  - Сейчас в dev: `docker compose down -v` → fresh CA + certs каждый раз
  - В production: ключи будут в HSM / Kubernetes Secrets, не в volumes
  - Нужно разработать `recover-certs.sh` для перевыпуска всех сертификатов
  - Детали: `infrastructure/docker/todo.md`
- [ ] **Скрипты тестовых данных** — `infrastructure/docker/todo.md`

## 12. Delta Sync (инкрементальная дельта-синхронизация) ✅

- [x] **Schema migration** — soft-delete: `UPDATED_AT NOT NULL DEFAULT NOW()` + `DELETED_AT TIMESTAMPTZ` + B-tree индексы на ~42 таблицах через DO-блоки в `v001-init.sql` (свежая миграция с нуля, старая удалена). `ALTER COLUMN ... SET DEFAULT NOW()` синхронизирован с `asop_schema.sql`.
- [x] **VERSION cursor** — глобальный sequence `asop_delta_version_seq` + `trg_fn_delta_version()` (INSERT/UPDATE/DELETE → `VERSION = nextval(...)`). Все 42 справочные таблицы имеют `VERSION BIGINT`. **Дельта-курсор — VERSION, а не UPDATED_AT** (монотонный глобальный sequence, не зависит от таймзоны).
- [x] **Keyset pagination** — мастера сортируют `ORDER BY VERSION ASC`, фильтр `VERSION > versionSince`; оркестратор переспрашивает страницы (`versionSince` = последний VERSION) до пустой.
- [x] **BEFORE DELETE триггеры** — generic `trg_fn_soft_delete()` (single-PK) + `trg_fn_soft_delete_2col()` (composite-PK) + `trg_fn_touch_updated()` для AUTO UPDATED_AT на UPDATE.
- [x] **Postgres SUPERUSER** — `infrastructure/docker/postgres-superuser.sql` монтируется в `/docker-entrypoint-initdb.d/01-superuser.sql`; `asop` = SUPERUSER (нужен для `SET session_replication_role='replica'` в PurgeJob).
- [x] **Новый модуль `:backend:shared:asop-proto`** — `schema.proto` с ~41 row messages + `DeltaChunk` + `XxxFile` messages. `protobuf-gradle-plugin:0.9.4` + `protobuf-java:3.25.5`.
- [x] **Новый модуль `:backend:orchestrator-service`** (порт 8094) — Spring Boot 3.3.5 WebFlux, Kafka consumer `asop.delta.commands`/`asop.delta.full.commands`, WebClient к мастер-сервисам, Redis chunk storage, S3 SDK (MinIO), PurgeJob (hourly, `session_replication_role='replica'`), `EventServiceConfig` (shared `EventService` без `@Service`). `mem_limit: 1g` (512m OOM).
- [x] **Master-service /delta endpoints** — все ~42 таблицы имеют `@GetMapping("/delta")` с query-параметрами `versionSince`/`includeDeleted`/`limit`/`carrierId`/`regionId`/`userIdsIn`. admin-service (14), carrier-service (4), route-service (13), user-service (4 — UNION user_carriers ∪ user_regions + camelCase алиасы), card-service (7 — JOIN ASOP_CARDS для userIdsIn, tariff-rates без user-фильтра → в GLOBAL_TABLES).
- [x] **userIdsIn батчинг** — URL ограничен ~4 КБ (Reactor Netty `max-initial-line-length`); оркестратор шлёт userIds батчами по 80 (`USER_IDS_BATCH`) в DeltaSyncService и FullSyncService.
- [x] **WebClient URIs** — scheme/host/port из `MasterEndpoint` (раньше относительные path → `localhost:80`).
- [x] **ChunkingService O(n²) fix** — инкрементальный `bufferBytes` (serializedSize + 16 на строку) вместо ре-сериализации буфера на каждой добавке.
- [x] **Orchestrator Redis host** — явный `@Primary reactiveRedisConnectionFactory()` из `REDIS_HOST/REDIS_PORT` (Boot 3.x игнорирует `spring.redis.*`).
- [x] **EventService → asop-common** — перенесён из gateway в `ru.asop.common.event.EventService/EventStatus/EventState` (без `@Service`, bean через `EventServiceConfig` в gateway + orchestrator).
- [x] **Gateway DeltaReferenceController** — POST /sync/references/delta|full (async, mTLS), GET .../{eventId}/meta|chunks/{n}|download (sync, Redis + MinIO-прокси). DeltaCommandService producer. TerminalResolver (terminalId → carrierId → regionId).
- [x] **Kafka topics** — `asop.delta.commands` + `asop.delta.full.commands` добавлены в `KafkaTopic.kt`. DTOs `DeltaSyncCommand`/`FullSyncCommand` в `asop-kafka-contracts`.
- [x] **MinIO infrastructure** — `minio` (9000/9001) + `minio-init` (bucket `asop-sync`, anonymous download) в docker-compose. Образы: `minio/minio:RELEASE.2024-10-13T13-34-11Z` + `minio/mc:latest`.
- [x] **Spring Boot compression** — `server.compression.enabled=true` на gateway (мими `application/x-protobuf`).
- [x] **Android** — Protobuf (`protobuf-java` + `protobuf-javalite` NOT used — `JsonFormat.printer()` reflection нужен). Room version 6, 8 entities (+ TerminalKeyEntity, TripPaymentEntity). `ReferenceSyncStore` (generic-накат через descriptor reflection). `DeltaSyncWorker` (60м periodic), `DeltaChunkPollWorker` (5м periodic), `FullDumpDownloadWorker` (one-shot). Drawer "Загрузить справочники" (AlertDialog with "Дельта сейчас" + "Полная выкачка").
- [x] **ContentProvider** — `ru.asop.terminal.provider` экспортирует `reference_rows`/`sync_meta` (permission `ru.asop.terminal.provider.READ`) для сверки из `android-test`.
- [x] **Seed-скрипты** — `seed-data-delta-1/2/3.sql` (bulk данные для трёх дельта-состояний) + `generate-ethalon.sh` (генерация ethalon JSON из Postgres, зеркалит логику мастеров /delta).
- [x] **android-test** — отдельное приложение (Kotlin + Compose), проверка синка через ContentProvider. `assets/expected-1.json` в git (~746 КБ); `expected-2.json` (~40 МБ) и `expected-all.json` (~47 МБ) gitignored (регенерируются `generate-ethalon.sh`).
- [x] **E2E validation** — реальный дельта-синк через Kafka: 627 чанков + meta в Redis, событие COMPLETED. Все 42 таблицы сверены с ethalon (canonicalized).
- [x] **Build verification** — `./gradlew build -x test` SUCCESS, `:app:assembleDebug` SUCCESS (android-terminal + android-test), `tsc -b` 0 ошибок. Docker: 20 контейнеров Up, `rolsuper=t`, soft-delete trigger works, orchestrator 42 tables, Kafka consumers assigned. seed-data.sql INSERTs OK.

## 13. GPS-трекинг, live-карта и пассажирское приложение ✅

Полный контур: терминал (mock/Fused) → gateway → Kafka → session-service → `ASOP_GPS_TRACKING` → snap-to-route → web-admin LiveMap `/live-map` + пассажирское Android-приложение (osmdroid). Подробно — `doc/gps.md`.

- [x] **session-service: приём GPS** — `GpsCommandConsumer` (группа `session-service-v5`): экспоненциальное сглаживание (`GpsPositionFilter`, alpha 0.6, per-terminal, `GpsPositionFilterRegistry` + cleanup 30 мин), watermark-ordering (`X-Terminal-Seq`: APPLIED/ALREADY_APPLIED/DEFERRED), INSERT `ASOP_GPS_TRACKING` (`ST_GeogFromText`, `STATUS='MOVING'`, `SESSION_ID=shift.id`), `CommandResult` → `asop.gps.events`.
- [x] **session-service: snap-to-route** — `gps/GpsRouteSnapper.kt`: `snap(pathId, lat, lon, maxDistanceMeters=300)`, кэш геометрии `ROUTE_OBJECT` TTL 5 мин, equirectangular-проекция + haversine.
- [x] **session-service: Live-API** — `TrackingController`/`TrackingService`: `GET /api/v1/tracking/live?regionId&carrierId&vehicleId&freshSec` (DISTINCT ON, JOIN vehicles/paths/vehicle-types, snapped поля) + `GET /api/v1/tracking/vehicle/{id}/track?minutes`. SecurityConfig `permitAll` на `/tracking/**`.
- [x] **gateway: публичный контур** — `ApiKeyHmacFilter` (X-API-Key + HMAC-SHA256 + rate-limit 60/мин), `PublicProxyController` (`/api/v1/public/**`: tracking→session, stops→route), `ServiceRegistry` `tracking`→session-service:8085, `stops`→route-service:8092, новая chain `@Order(0)` `/api/v1/public/**`.
- [x] **web-admin: LiveMap** — `LiveMapPage.tsx` (Leaflet, OSM, polling 3 c freshSec=120, CSS-интерполяция 4.5 c, цвет по типу ТС, фильтр raw/snapped, поиск, follow-vehicle). Sidebar «Мониторинг»/«Карта ТС».
- [x] **web-admin: RouteEditor** — `RouteEditorPage.tsx` (`RouteDrawer`: клик=вершина, Enter/«Готово» завершает, dblclick убран; `densifyPolyline` 40 м; `save()` пишет `ROUTE_OBJECT` GeoJSON `[lon,lat]`). Sidebar «Маршруты и Пути»/«Редактор маршрута».
- [x] **пассажирское приложение** — `frontend/passenger-app` (`ru.asop.passenger`, osmdroid + Hilt + Moshi + Retrofit): `MapScreen` (MAPNIK, скрывает ТС без снапа, интерполяция 4.5 c, трек, остановки/маршруты), `PassengerViewModel` (polling 3 c), `HmacInterceptor` (API-ключ + HMAC). Base URL `https://192.168.1.6:8080` (из `local.properties` `gateway.host`).
- [x] **route-service: публичные остановки** — `StopsBboxController` (`/api/v1/stops/bbox`, `ST_Centroid(ZONE_POLYGON)`), `StopRoutesController` (`/api/v1/stops/{id}/routes`).
- [x] **терминал: mock GPS** — `MockRoutePlayer` (`ROUTE_FILE="mock_route_301.json"`, 356 точек, `% points.size` цикл), `assets/mock_route_301.json`, `isDebugGps` (dev default true). GpsTrackingService `LOCATION_INTERVAL_MS=5_000`/fastest 3 c, требует открытый SHIFT+TRIP с ТС/путём, `SESSION_ID=shift.id`, batch≥10.
- [x] **DB/seed** — `ASOP_GPS_TRACKING` (GEOGRAPHY, индексы vehicle_time + GIST, партиционирование по RECORDED_AT); геометрия маршрута 301 (`...006700`, 356 точек) в `seed-data.sql` (`UPDATE ASOP_PATHS SET ROUTE_OBJECT`).
- [x] **E2E проверка** — on F20 debug-мок движется по 301 за ~5 с; `tracking/live` возвращает «ММ100777» с snap `(44.9441, 34.1255)`; web-admin LiveMap и пассажирское приложение показывают ТС по обеим точкам входа (count: 1, snapped).
- [x] **Баг-фикс live-API (live-карта пустая)** — `GpsRouteSnapper.snap()` использует `mapNotNull` и возвращал `Mono.empty()` при отсутствии геометрии пути/точки >300 м; `TrackingService.getLiveVehicles` через `flatMap` **терял ТС из ответа** (live API возвращал `[]` для путей без `ROUTE_OBJECT`). Фикс: `.defaultIfEmpty(dto(null))` после `findSnapped` — ТС всегда присутствует с raw-координатами, `snapped` null при отсутствии снапа. Также заполнена геометрия обратного пути `...6800` (реверс прямого `...6700`, 356 точек) — пассажирское приложение (фильтр `snapped != null`) показывает ТС по обеим точкам входа.

## 14. Оставшиеся GPS-доработки (планы)

- [ ] **ETA / прогноз прибытия** (движок светки расписания с движением — отдельная фаза, +3–5 дн.).
- [ ] **Push-уведомления** о приближении ТС.
- [ ] **Heading/bearing** — направление по двум последним точкам (+0,25 дн.).
- [ ] **Retention / партиционирование** `ASOP_GPS_TRACKING` по месяцам.
- [ ] **Self-hosted тайл-сервер + офлайн-кэш osmdroid** для продакшена (см. `doc/gps_maps.md`).

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

## 4. Frontend: Admin UI

- [ ] **Страницы**:
  - [ ] `CarriersPage` — список перевозчиков
  - [ ] `SessionsPage` — список смен
  - [ ] `CarrierDetailPage` — просмотр/редактирование перевозчика
  - [ ] `UserDetailPage` — просмотр пользователя

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

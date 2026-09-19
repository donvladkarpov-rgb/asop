# Промпт 016: Банковский эквайринг ВТБ — оплата топ-апа MIFARE и проезда банковской картой

## 1. Контекст

В АСОП **нет банковского эквайринга**. Транспортные карты (MIFARE Classic VCM1 / DESFire) работают на
остатке поездок на карте (`tripsLeft`), пополнение уже есть, но **бесплатное** (`TopUpViewModel`: оператор
авторизуется картой-ключом, пишет N поездок, шлёт транзакцию `0802` с `amount=0.0`).

Нужно: пассажир/дистрибьютор пополняет поездки и **платит банковской картой** (эквайер — **ВТБ**, платёжная
система **МИР**, в перспективе СБП), плюс в перспективе — оплата проезда банковской картой прямо на
терминале. Деньги на стороне АСОП физически проводит **сервер**, а не терминал.

В схеме уже есть задел (не используется): `ASOP_CARD_BANKS` (`PAN_TOKEN`/`PAN_LAST4`/`BIN`/`IS_TOKENIZED`),
`ASOP_TRANSACTIONS` (`AMOUNT`/`CURRENCY`/`ACQUIRER_REFERENCE`/`ERROR_CODE`/`ERROR_MESSAGE`),
`ASOP_TRANSACTION_CARDS.CARD_ROLE ('PAYER','REFUND','BENEFIT','GUEST')`, `ASOP_TARIFF_RATES.price`,
`ASOP_CARD_TARIFFS`, `ASOP_CARD_DEBTS`+`ASOP_DEBT_RECOVERY_ATTEMPTS`, `ASOP_BLACKLISTS`
(`BLOCK_TYPE`, `RELATED_DEBT_ID`, `AUTO_UNBLOCK_ON_RECOVERY`).

Легаси-контур (`doc/soft/asop-processing-Release-1.6`, `doc/bank_card.md`) физически **серверный** эквайринг:
`Multicard/SIRPOS` по **raw TCP** на `82.194.226.163:34082`, закрытая библиотека `com.transcard:multicard-client:1.0.3`
(в репозитории нет), RSA-шифрование данных карты, очереди reauth/cancel/refund, `tk_stop_list`, `blocked_tid_records`.
Обёртка на устройстве (`doc/soft/feitian-hardware-master`) — FTSDK/EMV, но `onOnlineProcess` **всегда отвечает
эмитенту `"00"`** (офлайн-одобрение) — для реальной оплаты не годится.

**Целевое устройство:** Feitian F20 (PCI PTS 5.1, EMVCo L1/L2), плюс обычные Android-телефоны (дистрибьютор).

---

## 2. Архитектурные решения

### Q1. Эквайринг — на сервере, отдельный сервис `payment-service`
Терминал/приложение не авторизуют у ВТБ сами; финансовый контур и учёт — в новом backend-сервисе.
Даёт: единый учёт, сверку, возвраты, фискализацию, минимизацию PCI-скоупа на устройстве.

### Q2. Эквайер ВТБ (МИР; СБП позже), без банковского коннектора на старте
Легаси-интерфейс — прямой TCP к SIRPOS. `multicard-client` недоступен, новый интерфейс ВТБ — TBD.
Закладываем **абстракцию `AcquirerGateway`**, чтобы:
- сейчас адаптер жил внутри `payment-service` (если сеть до SIRPOS есть);
- позже можно было **без переделок** добавить bank-side connector (`vtb-acquirer-connector` внутри периметра ВТБ),
  если доступ к SIRPOS только изнутри банка.
- **До получения интерфейса ВТБ работает `MockAcquirerAdapter`** (`asop.acquirer.provider=mock`, см. 3.1.9) —
  весь контур (app-payment → payment-service → долги/блэклист) тестируется без сети банка.

### Q3. Три Android-приложения, отдельные ключи подписи
- `android-terminal` (`ru.asop.terminal`) — существующее.
- `android-payment` (`ru.asop.payment`) — **новое**, EMV/эквайринг, **отдельный signing key** (PCI-изоляция).
- `android-distributor` (`ru.asop.distributor`) — **новое**, топ-ап, отдельный signing key.

### Q4. Устройства и способы оплаты
- Дистрибьютор: **F20** (банк через app-payment + наличные) и **обычный телефон** (NFC MIFARE + наличные).
- Топ-ап у дистрибьютора — **online**; тап банком на терминале как оплата проезда — **может быть offline**
  (offline floor limit → долг → блэклист при превышении порога суммы).

### Q5. Локальный межприложный канал — HTTP на 127.0.0.1
app-payment поднимает локальный сервер, клиенты — терминал и дистрибьютор. Авторизация запросов —
**HMAC общим pairing-секретом** (по образцу `ApiKeyHmacFilter`: `X-Request-Id`/`X-Timestamp`/`X-Signature`).
**Важно:** HTTP сам по себе не решает арбитраж NFC — для банковской карты нужен handoff (Q8).

### Q6. app-payment сам репортит платёж на сервер, offline-capable
Своя mTLS-идентичность (provisioning по образцу cert-sign saga) + локальная очередь отчётов
(паттерн `PendingEventEntity`+`SyncWorker`) — интернет не обязателен в момент оплаты.

### Q7. Чёрный список — единая `ASOP_BLACKLISTS` для ВСЕХ карт
- **Владелец — сервер**; физический эквайринг на сервере; раздача на все терминалы дельтами/полной выкачкой
  (существующий пайплайн `asop_blacklists` переиспользуется, он и был для всех карт).
- Матчинг банковской карты — **HMAC(PAN)**.
- Триггер — **сумма порога** долга.
- Авто-снятие — при полном погашении (`AUTO_UNBLOCK_ON_RECOVERY`, soft-delete → tombstone в дельту).
- Легаси-аналог: `tk_stop_list` («снимается после успешной до-авторизации, коды 76/95/82»).

### Q8. NFC: терминал — арбитр, платёжка — владелец по handoff (см. §3.6)

### Q9. Ценообразование — существующие `tariff-types`/`tariff-rates`/`fare-zones`/`card-tariffs`
Новая денежная модель не вводим; цена 1 поездки для топ-апа и тариф проезда банком берутся из справочников.

### Q10. Фискализация банковских операций — через `fiscal-service` (чек на топ-ап и на проезд).

---

## 3. Функциональные требования

### 3.1 Backend: `payment-service`

#### 3.1.1 Модули
- `:backend:payment-service` (+ запись в `settings.gradle.kts`).
- `:backend:shared:api:payment-api` — интерфейсы + DTO (только контракты).
- Gateway: producer команд и consumer событий `payment`, `ServiceRegistry: payments` → `payment-service`.

#### 3.1.2 Kafka
- `asop.payment.commands` (gateway→payment-service), `asop.payment.events` (payment-service→gateway).
- События: `PaymentAuthorizeRequested`, `PaymentAuthorized`, `PaymentFailed`, `PaymentCancelRequested`,
  `PaymentRefundRequested`, `DebtRecoveryCompleted`.
- Gateway `CommandEventConsumer` — добавить topic в список → `EventService.complete/fail`.

#### 3.1.3 Таблицы (Liquibase, единственный changeset `v001-init`, правки в `asop_schema.sql` + `v001-init.sql`)
- `ASOP_PAYMENTS` (новое): `PAYMENT_ID UUIDv7`, `CARD_ID` (→`ASOP_CARDS`, банковская карта),
  `AMOUNT`, `CURRENCY`, `PAYMENT_TYPE` (`TOPUP`/`FARE`/`DEBT_RECOVERY`), `STATUS`,
  `ACQUIRER_REFERENCE`, `RRN`, `AUTH_CODE`, `TERMINAL_ID`, `SESSION_ID`, `TRANSACTION_ID`, timestamps, `VERSION`.
- `ASOP_PAYMENT_ATTEMPTS` (новое): по образцу `bill_bank_operations_attempts` — `ATTEMPT_NUMBER`,
  `STATUS` (`PENDING`/`SUCCESS`/`FAILED`/`TIMEOUT`/`REJECTED`), `ERROR_CODE`, `ERROR_MESSAGE`,
  `BANK_RESPONSE JSONB`, `NEXT_RETRY_AT`, `DURATION_MS`.
- Переиспользуем: `ASOP_CARD_BANKS`, `ASOP_TRANSACTIONS`, `ASOP_CARD_DEBTS`,
  `ASOP_DEBT_RECOVERY_ATTEMPTS`, `ASOP_BLACKLISTS`.
- Триггеры версионирования/soft-delete/`UPDATED_AT` — по существующим паттернам
  (`trg_fn_delta_version`, `trg_fn_soft_delete`, `trg_fn_touch_updated`).

#### 3.1.4 `AcquirerGateway` + `VtbSirposAdapter`
- Интерфейс: `authorize`, `reauthorize` (reAuth), `cancel`, `refund`, `status`, `pushStopList`.
- `VtbSirposAdapter` — первая реализация. **TBD:** транспорт/протокол ВТБ (легаси — raw TCP/TLV),
  доступность библиотеки, TID-база, Vault-ключ `multicard_rsa`.
- Коды/поведение из легаси: `allowReAuthErrCodes=[76,95,82]`, `allowReDefferErrCodes=[74,811]`,
  retry/timeout-очереди, блокировка TID (`blocked_tid_records`).
- Идемпотентность: `PAYMENT_ID` (UUIDv7) — повтор отчёта терминала не создаёт дубль.

#### 3.1.5 Приём отчёта платежа от app-payment
- `POST /api/v1/payment/report` (mTLS, внутреннее доверие) — `{paymentId, cardToken, amount, type,
  acqReference, status, terminalId, occurredAt}`.
- Публичный callback статуса от эквайера: `POST /api/v1/public/payment-status` (через `ApiKeyHmacFilter`).
- Сверка: matching `ACQUIRER_REFERENCE` ↔ выписка ВТБ.

#### 3.1.6 Чёрный список (CRUD + авто-политика)
- `BlacklistController` — добавлен CRUD `POST /api/v1/blacklists` (upsert по CARD_ID, ручной блок,
  `PERMANENT`/`NEGATIVE_BALANCE`, `BlacklistBlockRequest{cardId, blockType, relatedDebtId?, autoUnblockOnRecovery?}`),
  `DELETE /api/v1/blacklists/{cardId}` (soft-delete → tombstone в дельту), `GET /api/v1/blacklists`
  (фильтры `blockType`/`includeDeleted`) — `BlacklistService` (R2DBC, валидация BLOCK_TYPE → 400).
  `/delta` сохранён. Реализовано.
- Авто-политика — продуктивные PL/pgSQL-функции (v001-init.sql): `fn_create_card_debt` считает
  **суммарный открытый долг** карты и блокирует (`NEGATIVE_BALANCE`, `RELATED_DEBT_ID`,
  `AUTO_UNBLOCK_ON_RECOVERY=true`) если сумма > = порога; `fn_recover_card_debt` после погашения
  пере-оценивает: остались долги >= порога → пере-блок; иначе авто-unblock. Порог —
  base-строка `ASOP_CONFIG_PARAMS` (`blacklist.debtThreshold`), опц. явный параметр функции.
  `debt-service` `DebtCommandConsumer` теперь вызывает эти функции вместо raw INSERT/UPDATE.
- `BLOCK_TYPE`: `NEGATIVE_BALANCE` (долг) / `PERMANENT` (ручной/фрод).
- Матчинг банковской карты — `HMAC(PAN)` (значение хранить в token-поле; ключ HMAC не на сервере карт, а
  выдаётся app-payment при pairing — см. 3.2.3).

#### 3.1.7 Фискализация
- Успешный платёж (`TOPUP`/`FARE`) → событие в `fiscal-service` (чек). Льготные — по правилам АСОП.

**Решение (уточнение заказчика):** «при открытии рейса водитель вводит TID — все платежи и льготы
вешаются на этот TID». Атрибуция чека — через TID рейса, НЕ через события платежа напрямую.

**Реализовано (fiscal-service):**
- `FiscalContextResolver` — резолвит фискальный контекст одним SQL:
  `payment.sessionId → ASOP_SESSIONS.TID_ID → ASOP_TIDS → ASOP_CONTRACTS (актуальный BANK-договор,
  START_DATE<=now<=COALESCE(END_DATE,now)) → CARRIER_ID → ASOP_CARRIER_FISCALIZERS (is_active,
  ORDER BY is_primary DESC LIMIT 1) → CARRIER_FISCALIZER_ID`. Пустая цепочка → Mono.empty(), чек не создаётся.
- `PaymentReceiptConsumer` — `@KafkaListener("asop.payment.events")`: `PaymentAuthorized` → чек
  создаётся только если `transactionId != null` И `sessionId != null` (иначе skip+log).
  Идемпотентность — `FiscalReceiptRepository.findByTransactionId` (switchIfEmpty). Вставка
  `FiscalReceiptEntity` (PENDING, maxAttempts=10) через `R2dbcEntityTemplate.insert`.
- `application.yml`: `asop.kafka.topics.payment-events: asop.payment.events`.

**Зависит от водительского ввода TID в `OpenTripScreen` — теперь ОБЯЗАТЕЛЬНЫЙ ввод:**
- Название dropdown → «TID эквайринга *»; если для перевозчика нет действующих TID — подсказка «Обновите справочники».
- `SessionFlowViewModel.canConfirmOpenTrip` требует `tripTidId != null`; `confirmOpenTrip` при пустом TID → FAILED «Выберите TID банковского эквайринга».
- Пул TID из `reference_rows` через `observeValidTidsByCarrier` (только `isValid=true`, delta `/api/v1/tids/delta`).
- TID передаётся в `SessionOpenRequest.tidId` → `ASOP_SESSIONS.TID_ID` → фискальный резолвер §3.1.7.

#### 3.1.8 Gateway
- `ServiceRegistry`: `payments` → `payment-service`, `payment` (публичный) → `payment-service`.
- `SecurityConfig`: mTLS для `/api/v1/payment/**`; публичный контур для callback.

**Реализовано (ASOP_BANK_PAYMENTS + gateway):**
- **DDL**: таблица получила имя `ASOP_BANK_PAYMENTS` (вместо `ASOP_PAYMENTS`, чтобы не коллизировать
  с легаси-таблицей Offline Top-Up). Обновлены `v001-init.sql`, `asop_schema.sql` (CREATE TABLE,
  COMMENT, индексы `uq_bank_payments_*`, `idx_bank_payments_*`, FK `fk_bank_payments_card`,
  `fk_payment_attempts_payment → ASOP_BANK_PAYMENTS`, триггер `trg_touch_updated_asop_bank_payments`).
- `PaymentEntity @Table("ASOP_BANK_PAYMENTS")`, `PaymentRepository` — переименованы запросы;
  добавлен `findByAcquirerReference`.
- `PaymentService.statusCallback(PaymentStatusCallback)` — матчинг по `paymentId` ИЛИ
  `acquirerReference`, обновление статуса + поля, публикация `PaymentAuthorizedEvent` при
  переходе в AUTHORIZED (для фискализации §3.1.7).
- `PaymentStatusCallbackController` — `POST /api/v1/payment-status` (callback от эквайера).
- Gateway `PublicProxyController`: добавлен `sub.startsWith("payment") → payment-service:8093`
  (public callback `/api/v1/public/payment-status`).
- Gateway `SecurityConfig`: новая цепочка `@Order(2)` `paymentSecurityFilterChain` —
  `securityMatcher("/api/v1/payment/**")`, mTLS (`TerminalPrincipalExtractor`), без JWT.
- `ServiceRegistry`: добавлена запись `"payment" → payment-service:8093` (public).

#### 3.1.9 Мок эквайринга (обязателен — интерфейс ВТБ TBD, разработку разблокируем сразу)
Разработка backend/app-payment/distributor не должна ждать реального интерфейса ВТБ. Делаем **два уровня мока**:

**Уровень 1 — `MockAcquirerAdapter` (in-process, основной).**
- Реализация `AcquirerGateway` рядом с `VtbSirposAdapter`; выбор через `asop.acquirer.provider` (`mock`|`vtb`),
  env `ASOP_ACQUIRER_PROVIDER`, default `mock` в dev.
- Детерминированные сценарии без внешней сети, управляются правилами из `ASOP_CONFIG_PARAMS`
  (`acquirer.mock.*`) или env:
  - `APPROVE` — по умолчанию; генерит `RRN`, `AUTH_CODE`, `ACQUIRER_REFERENCE` (префикс `MOCK-`).
  - `DECLINE` — код отказа эмитента (напр. `05`).
  - `TIMEOUT` — имитация таймаута → `ASOP_PAYMENT_ATTEMPTS` `TIMEOUT` + `NEXT_RETRY_AT` (проверка retry).
  - `DEFER` — код 74/811 (легаси `allowReDefferErrCodes`) → отложенная авторизация.
  - `REAUTH_REQUIRED` — код 76/95/82 (легаси `allowReAuthErrCodes`) → повторная авторизация.
  - `DUPLICATE` — повторный `PAYMENT_ID` → проверка идемпотентности.
- Матрица выбора сценария (расширяемо): по `amount` (напр. копейки `x.01`→DECLINE, `x.02`→TIMEOUT,
  `x.03`→DEFER, `x.04`→REAUTH) и/или по `panLast4`/`cardToken`. Конфиг — JSON в base-строке `config-params`.
- Настраиваемая задержка (`acquirer.mock.latencyMs`) — для проверки таймаутов/поведения UI.
- Тестовый endpoint `POST /api/v1/payment/mock/scenario` (только при `provider=mock`) — задать
  «следующий ответ» вручную для e2e.
- `MockAcquirerAdapter` **не** делает сетевых вызовов и не шифрует данные карты (нет `multicard_rsa`,
  нет PAN) — работает по `cardToken`/amount. В `BANK_RESPONSE JSONB` пишет синтетический ответ.

**Уровень 2 — standalone `mock-acquirer` (docker-compose, опционально).**
- Отдельный контейнер (лёгкий Spring Boot / иначе отдельный Gradle-модуль `:backend:mock-acquirer-service`,
  профиль `mock`), поднимается рядом с `payment-service` и эмулирует внешний контур ВТБ по HTTP —
  чтобы тестировать `VtbSirposAdapter`-путь и сетевые сбои без SIRPOS.
- `VtbSirposAdapter` в этом режиме ходит на `MOCK_ACQUIRER_URL` вместо SIRPOS (endpoint тот же,
  что будет у ВТБ — фиксируем контракт заглушкой).
- Health/metrics для тестов; управление сценарием тем же `/mock/scenario`.

**Переиспользование:** `MockAcquirerAdapter` нужен также `payment-service` unit/e2e-тестам долгов
(`ASOP_DEBT_RECOVERY_ATTEMPTS`) и авто-блэклиста (порог → postивлечение долга → auto-unblock).

### 3.2 Android: `android-payment` (`frontend/android-payment`, `ru.asop.payment`)

#### 3.2.1 Структура и подпись
- Отдельный included build (`includeBuild("frontend/android-payment")`), **отдельный signing key** (debug/release).
- FTSDK: `FTSDK_api_*.jar`, `FTSDK_SysAPI_*.jar` (из `doc/soft/feitian-hardware-master/app/libs`).

#### 3.2.2 EMV
- FTSDK `Emv` + `NfcReader` (собственный ридер Feitian, не Android `NfcAdapter`).
- **Реальный online-процессинг** (`setIssuerOnlineResponseData` + `respondEvent`) вместо `respond "00"`.
- МИР; параметры ядра/terminal config — от ВТБ.
- Offline floor limit → офлайн-одобрение → долг в АСОП.

#### 3.2.3 Локальный HTTP + pairing
- Сервер на `127.0.0.1:<port>` (выбрать статический порт), cleartext только для localhost
  (`network_security_config`) либо self-signed TLS.
- API: `POST /pay` (`{amount, currency, requestId}`), `GET /status/{requestId}`, `POST /void/{requestId}`,
  `GET /capabilities`. Авторизация — HMAC общим pairing-секретом.
- **Pairing-секрет** и HMAC-ключ для матчинга `HMAC(PAN)` — выдаются сервером при регистрации/привязке
  (endpoint в `terminal-service`/`payment-service`); домен `.payment` pairing-токен.
- Обратный результат: Intent-result или HTTP-callback на терминал (см. §3.6).

#### 3.2.4 Provisioning / mTLS
- Своя идентичность по образцу cert-sign saga (`POST /api/v1/terminals/cert-sign` — расширить/добавить тип
  приложения), отдельный keystore, свои `TerminalKeyCryptor`-эквиваленты для секретов.

#### 3.2.5 Offline-очередь и репорт
- Локальная очередь результатов платежей (Room) + worker(ы) — по паттерну `PendingEventEntity`+`SyncWorker`+
  `EventPollWorker`. Платёж считается проведённым на сервере после `POST /api/v1/payment/report`.

#### 3.2.6 Блэклист
- Периодический pull `GET /api/v1/payments/blacklist?versionSince=…` → локальный кэш.
- Проверка `HMAC(PAN)` до старта EMV; offline — по локальному кэшу, online — плюс на стороне ВТБ.

#### 3.2.7 UI
- Сумма → «приложите карту» → статус (успех/отказ/отложен) → чек. Отдельный экран/режим для проезда.

### 3.3 Android: `android-distributor` (`frontend/android-distributor`, `ru.asop.distributor`)

- Отдельный included build, свой signing key.
- Переиспользует MIFARE-код через **общий модуль** (см. 3.4): `MifareClassicCardWriter`,
  `TerminalKeyCryptor`, VCM1-формат/`readTripsLeft`, `AsopCardType`, матрица авторизации.
- Ключи `ASOP_KEYS` → `terminal_keys` (свой Keystore-AES), регистрация терминала/приложения (mTLS).
- Flow топ-апа: карта дистрибьютора (auth) → карта пассажира → кол-во поездок (цена из `tariff-rates`) →
  оплата (**банк** через локальный `/pay`, если F20; **наличные** всегда) → запись `tripsLeft` → репорт
  `0802` + платёж. Offline — очередь, карта уже пополнена.
- F20 vs телефон: на телефоне банк-опция недоступна (нет app-payment), только наличные.

### 3.4 Общий Android-модуль MIFARE
- Вынести MIFARE/VCM1/keys код из `android-terminal` в библиотеку (отдельный Gradle-проект +
  composite build / dependency substitution, либо отдельный included build `frontend/android-card-lib`).
- `android-terminal` и `android-distributor` зависят от неё.

### 3.5 Терминал: NFC-арбитр + классификация тапа + fare-decision

**Решения пользователя (2026-09-18):** основной режим — **A (авто-детект по тапу)**. Отдельной кнопки
«Оплата банковской картой» (режим B) в MVP нет; терминал сам классифицирует любую приложенную карту и
маршрутизирует. Режим B может быть добавлен позже как fallback, если PoC покажет нестабильность авто-детекта.

**Как есть сейчас:** `SessionFlowScreen` арм'ит `enableReaderMode(FLAG_READER_NFC_A)` + `enableForegroundDispatch`
с techLists `["MifareClassic"]`, `["IsoDep"]`; любая карта приходит в единую точку
`SessionFlowViewModel.onTagDiscovered(tag)` (дебаунс 1500 мс по UID). В `TAP_PASSENGER` вызывается
`handlePassengerTap` → `Vcm1CardAuth.readWithSession` → `MifareClassic.get(tag)` — то есть терминал умеет
**только MIFARE Classic**; банковская карта даёт ошибку. При этом `CardActivationViewModel.detectTech`
считает **любой IsoDep — DESFire**, а банковская МИР-карта тоже IsoDep — ключевая коллизия.

#### 3.5.1 Runtime после открытия рейса (целевой)

1. Водитель открывает рейс (OPEN_TRIP → авторизация MIFARE + cascade). `SessionFlowScreen` остаётся в
   composition, `LaunchedEffect` переключает `kind = TAP_PASSENGER` (`switchToPassengerMode()`).
   Reader **не переармливается** — `DisposableEffect` НЕ кейается на `cardStep`/`kind`; он живёт,
   пока экран виден. Терминал показывает «Ждите карту пассажира».
2. Пассажир прикладывает карту → `onTagDiscovered(tag)` (ReaderMode callback ИЛИ `onNewIntent` →
   `NfcTagBus` → poll в VM). Дебаунс 1500 мс по UID.
3. **Классификация** (`CardClassifier.classify(tag)`, read-only, до любого чтения identity):
   - `MifareClassic.get(tag) != null` → `MIFARE_CLASSIC`;
   - `IsoDep` → `DesfireCardWriter.selectApplicationDetailed(iso, 0xA05A01)`:
     `OK` → `ASOP_DESFIRE`; `UNSUPPORTED`/`ERROR_STATUS` → `BANK_EMV`; `IO_ERROR`/exception → `UNSUPPORTED`;
   - иначе → `UNSUPPORTED`.
4. Маршрутизация в `TAP_PASSENGER`:
   - `MIFARE_CLASSIC` → «Случай 1» (терминал сам, offline);
   - `ASOP_DESFIRE` → пока `NFC_ERROR` «DESFire-проезд не поддерживается» (отдельная фаза);
   - `BANK_EMV` → «Случай 2» (HANDOFF);
   - `UNSUPPORTED` → `NFC_ERROR` + error beep.
5. Для **не-passenger** flow (open/close shift/trip, авторизация водителя/админа): classify; если не
   `MIFARE_CLASSIC` — сразу `NFC_ERROR` «Это не ASOP-карта» (банковская карта не запускает handoff и не
   уходит в долгий неудачный `Vcm1CardAuth.read`).

#### 3.5.2 Классификация

Новый `nfc/CardClassifier.kt`: `enum TapCardKind { MIFARE_CLASSIC, ASOP_DESFIRE, BANK_EMV, UNSUPPORTED }`.
Правило «любой `IsoDep` = DESFire» из `CardActivationViewModel.detectTech` дополняется AID-пробой
`0xA05A01` (native DESFire `0x5A`, read-only). `CardClassifier.classify` **никогда не бросает** и всегда
закрывает `IsoDep` в `finally` — освобождает карту для возможного handoff.

#### 3.5.3 Случай 1 — MIFARE/DESFire (терминал сам, offline OK)

1. identity терминальными ключами (`terminal_keys`);
2. блок: `asop_blacklists` по `cardId` (MIFARE) / идентификатору DESFire;
3. льгота/цена: `reference_rows` (`user-benefits`/`path-benefits`, `tariff-rates`);
4. списание 1 поездки в той же mfc-сессии (`Session.updateTrips`) либо `tripsDebited=0` для льготной;
5. писк + результат на экране. app-payment **не участвует**.

#### 3.5.4 Случай 2 — банковская карта (HANDOFF, app-payment)

1. терминал **не** читает EMV (PCI: нет ядра/ключей/лицензии). `beginBankHandoff()`:
   - `PaymentHandoff.isPaymentAppInstalled(context)` == false → мгновенно `NFC_ERROR` +
     «Приложение оплаты не установлено», handoff не запускается;
   - иначе генерится `requestId` (UUIDv7), состояние `bankPayment.phase = REQUESTED`;
2. UI (режим A) — пока `REQUESTED`/`PROCESSING`, reader принудительно погашен (см. §3.6), экран показывает
   «Оплата банковской картой»; `SessionFlowScreen` запускает app-payment через `startActivityForResult`;
3. app-payment: FTSDK `NfcReader` → пассажир **прикладывает карту повторно** (Tag не переносится между
   процессами), EMV + авторизация: online через `payment-service`→ВТБ либо offline floor limit → долг;
   блэклист проверяет app-payment (кэш `HMAC(PAN)`) или сервер — **терминал PAN не видит**;
4. результат → `PaymentHandoff.parseResult(data)`; терминал `onBankPaymentResult`:
   success → `SUCCESS` + проезд зафиксирован (отчёт платежа шлёт app-payment), `phase` сбрасывается через
   `resultDisplayMs`; failed → `FAILED` + error beep;
5. по завершении (или при отмене) `phase = NONE` → `NfcTagBus.release("payment")` + re-arm reader.

**Fare-decision общий для обоих случаев** (блок/льгота/цена); для банка identity = `HMAC(PAN)`,
привязка к пользователю АСОП опциональна (иначе аноним/полный тариф). Расчёт суммы для банка из
`tariff-rates` — отдельная задача (сейчас передаётся `amount` из заглушки fare-resolver).

### 3.6 Протокол NFC-handoff (детально, режим A)

**Владелец NFC — всегда терминал; app-payment владеет им временно.** Android foreground-dispatch отдаёт
`Tag` только foreground-приложению, а Feitian PiccService сериализует клиентов (одно чтение в момент времени).

1. **Disarm.** `beginBankHandoff()` переводит `bankPayment.phase = REQUESTED`. `SessionFlowScreen`
   добавляет `handingOff` (= `REQUESTED || PROCESSING`) в ключи `DisposableEffect`:
   `needsActiveReader = nfcEnabled && !handingOff`. Эффект перезапускается → `onDispose` вызывает
   `NfcReaderRefCount.releaseAndShouldDisable()` → при refcount==0 `disableReaderMode` +
   `disableForegroundDispatch`. Прежний механизм «reader не гаснет, пока экран виден» **не подходит** для
   handoff (экран НЕ размонтируется) — поэтому disarm идёт через ключ, а не через `onDispose` экрана.
2. **Claim.** `NfcTagBus.claim("payment")` — шина больше не отдаёт таги `SessionFlowViewModel`; попытка
   claim'а другого владельца (TopUp) при активном handoff игнорируется/отклоняется.
3. **Launch.** `PaymentHandoff.buildIntent(context, request)` (explicit, package `ru.asop.payment`,
   `ACTION_PAY`, extra `EXTRA_REQUEST` = JSON) → `startActivityForResult`. app-payment выходит foreground.
4. **Read.** app-payment открывает FTSDK `NfcReader`/`Emv`; пассажир **прикладывает карту повторно**.
   EMV + авторизация; результат возвращается в `EXTRA_RESULT` (JSON) с `RESULT_OK`/`RESULT_CANCELED`.
5. **Result.** `PaymentHandoff.parseResult(data)` → `SessionFlowViewModel.onBankPaymentResult(...)`:
   - success: `phase = SUCCESS`, поля `amount`/`maskedPan`/`acqReference`/`rrn`; через `resultDisplayMs`
     → `phase = NONE`; `TonePlayer.successBeep`;
   - failed: `phase = FAILED`, `errorMessage`; `TonePlayer.errorBeep`; через `resultDisplayMs` → `NONE`;
   - cancel: `phase = NONE` (тихо).
6. **Re-arm.** `phase` вернулся в `NONE` → `handingOff=false` → `DisposableEffect` перезапускается и
   заново арм'ит reader; `NfcTagBus.release("payment")`. Если пользователь ушёл с экрана во время handoff —
   `onDispose` всё равно вернёт refcount и снимет claim (release идемпотентен).
7. **Warm-путь (HTTP 127.0.0.1 + HMAC).** Резерв: если app-payment уже запущен, терминал шлёт
   `POST http://127.0.0.1:8790/pay` с `X-Request-Id`/`X-Timestamp`/`X-Signature` (HMAC-SHA256) до запуска
   Activity. Клиент + формат подписи — `PaymentHandoff.buildSignature()` / `X-Asop-Signature`; endpoint
   появится в app-payment (Фаза 3). Cleartext к `127.0.0.1` разрешён в `network_security_config.xml`.
8. **Повторный тап обязателен в авто-режиме A** — `Tag` не переносится между процессами; это ожидаемое
   поведение, экран подсказывает «Приложите карту к эквайеру повторно».
9. **Обязательный PoC:** на реальном F20 проверить, что FTSDK-reader (app-payment) и Android `NfcAdapter`
   (терминал) **сериализуются** без конфликта за PiccService: (a) терминал дизармил → app-payment читает;
   (b) после возврата терминал заново арм'ит и читает MIFARE. Если конфликт есть — app-payment переводится
   на собственный `enableReaderMode` и терминал relinquish'ит диспатч (только foreground app получает Tag).

**Реализовано сейчас (терминал):** `CardClassifier`, `PaymentHandoff`, состояние `bankPayment` в
`SessionFlowViewModel`, disarm/re-arm в `SessionFlowScreen`, UI статуса. **Не реализовано:** само
`app-payment` (Фаза 3), расчёт `amount` по тарифу, HTTP warm-путь (нужен endpoint в app-payment).

### 3.7 Web-admin
- Страница «Платежи» — мониторинг, фильтры (регион/перевозчик/дистрибьютор), возвраты, попытки.
- Страница «Чёрный список банковских карт» — просмотр, ручной block/unblock, связь с долгом.
- «Сверка с ВТБ» — matching по `ACQUIRER_REFERENCE`.
- GLOBAL-фильтр: не затрагивается (оплаты — не справочник).

**Реализовано:**
- Список платежей backend: `GET /api/v1/payments` (payment-service, `PaymentController.list` /
  `PaymentService.list`) — фильтры `status`, `paymentType`, `terminalId`, `includeDeleted`, `limit/offset`,
  ORDER BY CREATED_AT DESC. Динамический WHERE через `DatabaseClient`. Gateway `payments` → payment-service.
- Страница «Платежи» (`/payments`, `api/payments.ts`, `pages/Payments.tsx`): таблица (время/сумма/тип/статус/
  эквайер/PAN**/RRN/ref/терминал/ошибка), фильтры статус+тип+includeDeleted, авто-обновление 15 с,
  цветные статусы (status-green/red/gray/orange). Sidebar — верхний раздел.
- Страница «Чёрный список» (`/blacklists`, `api/blacklist.ts`, `pages/Blacklists.tsx`):
  список с фильтром по `blockType` (NEGATIVE_BALANCE/PERMANENT), ручная блокировка
  (`POST /api/v1/blacklists`, форма cardId/blockType/relatedDebtId/autoUnblockOnRecovery),
  снять блокировку (`DELETE /api/v1/blacklists/{cardId}` → soft-delete → tombstone в дельту).

Остальное (§3.7 «Сверка с ВТБ», возвраты/попытки) — после реализации app-payment и появления реальных
платежей/интерфейса ВТБ.

### 3.8 E2E (Docker, mock-эквайер) — результаты и фиксы
- **DDL-фикс `pk_bank_payments`**: PK банковской таблицы переименован (был `pk_payments`) — имена
  PK/UNIQUE-ограничений создают индексы, уникальные в схеме; имя `pk_payments` уже занято КОНСТРЕЙНТОМ
  легаси `ASOP_PAYMENTS` (Offline Top-Up). Liquibase без этого падал
  `ERROR: relation "pk_payments" already exists`.
- **DDL `ASOP_PAYMENT_ATTEMPTS.BANK_RESPONSE` = TEXT** (был JSONB): Spring Data R2DBC биндит String в
  jsonb-колонку без каста → `column "bank_response" is of type jsonb but expression is of type character varying`.
  JSON остаётся валидным в TEXT (пишется `ObjectMapper`-ом).
- **`GET /api/v1/payments` (list)**: маппинг на контроллере — только `@GetMapping` (БЕЗ абсолютного
  пути, иначе 404): `@RequestMapping("/api/v1/payments")` интерфейса `PaymentApi` мержится как
  class-level, и абсолютный путь склеивается в `/api/v1/payments/api/v1/payments`.
- **`DatabaseClient.bind()` immutable spec**: как в AGENTS.md — результат `bind()` нужно сохранять в
  цепочке (`let { s -> if (cond) s.bind(...) else s }`), иначе `No parameter specified for [limit]`.
- **Пас (мок-эквайер, gateway hmac_key `a1b2...e1f2`)**:
  - authorize 100.00/250.00 → AUTHORIZED (ref/RRN/authCode, attempt SUCCESS); 101.01 → DECLINED
  - идемпотентность authorize по requestId → тот же paymentId
  - report → идемпотентный update (acqReference обновился); statusCallback (match по
    acquirerReference) PENDING→AUTHORIZED публикует событие
  - list с фильтрами status/paymentType (по отдельности и в комбинации)
  - gateway public `/api/v1/public/payment-status`: HMAC-подпись (timestamp) → 200; без подписи
    (plain key) → 200; битая подпись → 403; без ключа → 401
  - blacklists через gateway JWT-прокси: block (201) → list (фильтр blockType) → upsert повторного
    block (PERMANENT перезатёр NEGATIVE_BALANCE, VERSION 4359→4361) → unblock (soft-delete) →
    list исключает, includeDeleted=true показывает tombstone
  - FK `fk_blacklists_card` не даёт заблокировать несуществующую карту (корректно)

---

## 4. Порядок реализации

- **Фаза 0 (PoC, критично):** сериализация FTSDK-reader ↔ Android NfcAdapter на F20; получение интерфейса ВТБ
  (транспорт/протокол/учётки/ключи) и решение по коннектору. **Не блокирует** Фазы 1–5: эквайринг
  тестируется на `MockAcquirerAdapter`.
- **Фаза 1 (контракты):** `doc/bank_card.md` (архитектура) + `doc/bank_card_api.md` (локальный API app-payment,
  pairing, HMAC, формат отчёта).
- **Фаза 2 (backend):** `payment-service` + `payment-api`, Kafka, таблицы, `AcquirerGateway` +
  **`MockAcquirerAdapter` (сначала)**, затем `VtbSirposAdapter`,
  приём отчёта, блэклист CRUD+авто-политика, фискализация, gateway routing.
- **Фаза 3 (app-payment):** отдельный ключ, EMV, локальный HTTP+HMAC, provisioning, offline-очередь, pull блэклиста, UI.
- **Фаза 4 (общий lib + app-distributor):** вынос MIFARE-кода, топ-ап (наличные/банк), ключи, регистрация.
- **Фаза 5 (терминал):** NFC-арбитр, handoff, fare-decision, приём банковской карты для проезда.
- **Фаза 6 (web-admin + сверка).**

---

## 5. Файлы для изменений

### Новые
- `backend/payment-service/**`, `backend/shared/api/payment-api/**`
- `backend/payment-service/.../acquirer/MockAcquirerAdapter.kt` + `MockScenarioConfig`
- `backend/mock-acquirer-service/**` (опционально, профиль `mock`, docker-compose)
- `frontend/android-payment/**`, `frontend/android-distributor/**`, общий Android MIFARE-модуль
- `doc/bank_card_api.md`
- web-admin: `api/payments.ts`, `api/blacklist.ts`, страницы Payments/Blacklist/Reconciliation

### Изменяемые
- `settings.gradle.kts` — новые backend-модули + `includeBuild` Android-приложений
- `infrastructure/db-migrations/asop_schema.sql`, `.../v001-init.sql`, `.../migrations/v001-init.yaml`
- `backend/gateway-service/.../ServiceRegistry.kt`, `SecurityConfig`, `CommandEventConsumer`
- `backend/card-service/.../controller/BlacklistController.kt` (CRUD)
- `backend/shared/asop-kafka-contracts/**` + `:backend:shared:api:*` — события/DTO
- `frontend/android-terminal` — NFC-арбитр, handoff, fare-decision, вынос MIFARE в lib
- `frontend/android-terminal/.../network/SyncApi.kt` — опционально endpoint'ы платежей
- `AGENTS.md` — раздел про банковский эквайринг (после реализации)

---

## 6. Open-вопросы

1. **Интерфейс ВТБ/SIRPOS:** транспорт, протокол, библиотека, TID-база, ключи; нужен ли bank-side коннектор
   (решаем после сети от ВТБ). **Не блокирует:** до ответа ВТБ работаем на `MockAcquirerAdapter` (3.1.9).
2. **HMAC(PAN) и ключ матчинга:** какой ключ, откуда и как доставляется в app-payment (через pairing).
3. **Порог суммы** для авто-блэклиста и **лимит offline floor** — конкретные значения.
4. **Льготы по банковской карте:** привязка банковской карты к пользователю АСОП — обязательна или опциональна?
5. **Запись `tripsLeft` на MIFARE при банковской оплате:** порядок «деньги→карта» (сейчас сервер→карта),
   поведение при отказе после оплаты.
6. **Фискализация** — какие типы карт/льгот не идут в чек (легаси: `isPrivileged`).

# План интеграции оплаты банковской картой: терминал → ASOP → ВТБ

## Контекст (сводка решений)

- **Железо**: Feitian F20 Smart Mobile POS (PCI PTS 5.1, EMV L1/L2, Android 14)
- **Договор**: Интернет-эквайринг ВТБ (CNP)
- **Исполнение платежа**: закрытый VIP-канал ASOP → ВТБ (не публичный REST), будет проложен дополнительно
- **EMV-ядро на терминале**: сторонний сертифицированный kernel (Feitian V8 SDK), не банковское ПО
- **Авторизация**: гибрид (online через VIP-канал при связи, offline + batch при отсутствии)
- **Тип оплаты**: карточная (payWave/payPass), не СБП

## Ключевое открытие (исследование)

**Ни один российский банк не имеет публичного REST API, принимающего EMV-криптограмму/ISO8583 от терминала** — все их API (Сбер `register.do`, Т-Банк `/v2/Init`, ВТБ, Альфа, ЮKassa) принимают только CNP e-commerce заказы с редиректом/3DS. Параметра «EMV cryptogram / ARQC» в них нет.

Card-present EMV в РФ работает по **закрытому POS-протоколу** напрямую между терминалом и хостом банка-эквайера. Поскольку у проекта есть договорённость о **VIP-канале ASOP → ВТБ**, архитектура «посылка от терминала → сервер → ВТБ» реализуема в модели front-end-to-host (как у процессинговых центров).

## Найденные спецификации Feitian F20 SDK

Источники (все получены прямым HTTP, поисковики были заблокированы):

| Источник | URL | Содержание |
|---|---|---|
| **V8 Feitian SDK Introduction PDF** | `https://www.ftsafe.com/payment/wp-content/uploads/2021/08/V8-Feitian-SDK-Introduction.pdf` | Архитектура: App → SDK JAR → AIDL → POS Server → JNI → SP. SmartCard (M0-3, EMV, MSR), PinPad (PCI 5.x, ISO9564, DUKPT), PRN, FP, QR, GPS. **EMV Kernel** — часть фреймворка. |
| **Knowledge Base** | `https://project-tracker.ftsafepayment.com/knowledge-base` | Разделы: SmartPay (25 ст.), STORM (28), Device (11), Firmware & SDK (1 ст. «F20 Firmware and SDK» — за логином) |
| **SmartPay страница** | `https://www.ftsafe.com/payment/smartpay/` | Платёжное приложение Feitian для Android POS: App Pay, ECR Pay, Purchase, PreAuth, Refund, Settlement |
| **FTPAY** | `https://www.ftsafe.com/payment/ft-pay-2/` | Альтернативное платёжное приложение |
| **FT STORM** | `https://www.ftsafe.com/payment/storm/` | Remote Terminal Manager + Key Injection (TMK/TPK/BDK/IPEK) |
| **Страница Downloads** | `https://www.ftsafe.com/payment/downloads/` | SDK не публичный; раздаётся через Project Tracker (логин) |
| **Developer Center** | `https://developer.ftsafe.com/` | IAM-docs SPA (требует JS и учётку) |

### Как получить доступ к SDK

Прямое обращение к Feitian (Payment & IoT BU):

```
info@ftsafe.com
sales-support1@ftsafe.com
+86 10 6230 4466 (Китай, главный офис)
+1 408-352-5553 (USA)
```

В заявке запросить:
- SDK V8 для F20 (Android) — JAR + AIDL + POS Server
- Регистрацию в Project Tracker (Knowledge Base)
- Спецификацию EMV-интеграции (API контактless kernel, формат посылки ARQC)
- Примеры интеграции (demo app)

Также запросить у дистрибьютора/Feitian возможность доступа к **FTPAY/STORM OEM** (если ASOP-приложение должно вызывать kernel, а не быть автономным POS).

## Архитектура (предлагаемая)

```
F20 (Feitian kernel через V8 SDK)
  │  tap банковской карты → EMV-ридер ядра
  │  │
  │  ├─ online (есть связь): ядро возвращает ARQC/криптограмму + PAN + сумма
  │  │   → ASOP-app: sync POST /api/v1/sync/payments/auth (timeout 5 с)
  │  │   → Gateway (sync-proxy, WebClient) → payment-service → VIP mTLS → ВТБ
  │  │   → ответ (approve/decline) → терминал
  │  │
  │  └─ offline (нет связи / fallback): offline-авторизация + ARQC
  │      → ASOP-app: Room PendingEvent → SyncWorker (15 min) → Kafka asop.payment.commands
  │      → payment-service → VIP mTLS → ВТБ (batch settlement)
  │
  ▼
Gateway
  ├─ sync: POST /api/v1/sync/payments/auth (mTLS chain-1, новый endpoint)
  ├─ async: POST /api/v1/sync/payments/offline (существующий 202 + X-Event-Id паттерн)
  └─ proxy: ServiceRegistry → payment-service (новый порт)
      │
      ▼
payment-service (новый модуль, порт ~809x)
  ├─ PaymentChannel (interface) — абстракция канала
  │   └─ VtbChannelAdapter (WebClient + mTLS VIP + специфический протокол)
  ├─ Kafka consumer asop.payment.commands → batch обработка
  ├─ sync REST /api/v1/payments/authorize (вызывается gateway для online)
  └─ reconciliation (реестры, refund, reverse)
```

## План реализации

### Фаза 0: Получение спецификаций (блокирующая, вне кода)

1. Запрос в ВТБ (технический отдел эквайринга): протокол VIP-канала
   - Формат: ISO8583 / XML / приватный REST?
   - mTLS: клиентский сертификат, взаимная аутентификация, требования HSM
   - MID/TID: регистрация F20 как терминала, получение TID на каждый девайс
   - Online: время ожидания ответа, timeout
   - offline: реестры/формат batch
   - Возвраты/refund: протокол

2. Запрос в Feitian (через контакты выше): SDK V8 + EMV API
   - API для вызова contactless kernel (startContactlessRead → callback с транзакцией)
   - Формат результата: ISO8583-сырой / JSON / объект транзакции
   - Пример: интеграция с платёжным приложением (FTPAY/SmartPay) или встраивание kernel напрямую
   - Возможность работы NFC в режиме, не блокированном платёжным ядром PCI PTS

### Фаза 1: Каркас payment-service (можно начинать)

- Новый Spring Boot модуль `backend/payment-service`, порт 809x
- `PaymentChannel` interface с методами `authorize(request) → response`, `batchSubmit(batch) → result`
- Kafka topic `asop.payment.commands` (контракты в `asop-kafka-contracts`)
- Gateway: новый sync endpoint `POST /api/v1/sync/payments/auth` (fast-path, WebClient)
- Gateway: async endpoint `POST /api/v1/sync/payments/offline` (существующий паттерн 202+polling)
- `EventService` в gateway (уже есть Redis, `asop:event:*`)

### Фаза 2: VTB-адаптер

- `VtbChannelAdapter` — WebClient с mTLS (truststore + клиентский сертификат + приватный ключ)
- Протокол маппинга: посылка → спецификация ВТБ (зависит от Фазы 0)
- HSM/ключи: по спецификации ВТБ (возможно PKCS#11 или Bouncy Castle)

### Фаза 3: Терминальная интеграция (android-terminal)

- `FeitianSdkFacade` — обёртка вокруг V8 Feitian SDK
  - вызов kernel для бесконтактного чтения
  - получение посылки (EMV-транзакция)
  - классификация: online vs offline
- Онлайн: sync fast-path c timeout (не через Room/Kafka)
- Оффлайн: `PendingEventEntity` → `SyncWorker` (существующий паттерн)
- Hybrid: решение о маршруте принимается на терминале

### Фаза 4: Сверка/возвраты

- Обработка реестров ВТБ (сверка с локальными транзакциями)
- Refund/reverse через отдельные async-команды

## Открытые вопросы

- **PCI DSS**: ASOP-приложение должно обрабатывать PAN массированно? Если да — SAQ D. Если kernel даёт masked PAN — scope меньше.
- **DUKPT ключи**: инжекция через STORM или напрямую? Влияет на архитектуру terminal-management.
- **Время online-авторизации**: если ВТБ отвечает дольше 5 с — гибридная схема скорее offline + batch.
- **F20 NFC lock**: PCI PTS может блокировать NFC для сторонних приложений — нужна проверка на реальном F20 с Feitian SDK (NDA-доступ).

## MVP-валидации проезда (промпт 011)

После согласования VIP-канала payment-flow, **MVP** уже реализуется через прямую запись транзакций без списания денег:

- **`TRANSACTION_TYPE_CODE='VALIDATION'` (…0803)** — «Валидация (без списания)».
- **`TRANSACTION_RESULT_CODE='VALIDATION_ONLY'` (…0903)** — «Зафиксировано (без списания)».
- Room `TripPaymentEntity` → Kafka `transaction-service` → `ASOP_TRANSACTIONS (amount=0)` через `asop.transaction.commands`.
- Это уже работает end-to-end в MVP-режиме (см. `doc/smoke-tests.md → Smoke Test: Driver Session workflow`).
- `transaction-result_id='VALIDATION_ONLY'` отмечает запись, как «не списание», без реального движения средств; переход на Phase 5 (`asop.payment.commands`) будет требовать только swap `transactionResultId` в `TripPaymentEntity` defaults и ServerKafka-routing от `transaction-topic` к `payment-topic`.

**Tap-флоу пассажира** для MVP:

```
Пассажир прикладывает карту → OpenTripScreen (TAP_PASSENGER)
  → INSERT TripPaymentEntity (amount=0, transactionResultId='…0903 VALIDATION_ONLY')
  → PendingEvent(TRANSACTION_COMPLETE) → SyncWorker
  → POST /sync/transactions
  → gateway TransactionCommandService → Kafka asop.transaction.commands
  → card-service TransactionCommandConsumer → INSERT ASOP_TRANSACTIONS (amount=0)
    + updateTripsBalance (last-wins по metadata.tripsAt)
```

При переходе к Phase 5 (реальное списание ВТБ):
- `transactionResultId` дефолт → стандартный (`Успешно`/`Отказ`);
- идемпотентность сохраняется через `ON CONFLICT (SESSION_ID/TAP_UUID)` уже реализован в MVP.


## Связанные файлы

- `frontend/android-terminal/app/...` — Android-терминал (будущий FeitianSdkFacade)
- `backend/gateway-service/...` — Gateway (новые sync/async endpoints)
- `backend/shared/asop-kafka-contracts/...` — Новые event DTO
- `backend/shared/api/payment-api/...` — Новый API-модуль (интерфейсы + DTO)
- `doc/context.md` — основной контекст ASOP (добавить новую главу)

## Результаты исследования (уже сделано)

- [x] Анализ публичных REST API российских эквайеров (Сбер, Т-Банк, ЮKassa, Альфа, ВТБ, ГПБ) — **ни один не принимает EMV-криптограмму**
- [x] V8 Feitian SDK Introduction PDF — скачан и прочитан (архитектура, состав, EMV Kernel, PinPad, SmartCard M0-M3)
- [x] Knowledge Base (SmartPay, STORM, F20 Firmware and SDK) — найдены, контент за логином
- [x] Контакты Feitian для запроса SDK: `info@ftsafe.com`, `sales-support1@ftsafe.com`
- [x] Определена архитектура: F20 → V8 SDK → ASOP → Gateway → payment-service → VIP → ВТБ
- [x] Определён гибрид online/offline режим

## Статус Фазы 3 (android-payment) — e2e PoC на F20 (2026-09-18)

**Сделано и проверено на реальном устройстве Feitian F20:**
- [x] `frontend/android-payment/` — отдельная Gradle-сборка (namespace `ru.asop.payment`, AGP 8.7.0/Kotlin 2.0.21), APK установлен.
- [x] Локальный HTTP-сервер `0.0.0.0:8790` + HMAC-SHA256 (по timestamp, `X-API-Key`/`X-Timestamp`/`X-Signature`, секрет `asop-payment-pairing-dev-secret`); `/capabilities`, `/pay`, `/status/{requestId}`, `/status`, `/void`. Идемпотентность `/pay` по `requestId`; float-фикс `round(amount*100)%100` (100.01→10001, не 10000).
- [x] Mock-эквайер по копейкам: `.01`→DECLINED, `.02`→TIMEOUT, `.03`→DEFERRED (74), `.04`→PENDING (REAUTH), `.05`→DUPLICATE, иначе APPROVED. `errorCode:null`-фикс (JSONObject.NULL/отсутствие/`"null"` → null).
- [x] **Handoff e2e**: `am start -a ru.asop.payment.ACTION_PAY --es request <PayRequest JSON>` → `PaymentMain` → `payResult` APPTOVED с `acqReference=MOCK-<requestId>`; результат поллится `/status/{requestId}`.
- [x] **FTSDK**: `NfcReader.getInstance(context)` null ДО `ServiceManager.bindPosServer(context, callback)` (async-bind, CountDownLatch). `checkNFCCardreader=0` (NFC доступен), `isExist=false` (нет карты на поле). Прогрев при старте (PaymentApp.onCreate) — bind ~3 c; ретрай 2×.
- [x] **Offline-очередь отчётов**: Room `pending_payments` (`PendingPaymentEntity`: requestId/paymentId/payloadJson/paymentType/reportStatus PENDING|SENT, version 2 destructive). `ReportWorker` (WorkManager, 15 мин, CONNECTED) → `ReportPayload.build` (PayResponse → PaymentReportRequest JSON, `AUTHORIZED/DECLINED/FAILED/DEFERRED/REVERSED`) → `AcquirerReportClient` (store-and-forward, seam под mTLS после pairing). Проверено содержимое `pending_payments` (row PENDING, type FARE).

**Осталось (блокировано):**
- Pairing/provisioning: mTLS client-cert via cert-sign + `DistributionConfig.setPaired` — участок вклчён (seam), транспорт по сертификату написать после ВТБ.
- Реальный EMV-контур (FTSDK EMV + key-инжекция) — ждёт SDK/ключей от ВТБ (`VtbSirposAdapter` — заглушка, интерфейс TBD).
- UI-доработка (список последних платежей, настройка) — косметика.
- End-to-end handoff терминал→app-payment на физическом карте (тап банковской карты) — терминал пересобран (`PaymentHandoff` под контракт `ACTION_PAY`/`EXTRA_REQUEST`=`PayRequest`/`EXTRA_RESULT`=`PayResponse`), требуется тап карты.

## Статус Фазы 4 — asop-nfc-lib + android-distributor (scaffold, 2026-09-19)

**Общее NFC/VCM1-ядро вынесено в standalone lib (composite build), дистрибьютор — рабочий scaffold.**
Решение по объёму (подтверждено): либа + scaffold БЕЗ серверного delta-sync (ключи/тарифы пока static/injected).

- [x] `frontend/android-nfc/` — lib `asop-nfc-lib` (group `ru.asop.nfc`, v0.1.0, AGP 8.7.0/Kotlin 2.0.21, minSdk 26, compose не нужен).
  Классы: `AsopCardType` (+`allRolesForBitmask`/`CardActivationMatrix`), `EntityType`, `CardIdentityVcm1` (VCM1 encode/decode, magic, tripsLeft), `Vcm1CardAuth` (read/readWithSession + `Session.updateTrips` в той же mfc-сессии — F20 требованием), `MifareClassicVcm1` (detectState/write/writeTripsLeft, multiPassWrite, factory-keys), `TerminalKeyCryptor` (AES-GCM Keystore, без Hilt). `./gradlew :asop-nfc-lib:assembleDebug` — зелёное.
- [x] `frontend/android-distributor/` — `settings.gradle.kts` c `includeBuild("../android-nfc")`; app-build (namespace `ru.asop.distributor`, dependency `ru.asop.nfc:asop-nfc-lib:0.1.0`, Compose + uuid-creator). `./gradlew :app:assembleDebug` — зелёное, APK установлен на F20, activity стартует без краша (NFC `state=on`).
- [x] Флоу `TopUpViewModel`: оператор-карта (auth, роль `DISTRIBUTOR_ADMIN/DISTRIBUTOR_DISPATCHER/SUPER_ADMIN`) → карта пассажира (`readWithSession`, живёт в сессии) → сумма пополнения → `PaymentClient.pay(...)` (HMAC, `127.0.0.1:8790`, cleartext разрешён только для `127.0.0.1` в `network_security_config`) → APPROVED → `Session.updateTrips` (local VCM1-write) → статус. Фолбэк «Наличные» (`cashIndex`).
- [x] Контракт distributor→app-payment подтверждён e2e на устройстве: POST `/pay` (`paymentType=TOPUP`, capture, message, секрет `asop-payment-pairing-dev-secret`) на живой `LocalPaymentServer` F20 → `APPROVED`, `paymentId`, `acqReference=MOCK-...` (получен curl'ом ровно тем же HMAC/SHA-телом, что шлёт `PaymentClient`).

**Осталось (Phase 4+, по шагам):**
- Тап физических карт на F20: операторская VCM1 → пассажирская VCM1 → запись tripsLeft + `/pay` в одном прогоне (нужен доступ к устройству с картами).
- Серверный контур для distributor: идентичность (cert-sign), онбординг `distributor_terminals`, delta-sync `asop_keys`/`asop_tariff_rates` (заменить static `KeyProvider`/`TariffProvider`).
- [x] Миграция `android-terminal` на `asop-nfc-lib` — выполнена (см. ниже).

## Статус Phase 4 миграции — android-terminal → asop-nfc-lib (2026-09-19)

- [x] Подключение композита: `frontend/android-terminal/settings.gradle.kts` — `includeBuild("../android-nfc")`; `app/build.gradle.kts` — `implementation("ru.asop.nfc:asop-nfc-lib:0.1.0")`.
- [x] Удалены дубли терминала: `activation/AsopCardType.kt`, `activation/EntityType.kt`, `activation/CardIdentityVcm1.kt`, `nfc/Vcm1CardAuth.kt`, `db/TerminalKeyCryptor.kt` (переходим на `ru.asop.nfc.*`).
- [x] Usage переключен на `ru.asop.nfc.*`: `CardReadViewModel`, `CardActivationScreen`, `SessionFlowScreen`, `TopUpViewModel`, `SessionFlowViewModel` (import+FQ), `CardActivationViewModel` (import+FQ), `MifareClassicReader`, `MifareClassicCardWriter`, `DesfireCardReader`, `ReferenceSyncStore` (import `ru.asop.nfc.TerminalKeyCryptor` — раньше resolve по пакету `db`).
- [x] Hilt: `AppModule.provideTerminalKeyCryptor()` (`@Provides @Singleton`) для lib-версии без `@Inject`.
- [x] В терминале остались терминал-специфичные: `nfc/MifareClassicCardWriter` (SAC1-легаси, `readVcm1`, `DetectResult`/matchedKey), `nfc/MifareClassicReader`, `nfc/DesfireCardReader`, `cardIdentity` UI/VMs. В lib НЕ выносилось.
- [x] Тесты: `CardIdentityVcm1Test` приведён к актуальной clean-break семантике (биты/роли под текущий порядок enum `AsopCardType`, `EntityType` только USER/NONE, `forAsopCardTypeOrdinal(13)=NONE`; часть ожиданий была латентно сломана pre-009 — вскрыта clean-сборкой). `./gradlew :app:clean :app:assembleDebug :app:testDebugUnitTest` — BUILD SUCCESSFUL, 11 тестов зелёные.

## Статус Фазы 5 — серверный контур android-distributor (prompt_017, 2026-09-19)

**Задача**: серверный контур для `android-distributor`: mTLS-идентичность (cert-sign), идемпотентный онбординг `ASOP_DISTRIBUTOR_TERMINALS`, delta-sync `asop_keys`/`asop_tariff_rates` — заменить static `KeyProvider`/`TariffProvider` на данные с сервера.

**Решение по объёму**: НЕ orchestrator/proto (полный Docker+proto-контур ради distributor не оправдан). Вместо этого новые mTLS-эндпоинты gateway `/api/v1/sync/distributor/**` (chain Order 1) — прямой JSON-`/delta` pull с мастеров:
- `POST /api/v1/sync/distributor/register` → terminal-service `POST /api/v1/distributor-terminals/register` (upsert по `terminalSerial`, идемпотентно);
- `GET /api/v1/sync/distributor/keys/delta?versionSince&includeDeleted` → admin `GET /api/v1/asop-keys/delta`;
- `GET /api/v1/sync/distributor/tariffs/delta?versionSince&includeDeleted` → card `GET /api/v1/tariff-rates/delta`.

`asop_keys` и `asop_tariff_rates` — GLOBAL_TABLES (`MasterRegistry`), поэтому специф-scope (TerminalResolver → carrierId/regionId), как у терминала, для distributor не нужен — тянет глобальные справочники целиком. `ASOP_DISTRIBUTOR_TERMINALS` не имеет VERSION/DELETED_AT — только CRUD-онбординг (не delta-синкается).

**Backend** (`./gradlew clean :backend:terminal-service:build :backend:gateway-service:build -x test` → BUILD SUCCESSFUL):
- [x] `terminal-service` `DistributorTerminalRepository.findByTerminalSerial`; `DistributorTerminalController.register`: новый serial → insert 201, существующий → update 200, новый без `cardsDistributorId`/`paymentProviderId` → 400, `terminalNumber` default = `terminalSerial`.
- [x] `gateway-service` `DistributorSyncController` (register / keys-delta / tariffs-delta, query passthrough, ошибка мастера → 502 BAD_GATEWAY). Покрыт mTLS chain `/api/v1/sync/**` (SecurityConfig Order 1).

**Distributor app** (`./gradlew :app:assembleDebug` → BUILD SUCCESSFUL):
- [x] `cert/CertManager.kt` (ECC P-256, AndroidKeyStore, alias `asop_distributor_cert`, prefs `asop_distributor_cert`), `cert/AsopKeyManager.kt` (mTLS OkHttp, legacy trust-all TrustManager).
- [x] `network/CertSignHmacInterceptor.kt` (X-API-Key/X-Timestamp/X-Signature, HMAC-SHA256 по timestamp, секреты из BuildConfig), `network/CertSignApi.kt` (cert-sign + poll `/api/v1/events/{id}`), `network/DistributorSyncApi.kt` (register/keys/tariffs, mTLS).
- [x] `sync/SyncStore.kt` (DataStore: serial, distributor_terminal_id, last_keys_version, last_tariffs_version, keys_json, tariffs_json, synced_at_ms), `sync/ProvisionSyncManager.kt` (certify → poll 4-hop саги → register → keys/tariffs delta → merge).
- [x] `core/KeyProvider.kt` переписан: кэш из `sync_store.keys()`; каждый цикл `decrypt(keyMaterial)` через `TerminalKeyCryptor`; soft-deleted выбрасываются; кандидаты без KDF-fallback, zero-ключ обязателен в конце; dev-fallback `ByteArray(24){it}` пока ключей нет. `core/TariffProvider.kt`: `defaultFare` = `min(price)` среди active записей с `carrierId/zoneId/pathId` = null → fallback min(active price) → 30.0.
- [x] `AppGraph.kt` (ручной DI без Hilt: moshi, cryptor, certManager, syncStore, plain/mTLS OkHttp, api-клиенты, syncManager, keyProvider, tariffProvider, paymentClient), `DistributorApp.kt` (держит graph), `MainActivity` (карточка синка + auto-bootstrap при старте, операторская карта из `sync_store`).
- [x] Moshi: рефлексия (`KotlinJsonAdapterFactory`), без kapt/codegen — DTO без `@JsonClass(generateAdapter=true)`. BuildConfig: `GATEWAY_BASE_URL` (default `10.0.2.2`), `CERT_SIGN_API_KEY`/`CERT_SIGN_HMAC_SECRET` (dev-дефолты = gateway `asop.cert-sign-api-keys`), `DISTRIBUTOR_CARDS_DISTRIBUTOR_ID`, `PAYMENT_PROVIDER_ID`.

**Заметки:**
- ~~Distributor переиспользует терминальный cert-sign flow~~ → **заменено** (2026-09-20): отдельный distributor cert-sign, без `ASOP_TERMINALS`-артефакта — см. «Статус Фазы 5++» ниже. Сага: gateway (HMAC) → crypto (`CertIssued`) → terminal-service (`CertStored`) → gateway `EventService.complete` → терминал хранит цепочку PEM.
- mTLS-клиент `AsopKeyManager` без валидации hostname/issuer — MVP (как legacy TrustManager в gateway WebClient).
- `includeBuild("../android-nfc")` внутри distributor standalone-сборки — ок (вложенный includeBuild ломает только у композит-членов корня, терминал — композит, distributor — нет).
- Kotlin-нюанс: `/**` внутри KDoc (путь `/distributor/**`) открывает вложенный блок-коммент → «Unclosed comment». В doc-комментариях пути `/distributor/**` нельзя писать с `**` — только `/distributor/...` или экранировать.

**Осталось (Phase 5+ / blocked):**
- E2E на устройстве: cert-sign → register → keys/tariffs delta (нужен поднятый Docker + `gateway.host` реального шлюза в `frontend/android-distributor/local.properties`; сейчас там только `sdk.dir`).
- Тап физических карт на F20 (операторская VCM1 → пассажирская VCM1 → tripsLeft + `/pay`) — нужен доступ к устройству с картами.
- Реальный EMV-контур (FTSDK EMV + ключи ВТБ) — ждёт SDK от ВТБ (`VtbSirposAdapter` — заглушка).

## Статус Фазы 5++ — отдельный distributor cert-sign + app-payment mTLS (2026-09-20)

**Причина**: distributor переиспользовал терминальный `POST /api/v1/terminals/cert-sign`, который через `ensureTerminal` создавал строку в `ASOP_TERMINALS` (и `ASOP_TERMINAL_CERTS`) — артефакт «фейкового терминала». Решено (пользователь): **отдельный distributor cert-sign**.

**Отдельный distributor cert-sign** (backend, `./gradlew clean :backend:gateway-service:build :backend:crypto-service:build :backend:terminal-service:build -x test` — BUILD SUCCESSFUL):
- `asop-kafka-contracts` `CertEvents.kt`: `CertSignRequested`/`CertIssued` получили `distributor: Boolean`, `cardsDistributorId: UUID?`, `paymentProviderId: String?`, `terminalModel: String?`.
- `terminal-api` `CertSignRequest`: те же флаги (default `false`/`null`).
- crypto-service: `CertCommandConsumer`/`CertIssuedPublisher.publishIssued` пробрасывают флаги (passthrough).
- terminal-service `CertCommandService`: ветка `handleDistributor` (при `distributor=true`) — валидация `cardsDistributorId`+`paymentProviderId`, tx-upsert `ASOP_DISTRIBUTOR_TERMINALS` (НЕ `ASOP_TERMINALS`, НЕ `ASOP_TERMINAL_CERTS`), `CertStored.certId = UuidUtils.newId()` (сервер-side сертификат не хранится).
- gateway: `CertCommandService.publishDistributor` (+ рефактор `publishEvent`), `CertCommandController` `POST /api/v1/distributor-terminals/cert-sign` (400 при `distributor=false`), `CertSignHmacFilter` допускает оба пути, `SecurityConfig` Order(3) `permitAll(POST …/distributor-terminals/cert-sign)`.

**app-payment mTLS** (переиспользует distributor cert-sign — кросс-решение, без новых серверных правок):
- `frontend/android-payment`: + OkHttp 4.12.0, BuildConfig `CERT_SIGN_API_KEY`/`CERT_SIGN_HMAC_SECRET`/`DISTRIBUTOR_CARDS_DISTRIBUTOR_ID`/`PAYMENT_PROVIDER_ID`(default `MOCK-PAY`).
- `cert/CertManager.kt` (ECC P-256, alias `asop_payment_cert`), `cert/AsopKeyManager.kt`, `network/CertSignHmacInterceptor.kt`, `network/ProvisioningManager.kt` (cert-sign → poll → store PEM → `DistributionConfig.setPaired(true)`), `AcquirerReportClient` теперь реально постит `POST /api/v1/payment/report` по mTLS (до pairing — `DEFERRED`).
- **Ключевой нюанс**: distributor и app-payment на том же F20 имеют **одинаковый `ANDROID_ID`** (один debug-ключ подписи), а cert-sign upsert'ит по `terminalSerial` → без префикса слили бы в одну строку. app-payment использует serial `PAY-<ANDROID_ID>` → вторая строка `ASOP_DISTRIBUTOR_TERMINALS`. Плюс `uq_distributor_terminals_provider_id` (UNIQUE `PAYMENT_PROVIDER_ID`) требует уникального provider на строку (distributor `MOCK`, app-payment `MOCK-PAY`).

**E2E (F20 `of8e6020`, gateway `192.168.1.6:8080`)**:
- distributor: cert-sign → register (200) → keys(1)/tariffs(250) — строка `3bbdeaa683b76147`, provider `MOCK`.
- app-payment: cert-sign → цепочка PEM сохранена, `paired=true` — строка `PAY-3bbdeaa683b76147`, provider `MOCK-PAY`.
- `ASOP_TERMINALS` / `ASOP_TERMINAL_CERTS` — пусты (0/0), артефакта нет.

**Известный гэп (dev OK, prod TODO)**: прямой `keys/delta` у distributor отдаёт `KEY_MATERIAL` = RSA-шифротекст (зашифрован публичным ключом сервера), а `KeyProvider` пытается AES-GCM-дешифровать через `TerminalKeyCryptor` → `decrypt key … failed`, `candidateKeys()` = dev-fallback `DEV_KEY(24) + ZERO_KEY(6)`. В dev-режиме `DEV_KEY` = dev-ключу crypto (`AAECAwQFBgc…`), поэтому тап работает. Для prod нужен RSA→AES-GCM-трансформ (аналог `KeyService` оркестратора) до `mergeKeys`.

## Статус 2026-09-21 — HTTP-handoff, mTLS app-payment, колонки валидаций, EMV-доступ F20

**HTTP-handoff банковской карты (терминал + distributor → app-payment по loopback, без Intent):**
- Терминал: `payment/PaymentHttpClient.kt` (POST `/pay` + poll `/status`, HMAC по timestamp, `127.0.0.1:8790`); `SessionFlowViewModel.beginBankHandoff` сам шлёт HTTP в корутине (вместо `startActivityForResult(ACTION_PAY)`), `SessionFlowScreen` освобождён от ActivityResult. Промпт «приложите карту повторно» остался в `OpenTripScreen`.
- Distributor: флоу перестроен под идеал `оператор → сумма+способ → [банк-карта → /pay] → MIFARE запись`; добавлен discriminator `MifareClassic.get(tag) != null` (MIFARE локально, не-MIFARE → HTTP); `TopUpViewModel` переписан (шаги `METHOD`/`BANK_CARD`/`MIFARE_WRITE`, read+write в одной mfc-сессии).
- Контракт app-payment `/pay` не менялся (`LocalPaymentServer`, HMAC по `x-timestamp`).

**mTLS-идентичность app-payment** (переиспользует distributor cert-sign — решение «крёст»):
- `cert/CertManager` (алиас `asop_payment_cert`), `cert/AsopKeyManager`, `network/CertSignHmacInterceptor`, `network/ProvisioningManager` (cert-sign → poll → store PEM → `DistributionConfig.setPaired(true)`), `AcquirerReportClient` теперь реально постит `POST /api/v1/payment/report` по mTLS (до pairing — `DEFERRED`).
- **Нюанс**: distributor и app-payment на одном F20 имеют одинаковый `ANDROID_ID` (один debug-ключ), а cert-sign upsert'ит по `terminalSerial` → app-payment использует serial `PAY-<ANDROID_ID>` → вторая строка `ASOP_DISTRIBUTOR_TERMINALS`; `uq_distributor_terminals_provider_id` требует уникальный provider (distributor `MOCK`, app-payment `MOCK-PAY`). OkHttp добавлен в deps.

**Банк-валидация в web-admin «Валидации»:**
- Терминал при `APPROVED` банк-картой пишет транзакцию «Оплата проезда» (`…0801`/`…0901`) с `amount`=тариф и метадатой `{bankCard, paymentSystem, cardLast4, cardToken, …}` (`SessionFlowViewModel.recordBankPayment`).
- `pages/Sessions.tsx`: колонки «Платёж (банк)» (`МИР:*1234` из метадаты) + «Сумма» (`amount`). Платёжная система — из BIN (`PaymentHandoff.paymentSystem`).

**EMV-доступ F20 — диагностика (важный вывод, корректирует прежнее «NFC залочен»):**
- FTSDK `NfcReader.openCardEx(0)` → `89` (`ERR_OP_TIMEOUT`) — потому что нужен cardType `2`=`CARD_TYPE_RF`, а не `0`(IC).
- Лицензионное ядро **доступно и работает**: `Emv.searchCard(2)` детектит МИР (ATS-обмен), `Emv.startEMV`/`startTransaction` стартует и задаёт TransData.
- **PAN карта отдаёт только внутри полной EMV-транзакции** (`SELECT→GPO→READ RECORD` в `startEMV`); через `getCardData("5A")`/`GET DATA` вне транзакции — «данные недоступны».
- До PAN нужно: корректный драйв интерактивного `startEMV`-флоу + загрузка терминальных параметров (AID-table / CAPK). Секретные MAC/SKI/DEA (ВТБ) — только для онлайн-авторизации.
- Диагностика в `app-payment`: `core/BankCardProbe.kt`, `core/EmvProbe.kt`, endpoints `GET /probe/card`, `/probe/emv`, `/probe/startemv`.

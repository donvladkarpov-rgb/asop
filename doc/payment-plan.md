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

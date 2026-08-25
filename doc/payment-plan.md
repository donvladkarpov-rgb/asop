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

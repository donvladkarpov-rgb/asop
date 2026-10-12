# ПРОМПТ 017: Модуль фискализации (fiscalization)

Цель: реализовать фискализацию банковских платежей по схеме «терминал → сервер (fiscal-service) → фискальный посредник (адаптер)» с отображением QR-чека на Android-терминале/дистрибьюторе, идемпотентностью и retry-механизмом.

## 1. Контекст проекта

Стек: Kotlin 2.0.21, Spring Boot 3.3.5 (WebFlux/R2DBC), PostgreSQL 14+PostGIS, Kafka 3.7.1, Redis, Gradle 8.10.2, Liquibase v001 единственный changeset при разработке.

Существующее:
- fiscal-service + fiscal-api (пусты/частично есть) — см. backend/fiscal-service, backend/shared/api/fiscal-api
- asop-kafka-contracts: FiscalReceiptRequestedEvent (уточнить при реализации)
- gateway: FiscalCommandController/FiscalCommandService — есть эндпоинты под отправку FiscalReceiptRequested в Kafka
- Android terminal/distributor: буферизация write-команд в Room + SyncWorker + EventPollWorker + Event polling
- Схема БД: ASOP_FISCALIZERS, ASOP_CARRIER_FISCALIZERS, ASOP_FISCALIZER_TOKENS, ASOP_FISCAL_RECEIPTS, ASOP_FISCAL_RECEIPT_ATTEMPTS, ASOP_FISCAL_MONTHLY_REPORTS (см. schema)
- ASOP_CONTRACTS: CONTRACTOR_TYPE, CARRIER_ID, CARDS_DISTRIBUTOR_ID, ATTRIBUTES, VERSION и др.

## 2. БД правки (Liquibase)

Править ТОЛЬКО в единственном changeset `v001-init` (dev-правило). Внести в `infrastructure/db-migrations/asop_schema.sql` и при необходимости в `v001-init.sql` (идентично).

### 2.1 ASOP_DISTRIBUTOR_FISCALIZERS (НОВАЯ)
```sql
CREATE TABLE ASOP_DISTRIBUTOR_FISCALIZERS
(
  DISTRIBUTOR_FISCALIZER_ID UUID         NOT NULL,
  CARDS_DISTRIBUTOR_ID      UUID         NOT NULL,
  FISCALIZER_ID             UUID         NOT NULL,
  CONTRACT_ID               UUID         NOT NULL,
  CUSTOM_API_URL            VARCHAR(500),
  IS_PRIMARY                BOOLEAN      DEFAULT false,
  IS_ACTIVE                 BOOLEAN      DEFAULT true,
  SETTINGS                  JSONB,
  CREATED_AT                TIMESTAMPTZ  NOT NULL,
  UPDATED_AT                TIMESTAMPTZ  NOT NULL,
  CONSTRAINT pk_distributor_fiscalizers PRIMARY KEY (DISTRIBUTOR_FISCALIZER_ID),
  CONSTRAINT fk_df_distributor FOREIGN KEY (CARDS_DISTRIBUTOR_ID) REFERENCES ASOP_CARDS_DISTRIBUTORS (CARDS_DISTRIBUTOR_ID),
  CONSTRAINT fk_df_fiscalizer   FOREIGN KEY (FISCALIZER_ID)   REFERENCES ASOP_FISCALIZERS (FISCALIZER_ID),
  CONSTRAINT fk_df_contract     FOREIGN KEY (CONTRACT_ID)     REFERENCES ASOP_CONTRACTS (CONTRACT_ID),
  CONSTRAINT uq_distributor_fiscalizer UNIQUE (CARDS_DISTRIBUTOR_ID, FISCALIZER_ID)
);
COMMENT ON TABLE ASOP_DISTRIBUTOR_FISCALIZERS IS 'Настройки фискализации для дистрибьютора карт.';
CREATE INDEX idx_df_distributor ON ASOP_DISTRIBUTOR_FISCALIZERS (CARDS_DISTRIBUTOR_ID);
CREATE INDEX idx_df_primary ON ASOP_DISTRIBUTOR_FISCALIZERS (CARDS_DISTRIBUTOR_ID) WHERE IS_PRIMARY = true AND IS_ACTIVE = true;
```

### 2.2 ASOP_CARRIER_FISCALIZERS — добавить CONTRACT_ID
В `ASOP_CARRIER_FISCALIZERS` добавить:
```sql
CONTRACT_ID               UUID         NOT NULL,
CONSTRAINT fk_cf_contract FOREIGN KEY (CONTRACT_ID) REFERENCES ASOP_CONTRACTS (CONTRACT_ID)
```
Учитывай, что в dev `v001-init` правится целиком.

### 2.3 ASOP_FISCALIZER_TOKENS
Ссылка только на существующую связь перевозчика. Токены шифруются в БД (Bytea). На Android **не** синкаются.

### 2.4 ASOP_FISCAL_RECEIPTS
Ключевые поля:
- RECEIPT_ID UUIDv7 — он же **Idempotency-Key**, PK
- TRANSACTION_ID UUID NOT NULL (FK ASOP_TRANSACTIONS)
- CARRIER_ID UUID NULL
- CARDS_DISTRIBUTOR_ID UUID NULL
- CARRIER_FISCALIZER_ID UUID NULL
- DISTRIBUTOR_FISCALIZER_ID UUID NULL
- SOURCE VARCHAR(20) CHECK ('TERMINAL','DISTRIBUTOR')
- PAYMENT_TYPE VARCHAR(20) DEFAULT 'CARD'
- RECEIPT_NUMBER VARCHAR(100), FISCAL_SIGN VARCHAR(100)
- STATUS VARCHAR(20) CHECK ('PENDING','SENT','CONFIRMED','RETRY','FAILED','CANCELLED')
- ATTEMPT_COUNT INT, MAX_ATTEMPTS INT DEFAULT 10
- LAST_ATTEMPT_AT, NEXT_RETRY_AT TIMESTAMPTZ
- LAST_ERROR_MESSAGE VARCHAR(1000)
- RECEIPT_DATA JSONB (минимум/расширенный контекст, который адаптер использует)
- REQUEST_PAYLOAD JSONB (финальный payload, отправленный адаптером)
- RESPONSE_DATA JSONB (ответ фискализатора)
- FISCAL_URL VARCHAR(500) (ссылка на чек для QR, напр. https://fp.bifit.com/processing-api/receipts/{RECEIPT_ID})
- CONFIRMED_AT, CREATED_AT, UPDATED_AT

Добавь индексы: idx_fr_transaction, idx_fr_carrier, idx_fr_distributor, idx_fr_status, idx_fr_retry_pending (STATUS, NEXT_RETRY_AT WHERE STATUS='RETRY' AND NEXT_RETRY_AT NOT NULL), idx_fr_created.

### 2.5 ASOP_FISCAL_RECEIPT_ATTEMPTS
Как в схеме: FK RECEIPT_ID CASCADE, TOKEN_ID опц.

### 2.6 ASOP_FISCAL_MONTHLY_REPORTS
Оставить как есть. Ничего не реализовывать сейчас (только структура).

## 3. Модули/пакеты

- backend/shared/api/fiscal-api — Controller interfaces + DTOs (ru.asop.api.fiscal.*)
- backend/fiscal-service — реализация (ru.asop.fiscal.*): config, controller, kafka, model(entity/repo), service, adapter, scheduler, util
- backend/shared/asop-kafka-contracts — при необходимости доработать FiscalReceiptRequestedEvent под новый контекст
- gateway-service — проксирование /api/v1/fiscal/** через ServiceRegistry (fiscal-service:8090) либо уже есть
- admin-service/web-admin — минимальный CRUD для fiscalizers/carrier-fiscalizers/distributor-fiscalizers/fiscalizer-tokens (токены только просмотр/добавление/деактивация, хранить зашифрованными)
- android-terminal + android-distributor — интеграция QR + запрет NFC 3с

## 4. Kafka

Топики по конвенции asop.{domain}.{commands|events}
- asop.fiscal.commands — FiscalReceiptRequestedEvent (gateway/terminal-flow)
- asop.fiscal.events — FiscalReceiptProcessedEvent (fiscal-service → gateway/consumers)

Фиксировать контракт в asop-kafka-contracts.

## 5. FiscalReceiptRequestedEvent (контекст от терминала/дистрибьютора)

Терминал формирует:
- receiptId: UUIDv7 (Idempotency-Key) — дублирует idempotency
- transactionId: UUID
- sessionId? (shift/trip)
- terminalId: UUID
- source: TERMINAL|DISTRIBUTOR
- carrierId? , distributorId? (cardsDistributorId)
- fiscalContext: JSON-объект с максимумом инфо для чека:
  - paymentAddress (напр. "Транспортное средство"/ИНН/адрес организации)
  - cashier: {name, tin?}
  - client: {address?}
  - items: [{calculationMethod, paymentSubject, name, price, quantity, vat, total, ...}]
  - total: BigDecimal
  - payments: {CARD: amount, ...}
  - userRequisite: {name, value} (инфо о проезде: билет/время/госномер ТС)
  - routeInfo?, vehicleInfo?, ticketNo?, paymentTime(ISO8601), metadata

Адаптер в fiscal-service собирает финальный REQUEST_PAYLOAD под конкретного фискализатора.

## 6. Адаптеры фискализации

Интерфейс:
```kotlin
interface FiscalizerAdapter {
  fun providerCode(): String  // 'BIFIT' и др
  fun buildRequest(ctx: FiscalContext): JsonNode/Map
  fun parseResponse(resp: HttpResponse): FiscalizerResult
  fun receiptUrl(receiptId: UUID, baseUrl: String?, customUrl: String?): String
}
```

Реализовать **BifitAdapter** по примеру из промпта:
- POST {base/custom}/processing-api/protected/documents/registration/receipts/token
- Headers: Connector-Token, Idempotency-Key, Content-Type: application/json
- Ответ 200 → body содержит fiscal number (681798784 в примере) — сохранить в RECEIPT_NUMBER/RESPONSE_DATA
- receiptUrl = https://fp.bifit.com/processing-api/receipts/{receiptId} (базовый шаблон при bifit; хранить можно в SETTINGS/ADAPTER)

Сервис **FiscalDispatchService** выбирает активный PRIMARY CARRIER_FISCALIZER/DISTRIBUTOR_FISCALIZER по carrierId/distributorId + IS_ACTIVE+IS_PRIMARY. Если нет PRIMARY — fallback на любой активный.

## 7. fiscal-service

Структура:
- model: FiscalizerEntity, CarrierFiscalizerEntity, DistributorFiscalizerEntity, FiscalizerTokenEntity, FiscalReceiptEntity, FiscalReceiptAttemptEntity (+ MonthlyReports)
- repository: R2dbc
- service: FiscalReceiptService, FiscalizerTokenService (расшифровка токена при отправке), FiscalDispatchService, RetryScheduler
- adapter: FiscalizerAdapterRegistry + BifitAdapter
- kafka: FiscalCommandConsumer (асинхронно принимает FiscalReceiptRequestedEvent) → создаёт/обновляет ASOP_FISCAL_RECEIPTS (PENDING), при успехе отправки переводит в SENT/CONFIRMED/RETRY/FAILED
- controller: internal (для админки CRUD) + при необходимости /api/v1/fiscal/receipts/{id}/status (sync)

**RetryScheduler**:
- @Scheduled (fixedDelay) каждые N секунд (из application.yml + env FISCAL_RETRY_INTERVAL_MS)
- выборка STATUS='RETRY' AND NEXT_RETRY_AT <= now()
- ограничение по ATTEMPT_COUNT < MAX_ATTEMPTS
- при успехе → CONFIRMED, FISCAL_URL, CONFIRMED_AT
- при ошибке → инкремент попытки, LAST_ERROR, рассчитать NEXT_RETRY_AT (экспонента/backoff: 30s,60s,120s,... макс ~1ч)
- при ATTEMPT_COUNT>=MAX_ATTEMPTS → CANCELLED (с LAST_ERROR)

Параметр MAX_ATTEMPTS/интервалы в application.yml (ссылки на ENV).

## 8. Gateway

ServiceRegistry: `fiscal` → fiscal-service:8090
Уже есть FiscalCommandController/FiscalCommandService для терминальных sync-команд (если нужно) — не ломать. Проксирование CRUD через ProxyController.

## 9. Admin-service + web-admin

CRUD:
- /api/v1/fiscalizers (code,name,baseApiUrl,isActive,...)
- /api/v1/carrier-fiscalizers (carrierId,fiscalizerId,contractId,customApiUrl,isPrimary,isActive,settings)
- /api/v1/distributor-fiscalizers (cardsDistributorId,fiscalizerId,contractId,customApiUrl,isPrimary,isActive,settings)
- /api/v1/fiscalizer-tokens (carrierFiscalizerId, tokenPlain? → шифровать перед сохранением, tokenHint, validFrom, validUntil,status)

Шифрование токенов: использовать тот же подход, что в проекте (server-key/crypto util) или симметричное AES-GCM с ключом из env (FISCAL_TOKEN_SECRET). Не хранить plaintext.

## 10. Android: терминал (transport) и дистрибьютор

### 10.1 Модель данных
Room:
- FiscalReceiptPendingEntity: receiptId(UUIDv7), transactionId, payloadJson, status(PENDING/SENDING/SENT/FAILED), createdAt, eventId?, fiscalUrl?, shownAt?
Или просто хранить в PendingEventEntity как фискальный event? Альтернатива — добавить тип FISCAL_RECEIPT в PendingEvent.

Но проще: при успешной банковской транзакции (после формирования TransactionCompleted event-пейлоада) сразу создать **локальный** фискальный pending:
- сгенерировать receiptId = UUIDv7
- собрать fiscalContext из доступных данных (session/shift/trip, route/path/vehicle, terminal, cashier=user, items, total, userRequisite)
- положить в Room `fiscal_receipts_pending` (новая таблица v7 Room) со статусом PENDING

### 10.2 Отправка
Фискальный event шлётся через SyncWorker/EventPollWorker вместе с остальными PENDING. Событие — `FiscalReceiptRequested` (mTLS /api/v1/sync/fiscal/receipts? или через общий sync-флоу). Gateway пускает в Kafka asop.fiscal.commands.

### 10.3 QR + блокировка NFC
После **успешного формирования** транзакции и фискальной структуры (оптимистично, даже если нет инета) показать диалог/оверлей:
- QR код из `fiscalUrlTemplate`? Но финальный fiscalUrl станет известен после ответа fiscal-service (CONFIRMED) → содержит receiptId.
- На 3 секунды: `NfcAdapter.disableReaderMode()` (или флаг `isFiscalQrShowing=true` → блокировать новые тапы)
- Показать QR по `https://fp.bifit.com/processing-api/receipts/{receiptId}` (для bifit). Для других адаптеров использовать `adapter.receiptUrl(receiptId,...)`
- Через 3 секунды скрыть, включить reader mode обратно, вернуться к предыдущему экрану
- Работает оффлайн на момент показа (URL известен по receiptId + выбранному фискализатору)

### 10.4 Интеграция
В местах успешной оплаты банковской картой (терминал: SessionFlow/Payment flow, дистрибьютор: пополнение/продажа) — после валидации "не в чёрном списке" и перед/после постановки TransactionCompleted в очередь — сформировать фискальный pending + показать QR на 3с + заблокировать NFC.

## 11. Проверка работоспособности

- Компиляция: `./gradlew clean build` (все модули)
- Liquibase: контейнер применяет v001
- CRUD фискализаторов в web-admin
- Терминал формирует receiptId+fiscalContext → шлёт событие → fiscal-service создаёт RECEIPT(PENDING) → выбирает адаптер → шлёт в bifit с Idempotency-Key=receiptId → при 200 пишет CONFIRMED + FISCAL_URL + номер
- Retry job: при временной ошибке → RETRY + backoff + попытки до MAX_ATTEMPTS→CANCELLED
- Android QR блокирует NFC 3с
- Дистрибьютор-апп аналогично

## 12. Файлы для правок

- infrastructure/db-migrations/asop_schema.sql (+ v001-init.sql по смыслу)
- backend/shared/api/fiscal-api/src/main/kotlin/ru/asop/api/fiscal/**
- backend/fiscal-service/src/main/kotlin/ru/asop/fiscal/**
- backend/shared/asop-kafka-contracts/src/main/kotlin/ru/asop/kafka/events/fiscal/**
- backend/gateway-service (ServiceRegistry при необходимости)
- backend/admin-service (Fiscalizer* controllers/repo если нет)
- frontend/web-admin (страницы фискализации)
- frontend/android-terminal (QR+NFC блок)
- frontend/android-distributor (аналогично)

## 13. Требования к реализации

- Kotlin idiomatic, WebFlux/R2DBC как в проекте
- UUIDv7 через UuidUtils/newId
- Без комментариев в коде (кроме KDoc при явной необходимости)
- Соблюдать существующие naming/conventions
- Меньше "умных" обобщений, читаемо
- Не трогать ASOP_FISCAL_MONTHLY_REPORTS функционально

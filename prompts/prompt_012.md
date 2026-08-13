# Промпт 012: Terminal Event Watermark (per-terminal seq) + Sync/Download informers

## Цель

Гарантировать **последовательную обработку** событий, отправляемых терминалом на сервер, чтобы избежать
потери данных из-за out-of-order delivery или FK-violations, а также добавить UI-информеры
синхронизации которые не мешают друг другу.

## Проблемы которые решаются

### 1. Out-of-order delivery + FK dependencies

Терминал эмитит 10 типов событий через `PendingEventEntity` → `SyncWorker` (15 мин periodic + on network restore)
→ `POST /api/v1/sync/{resource}` под mTLS → gateway → Kafka → 6 backend consumers. Kafka
**гарантирует порядок только внутри partition, не между topic'ами**.

`ASOP_SESSIONS` имеет FK на 7 справочников (`cards`, `users`, `terminals`, `tids`, `paths`,
`vehicles`, `session_types`). Если `SESSION_OPEN` приходит раньше `CARD_REGISTER` или любое
related-event, server-side INSERT падает на FK. На стороне терминала SESSION_OPEN улетает в
FAILED → данные теряются (больше не retry).

### 2. UI informants — visual conflict

Когда одновременно:
- **Download informer**: входящие данные с сервера (delta-sync, full-sync)
- **Upload informer**: исходящие данные на сервер (sync events из `pending_events`)

они накладываются друг на друга и мешают пользователю.

## Архитектура решения

### Per-terminal sequence numbering

Каждое исходящее событие на терминале получает монотонный `seq` (counter в DataStore).
Gateway пробрасывает `X-Event-Seq` HTTP header → Kafka header `X-Terminal-Seq`.
На сервере — таблица `asop_terminal_event_watermark(terminal_id, last_seq)`.

**Правила:**
- Если `seq == last_seq + 1`: событие применяется, watermark обновляется, drain цепочка pending'ов
- Если `seq > last_seq + 1`: событие кладётся в `asop_terminal_pending_seq`, ACK возвращается,
  но реальный INSERT не происходит — ждём предыдущие seq
- Если `seq <= last_seq`: идемпотентно ALREADY_APPLIED (можно repeat-send)
- ACK издаётся в любом случае (с `status: "PENDING_WATERMARK"` или `"IN_PROGRESS"`)

### Sync upload + Download bottom sheets (non-overlapping)

- **Download informer** (выезжает снизу): показывает прогресс delta-sync/full-dump
  (chunks downloaded, applied, total). Якорь — низ экрана (bottom=0dp).
- **Upload informer** (выезжает снизу чуть выше download): показывает pending_events
  PENDING → SENDING → SENT. Якорь — `paddingBottom = download_informer_height`.

  Если только upload — download не показывается (height=0).
  Если только download — upload не показывается.

## Изменения по слоям

### 1. Liquibase migration — `infrastructure/db-migrations/v001-init.sql`

Добавить в единый changeset (AGENTS.md: один changeset `id: v001-init`):

```sql
CREATE TABLE ASOP_TERMINAL_EVENT_WATERMARK (
    TERMINAL_ID UUID PRIMARY KEY REFERENCES ASOP_TERMINALS(TERMINAL_ID),
    LAST_SEQ BIGINT NOT NULL DEFAULT 0,
    UPDATED_AT TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE ASOP_TERMINAL_PENDING_SEQ (
    TERMINAL_ID UUID NOT NULL REFERENCES ASOP_TERMINALS(TERMINAL_ID),
    SEQ BIGINT NOT NULL,
    EVENT_TYPE VARCHAR(50) NOT NULL,
    PAYLOAD TEXT NOT NULL,
    HEADERS_JSON TEXT,
    RECEIVED_AT TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    ACKS_PUBLISHED_AT TIMESTAMPTZ,
    PRIMARY KEY (TERMINAL_ID, SEQ)
);

CREATE INDEX idx_pending_seq_terminal_seq
    ON ASOP_TERMINAL_PENDING_SEQ (TERMINAL_ID, SEQ);
CREATE INDEX idx_pending_seq_acks_pending
    ON ASOP_TERMINAL_PENDING_SEQ (ACKS_PUBLISHED_AT)
    WHERE ACKS_PUBLISHED_AT IS NULL;
```

### 2. Backend common — новый `WatermarkProcessor`

`backend/shared/asop-common/src/main/kotlin/.../WatermarkProcessor.kt`:

```kotlin
interface WatermarkProcessor {
    fun applyInOrder(
        terminalId: UUID,
        seq: Long,
        eventType: String,
        payload: String,
        headers: Map<String, String>,
        applyFn: () -> Mono<Void>,
        ackEventId: UUID
    ): Mono<ApplyResult>  // APPLIED | DEFERRED | ALREADY_APPLIED

    enum class ApplyResult { APPLIED, DEFERRED, ALREADY_APPLIED }
}
```

Реализация: DatabaseClient SELECT last_seq → compare → apply or defer → drain chain.

### 3. Backend consumers (6 файлов)

В каждый sync consumer добавить header `X-Terminal-Seq`:
- `session-service/.../SessionCommandConsumer.kt` (SESSION_OPEN + SESSION_CLOSE)
- `card-service/.../CardCommandConsumer.kt` (CARD_REGISTER + CARD_BLOCK)
- `card-service/.../TransactionCommandConsumer.kt` (TRANSACTION_COMPLETE)
- `debt-service/.../DebtCommandConsumer.kt` (DEBT_CREATE + DEBT_RECOVER)
- `fiscal-service/.../FiscalCommandConsumer.kt` (FISCAL_RECEIPT)
- `audit-service/.../AuditCommandConsumer.kt` (AUDIT_TASK)
- `session-service/.../GpsCommandConsumer.kt` (GPS_POSITION)

Все через `WatermarkProcessor.applyInOrder(...)`.

### 4. Gateway

- `service/SessionCommandService.kt` (и 5 других sync services): парсить `X-Event-Seq` HTTP
  header, передавать в Kafka header `X-Terminal-Seq`.
- Новый endpoint `controller/TerminalEventWatermarkController.kt`:
  - `GET /api/v1/terminals/{id}/event-watermark` → `{terminalId, lastSeq, pendingSeqCount, lastEventAt}`
  - Используется терминалом для bootstrap/catch-up при reset/reconnect

### 5. Android (терминал)

#### 5a. `db/SyncPreferences.kt`
Добавить `KEY_LAST_EVENT_SEQ: longPreferencesKey`. Методы:
- `nextSeq(): suspend Long` (read+1+persist)
- `lastSeq(): Flow<Long?>`
- `resetSeq()`
- `watermarkFromServer(): Flow<Long?>`

#### 5b. `db/entity/PendingEventEntity.kt`
Добавить `@ColumnInfo(name="seq") val seq: Long = 0L`.

#### 5c. `db/dao/PendingEventDao.kt`
- `getPendingOrdered(): List<PendingEventEntity>` — ORDER BY seq ASC
- `minPendingSeq(): Long?`, `maxPendingSeq(): Long?`, `countByStatus()`

#### 5d. `network/SyncApi.kt`
Все sync endpoints получают `@Header("X-Event-Seq") seq: Long`.

#### 5e. `network/SeqHeaderInterceptor.kt` (новый)
OkHttp interceptor: для запросов на `/api/v1/sync/**` добавляет `X-Event-Seq` header =
текущий `syncPreferences.lastSeq()`. Использование через Retrofit.

#### 5f. `worker/SyncWorker.kt`
При создании PendingEventEntity: `seq = syncPreferences.nextSeq()`. При выборке:
`getPendingOrdered()` (NOT `getPending()`). После успешного 202 → STATUS=SENDING.

#### 5g. `worker/WatermarkSyncWorker.kt` (новый)
- Periodic 1 час.
- Делает `GET /api/v1/terminals/{id}/event-watermark?lastSeq={mySeq}`.
- Если server's lastSeq > local MySeq → catch-up: terminal знает что часть events надо переслать
  (это не должно случиться если SyncWorker работал, но может при reset).
- Если server's lastSeq < local MySeq → terminal должен переслать все seq > server's lastSeq.

#### 5h. Bottom informers (в `ui/screen/MainScreen.kt`)
Два независимых ComposeBox-а в Column bottomBar:
- `DownloadInformer` (anchor bottom=0dp): показывает прогресс delta-sync/full-dump.
- `UploadInformer` (anchor bottom=`downloadInformerHeight + 4dp`): показывает pending_events
  PENDING/SENDING очередь.
- Если только один informer активен → не показывается второй.

## Исполнение в ASOP Platform

### Build pipeline

```bash
# Backend rebuild
./gradlew :backend:session-service:backend:asop-common:backend:gateway-service:bootJar
./gradlew :backend:card-service:backend:audit-service:backend:debt-service:backend:fiscal-service:bootJar

# Android
cd frontend/android-terminal && ./gradlew :app:assembleDebug

# Full restart
docker compose -f infrastructure/docker/docker-compose.yml down -v
docker compose -f infrastructure/docker/docker-compose.yml up -d --build
```

### Validation

In-order delivery:
1. Тап карты в "Открыть смену" → `CARD_REGISTER` помещается в pending_events с seq=1.
2. SyncWorker отправляет на gateway → Kafka → card-service → INSERT `ASOP_CARDS` → ACK → terminal помечает SENT.
3. "Подтвердить смену" → `SESSION_OPEN` с seq=2 → INSERT `ASOP_SESSIONS` → OK.

Out-of-order (artificial):
1. Вручную (adb) поменять `create_at` row в pending_events → чтобы seq=2 (SESSION_OPEN) была
   перед seq=1 (CARD_REGISTER) → при SyncWorker sync → server кладёт seq=2 в pending_seq table,
   ACK → terminal помечает SENT.
2. Через тап карты → seq=1 CARD_REGISTER приходит → server cascade: INSERT ASOP_CARDS → drain
   pending_seq → найден seq=2 → INSERT ASOP_SESSIONS → ACK.
3. Terminal поллить `GET /events/{gatewayEventId}` → оба события становятся COMPLETED.

## Compatibility

- Старые events без `seq` → устанавливается default=0 → server их принимает но watermark не двигает.
- Старый seq=0 может конфликтовать если несколько events имеют seq=0; primary key
  `(terminal_id, seq)` не позволит дубликат — второй такой INSERT упадёт ON CONFLICT.
  Решение: логика applyInOrder считает seq=0 как ALREADY_APPLIED сразу. Альтернативно —
  расширить default до `seq = max(last_seq + 1, 1)` при первой эмиссии с terminal.

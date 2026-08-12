# Промпт 011 — Управление сменами и рейсами водителя на Android-терминале

**Цель**: Реализовать полный цикл работы водителя на терминале: **открытие смены → открытие рейса → прикладывание карт пассажиров → закрытие рейса → закрытие смены**, с локальной записью в Room и асинхронной синхронизацией на сервер (Kafka через gateway).

---

## 1. Скоуп

Одна смена `SHIFT` → один активный рейс `TRIP` в каждый момент → неограниченное число валидаций пассажирских карт внутри рейса → закрытие рейса → возврат к смене (можно открыть следующий рейс) → закрытие смены.

Сессионная иерархия (3 уровня через `ASOP_SESSIONS.PARENT_SESSION_ID`):

```
ASOP_SESSIONS (parent=NULL, session_type=SHIFT) — корень смены
   └─ ASOP_SESSIONS (parent=shift_id, session_type=TRIP) — рейс
        └─ ASOP_TRANSACTIONS (session_id=trip_id) — валидации пассажиров
```

---

## 2. Сессионные типы

Добавить в `infrastructure/docker/seed-data.sql`:

- `SESSION_TYPE_CODE='TRIP'`, `SESSION_TYPE_NAME='Рейс'` — дочерняя сессия рейса внутри смены.
- `MAX_OPEN_TRIPS_PER_SHIFT=1` (hardcoded константа).

`SHIFT` и `BREAK` уже сидируются — **не трогать**.

Также добавить в `seed-data.sql`:

- `TRANSACTION_TYPE_CODE='VALIDATION'`, `name='Валидация без списания'`.
- `TRANSACTION_RESULT_CODE='VALIDATION_ONLY'`, `name='Зафиксировано (без списания)'`.

---

## 3. DDL/расширение Room

Android `SessionEntity` (`@Entity("sessions")`) сейчас имеет **6 полей** — расширить до 17+, синхронизировать с сервером:

```kotlin
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val id: String,                           // UUIDv7
    val sessionTypeCode: String,                          // "SHIFT" | "TRIP" | "BREAK"
    val sessionTypeId: String,                            // ASOP_SESSION_TYPES row id
    @ColumnInfo(name = "parent_session_id") val parentSessionId: String? = null,
    @ColumnInfo(name = "terminal_id") val terminalId: String? = null,
    @ColumnInfo(name = "tid_id") val tidId: String? = null,
    @ColumnInfo(name = "opened_by_user_id") val openedByUserId: String? = null,
    @ColumnInfo(name = "closed_by_user_id") val closedByUserId: String? = null,
    @ColumnInfo(name = "card_id") val cardId: String? = null,
    @ColumnInfo(name = "path_id") val pathId: String? = null,
    @ColumnInfo(name = "vehicle_id") val vehicleId: String? = null,
    @ColumnInfo(name = "carrier_id") val carrierId: String? = null,
    @ColumnInfo(name = "region_id") val regionId: String? = null,
    @ColumnInfo(name = "timezone") val timezone: String? = null,
    val status: String,                                   // "OPEN" | "CLOSED" | ...
    @ColumnInfo(name = "opened_at") val openedAt: Long,
    @ColumnInfo(name = "closed_at") val closedAt: Long? = null,
    @ColumnInfo(name = "opened_at_local") val openedAtLocal: Long,
    @ColumnInfo(name = "closed_at_local") val closedAtLocal: Long? = null,
    @ColumnInfo(name = "expiration_time") val expirationTime: Long,
    @ColumnInfo(name = "last_sync_at") val lastSyncAt: Long? = null,
    val attributes: String? = null                        // JSON
)
```

Новая Room entity `TripPaymentEntity` (`@Entity("trip_payments")`):

```kotlin
@Entity(tableName = "trip_payments")
data class TripPaymentEntity(
    @PrimaryKey val id: String,                           // UUIDv7
    @ColumnInfo(name = "trip_session_id") val tripSessionId: String,  // parent_session_id
    @ColumnInfo(name = "card_id") val cardId: String,                // ASOP_CARDS.card_id
    @ColumnInfo(name = "amount") val amount: Double = 0.0,           // MVP = 0.0
    @ColumnInfo(name = "transaction_result_id") val transactionResultId: String, // "VALIDATION_ONLY"
    val timestamp: Long,
    @ColumnInfo(name = "last_sync_at") val lastSyncAt: Long? = null
)
```

---

## 4. Аутентификация и авторизация

**Открытие смены / рейса**:
- Водитель прикладывает VCM1-карту (промпт 008/009 — `CardReadScreen`).
- Card ACTIVATED, `bitmask` содержит роль `DRIVER`.
- Извлекаем `cardId`, `userId` (entity.userid), проверяем:
  - `userId` существует в `asop_users` (server-pushed Room).
  - `userId` есть записи в `asop_user_carriers` (`user_carriers` в локальном Room) → даёт `carrierId`.
- Если проверки пройдены → окно «Открыть смену». Иначе — error toast.

**Закрытие смены** (по матрице):
- Другой водитель того же перевозчика: `user_carriers.carrier_id == session.carrier_id`.
- DISPATCHER: роль `CARRIER_DISPATCHER | KRS_DISPATCHER` для того же `carrier_id`.
- ADMIN перевозчика: `CARRIER_ADMIN` (если создана) для того же `carrier_id`.
- ADMIN организатора: `ORGANIZER_ADMIN` для организатора → cascade на carriers.
- ADMIN региона: `REGION_ADMIN` для региона → cascade on organizers → carriers.
- `ADMIN | SUPER_ADMIN`: любая смена.

**Закрытие рейса** — **только владелец смены** (тот же userId, что открывал). НЕ может закрыть другой водитель, диспетчер, админ.

---

## 5. Идемпотентность при offline

**Клиент генерирует UUIDv7 в начале действия**:
- Tap «Открыть смену» → card auth → `sessionId = UuidUtils.newId()` → INSERT в Room `sessions` с `status=OPEN`, `last_sync_at=NULL` → emit `PendingEventEntity` тип `SESSION_OPEN`.
- При reconnect: SyncWorker повторно отправляет все PENDING события. Поскольку `sessionId` фиксирован клиентом, сервер делает `INSERT … ON CONFLICT (SESSION_ID) DO NOTHING` → нет двойного учёта.
- Аналогично для close/open trip/close trip/transaction.

**Клиент НЕ узнаёт server-side SESSION_ID** — он генерируется на клиенте.

---

## 6. UI drawer — реализовать stubs

`TerminalNavHost.kt` уже имеет пункты (это stubs):
- «Открыть смену» → `OpenShiftScreen`
- «Закрыть смену» → `CloseShiftScreen`
- «Открыть рейс» → `OpenTripScreen` (только если shift OPEN)
- «Закрыть рейс» → `CloseTripScreen`

Реализовать сценарии:

- «Открыть смену»:
  1. Tap card → `CardReadScreen` (reuse)
  2. Если cardId авторизован как DRIVER → confirm dialog
  3. Tap «Открыть» → INSERT Room + emit SESSION_OPEN Kafka event (sessionType='SHIFT')
  4. NAV back to MainScreen

- «Закрыть смену»:
  1. Tap card → auth
  2. auth проверяет `canCloseSession` (§4)
  3. confirm dialog
  4. Tap «Закрыть» → UPDATE Room + emit SESSION_CLOSE

- «Открыть рейс» (только если shift OPEN):
  1. Bottom-sheet dropdowns (cascade):
     - **TID**: фильтр `asop_tids.carrier_id == shift.carrier_id`
     - **Vehicle**: фильтр `asop_vehicles.carrier_id == shift.carrier_id`
     - **Route**: фильтр `asop_routes.region_id == shift.region_id`
     - **Path**: фильтр `asop_paths.route_id == selected route_id`
  2. Все options из локального Room `reference_rows`
  3. confirm → INSERT Room SessionEntity (parent_session_id=shiftId, session_type=TRIP) + emit SESSION_OPEN

- «Закрыть рейс»: только тот же userId, что открыл shift.

**Bottom-informer `MainScreen`**:
- Shift OPEN, Trip CLOSED → «Смена открыта: {userFullName}. Рейс не открыт.»
- Trip OPEN → «Рейс открыт: {routeName} {pathName}. ТС: {vehiclePlate}. Ждём валидации.»
- Both CLOSED → «Смена закрыта.»

Цвета: green=open, amber=без trip, gray=closed.

---

## 7. Валидация пассажирских карт (MVP — без списания)

Внутри открытого TRIP, при tap пассажирской карты:

- Если cardId валидный → создаём `PendingEventEntity` тип `TRANSACTION_COMPLETE`:
  ```kotlin
  TransactionCompleteRequest(
      sessionId = tripSessionId,                 // PARENT_SESSION_ID
      transactionTypeId = <VALIDATION>,
      transactionResultId = <VALIDATION_ONLY>,    // NEW seed
      amount = 0.0,                                // MVP
      currency = "RUB",
      cardId = cardId,
      metadata = "MVP_NO_DEDUCT",
      regionId = trip.regionId,
      carrierId = trip.carrierId,
      timezone = deviceTz
  )
  ```

- Emit Kafka `asop.transaction.commands`. SyncWorker отправляет.
- Сервер `transaction-service` INSERT в `ASOP_TRANSACTIONS` (amount=0).

---

## 8. GPS-привязка к SHIFT

`GpsTrackingService.kt` изменить:
- Использует `SessionDao.observeCurrentOpenShift()` — Flow<SessionEntity?> где `sessionTypeCode='SHIFT' AND status='OPEN'`.
- Emit `GpsPositionReported`: `sessionId = shiftSession.id`.
- Если shift closed → буферизация GPS 5 минут in-memory (max 100 точек) → flush при reconnect.

Контракт: `ASOP_GPS_TRACKING.SESSION_ID = shift.id`.

---

## 9. Команды и endpoints

Backend сессионного модуля уже имеет `POST /api/v1/sync/sessions/open` и `PUT /api/v1/sync/sessions/{id}/close`. Этих достаточно для всех типов — `sessionTypeId`+`parentSessionId` в payload делает выбор.

**Что добавить в backend**:
- `SessionService.canCloseSession(sessionId, requester)` — матрица §4.
- `SessionService.open()` rotation: пишет `parentSessionId` для trip-сессий; пишет `cardId`, `openedByUserId`, `carrierId`, `regionId`, `timezone`, `tidId`.
- Новый seed для `SESSION_TYPES.TRIP` + `TRANSACTION_TYPES.VALIDATION` + `TRANSACTION_RESULTS.VALIDATION_ONLY`.

---

## 10. Атомарность и race conditions

**Server-side guard**:
- При `POST /session-open` с `sessionTypeId=TRIP`:
  - проверить, что текущая смена OPEN.
  - проверить, что НЕТ другой открытой TRIP (`parent=shift.id AND type=TRIP AND status IN IN_PROGRESS`). Если есть → **409 Conflict**.

**Client-side guard**:
- При tap «Открыть рейс» — если `SessionDao.getCurrentOpenTrip() != null` → запретить кнопку + snack.

---

## 11. Файлы для изменений

**Backend (Kotlin)**:
- `session-service/.../service/SessionService.kt` — добавить `canCloseSession()`, обогатить `open()`.
- `session-service/.../model/SessionEntity.kt` — добавить `carrierId`, `regionId`, `cardId`, `openedByUserId`, `closedByUserId`, `attributes`.
- `seed-data.sql` — добавить TRIP, VALIDATION, VALIDATION_ONLY.

**Android (Kotlin + Compose)**:
- `db/entity/SessionEntity.kt` — расширить до 17 полей.
- `db/entity/TripPaymentEntity.kt` — новый.
- `db/dao/SessionDao.kt` — добавить `getCurrentOpenTrip()`, `getCurrentOpenShift()`.
- `db/dao/TripPaymentDao.kt` — новый.
- `db/AppDatabase.kt` — bump версия (v6).
- `ui/screen/OpenShiftScreen.kt`, `CloseShiftScreen.kt`, `OpenTripScreen.kt`, `CloseTripScreen.kt` — новые 4 экрана.
- `ui/screen/MainScreen.kt` — bottom informer.
- `ui/TerminalNavHost.kt` — добавить routes.
- `service/GpsTrackingService.kt` — привязать sessionId к current shift.
- `network/models/SyncModels.kt` — расширить `SessionOpenRequest` (carrierId, regionId, cardId, openedByUserId).
- `worker/SyncWorker.kt` — parent_session_id в payload, idempotency.

---

## 12. Поэтапный план работы

| Шаг | Что | Оценка |
|-----|-----|--------|
| 1 | seed-data.sql: добавить TRIP, VALIDATION, VALIDATION_ONLY | 30 мин |
| 2 | Backend `SessionService.canCloseSession()`, обогатить `open()`, `SessionEntity` | 2 ч |
| 3 | Android `SessionEntity`/`TripPaymentEntity` + bump AppDatabase v6 | 1 ч |
| 4 | New screens (OpenShift, CloseShift, OpenTrip, CloseTrip) + card-auth flow | 4 ч |
| 5 | MainScreen informer + drawer onClick wiring | 1 ч |
| 6 | GPS service: shift-session binding | 1 ч |
| 7 | SyncWorker: parent_session_id in payload, idempotency | 1 ч |
| 8 | Smoke (offline, auth matrix, 409 trip conflict) | 2 ч |

**Итого**: ~12 часов.

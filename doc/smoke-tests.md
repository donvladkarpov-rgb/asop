# Smoke Tests — Terminal Sync Architecture

## Pre-conditions

```bash
# 1. Build backend JARs
./gradlew bootJar

# 2. Build Android APK
cd frontend/android-terminal && ./gradlew :app:assembleDebug && cd ../..

# 3. Start Docker fresh (all data wiped)
docker compose -f infrastructure/docker/docker-compose.yml down -v
docker compose -f infrastructure/docker/docker-compose.yml up -d --build

# 4. Wait for all containers to be healthy
docker compose ps

# 5. Verify gateway is up
curl -k https://localhost:8080/actuator/health
```

---

## Scenario 1: Cert sign saga (terminal first registration)

**Goal:** Android terminal without cert gets provisioned through 4-hop saga.

### Steps

1. Launch Android emulator
2. App detects no certificate → shows Provisioning screen
3. Tap "Generate keys and request certificate"
4. `CertificateService.provision(androidId)` выполняется — `terminalSerial` = `Settings.Secure.ANDROID_ID`, НЕ тестовая строка. Polling `GET /api/v1/events/{eventId}` читает статус из Redis (TTL 24 ч).

### Expected HTTP trace

```bash
# Step 1: POST /cert-sign (plain HTTPS, no mTLS)
curl -k -X POST https://localhost:8080/api/v1/terminals/cert-sign \
  -H "Content-Type: application/json" \
  -d '{
    "terminalSerial": "test-terminal-001",
    "terminalNumber": "T-001",
    "terminalModel": "EMULATOR",
    "publicKeyBase64": "<EC-P256-public-key-Base64>"
  }'

# Response: 202 Accepted
# Headers:
#   X-Event-Id: <uuid>
# Body:
{
  "eventId": "<uuid>",
  "topic": "asop.terminal.cert.commands",
  "acceptedAt": "2026-07-17T10:00:00Z",
  "locationHint": null
}

# Step 2: Poll event status (every 2s, max 5 min)
curl -k https://localhost:8080/api/v1/events/<eventId>

# While PENDING: 202 Accepted
{
  "eventId": "<uuid>",
  "commandTopic": "asop.terminal.cert.commands",
  "state": "PENDING",
  "createdAt": "2026-07-17T10:00:00Z"
}

# After ~5-10s: 200 OK
{
  "eventId": "<uuid>",
  "commandTopic": "asop.terminal.cert.commands",
  "state": "COMPLETED",
  "resultData": "{\"certId\":\"...\",\"terminalId\":\"...\",\"certSerial\":\"...\",\"certificateBase64\":\"...\",\"validFrom\":\"...\",\"validUntil\":\"...\",\"caChain\":\"...\"}",
  "createdAt": "2026-07-17T10:00:00Z",
  "completedAt": "2026-07-17T10:00:10Z"
}
```

### Verification

```bash
# Kafka: check topic asop.terminal.cert.commands
docker compose exec kafka kafka-console-consumer \
  --bootstrap-server localhost:9093 --topic asop.terminal.cert.commands \
  --from-beginning --max-messages 1

# Kafka: check topic asop.terminal.cert.issued
docker compose exec kafka kafka-console-consumer \
  --bootstrap-server localhost:9093 --topic asop.terminal.cert.issued \
  --from-beginning --max-messages 1

# Kafka: check topic asop.terminal.cert.events
docker compose exec kafka kafka-console-consumer \
  --bootstrap-server localhost:9093 --topic asop.terminal.cert.events \
  --from-beginning --max-messages 1

# PostgreSQL: check terminal created
docker compose exec postgres psql -U asop -d asop -c \
  "SELECT TERMINAL_ID, TERMINAL_SERIAL, STATUS FROM ASOP_TERMINALS WHERE TERMINAL_SERIAL = 'test-terminal-001';"

# PostgreSQL: check cert stored
docker compose exec postgres psql -U asop -d asop -c \
  "SELECT CERT_ID, TERMINAL_ID, CERT_SERIAL, IS_CURRENT FROM ASOP_TERMINAL_CERTS;"
```

### Troubleshooting

- **Android gets no response:** Check `crypto-service` logs: `docker compose logs crypto-service`
- **Poll never completes:** Check `terminal-service` logs: `docker compose logs terminal-service`
- **Cert not stored:** Check gateway logs for CertEventConsumer: `docker compose logs gateway-service`

---

## Scenario 2: Terminal sync — session open (online)

**Goal:** Open a session while online — full Kafka + EventService flow.

### Android flow

1. Terminal operator selects session type, path, vehicle from cached data
2. Android inserts `SessionEntity` locally (Room, status=OPEN)
3. Android creates `PendingEventEntity` (PENDING, eventType=SESSION_OPEN)
4. Android calls `WorkScheduler.enqueueOneShotSync()`
5. `SyncWorker` reads PENDING event, POSTs to `/api/v1/sync/sessions/open`
6. Gateway returns 202 + X-Event-Id
7. PendingEvent → SENDING, gatewayEventId saved
8. `EventPollWorker` polls `GET /api/v1/events/{eventId}`
9. Backend consumer processes, publishes to `asop.session.events`
10. Gateway `CommandEventConsumer` → `EventService.complete(eventId)`
11. Poll returns 200 COMPLETED → PendingEvent marked SENT

### Expected HTTP

```bash
# POST /sync/sessions/open (mTLS required)
curl -k --cert terminal.p12 --key terminal-key.pem \
  -X POST https://localhost:8080/api/v1/sync/sessions/open \
  -H "Content-Type: application/json" \
  -d '{
    "sessionTypeId": "11111111-1111-1111-1111-111111111111",
    "terminalId": "22222222-2222-2222-2222-222222222222",
    "pathId": "33333333-3333-3333-3333-333333333333",
    "vehicleId": "44444444-4444-4444-4444-444444444444"
  }'

# Response: 202 Accepted
# X-Event-Id: <uuid>
```

### Room DB state (Android Studio Database Inspector)

```sql
-- Check pending event
SELECT id, event_type, status, gateway_event_id, retry_count
FROM pending_events;

-- Check session
SELECT id, status, opened_at, last_sync_at
FROM sessions;
```

### Verification

```bash
# Kafka: session.commands
docker compose exec kafka kafka-console-consumer \
  --bootstrap-server localhost:9093 --topic asop.session.commands \
  --from-beginning --max-messages 1

# PostgreSQL: sessions table
docker compose exec postgres psql -U asop -d asop -c \
  "SELECT * FROM ASOP_SESSIONS ORDER BY STARTED_AT DESC LIMIT 1;"
```

---

## Scenario 3: Offline buffering + sync on reconnect

**Goal:** Queue operations offline, flush when network returns.

### Steps

1. Enable Airplane mode in Android emulator
2. Tap "Open session" → local Room insert + PendingEvent PENDING
3. Tap "Submit transaction" → local Room insert + PendingEvent PENDING
4. Wait 1 minute — verify `SyncWorker` does NOT attempt network calls
5. Disable Airplane mode
6. `NetworkCallback.onAvailable()` triggers `WorkScheduler.enqueueOneShotSync()`
7. SyncWorker sends both pending events → 202 + eventIds
8. Both PendingEvents move to SENDING
9. EventPollWorker polls → COMPLETED → SENT

### Room DB state

```sql
-- After offline writes (should have 2 PENDING events)
SELECT event_type, status, retry_count FROM pending_events;

-- After sync (should have 0 PENDING, 2 SENT)
SELECT event_type, status, gateway_event_id FROM pending_events;

-- After failed poll (if gateway down)
SELECT event_type, status, error_message FROM pending_events;
```

### Verification

```bash
# Check both session + transaction in DB
docker compose exec postgres psql -U asop -d asop -c \
  "SELECT 'sessions' as tbl, COUNT(*) FROM ASOP_SESSIONS UNION ALL
   SELECT 'transactions', COUNT(*) FROM ASOP_TRANSACTIONS;"
```

### Troubleshooting

- **SyncWorker doesn't trigger on reconnect:** Check `NetworkMonitor` logs on Android via `adb logcat -s NetworkMonitor`
- **Pending events stuck PENDING:** Check `SyncWorker` logs: `adb logcat -s SyncWorker`
- **Events stuck SENDING:** Check `EventPollWorker` logs: `adb logcat -s EventPollWorker`

---

## Scenario 4: GPS tracking

**Goal:** Foreground GPS service sends positions during active shift.

### Steps

1. Main screen → tap GPS toggle to ON
2. Grant `ACCESS_FINE_LOCATION` permission (if not already granted)
3. `GpsTrackingService` starts as foreground service (visible notification)
4. Every ~30s, a new location point is generated
5. Location → `GpsPositionReport` → online send attempt → if offline: PendingEvent (GPS_POSITION)
6. After 10 offline GPS points → `WorkScheduler.enqueueOneShotSync()` triggered
7. Tap GPS toggle to OFF → service stops

### Expected HTTP

```bash
# GPS position report (mTLS)
curl -k --cert terminal.p12 --key terminal-key.pem \
  -X POST https://localhost:8080/api/v1/sync/gps/positions \
  -H "Content-Type: application/json" \
  -d '{
    "vehicleId": "44444444-4444-4444-4444-444444444444",
    "pathId": "33333333-3333-3333-3333-333333333333",
    "sessionId": "55555555-5555-5555-5555-555555555555",
    "latitude": 55.7558,
    "longitude": 37.6173,
    "speedKmh": 42.5,
    "recordedAt": "2026-07-17T10:30:00Z"
  }'

# Response: 202 Accepted
```

### Verification

```bash
# PostgreSQL: GPS tracking entries
docker compose exec postgres psql -U asop -d asop -c \
  "SELECT * FROM ASOP_GPS_TRACKING ORDER BY RECORDED_AT DESC LIMIT 5;"
```

---

## Scenario 5: Reference data sync (pull справочников)

**Goal:** Android pulls regions, routes, stops via proxy and caches locally.

### Steps

1. After cert provision, Android syncs reference data
2. `GET /api/v1/regions` → JSON array → cached in Room
3. `GET /api/v1/routes` → JSON array → cached in Room
4. `GET /api/v1/transport-stops` → JSON array → cached in Room
5. UI dropdowns show cached data (no network needed for display)
6. Admin changes region name via web-admin UI → region `updated_at` changes
7. Next sync pulls delta (full reload in MVP, delta sync TBD)

### Expected HTTP

```bash
# Pull regions via gateway proxy (JWT required)
curl -k -H "Authorization: Bearer <admin-token>" \
  https://localhost:8080/api/v1/regions

# Response: 200 OK
# Body: [{"id":"...","name":"Московская область","code":"77","updatedAt":"..."}]
```

---

## Scenario 6: Backend restart resilience

**Goal:** Verify terminal survives gateway restart without data loss.

### Steps

1. Terminal online, active session running
2. Restart gateway: `docker compose restart gateway-service`
3. Android PendingEvents in SENDING status — polling returns 404 or connection refused
4. `EventPollWorker` increments retry_count
5. After `MAX_POLL_RETRIES` (20) → mark as FAILED with "gateway restarted" error
6. UI shows failed events (retry available)

### Verification

```bash
# Restart gateway
docker compose restart gateway-service

# Check gateway logs after restart
docker compose logs gateway-service

# Android: check failed events
adb logcat -s EventPollWorker | grep "not found"
```

---

## Scenario 7: Card register + transaction flow

**Goal:** Register a card, then complete a transaction.

### Steps

1. Android operator taps "Register card"
2. Selects card type from cached list, optionally assigns to user
3. `PendingEventEntity` created (CARD_REGISTER)
4. SyncWorker → POST `/api/v1/sync/cards/register` → 202
5. Poll → COMPLETED
6. Operator taps "Submit transaction"
7. Selects card, transaction type, enters amount
8. `PendingEventEntity` created (TRANSACTION_COMPLETE)
9. SyncWorker → POST `/api/v1/sync/transactions` → 202
10. Poll → COMPLETED with TransactionResponse data

### Expected HTTP

```bash
# Register card (mTLS)
curl -k --cert terminal.p12 --key terminal-key.pem \
  -X POST https://localhost:8080/api/v1/sync/cards/register \
  -H "Content-Type: application/json" \
  -d '{
    "cardTypeId": "66666666-6666-6666-6666-666666666666",
    "userId": null
  }'

# Response: 202 Accepted

# Complete transaction (mTLS)
curl -k --cert terminal.p12 --key terminal-key.pem \
  -X POST https://localhost:8080/api/v1/sync/transactions \
  -H "Content-Type: application/json" \
  -d '{
    "sessionId": "55555555-5555-5555-5555-555555555555",
    "transactionTypeId": "77777777-7777-7777-7777-777777777777",
    "transactionResultId": "88888888-8888-8888-8888-888888888888",
    "amount": 150.00,
    "currency": "RUB",
    "cardId": "99999999-9999-9999-9999-999999999999"
  }'

# Response: 202 Accepted
```

### Verification

```bash
# Check card in DB
docker compose exec postgres psql -U asop -d asop -c \
  "SELECT * FROM ASOP_CARDS ORDER BY CREATED_AT DESC LIMIT 1;"

# Check transaction in DB
docker compose exec postgres psql -U asop -d asop -c \
  "SELECT * FROM ASOP_TRANSACTIONS ORDER BY CREATED_AT DESC LIMIT 1;"
```

---

## Appendix: Common curl options

```bash
# Base URL
BASE="https://localhost:8080"

# Admin JWT token (get from Keycloak)
TOKEN=$(curl -k -X POST https://localhost:8443/realms/asop/protocol/openid-connect/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "client_id=asop-admin" \
  -d "username=admin@asop.local" \
  -d "password=<admin-password>" \
  -d "grant_type=password" | jq -r '.access_token')

# Terminal mTLS (with client cert)
MTLS="--cert /tmp/certs/terminal.p12 --key /tmp/certs/terminal-key.pem --pass changeit"

# Poll event
curl -k $BASE/api/v1/events/<eventId>

# Proxy GET (with JWT)
curl -k -H "Authorization: Bearer $TOKEN" $BASE/api/v1/regions
```

## Appendix: Android debugging commands

```bash
# View all terminal logs
adb logcat -s SyncWorker EventPollWorker GpsTrackingService NetworkMonitor TerminalViewModel

# Database Inspector: open Android Studio → View → Tool Windows → Database Inspector
# Select ru.asop.terminal → asop_terminal.db

# Force WorkManager sync
adb shell am broadcast -a "androidx.work.diagnostics.REQUEST_DIAGNOSTICS" \
  -p "ru.asop.terminal"

# Check WorkManager status
adb shell dumpsys jobscheduler | grep -A 20 "ru.asop.terminal"
```

## Smoke Test: Delta Region Isolation (промпт 010)

**Goal:** Убедиться, что после промпта 010 терминалы разных регионов получают только свои данные.

```bash
# Предварительно: найти region UUID регионов 0101 (Москва), 0103 (Крым).
# Из контейнера gateway выполнить curl напрямую в master-сервисы.
docker exec docker-gateway-service-1 bash -c '
  R1="00000000-0000-0000-0000-000000000101"
  R3="00000000-0000-0000-0000-000000000103"

  curl -k -s "https://admin-service:8091/api/v1/territories/delta?regionId=$R1&limit=100" \
    | python3 -c "import json,sys; d=json.load(sys.stdin); print(f\"territories/0101=\\${len(d)}\")"
  curl -k -s "https://admin-service:8091/api/v1/territories/delta?regionId=$R3&limit=100" \
    | python3 -c "import json,sys; d=json.load(sys.stdin); print(f\"territories/0103=\\${len(d)}\")"

  curl -k -s "https://admin-service:8091/api/v1/organizers/delta?regionId=$R1&limit=100" \
    | python3 -c "import json,sys; d=json.load(sys.stdin); print(f\"organizers/0101=\\${len(d)}\")"
  curl -k -s "https://admin-service:8091/api/v1/organizers/delta?regionId=$R3&limit=100" \
    | python3 -c "import json,sys; d=json.load(sys.stdin); print(f\"organizers/0103=\\${len(d)}\")"

  curl -k -s "https://admin-service:8091/api/v1/organizer-territories/delta?regionId=$R1&limit=100" \
    | python3 -c "import json,sys; d=json.load(sys.stdin); print(f\"org_territories/0101=\\${len(d)}\")"
  curl -k -s "https://admin-service:8091/api/v1/organizer-territories/delta?regionId=$R3&limit=100" \
    | python3 -c "import json,sys; d=json.load(sys.stdin); print(f\"org_territories/0103=\\${len(d)}\")"

  curl -k -s "https://admin-service:8091/api/v1/benefit-steps/delta?regionId=$R1&limit=100" \
    | python3 -c "import json,sys; d=json.load(sys.stdin); print(f\"benefit_steps/0101=\\${len(d)}\")"
  curl -k -s "https://admin-service:8091/api/v1/benefit-steps/delta?regionId=$R3&limit=100" \
    | python3 -c "import json,sys; d=json.load(sys.stdin); print(f\"benefit_steps/0103=\\${len(d)}\")"

  curl -k -s "https://route-service:8092/api/v1/contract-routes/delta?regionId=$R1&limit=200" \
    | python3 -c "import json,sys; d=json.load(sys.stdin); print(f\"contract_routes/0101=\\${len(d)}\")"
  curl -k -s "https://route-service:8092/api/v1/contract-routes/delta?regionId=$R3&limit=200" \
    | python3 -c "import json,sys; d=json.load(sys.stdin); print(f\"contract_routes/0103=\\${len(d)}\")"

  curl -k -s "https://route-service:8092/api/v1/path-benefits/delta?regionId=$R1&limit=200" \
    | python3 -c "import json,sys; d=json.load(sys.stdin); print(f\"path_benefits/0101=\\${len(d)}\")"
  curl -k -s "https://route-service:8092/api/v1/path-benefits/delta?regionId=$R3&limit=200" \
    | python3 -c "import json,sys; d=json.load(sys.stdin); print(f\"path_benefits/0103=\\${len(d)}\")"

  curl -k -s "https://user-service:8082/api/v1/user-roles/delta?regionId=$R1&limit=200" \
    | python3 -c "import json,sys; d=json.load(sys.stdin); print(f\"user_roles/0101=\\${len(d)}\")"
  curl -k -s "https://user-service:8082/api/v1/user-roles/delta?regionId=$R3&limit=200" \
    | python3 -c "import json,sys; d=json.load(sys.stdin); print(f\"user_roles/0103=\\${len(d)}\")"
'
```

**Ожидаемый результат:** для каждой из 7 таблиц количество rows для `0101` и `0103` меньше, чем total. Например, для organizer-territories: 0101 → только organizer 0301 (Москва), 0103 → только 0303 (Крым), никакого взаимного overlap.

```

## Smoke Test: Driver Session workflow (промпт 011)

**Goal:** Проверить, что lifecycle «открыть смену → открыть рейс → валидации пассажиров → закрыть рейс → закрыть смену» работает end-to-end, с проверкой матрицы прав и 409 conflict guard для TRIP-ов.

### Endpoint basic flow

```bash
SHIFT_TYPE="00000000-0000-0000-0000-000000000601"
TRIP_TYPE="00000000-0000-0000-0000-000000000603"
DRIVER_USER="019ff5b6-fc58-7865-a2c5-0d86e90699f7"  # Admin (driver role)
CARRIER="00000000-0000-0000-0000-0000000001403"          # ГУП «Крымавтотранс»
TERMINAL_ID="$(cat /tmp/terminal-id.txt 2>/dev/null || echo "00000000-0000-0000-0000-000000000001")"

# Generate same client UUIDv7 twice → idempotency assurance
SHIFT_CLIENT_ID="$(uuidgen | tr A-Z a-z)"
TRIP_CLIENT_ID="$(uuidgen | tr A-Z a-z)"

# 1. Open SHIFT
docker exec docker-gateway-service-1 curl -k -s -X POST \
  "https://gateway-service:8080/api/v1/sync/sessions/open" \
  -H "Content-Type: application/json" \
  -d "{\"sessionTypeId\":\"$SHIFT_TYPE\",\"tidId\":null,\"pathId\":null,\"vehicleId\":null,\"openedByUserId\":\"$DRIVER_USER\",\"cardId\":\"02B206F1-AAAA-BBBB-CCCC-000000000001\",\"carrierId\":\"$CARRIER\",\"regionId\":\"00000000-0000-0000-0000-0000000000103\",\"timezone\":\"Europe/Moscow\"}" \
  | jq .eventId
# Ожидаем 202 + X-Event-Id в ответе.

# 2. Verify in DB
docker exec docker-postgres-1 psql -U asop -d asop -c \
  "SELECT session_type_code, parent_session_id, opened_by_user_id, status FROM asop_sessions WHERE opened_by_user_id='$DRIVER_USER' AND session_type_id='$SHIFT_TYPE' AND status='IN_PROGRESS';"
# Ожидаем 1 row: SHIFT, parent=NULL, opened_by=DRIVER, IN_PROGRESS

# 3. Open TRIP referencing shift.id as parent
TRIP_ATTR="{\"carrierId\":\"$CARRIER\",\"regionId\":\"00000000-0000-0000-0000-0000000000103\"}"
docker exec docker-gateway-service-1 curl -k -s -X POST \
  "https://gateway-service:8080/api/v1/sync/sessions/open" \
  -H "Content-Type: application/json" \
  -d "{\"sessionTypeId\":\"$TRIP_TYPE\",\"parentSessionId\":\"$SHIFT_ID\",\"openedByUserId\":\"$DRIVER_USER\",\"tidId\":\"$TID_ID\",\"pathId\":\"$PATH_ID\",\"vehicleId\":\"$VEHICLE_ID\",\"carrierId\":\"$CARRIER\",\"regionId\":\"00000000-0000-0000-0000-0000000000103\"}"

# 4. Try parallel Trip (should fail with 409 in production server, 202 here but DB-level will dedupe)
docker exec docker-gateway-service-1 curl -k -s -X POST ...
# Ожидаем либо 409 Conflict (если включен server-side check) либо вторую запись с другим sessionId;
# после двух updates server-side SessionService должен вернуть либо 409 (включен guard) либо server detect duplicate.

# 5. Close TRIP — same user or admin
docker exec docker-gateway-service-1 curl -k -s -X PUT \
  "https://gateway-service:8080/api/v1/sync/sessions/$TRIP_ID/close" \
  -H "Content-Type: application/json" \
  -d "{\"reason\":\"end_of_trip\",\"cardId\":\"$DRIVER_CARD_ID\"}"

# 6. Tap passenger cards → INSERT trip_payments (offline) + emit Kafka transactions
# ref. Phase 7 Android SyncApi.completeTransaction
# Каждый tap → session=TRIP.id, typeCode=...0803 (VALIDATION), resultCode=...0903 (VALIDATION_ONLY), amount=0.

# 7. Close SHIFT — by another driver of same carrier (auth matrix test)
ANOTHER_DRIVER_USER="019ff5b6-fc58-7865-a2c5-000000000002"  # hypothetical same carrier
docker exec docker-gateway-service-1 curl -k -s -X PUT \
  "https://gateway-service:8080/api/v1/sync/sessions/$SHIFT_ID/close" \
  -H "Content-Type: application/json" \
  -d "{\"reason\":\"end_of_shift\",\"cardId\":\"$ANOTHER_DRIVER_CARD\"}"
# Ожидаем 202 — `canCloseShift` через carrier_id из ATTRIBUTES JSONB и ANOTHER_DRIVER's
# asop_user_carriers.carrier_id == $CARRIER → OK.

# 8. Verify final DB state
docker exec docker-postgres-1 psql -U asop -d asop -c \
  "SELECT session_type_code, status FROM asop_sessions WHERE opened_by_user_id='$DRIVER_USER' ORDER BY started_at DESC LIMIT 3;"
# Ожидаем: SHIFT (CLOSED), TRIP (CLOSED), and maybe TRIP (CLOSED).
```

### Authorization matrix verification

Создайте seed users через Keycloak admin:
- `DRIVER_A` (`CARRIER_DISPATCHER` нет, только DRIVER на carrier=1403)
- `DRIVER_B` (`CARRIER_DISPATCHER` нет, только DRIVER на carrier=1403)
- `DISPATCHER_C` (`CARRIER_DISPATCHER` на carrier=1403)
- `DISPATCHER_F` (другой carrier, тот же region)
- `ORGADMIN_D` (`ORGANIZER_ADMIN` для организатора 1403)
- `REGADMIN_E` (`REGION_ADMIN` для региона 0103)
- `ROOT` (`SUPER_ADMIN`)

```sql
-- Вставить userCarriers и userRegions через seed-data-delta-*.sql или прямой INSERT
INSERT INTO asop_user_carriers (user_id, carrier_id) VALUES (USER_OF_DRIVER_A, CARRIER_1403);
-- (аналогично для остальных)
```

Затем для каждой смены, открытой DRIVER_A на carrier=1403:
- DRIVER_B → закрывает смену → **ОК** (тот же carrier_id)
- DISPATCHER_C → закрывает смену → **ОК** (CARRIER_DISPATCHER на 1403)
- DISPATCHER_F → закрывает смену → **403** (другой carrier)
- ORGADMIN_D → закрывает смену → **ОК** (cascade через организатор → carrier)
- REGADMIN_E → закрывает смену → **ОК** (cascade через region → organizer → carrier)
- ROOT → закрывает смену → **ОК** (SUPER_ADMIN)

### 409 Conflict guard (TRIP uniqueness inside SHIFT)

```bash
# Откройте shift один.
# Попытайтесь открыть TRIP A.
docker exec -i docker-postgres-1 psql -U asop -d asop -c \
  "UPDATE asop_sessions SET status='CLOSED', closed_at=NOW() WHERE session_type_code='TRIP' AND parent_session_id='$SHIFT_ID';"
# Попытайтесь открыть TRIP B (тот же parent).
# Server-side guard в SessionCommandConsumer должен вернуть 409.
docker exec docker-gateway-service-1 curl -k -s -w "%{http_code}\n" -X POST ...
# Ожидаем 409 Conflict, т.к. существующий IN_PROGRESS TRIP для shift уже есть (если сохранился).
# Если первая попытка TRIP закрылась — вторая должна пройти.


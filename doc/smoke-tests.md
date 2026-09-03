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
# Step 1: POST /cert-sign (plain HTTPS, no mTLS, HMAC-protected)
# HMAC: X-API-Key + X-Timestamp(epochSec) + X-Signature = HmacSHA256(timestamp, secret).hex
TS=$(date +%s)
SIG=$(printf '%s' "$TS" | openssl dgst -sha256 -hmac '9f8e7d6c5b4a3210fedcba9876543210fedcba9876543210fedcba9876543210' -hex | awk '{print $2}')
curl -k -X POST https://localhost:8080/api/v1/terminals/cert-sign \
  -H "Content-Type: application/json" \
  -H "X-API-Key: asop-terminal-cert-key" \
  -H "X-Timestamp: $TS" \
  -H "X-Signature: $SIG" \
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

## Scenario 4: GPS tracking + live-map

**Goal:** Foreground GPS service sends positions during active shift; live-map shows snapped vehicles.

### Steps

1. Main screen → tap GPS toggle to ON
2. Grant `ACCESS_FINE_LOCATION` permission (if not already granted)
3. `GpsTrackingService` starts as foreground service (visible notification)
4. **Требуется открытая смена (SHIFT) и рейс (TRIP) с выбранными ТС и путём** — иначе точка молча отбрасывается (`vehicleId/pathId` оба null)
5. Debug-режим (`isDebugGps=true`, dev default): координаты от `MockRoutePlayer` (маршрут 301, 356 точек); иначе `FusedLocationProviderClient` (интервал 5 c / fastest 3 c)
6. Каждая точка → `GpsPositionReport` → online send → если offline: PendingEvent (GPS_POSITION)
7. После 10 offline GPS точек → `WorkScheduler.enqueueOneShotSync()`
8. Tap GPS toggle to OFF → service stops

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
    "latitude": 44.9441,
    "longitude": 34.1255,
    "speedKmh": 42.5,
    "recordedAt": "2026-07-17T10:30:00Z"
  }'

# Response: 202 Accepted
```

### Verification: БД + live-API

```bash
# PostgreSQL: GPS tracking entries
docker compose exec postgres psql -U asop -d asop -c \
  "SELECT POSITION_ID, VEHICLE_ID, PATH_ID, SESSION_ID, ST_Y(GPS_COORD::geometry), ST_X(GPS_COORD::geometry), SPEED_KMH, RECORDED_AT FROM ASOP_GPS_TRACKING ORDER BY RECORDED_AT DESC LIMIT 5;"

# Live-API (последняя точка на ТС, при необходимости снапнута к маршруту 301)
curl -s http://localhost:8094/api/v1/tracking/live?freshSec=120   # напрямую к session-service
# или через gateway (JWT / public API-ключ):
curl -s -H "X-API-Key: asop-passenger-prod-key-2026" \
  http://localhost:8080/api/v1/public/tracking/live?freshSec=120

# Трек ТС за последние 15 мин
curl -s http://localhost:8094/api/v1/tracking/vehicle/{vehicleId}/track?minutes=15
```

### Verification: карты

- **web-admin** `https://localhost:3443/live-map` (JWT, Ctrl+Shift+R после пересборки) — ТС на маршруте 301, маркер плавно движется, попап (№ТС/тип/путь/скорость), фильтр «По маршруту (snapped)».
- **пассажирское приложение** (Samsung, `ru.asop.passenger`) — live-карта osmdroid показывает только ТС с snapped-позицией (`freshSec=120`, polling 3 c).

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

## Smoke Test: Driver Session workflow (промпт 011)

**Goal:** проверить end-to-end lifecycle «открыть смену → открыть рейс → валидация пассажира → закрыть рейс → закрыть смену»: mTLS от имени терминала, клиентская идемпотентность (`ON CONFLICT (SESSION_ID) DO NOTHING`), 409-guard на второй активный TRIP и матрица авторизации закрытия (`SessionService.canClose`).

Все команды выполняются из корня репозитория. Предварительно: стек поднят (см. Pre-conditions) и накатаны seed-скрипты:

```bash
for f in seed-data.sql seed-data-delta-1.sql seed-data-delta-2.sql seed-data-delta-3.sql; do
  docker compose -f infrastructure/docker/docker-compose.yml exec -T postgres psql -U asop -d asop < infrastructure/docker/$f
done
```

### Константы (все ID — из seed-data.sql)

```bash
BASE="https://localhost:8080"
PSQL="docker compose -f infrastructure/docker/docker-compose.yml exec -T postgres psql -U asop -d asop -tAc"

REGION="00000000-0000-0000-0000-000000000103"       # Республика Крым
CARRIER="00000000-0000-0000-0000-000000001403"      # ГУП «Крымавтотранс»
SHIFT_TYPE="00000000-0000-0000-0000-000000000601"   # SHIFT
TRIP_TYPE="00000000-0000-0000-0000-000000000603"    # TRIP
VAL_TYPE="00000000-0000-0000-0000-000000000803"     # «Валидация (без списания)»
VAL_RESULT="00000000-0000-0000-0000-000000000903"   # «Зафиксировано (без списания)»
DRIVER_A="00000000-0000-0000-0000-302000002200"     # Пётр  (user_carriers → 1403)
DRIVER_B="00000000-0000-0000-0000-302000002400"     # Анна  (user_carriers → 1403)
ALIEN_DRIVER="00000000-0000-0000-0000-301000001200" # Татьяна (1402 — ЧУЖОЙ перевозчик)

# Poll события до терминального статуса (COMPLETED/FAILED), ≤ 80 сек
poll() {
  for i in $(seq 1 40); do
    S="$(curl -sk "$BASE/api/v1/events/$1" | jq -r '.state')"
    [ "$S" = "COMPLETED" ] && break
    [ "$S" = "FAILED" ] && break
    sleep 2
  done
  curl -sk "$BASE/api/v1/events/$1"
}
```

Sanity-check сида (каждый запрос должен вернуть `1`):

```bash
$PSQL "SELECT COUNT(*) FROM asop_carriers WHERE carrier_id='$CARRIER' AND region_id='$REGION';"
$PSQL "SELECT COUNT(*) FROM asop_session_types WHERE session_type_id IN ('$SHIFT_TYPE','$TRIP_TYPE');"
$PSQL "SELECT COUNT(*) FROM asop_user_carriers WHERE user_id IN ('$DRIVER_A','$DRIVER_B') AND carrier_id='$CARRIER';"
```

### Шаг 0. Терминальный сертификат (mTLS-креденшал для curl)

Весь сценарий проходит под mTLS, поэтому сначала получаем сертификат тестового терминала
через cert-sign saga (детали — Scenario 1 выше):

```bash
mkdir -p /tmp/asop-smoke && cd /tmp/asop-smoke
openssl ecparam -name prime256v1 -genkey -noout -out terminal-key.pem
openssl req -new -key terminal-key.pem -subj "/CN=E2E-SMOKE-001" -out terminal.csr
PUB_B64="$(openssl ec -in terminal-key.pem -pubout -outform DER | base64 -w0)"

TS="$(date +%s)"
SIG="$(printf '%s' "$TS" | openssl dgst -sha256 -hmac '9f8e7d6c5b4a3210fedcba9876543210fedcba9876543210fedcba9876543210' -hex | awk '{print $2}')"
RESP="$(curl -sk -X POST "$BASE/api/v1/terminals/cert-sign" \
  -H 'Content-Type: application/json' \
  -H "X-API-Key: asop-terminal-cert-key" \
  -H "X-Timestamp: $TS" \
  -H "X-Signature: $SIG" \
  -d "{\"terminalSerial\":\"E2E-SMOKE-001\",\"publicKeyBase64\":\"$PUB_B64\"}")"
EVENT_ID="$(echo "$RESP" | jq -r '.eventId')"
echo "202 Accepted, X-Event-Id=$EVENT_ID"

BODY="$(poll "$EVENT_ID")"
[ "$(echo "$BODY" | jq -r '.state')" = "COMPLETED" ] || { echo "$BODY"; exit 1; }

echo "$BODY" | jq -r '.resultData | fromjson | .certificateBase64' | base64 -d \
  | openssl x509 -inform DER -out terminal-cert.pem
echo "$BODY" | jq -r '.resultData | fromjson | .caChain' > ca-chain.pem

MTLS="--cert /tmp/asop-smoke/terminal-cert.pem --key /tmp/asop-smoke/terminal-key.pem"
curl -sk $MTLS -o /dev/null -w "mTLS OK: HTTP %{http_code}\n" "$BASE/api/v1/events/$EVENT_ID"
cd - >/dev/null
```

### Шаг 1. Открыть смену (SHIFT)

`sessionId` генерирует КЛИЕНТ (UUIDv7) — он же ключ идемпотентности при offline retry:

```bash
SHIFT_ID="$(uuidgen | tr 'A-Z' 'a-z')"

EV="$(curl -sk $MTLS -X POST "$BASE/api/v1/sync/sessions/open" \
  -H 'Content-Type: application/json' -d "{
    \"sessionId\": \"$SHIFT_ID\",
    \"sessionTypeId\": \"$SHIFT_TYPE\",
    \"openedByUserId\": \"$DRIVER_A\",
    \"cardId\": \"02000000-0000-0000-0000-0000000000a1\",
    \"carrierId\": \"$CARRIER\",
    \"regionId\": \"$REGION\",
    \"timezone\": \"Europe/Moscow\"
  }" | jq -r '.eventId')"
# → 202 Accepted; дожидаемся обработки:
poll "$EV" | jq -r '.state'    # → COMPLETED
```

Проверки:

```bash
# Ровно одна запись:
$PSQL "SELECT session_id, session_type_code, status FROM asop_sessions WHERE session_id='$SHIFT_ID';"
# → $SHIFT_ID | SHIFT | IN_PROGRESS

# Идемпотентность: повторный POST с тем же sessionId НЕ создаёт дубль
# (consumer: INSERT … ON CONFLICT (SESSION_ID) DO NOTHING):
$PSQL "SELECT COUNT(*) FROM asop_sessions WHERE session_id='$SHIFT_ID';"   # → 1
```

### Шаг 2. Открыть рейс (TRIP) внутри смены

```bash
TRIP_ID="$(uuidgen | tr 'A-Z' 'a-z')"

EV="$(curl -sk $MTLS -X POST "$BASE/api/v1/sync/sessions/open" \
  -H 'Content-Type: application/json' -d "{
    \"sessionId\": \"$TRIP_ID\",
    \"sessionTypeId\": \"$TRIP_TYPE\",
    \"parentSessionId\": \"$SHIFT_ID\",
    \"openedByUserId\": \"$DRIVER_A\",
    \"tidId\": null, \"pathId\": null, \"vehicleId\": null,
    \"carrierId\": \"$CARRIER\", \"regionId\": \"$REGION\", \"timezone\": \"Europe/Moscow\"
  }" | jq -r '.eventId')"
poll "$EV" | jq -r '.state'    # → COMPLETED

$PSQL "SELECT session_id, status FROM asop_sessions
       WHERE parent_session_id='$SHIFT_ID' AND status='IN_PROGRESS';"
# → одна строка: $TRIP_ID | IN_PROGRESS
```

### Шаг 3. 409 Conflict guard: второй активный TRIP запрещён

Пока TRIP из шага 2 в `IN_PROGRESS`, открытие второго TRIP той же смены падает на consumer'е
(`EXISTS … PARENT_SESSION_ID AND STATUS='IN_PROGRESS'` → событие FAILED, запись не создаётся):

```bash
TRIP_2ND="$(uuidgen | tr 'A-Z' 'a-z')"

EV="$(curl -sk $MTLS -X POST "$BASE/api/v1/sync/sessions/open" \
  -H 'Content-Type: application/json' -d "{
    \"sessionId\": \"$TRIP_2ND\",
    \"sessionTypeId\": \"$TRIP_TYPE\",
    \"parentSessionId\": \"$SHIFT_ID\",
    \"openedByUserId\": \"$DRIVER_A\",
    \"carrierId\": \"$CARRIER\", \"regionId\": \"$REGION\"
  }" | jq -r '.eventId')"

[ "$(poll "$EV" | jq -r '.state')" = "FAILED" ]                           # событие FAILED
$PSQL "SELECT COUNT(*) FROM asop_sessions WHERE session_id='$TRIP_2ND';"  # → 0
```

После закрытия первого TRIP (шаг 5) второй открыть можно.

### Шаг 4. Валидация пассажира (tap, MVP без списания)

```bash
PAX_CARD="$(uuidgen | tr 'A-Z' 'a-z')"
NOW_MS="$(date +%s)000"

EV="$(curl -sk $MTLS -X POST "$BASE/api/v1/sync/transactions" \
  -H 'Content-Type: application/json' -d "{
    \"sessionId\": \"$TRIP_ID\",
    \"transactionTypeId\": \"$VAL_TYPE\",
    \"transactionResultId\": \"$VAL_RESULT\",
    \"amount\": 0,
    \"currency\": \"RUB\",
    \"cardId\": \"$PAX_CARD\",
    \"metadata\": \"{\\\"tripsBefore\\\":10,\\\"tripsAfter\\\":9,\\\"tripsAt\\\":$NOW_MS,\\\"anonymous\\\":false}\",
    \"carrierId\": \"$CARRIER\", \"regionId\": \"$REGION\"
  }" | jq -r '.eventId')"
poll "$EV" | jq -r '.state'    # → COMPLETED

$PSQL "SELECT COUNT(*), MIN(amount) FROM asop_transactions WHERE session_id='$TRIP_ID';"
# → 1 | 0   (тип/результат …0803 / …0903, amount = 0)
# Если пассажирская карта зарегистрирована в ASOP_CARD_MIFARES, её TRIPS_LEFT станет 9
# (updateTripsBalance, last-wins по tripsAt — запаздывающие транзакции не перетирают свежий баланс).
```

### Шаг 5. Закрыть рейс

```bash
EV="$(curl -sk $MTLS -X PUT "$BASE/api/v1/sync/sessions/$TRIP_ID/close" \
  -H 'Content-Type: application/json' \
  -d "{\"reason\": \"end_of_trip\", \"closedByUserId\": \"$DRIVER_A\"}" | jq -r '.eventId')"
poll "$EV" | jq -r '.state'    # → COMPLETED

$PSQL "SELECT status FROM asop_sessions WHERE session_id='$TRIP_ID';"    # → CLOSED
```

### Шаг 6. Закрыть смену — матрица авторизации `canClose`

**Позитив:** другой водитель ТОГО ЖЕ перевозчика закрывает смену водителя A:

```bash
EV="$(curl -sk $MTLS -X PUT "$BASE/api/v1/sync/sessions/$SHIFT_ID/close" \
  -H 'Content-Type: application/json' \
  -d "{\"reason\": \"end_of_shift\", \"closedByUserId\": \"$DRIVER_B\"}" | jq -r '.eventId')"
poll "$EV" | jq -r '.state'    # → COMPLETED

$PSQL "SELECT status, closed_by_user_id FROM asop_sessions WHERE session_id='$SHIFT_ID';"
# → CLOSED | $DRIVER_B
```

**Негатив:** водитель чужого перевозчика смену закрыть НЕ может. Откройте новую смену
от `DRIVER_A` (шаг 1 с новым `sessionId` в `NEW_SHIFT_ID`), затем:

```bash
EV="$(curl -sk $MTLS -X PUT "$BASE/api/v1/sync/sessions/$NEW_SHIFT_ID/close" \
  -H 'Content-Type: application/json' \
  -d "{\"reason\": \"alien_close_attempt\", \"closedByUserId\": \"$ALIEN_DRIVER\"}" | jq -r '.eventId')"

[ "$(poll "$EV" | jq -r '.state')" = "FAILED" ]   # canClose=false → событие FAILED
$PSQL "SELECT status FROM asop_sessions WHERE session_id='$NEW_SHIFT_ID';"   # → IN_PROGRESS
```

Расширенная матрица (опционально): создайте через web-admin пользователей `DISPATCHER_C`
(CARRIER_DISPATCHER @1403), `ORGADMIN_D` (ORGANIZER_ADMIN организатора перевозчика 1403),
`REGADMIN_E` (REGION_ADMIN @103), `ROOT` (SUPER_ADMIN) и повторите закрытие свежей смены
с каждым `closedByUserId`: C/D/E/R → COMPLETED (смена CLOSED); пользователь вне scope → FAILED.

### Шаг 7. Финальное состояние

```bash
$PSQL "SELECT session_type_code, status FROM asop_sessions
       WHERE opened_by_user_id='$DRIVER_A' ORDER BY started_at DESC LIMIT 4;"
# Ожидаемо: SHIFT CLOSED ×1–2, TRIP CLOSED ×1 (+ SHIFT IN_PROGRESS из негативного кейса)
```

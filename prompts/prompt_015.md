# Промпт 015: GPS-карта — админка, пассажирское приложение, фильтр Кальмана, партиционирование

## 1. Контекст

Существует полный пайп записи GPS-координат: Android `GpsTrackingService` → `PendingEvent` → `SyncWorker` → Gateway `GpsCommandController` → Kafka `asop.gps.commands` → session-service `GpsCommandConsumer` → INSERT `ASOP_GPS_TRACKING`. Но **нет ни одного read-endpoint** — данные пишутся и не читаются. Таблица `ASOP_GPS_TRACKING` содержит координаты только в открытом рейсе (vehicleId + pathId NOT NULL).

Цель промпта: построить чтение GPS-данных (карта live ТС в админке), пассажирское приложение (Android, карта с остановками/маршрутами/ТС), а также инфраструктурные доработки: фильтр Кальмана, партиционирование, API-ключи с HMAC, mock GPS для тестов.

**Целевое устройство:** Feitian F20, Android 14, GPS неточный (Крым).

---

## 2. Архитектурные решения

### Q1. Kafka для клиентов — НЕ НАДО
Kafka — шина для backend-сервисов. Мобильные клиенты intermittent, offline, не поддерживают persistent connections. REST polling каждые 15 сек — adequately для «живой карты».

**Архитектура:**
```
Terminal → Kafka → session-service (Kalman filter → INSERT) → DB
                                                           ↑
Admin web-app ─── REST poll 15s ──→ Gateway → session-service
Passenger app ─── REST poll 15s ──→ Gateway → session-service (public chain)
```

### Q2. Фильтр Кальмана — ОБЯЗАТЕЛЬНО
В Крыму GPS неточный (прыжки 200-500м). Координаты **обязательно** сглаживать перед INSERT.

Реализация: `KalmanFilter` в session-service, вызывается **перед INSERT** в `GpsCommandConsumer`:
```
raw GPS → KalmanFilter.filter(lat, lon, accuracy) → smoothed lat/lon → INSERT
```

Параметры:
- Process noise (Q): `0.03²` (динамическая модель — ТС движется по маршруту)
- Measurement noise (R): accuracy из GPS (если нет accuracy → `15м` по умолчанию)
- Initial uncertainty: `100²`
- State vector: `[lat, lon, dLat, dLon]` (координаты + скорость как часть state)
- Результат: сглаженная траектория без «прыжков»

### Q3. API-ключи — HMAC для MVP
- Один API-ключ для passengers app
- Клиент шлёт `X-API-Key` + `X-Timestamp` + `X-Signature` (HMAC-SHA256(body + timestamp, key))
- Сервер проверяет: freshness timestamp (±5 мин), затем HMAC подпись
- Старый клиент без HMAC продолжает работать (fallback на plain key если signature отсутствует)
- Таблица `ASOP_API_KEYS` в БД

### Q4. Mock GPS на терминале
В `GpsTrackingService`: альтернативный source координат из JSON-файла (предзаписанный маршрут).
- Включение: `DataStore` флаг `isDebugGps` (без UI переключателя)
- Файл: `assets/mock_route_simferopol.json` — 100 точек, 30 сек интервал, ~50 мин, маршрут по Симферополю (44.95, 34.11)
- Вся остальная цепочка (PendingEvent → SyncWorker → Gateway → Kafka → DB) не меняется
- В промышленной среде: `isDebugGps = false` → `FusedLocationProviderClient`

### Q5. Партиционирование `ASOP_GPS_TRACKING`
Помесячное по `RECORDED_AT` (PARTITION BY RANGE). Старт с 2026-09 (текущий месяц).

Автосоздание партиций: `@Scheduled` задача создаёт партицию на +2 месяца вперёд каждый первый день месяца.

Retention: `keys.gpsRetentionMonths = 6` из `ASOP_CONFIG_PARAMS`. PurgeJob удаляет/DROP старые партиции.

---

## 3. Функциональные требования

### 3.1 Backend: API-ключи с HMAC

#### 3.1.1 Таблица `ASOP_API_KEYS`

Добавить в `v001-init.yaml` (новый `sqlFile` блок) и в `asop_schema.sql`:

```sql
CREATE TABLE ASOP_API_KEYS (
    KEY_ID      UUID         NOT NULL,
    KEY_VALUE   VARCHAR(128) NOT NULL,       -- HMAC secret key (hex, 64 chars)
    NAME        VARCHAR(100) NOT NULL,       -- human label: "Пассажирское приложение"
    HMAC_SECRET VARCHAR(128) NOT NULL,       -- HMAC-SHA256 secret (hex)
    IS_ACTIVE   BOOLEAN      DEFAULT true,
    RATE_LIMIT  INT          DEFAULT 60,     -- requests per minute
    CREATED_AT  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    DELETED_AT  TIMESTAMPTZ,
    CONSTRAINT pk_api_keys PRIMARY KEY (KEY_ID),
    CONSTRAINT uq_api_keys_value UNIQUE (KEY_VALUE)
);
```

Seed: один запись:
```sql
INSERT INTO ASOP_API_KEYS (KEY_ID, KEY_VALUE, NAME, HMAC_SECRET, IS_ACTIVE, RATE_LIMIT)
VALUES (
    '00000000-0000-0000-0000-000000000001',
    'asop-passenger-prod-key-2026',
    'Пассажирское приложение',
    'a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6a7b8c9d0e1f2a3b4c5d6a7b8c9d0e1f2',
    true,
    60
);
```

#### 3.1.2 Gateway: публичная цепочка безопасности

Новая цепочка `@Order(0)` в `SecurityConfig.kt`:
- Matcher: `/api/v1/public/**`
- permitAll (без JWT/mTLS)
- Фильтр `ApiKeyHmacFilter`:
  1. Читает `X-API-Key`, `X-Timestamp`, `X-Signature` из заголовков
  2. Проверяет freshness: `|now - timestamp| < 5 min`
  3. Вычисляет `HMAC-SHA256(body + timestamp, hmac_secret)` → сравнивает с `X-Signature`
  4. Если signature отсутствует → fallback на plain key match (`KEY_VALUE = :key`)
  5. Rate limit: in-memory ` ConcurrentHashMap<key, SlidingWindowCounter>` (60 req/min)

#### 3.1.3 Клиентская HMAC-подпись (Android passenger app)

```kotlin
class HmacInterceptor(private val apiKey: String, private val hmacSecret: String) : Interceptor {
    override fun intercept(chain: Chain): Response {
        val body = chain.request().body?.toString() ?: ""
        val timestamp = Instant.now().epochSecond.toString()
        val payload = body + timestamp
        val signature = Mac.getInstance("HmacSHA256")
            .apply { init(SecretKeySpec(hmacSecret.toByteArray(), "HmacSHA256"))}
            .doFinal(payload.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val request = chain.request().newBuilder()
            .addHeader("X-API-Key", apiKey)
            .addHeader("X-Timestamp", timestamp)
            .addHeader("X-Signature", signature)
            .build()
        return chain.proceed(request)
    }
}
```

### 3.2 Backend: GPS Live Query Endpoint

session-service (владеет `ASOP_GPS_TRACKING`):

**GET /api/v1/tracking/live** (authenticated — через gateway proxy)

Query params: `regionId?`, `carrierId?`, `vehicleId?`, `freshSec` (default 600)

```sql
WITH latest AS (
    SELECT DISTINCT ON (g.VEHICLE_ID)
        g.VEHICLE_ID, g.GPS_COORD, g.RECORDED_AT, g.SPEED_KMH, g.PATH_ID, g.SESSION_ID
    FROM ASOP_GPS_TRACKING g
    WHERE g.RECORDED_AT > NOW() - (:freshSec || ' seconds')::INTERVAL
      AND (:vehicleId::UUID IS NULL OR g.VEHICLE_ID = :vehicleId)
    ORDER BY g.VEHICLE_ID, g.RECORDED_AT DESC
)
SELECT l.*,
       ST_Y(l.GPS_COORD::geometry) AS latitude,
       ST_X(l.GPS_COORD::geometry) AS longitude,
       v.VEHICLE_NUMBER, v.VEHICLE_NAME,
       vh.VEHICLE_TYPE_NAME,
       p.PATH_NAME, p.ROUTE_ID,
       sesh.STATUS AS session_status
FROM latest l
JOIN ASOP_VEHICLES v ON v.VEHICLE_ID = l.VEHICLE_ID
JOIN ASOP_SESSIONS sesh ON sesh.SESSION_ID = l.SESSION_ID
LEFT JOIN ASOP_PATHS p ON p.PATH_ID = l.PATH_ID
LEFT JOIN ASOP_VEHICLE_TYPES vh ON vh.VEHICLE_TYPE_ID = v.VEHICLE_TYPE_ID
WHERE sesh.STATUS = 'IN_PROGRESS'
  AND (:carrierId::UUID IS NULL OR v.CARRIER_ID = :carrierId)
  AND (:regionId::UUID IS NULL
       OR v.CARRIER_ID IN (SELECT CARRIER_ID FROM ASOP_CARRIERS WHERE REGION_ID = :regionId))
```

DTO `LiveVehicleDto`:
```kotlin
data class LiveVehicleDto(
    val vehicleId: UUID,
    val vehicleNumber: String,
    val vehicleName: String,
    val vehicleType: String,
    val latitude: Double,
    val longitude: Double,
    val speedKmh: Double?,
    val recordedAt: Instant,
    val pathId: UUID?,
    val pathName: String?,
    val routeId: UUID?,
    val sessionId: UUID?
)
```

Реализация: `DatabaseClient` (не Repository — явный SQL), `TrackingController` + `TrackingService`.

### 3.3 Backend: Public GPS Endpoint (passengers)

**GET /api/v1/public/tracking/live** (API key auth, permitAll)

Тот же SQL, но без фильтров regionId/carrierId (все ТС в рейсе), default `freshSec=300`.

**GET /api/v1/public/tracking/vehicle/{vehicleId}/track** (API key auth)

История точки ТС за последние N минут (для отрисовки следа):
```sql
SELECT ST_Y(GPS_COORD::geometry) AS latitude,
       ST_X(GPS_COORD::geometry) AS longitude,
       RECORDED_AT, SPEED_KMH
FROM ASOP_GPS_TRACKING
WHERE VEHICLE_ID = :vehicleId
  AND RECORDED_AT > NOW() - (:minutes || ' minutes')::INTERVAL
ORDER BY RECORDED_AT ASC
```

### 3.4 Backend: Stops Endpoints

**GET /api/v1/stops/bbox** (authenticated, gateway → route-service)

Query params: `southWest` (lat,lng), `northEast` (lat,lng), `regionId?`

```sql
SELECT STOP_ID, STOP_NAME, STOP_CODE, STOP_ADDRESS, REGION_ID,
       ST_Y(ST_Centroid(ZONE_POLYGON)::geometry) AS latitude,
       ST_X(ST_Centroid(ZONE_POLYGON)::geometry) AS longitude
FROM ASOP_TRANSPORT_STOPS
WHERE ST_Within(ZONE_POLYGON::geometry, ST_MakeEnvelope(:west, :south, :east, :north, 4326))
  AND IS_ACTIVE = true AND DELETED_AT IS NULL
  AND (:regionId::UUID IS NULL OR REGION_ID = :regionId)
```

**GET /api/v1/public/stops/bbox** (API key auth, passengers)

Тот же SQL, публичный.

**GET /api/v1/stops/{stopId}/routes** и **GET /api/v1/public/stops/{stopId}/routes**

```sql
SELECT DISTINCT r.ROUTE_ID, r.ROUTE_NAME, r.ROUTE_NUMBER,
       p.PATH_ID, p.PATH_NAME, p.ROUTE_OBJECT
FROM ASOP_PATH_TRANSPORT_STOPS pts
JOIN ASOP_PATHS p ON p.PATH_ID = pts.PATH_ID
JOIN ASOP_ROUTES r ON r.ROUTE_ID = p.ROUTE_ID
WHERE pts.STOP_ID = :stopId AND p.DELETED_AT IS NULL AND r.DELETED_AT IS NULL
ORDER BY r.ROUTE_NUMBER
```

### 3.5 Backend: Фильтр Кальмана

**Класс `KalmanFilter`** в session-service (`ru.asop.session.gps`):

```kotlin
class KalmanFilter(
    private val processNoise: Double = 0.03,    // Q
    private val measurementNoise: Double = 15.0  // R (default accuracy, meters)
) {
    // State: [lat, lon, dLat, dLon]
    private var x = DoubleArray(4)
    private var P = Array(4) { DoubleArray(4) } // initial uncertainty
    private var initialized = false

    fun filter(lat: Double, lon: Double, accuracy: Double?): Pair<Double, Double> {
        val r = accuracy?.takeIf { it > 0 } ?: measurementNoise
        if (!initialized) {
            x = doubleArrayOf(lat, lon, 0.0, 0.0)
            P = Array(4) { i -> DoubleArray(4) { j -> if (i == j) 100.0 * 100.0 else 0.0 } }
            initialized = true
            return lat to lon
        }
        // Predict (constant velocity model)
        // Update (Kalman gain + measurement)
        // Return smoothed lat, lon
        ...
    }

    fun reset() { initialized = false }
}
```

Вызов в `GpsCommandConsumer`: `val (smoothedLat, smoothedLon) = kalmanFilter.filter(lat, lon, null)`

Один `KalmanFilter` instance на terminalId (stateful, хранит предыдущую позицию). Маппинг `terminalId → KalmanFilter` в `ConcurrentHashMap` с TTL cleanup (30 мин idle → remove).

### 3.6 Backend: Партиционирование ASOP_GPS_TRACKING

**DDL (в v001-init.sql, замена текущей таблицы):**

```sql
CREATE TABLE ASOP_GPS_TRACKING (
    POSITION_ID UUID      NOT NULL,
    VEHICLE_ID  UUID      NOT NULL,
    PATH_ID     UUID      NOT NULL,
    SESSION_ID  UUID,
    GPS_COORD   GEOGRAPHY(POINT, 4326),
    RECORDED_AT TIMESTAMPTZ NOT NULL,
    SPEED_KMH   NUMERIC(5, 2),
    STATUS      VARCHAR(30) DEFAULT 'MOVING',
    CONSTRAINT pk_gps_tracking PRIMARY KEY (POSITION_ID, RECORDED_AT),
    CONSTRAINT fk_gps_vehicle FOREIGN KEY (VEHICLE_ID) REFERENCES ASOP_VEHICLES (VEHICLE_ID) ON DELETE CASCADE,
    CONSTRAINT fk_gps_path FOREIGN KEY (PATH_ID) REFERENCES ASOP_PATHS (PATH_ID),
    CONSTRAINT fk_gps_session FOREIGN KEY (SESSION_ID) REFERENCES ASOP_SESSIONS (SESSION_ID)
) PARTITION BY RANGE (RECORDED_AT);

-- Индексы (на каждой партиции создаются автоматически)
CREATE INDEX idx_gps_vehicle_time ON ASOP_GPS_TRACKING (VEHICLE_ID, RECORDED_AT DESC);
CREATE INDEX idx_gps_geo ON ASOP_GPS_TRACKING USING GIST (GPS_COORD);

-- Партиции на 12 месяцев вперёд от текущего месяца
CREATE TABLE ASOP_GPS_TRACKING_2026_09 PARTITION OF ASOP_GPS_TRACKING
    FOR VALUES FROM ('2026-09-01') TO ('2026-10-01');
CREATE TABLE ASOP_GPS_TRACKING_2026_10 PARTITION OF ASOP_GPS_TRACKING
    FOR VALUES FROM ('2026-10-01') TO ('2026-11-01');
CREATE TABLE ASOP_GPS_TRACKING_2026_11 PARTITION OF ASOP_GPS_TRACKING
    FOR VALUES FROM ('2026-11-01') TO ('2026-12-01');
CREATE TABLE ASOP_GPS_TRACKING_2026_12 PARTITION OF ASOP_GPS_TRACKING
    FOR VALUES FROM ('2026-12-01') TO ('2027-01-01');
CREATE TABLE ASOP_GPS_TRACKING_2027_01 PARTITION OF ASOP_GPS_TRACKING
    FOR VALUES FROM ('2027-01-01') TO ('2027-02-01');
CREATE TABLE ASOP_GPS_TRACKING_2027_02 PARTITION OF ASOP_GPS_TRACKING
    FOR VALUES FROM ('2027-02-01') TO ('2027-03-01');
-- ... ещё 6 месяцев (итого 12 от 2026-09)
```

**Важно:** PK теперь `(POSITION_ID, RECORDED_AT)` — обязательное требование для partitioned table (partition key должен быть в PK).

**Автосоздание партиций:** Spring `@Scheduled(fixedDelay = 86400000)` (раз в сутки) — проверяет наличие партиции на текущий месяц + 2 → создаёт если нет.

**Архивирование:** `GpsPurgeJob` (async, раз в сутки):
```sql
-- Найти партиции старше retention
SELECT tablename FROM pg_tables
WHERE tablename LIKE 'asop_gps_tracking_20__%'
  AND tablename < 'asop_gps_tracking_' || to_char(NOW() - INTERVAL '6 months', 'YYYY_MM')
-- DROP TABLE для каждой (fast, без DELETE)
```

Retention configurable через `ASOP_CONFIG_PARAMS`: `keys.gpsRetentionMonths = 6`.

### 3.7 Backend: ServiceRegistry additions

```kotlin
"tracking" to "http://session-service:8085",
"public/tracking" to "http://session-service:8085",
"stops" to "http://route-service:8092",
"public/stops" to "http://route-service:8092"
```

### 3.8 Backend: Миграции Liquibase

В `v001-init.yaml` добавить новый `sqlFile` блок (внутри существующего changeset `v001-init`):
```yaml
- sqlFile:
    relativeToChangelogFile: true
    path: sql/v001-gps-tracking.sql
    splitStatements: false
```

Файл `sql/v001-gps-tracking.sql`:
1. DROP старой таблицы `ASOP_GPS_TRACKING` (если есть)
2. CREATE TABLE ASOP_GPS_TRACKING (partitioned)
3. CREATE TABLE 12 партиций
4. CREATE TABLE ASOP_API_KEYS
5. INSERT seed API key

Актуализировать `asop_schema.sql` — синхронизировать DDL.

---

## 4. Web-admin: Карта live ТС

### 4.1 Зависимости

```bash
cd frontend/web-admin
npm install leaflet react-leaflet
npm install -D @types/leaflet
```

### 4.2 API-клиент (`src/api/tracking.ts`)

```typescript
export interface LiveVehicle {
  vehicleId: string;
  vehicleNumber: string;
  vehicleName: string;
  vehicleType: string;
  latitude: number;
  longitude: number;
  speedKmh: number | null;
  recordedAt: string;
  pathId: string | null;
  pathName: string | null;
  routeId: string | null;
}

export async function getLiveVehicles(params: {
  regionId?: string; carrierId?: string; vehicleId?: string; freshSec?: number
}): Promise<LiveVehicle[]>
```

### 4.3 Страница `LiveMapPage.tsx`

- `MapContainer` (center: [44.95, 34.11] (Крым), zoom 10)
- `TileLayer` (OSM: `https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png`)
- Маркеры для каждого ТС (иконка по vehicleType, попап: №ТС, тип, маршрут, скорость, обновлено)
- `useQuery` с `refetchInterval: 15_000`
- Боковая панель фильтров (absolute right, semi-transparent):
  - Регион (глобальный фильтр)
  - Перевозчик (dropdown)
  - Тип ТС (multi-select)
  - Поиск по номеру
- `fitBounds` при загрузке / смене фильтра
- Кнопка «Следить за ТС» → `map.flyTo`

### 4.4 Роутинг + Sidebar

```tsx
// App.tsx
<Route path="/live-map" element={<LiveMapPage />} />

// Sidebar.tsx — раздел «Мониторинг»
{ label: "Карта ТС", path: "/live-map", icon: <MapIcon /> }
```

---

## 5. Пассажирское приложение (Android)

### 5.1 Структура модуля

```
frontend/passenger-app/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── assets/mock_route_simferopol.json   (100 точек, 30 сек)
│       └── java/ru/asop/passenger/
│           ├── di/AppModule.kt
│           ├── network/
│           │   ├── PassengerApi.kt
│           │   ├── HmacInterceptor.kt
│           │   └── Dtos.kt
│           ├── gps/
│           │   └── MockRoutePlayer.kt          (чтение JSON, выдача координат)
│           ├── ui/
│           │   ├── MapScreen.kt
│           │   ├── StopsSheet.kt
│           │   ├── RoutesSheet.kt
│           │   ├── VehicleSheet.kt
│           │   └── theme/
│           └── model/
│               └── PassengerViewModel.kt
├── build.gradle.kts (root)
├── settings.gradle.kts
└── gradle.properties
```

### 5.2 Зависимости

```kotlin
implementation("org.osmdroid:osmdroid-android:6.1.18")
implementation("com.squareup.retrofit2:retrofit:2.11.0")
implementation("com.squareup.moshi:moshi:1.15.1")
implementation("com.google.dagger:hilt-android:2.52")
implementation("androidx.compose.material3:material3")
implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.0")
```

### 5.3 Экраны (4 экрана, bottom-sheet паттерн)

**MapScreen (основной):**
- osmdroid `MapView` (OSM tiles, zoom 10-18)
- Маркеры live vehicles (икаонка по типу, подпись «авт. №123»)
- Pull-to-refresh polling (15 сек через `LaunchedEffect` + timer)
- `BottomSheetScaffold` с контентом

**Машина состояний:**
```
MAP_ONLY → STOP_SELECTED → ROUTE_SELECTED → VEHICLE_SELECTED
```

1. **MAP_ONLY**: все live ТС региона, кнопка «Остановки» → load stops by bbox → bottom sheet
2. **STOP_SELECTED**: название, адрес, кнопка «Маршруты через остановку» → load routes
3. **ROUTE_SELECTED**: подсветить ТС маршрута (фильтр по pathId)
4. **VEHICLE_SELECTED**: карточка ТС (номер, тип, маршрут, скорость, обновлено), кнопка «Показать след» → load track

### 5.4 API-клиент

```kotlin
interface PassengerApi {
    @GET("public/tracking/live")
    suspend fun getLiveVehicles(
        @Query("freshSec") freshSec: Int = 300
    ): List<LiveVehicle>

    @GET("public/tracking/vehicle/{vehicleId}/track")
    suspend fun getVehicleTrack(
        @Path("vehicleId") vehicleId: String,
        @Query("minutes") minutes: Int = 15
    ): List<TrackPoint>

    @GET("public/stops/bbox")
    suspend fun getStopsInBBox(
        @Query("southWest") southWest: String,
        @Query("northEast") northEast: String
    ): List<Stop>

    @GET("public/stops/{stopId}/routes")
    suspend fun getRoutesForStop(
        @Path("stopId") stopId: String
    ): List<Route>
}
```

### 5.5 Mock Route Player

Класс `MockRoutePlayer` в `gps/`:
- Читает `assets/mock_route_simferopol.json` (массив `{lat, lon, speed, delayMs}`)
- Индекс текущей точки, `Handler.postDelayed` по `delayMs`
- Отдаёт координаты в `GpsTrackingService` вместо `FusedLocationProviderClient`
- Флаг включения: `SyncPreferences.isDebugGps` (DataStore)
- Ротация: когда массив кончается → начать сначала (zap.loop)

### 5.6 Мок-данные

Файл `assets/mock_route_simferopol.json`:
- 100 точек по маршруту Симферополь (44.95, 34.11)
- Интервал 30 сек (30000 ms), общее время ~50 минут
- Координаты с реалистичным разбросом (±0.0001 ≈ 10м)
- Формат: `[{"lat": 44.9572, "lon": 34.1108, "speed": 40.0, "delayMs": 0}, ...]`

---

## 6. Порядок реализации

### Фаза 0: Бэкенд-основа (2,5-4 дня)
1. DDL: `ASOP_API_KEYS` + `ASOP_GPS_TRACKING` partitioned + партиции (Liquibase v001-init.yaml, asop_schema.sql)
2. `KalmanFilter` + интеграция в `GpsCommandConsumer`
3. Gateway: `ApiKeyHmacFilter` + `@Order(0)` цепочка + SecurityConfig
4. session-service: `TrackingController` + `TrackingService` (live + vehicle track)
5. route-service: `StopsBboxController`, `StopRoutesController`
6. ServiceRegistry updates

### Фаза 1: Админ-карта (2,5-3 дня)
1. `npm install leaflet react-leaflet @types/leaflet`
2. `LiveMapPage.tsx` (карта + маркеры + попапы + polling)
3. `api/tracking.ts`
4. Фильтры + Sidebar entry
5. Роутинг

### Фаза 2: Пассажирское приложение (5-7 дней)
1. Модуль-каркас (Gradle, Hilt, Compose, osmdroid, Retrofit)
2. `PassengerApi` + `HmacInterceptor`
3. `MockRoutePlayer` + mock-данные
4. `MapScreen` (osmdroid + маркеры + polling)
5. Bottom sheets (stop → routes → vehicle)
6. Сборка APK

---

## 7. Файлы для изменений

### Новые файлы
- `infrastructure/db-migrations/sql/v001-gps-tracking.sql` — DDL миграции
- `backend/session-service/src/main/kotlin/ru/asop/session/gps/KalmanFilter.kt`
- `backend/session-service/src/main/kotlin/ru/asop/session/controller/TrackingController.kt`
- `backend/session-service/src/main/kotlin/ru/asop/session/service/TrackingService.kt`
- `backend/route-service/src/main/kotlin/ru/asop/route/controller/StopsBboxController.kt`
- `backend/route-service/src/main/kotlin/ru/asop/route/controller/StopRoutesController.kt`
- `backend/gateway-service/src/main/kotlin/ru/asop/gateway/config/ApiKeyHmacFilter.kt`
- `backend/shared/api/gateway-api/src/main/kotlin/ru/asop/api/gateway/dto/response/LiveVehicleDto.kt`
- `frontend/web-admin/src/pages/LiveMapPage.tsx`
- `frontend/web-admin/src/api/tracking.ts`
- `frontend/passenger-app/` — новый модуль целиком

### Изменяемые файлы
- `infrastructure/db-migrations/migrations/v001-init.yaml` — добавить sqlFile блок
- `infrastructure/db-migrations/asop_schema.sql` — актуализировать DDL
- `backend/gateway-service/src/main/kotlin/ru/asop/gateway/config/SecurityConfig.kt` — @Order(0) chain
- `backend/gateway-service/src/main/kotlin/ru/asop/gateway/config/ServiceRegistry.kt` — tracking, stops
- `backend/session-service/src/main/kotlin/ru/asop/session/kafka/GpsCommandConsumer.kt` — Kalman filter
- `backend/session-service/src/main/kotlin/ru/asop/session/model/GpsTrackingEntity.kt` — RECORDED_AT в PK
- `backend/session-service/src/main/kotlin/ru/asop/session/repository/GpsTrackingRepository.kt` — custom queries
- `frontend/web-admin/src/App.tsx` — route /live-map
- `frontend/web-admin/src/components/Sidebar.tsx` — раздел «Мониторинг»
- `frontend/android-terminal/app/src/main/java/ru/asop/terminal/service/GpsTrackingService.kt` — mock GPS
- `frontend/android-terminal/app/src/main/java/ru/asop/terminal/util/SyncPreferences.kt` — isDebugGps flag

---

## 8. Open-вопросы

- Fallback HMAC (plain key) — оставить навсегда или deprecate после rollout passenger app?
- Стоит ли добавить WebSocket/SSE для real-time < 5 сек в будущем, или REST polling достаточен?
- Passenger app: первый запуск — запрос regionId у пользователя или auto-detect по GPS?
- Mock-данные: массив координат на 50 мин ≈ 50 КБ JSON — в assets ок, или лучше raw resource?

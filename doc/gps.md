# GPS-трекинг, live-карта и пассажирское приложение — фактическое состояние

**Дата:** 27.08.2026
**Статус:** реализовано end-to-end (терминал → БД → snap-to-route → web-admin LiveMap + пассажирское Android-приложение)
**Связанное:** `AGENTS.md` (раздел «GPS-трекинг, live-карта и пассажирское приложение»), `doc/context.md` §8, `doc/gps_maps.md`

---

## 1. Цепочка доставки координат (реализовано)

```
Android GpsTrackingService (foreground, mock/FusedLocation) → POST /api/v1/sync/gps/positions (mTLS, X-Event-Seq)
→ gateway GpsCommandController → EventService PENDING + Kafka asop.gps.commands
  (headers X-Event-Id / X-Terminal-Seq / X-Carrier-Id / X-Region-Id / X-Timezone)
→ session-service GpsCommandConsumer (группа session-service-v5):
    GpsPositionFilterRegistry.getFilter(terminalKey) → сглаживание (alpha=0.6, экспоненциальное)
    watermark.applyInOrder(terminalId, seq, …) → APPLIED / ALREADY_APPLIED / DEFERRED
    INSERT ASOP_GPS_TRACKING (ST_GeogFromText WKT, STATUS='MOVING', SESSION_ID=shift.id)
→ CommandResult (COMPLETED / PENDING_WATERMARK / FAILED) → asop.gps.events
→ gateway CommandEventConsumer → EventService.complete/fail
```

| Звено | Реализация |
|---|---|
| Терминал | `GpsTrackingService` (foreground), `LOCATION_INTERVAL_MS=5_000` / fastest 3 c, batch ≥10 → one-shot sync |
| Debug GPS | `MockRoutePlayer` (`ROUTE_FILE="mock_route_301.json"`, 356 точек, зацикливание `% points.size`) при `isDebugGps=true` (dev default); иначе `FusedLocationProviderClient` (PRIORITY_HIGH_ACCURACY) |
| Gateway | `GpsCommandController` → `GpsPositionReported` в `asop.gps.commands`; `X-Event-Seq` HTTP → `X-Terminal-Seq` Kafka |
| Consumer | session-service `GpsCommandConsumer` — сглаживание + watermark-ordering + INSERT в `ASOP_GPS_TRACKING` |
| Обратная связь | `CommandResult` → `asop.gps.events` → gateway → EventService (`202` PENDING → `200`/`422`) |

### Условие записи точки (важно)

Точка попадает в БД только при **всех** условиях:
1. На терминале включён тумблер **«Геопозиция»**;
2. Открыта **смена** (SHIFT);
3. Открыт **рейс** (TRIP) с выбранными **ТС и путём**.

`vehicleId = trip?.vehicleId ?: shift?.vehicleId`, `pathId — аналогично`; если оба null →
точка **молча отбрасывается** (не буферизуется). `SESSION_ID = shift.id` (НЕ trip.id) —
отчёты согласованы непрерывно от открытия смены до её закрытия, `vehicleId/pathId` — из
текущего открытого TRIP.

### Watermark-ordering (промпт 012)

`X-Terminal-Seq` (HTTP `X-Event-Seq`) гарантирует **порядок событий терминала**:
- `terminalId == null` → применяется сразу без watermark;
- иначе `APPLIED` / `ALREADY_APPLIED` → `COMPLETED`; `DEFERRED` → `PENDING_WATERMARK`.

### Сглаживание (не Kalman, а экспоненциальное)

`GpsPositionFilter` (в файле `gps/KalmanFilter.kt`) — экспоненциальное сглаживание
`alpha=0.6` (0 = игнорировать сырьё, 1 = сырые). Состояние фильтра — на терминал.
`@Scheduled 30 мин` → `filterRegistry.cleanup()` (сбрасывает idle-фильтры >30 мин).

---

## 2. Snap-to-route (session-service `gps/GpsRouteSnapper.kt`)

- `snap(pathId, lat, lon, maxDistanceMeters=300.0): Mono<SnappedPoint>` — проекция точки
  на полилинию `ASOP_PATHS.ROUTE_OBJECT` (jsonb GeoJSON `LineString`, `[lon,lat]`).
- **Кэш геометрии** `ConcurrentHashMap<UUID, CachedGeometry>` **TTL 5 мин** + re-entrancy
  guard (in-flight загрузка расшаривается между запросами). SQL:
  `SELECT ROUTE_OBJECT::text AS ROUTE_JSON FROM ASOP_PATHS WHERE PATH_ID = :pathId AND DELETED_AT IS NULL`.
- `parseLineString` → `List<[lat, lon]>`; `projectPointToSegment` — локальная
  equirectangular аппроксимация (`DEG_TO_M_LAT=111_320`, `DEG_TO_M_LON=78_800`);
  дистанция `haversineMeters` (`EARTH_RADIUS_M=6_371_000`).
- Если лучшая дистанция **> maxDistanceMeters** → `null` (без снапа).

---

## 3. Live-API (session-service)

### `GET /api/v1/tracking/live?regionId&carrierId&vehicleId&freshSec` (default `freshSec=600`)

- CTE `latest`: `DISTINCT ON (g.VEHICLE_ID)` по последней точке, `RECORDED_AT > NOW() - freshSec`.
- Селекты: `ST_Y/ST_X(GPS_COORD::geometry)`, JOIN `ASOP_VEHICLES` (VEHICLE_NUMBER/VEHICLE_NAME),
  LEFT JOIN `ASOP_PATHS` (PATH_NAME/ROUTE_ID), LEFT JOIN `ASOP_VEHICLE_TYPES` (TYPE_NAME).
- Фильтры: `v.CARRIER_ID = :carrierId`; регион через
  `CARRIER_ID IN (SELECT CARRIER_ID FROM ASOP_CARRIERS WHERE REGION_ID = :regionId)`.
- `flatMap` → `findSnapped(pathId, lat, lon)` → `LiveVehicleDto` с
  `snappedLatitude/snappedLongitude` (null если нет геометрии или >300 м).
- **Важно (баг-фикс live-tracking)**: `GpsRouteSnapper.snap()` использует `mapNotNull` и возвращает
  `Mono.empty()` когда геометрия отсутствует либо точка > maxDistance. В `TrackingService.getLiveVehicles`
  это компенсируется `.defaultIfEmpty(dto(null))` — иначе `flatMap` **полностью пропадал ТС из ответа**
  (live API возвращал `[]` при пустой геометрии пути). Vehicle всегда включается в ответ с raw-координатами
  и `snapped=null`; snap лишь дополняет `snappedLatitude/snappedLongitude`.

`LiveVehicleDto`: `vehicleId, vehicleNumber, vehicleName, vehicleType, latitude, longitude,
snappedLatitude?, snappedLongitude?, speedKmh?, recordedAt, pathId?, pathName?, routeId?, sessionId?`.

### `GET /api/v1/tracking/vehicle/{vehicleId}/track?minutes` (default 15)

`TrackPointDto`: все точки за N минут ASC, каждая снапнута.

session-service `SecurityConfig`: `pathMatchers("/api/v1/tracking/**").permitAll()`
(публичный — аутентификацию делает gateway: JWT для web-admin, API-ключ для пассажира).

---

## 4. Gateway: публичный контур + proxy

- `config/ApiKeyHmacFilter.kt` — WebFilter на `/api/v1/public/**`: `X-API-Key` (нет → 401,
  не в keyCache → 403), sliding-window rate-limit (default 60/мин → 429). Если приходят
  `X-Timestamp`+`X-Signature` → HMAC-SHA256 по строке `"" + timestamp`, `|now-ts|>300с` → 401.
  Конфиг `asop.api-keys` / env `ASOP_API_KEYS`, формат `key:hmac_secret:rate_limit`;
  default `asop-passenger-prod-key-2026:a1b2...e1f2:60`.
- `controller/PublicProxyController.kt` — `/api/v1/public/**`: `tracking*` → session-service:8085,
  `stops*` → route-service:8092, иначе 404. Пробрасывает query-string, снимает префикс `public/`.
- `SecurityConfig` gateway: **новая chain `@Order(0)`** `publicPassengerFilterChain` для
  `/api/v1/public/**` (`permitAll`, auth в WebFilter).
- `ServiceRegistry`: `tracking` → session-service:8085, `stops` → route-service:8092
  (используются и JWT-proxy web-admin).

---

## 5. web-admin

### `pages/LiveMapPage.tsx` (Карта ТС, `/live-map`)

- Leaflet + OSM-тайлы, центр `[44.95,34.11]`, zoom 12; polling
  `getLiveVehicles({freshSec:120})` каждые **3 c** (TanStack Query `refetchInterval`).
- Плавное перемещение маркеров: CSS `transition: transform 4.5s linear`.
- Цвет по `vehicleType`: Автобус `#2563eb`, Троллейбус `#16a34a`, Трамвай `#dc2626`,
  Маршрутное такси `#f59e0b`, иначе `#6b7280`.
- Попап: №ТС, тип, имя, `pathName`, `speedKmh`, `recordedAt`.
- Фильтры: «По маршруту (snapped)» (`showSnapped` — snapped vs raw), поиск по №, типы ТС,
  удаление устаревших маркеров.
- Follow-vehicle: клик по маркеру → pan (debounce 2.5 c), остановка при drag/zoom.

### `pages/routes/RouteEditorPage.tsx` (Редактор маршрута, `/route-editor`)

- `RouteDrawer`: клик = вершина, пунктир-превью к курсору, Enter/«Готово (линия)» завершает
  (**dblclick убран** — ломал «третью точку»), Ctrl+click удаляет вершину, «Очистить».
- `densifyPolyline(points, DENSE_STEP_METERS=40)` — пересэмплинг ~40 м между точками.
- `save()`: `routeObject = JSON.stringify({type:'LineString', coordinates: dense.map(([lat,lon])=>[lon,lat])})`
  (GeoJSON `[lon,lat]`) → `updatePath`.
- Показ «Вершин (кликов)» + «Точек в базе (шаг 40 м)»; `finishedRef` всегда `false` на
  загрузке — существующую геометрию можно продлевать.
- `api/tracking.ts`: `getLiveVehicles`, `getVehicleTrack`.

---

## 6. Пассажирское приложение (`frontend/passenger-app`, `ru.asop.passenger`)

- Kotlin + Compose + **osmdroid 6.1.18** + Hilt + Moshi + Retrofit; `applicationId=ru.asop.passenger`,
  minSdk 26 / targetSdk 35.
- Base URL `https://192.168.1.6:8080` (BuildConfig `GATEWAY_BASE_URL` из `local.properties`
  `gateway.host`, default `10.0.2.2`); **отладка отключает SSL-проверку** (trustAllCerts).
- `PassengerApi`: `GET public/tracking/live`, `public/tracking/vehicle/{id}/track`,
  `public/stops/bbox(?southWest&northEast)`, `public/stops/{stopId}/routes`.
- `HmacInterceptor` — добавляет `X-API-Key`/`X-Timestamp`/`X-Signature` (ключ/секрет в `AppModule.kt`).
- `MapScreen`: `TileSourceFactory.MAPNIK`; `displayVehicles = vehicles.filter { snapped != null }` —
  **скрывает ТС без снапа** (не на маршруте); позиция `snappedLatitude ?: latitude`;
  **интерполяция 4.5 c** (coroutine, 30 шагов); трек выбранного ТС — polyline (синий, 6px);
  состояния `MAP_ONLY/STOP_SELECTED/ROUTE_SELECTED/VEHICLE_SELECTED`; FAB → `loadStops(bbox)`.
- `PassengerViewModel`: polling `getLiveVehicles(freshSec=120)` каждые **3 c**.
- Остановки и маршруты остановки — route-service `StopsBboxController` (`/api/v1/stops/bbox`,
  `ST_Centroid(ZONE_POLYGON)`) и `StopRoutesController` (`/api/v1/stops/{id}/routes`,
  JOIN `ASOP_PATH_TRANSPORT_STOPS → ASOP_PATHS → ASOP_ROUTES`).

---

## 7. Mock GPS (терминал)

- `gps/MockRoutePlayer.kt` — `ROUTE_FILE = "mock_route_301.json"` (assets), список
  `RoutePoint(lat, lon, speed, delayMs)`; зацикливание `currentIndex = (currentIndex+1) % points.size`;
  emit через `Handler(Looper.getMainLooper())`.
- `assets/mock_route_301.json` — **356 точек** по маршруту 301 (`...222000006700`),
  `delayMs=5000`, `speed=40`, цикл ~29.7 мин.
- `SyncPreferences.KEY_DEBUG_GPS` (`isDebugGps`, default **true dev**): debug → mock, иначе FusedLocation.
- Старый `mock_route_simferopol.json` (100 точек, путь `...222000008400`) — не используется.

---

## 8. База данных

`ASOP_GPS_TRACKING` (asop_schema.sql):

```
POSITION_ID UUID PK      VEHICLE_ID UUID NOT NULL   PATH_ID UUID NOT NULL
SESSION_ID  UUID NULL     GPS_COORD GEOGRAPHY(POINT,4326)
RECORDED_AT TIMESTAMPTZ   SPEED_KMH NUMERIC(5,2)     STATUS VARCHAR(30) DEFAULT 'MOVING'
PK (POSITION_ID, RECORDED_AT); PARTITION BY RANGE (RECORDED_AT)
FK: fk_gps_vehicle→ASOP_VEHICLES (CASCADE), fk_gps_path→ASOP_PATHS, fk_gps_session→ASOP_SESSIONS
Индексы: idx_gps_vehicle_time (VEHICLE_ID, RECORDED_AT DESC), idx_gps_geo GIST(GPS_COORD)
```

**Геометрия маршрута** — `ASOP_PATHS.ROUTE_OBJECT` (jsonb GeoJSON `LineString`, `[lon,lat]`).
Маршрут 301 («прямой», путь `00000000-0000-0000-0000-222000006700`, 356 точек) сидится в
`infrastructure/docker/seed-data.sql` (`UPDATE ASOP_PATHS SET ROUTE_OBJECT = '...'::jsonb`).

Геометрия старого mock-маршрута `...222000008400` — в `infrastructure/docker/seed-route-geometries.sql`.

---

## 9. Константы / дефолты (quick ref)

| Параметр | Значение | Где |
|---|---|---|
| Интервал GPS | `LOCATION_INTERVAL_MS=5_000`, fastest `3_000` | терминал |
| Batch GPS | `GPS_BATCH_SIZE=10` → one-shot sync | терминал |
| `freshSec` дефолт | **600** (бэкенд); web-admin и пассажир поллят `120` каждые **3 c** | session-service / web / app |
| Max дистанция снапа | `maxDistanceMeters=300.0` | snapper |
| Кэш геометрии | TTL **5 мин** | snapper |
| Шаг плотности | `DENSE_STEP_METERS=40` | редактор маршрута |
| Интерполяция маркеров | **4.5 c** | web (CSS) / app (coroutine) |
| API-ключ | `asop-passenger-prod-key-2026:a1b2...e1f2:60` | gateway `asop.api-keys` |
| Base URL пассажира | `https://192.168.1.6:8080` | `passenger-app/local.properties` |

---

## 10. Статус: что готово / что осталось

### ✅ Реализовано
- Полная цепочка: терминал (mock/Fused) → gateway → Kafka → session-service → `ASOP_GPS_TRACKING`.
- Snap-to-route (`GpsRouteSnapper`) с кэшем геометрии TTL 5 мин и maxDistance 300 м.
- Live-API `GET /tracking/live` + `GET /tracking/vehicle/{id}/track`.
- Gateway публичный контур (API-ключ + HMAC + rate-limit) и `stops`/`tracking` ServiceRegistry.
- web-admin LiveMap (`/live-map`) с фильтром raw/snapped, поиском, follow-vehicle.
- web-admin RouteEditor (`/route-editor`) с densify 40 м и редактированием `ROUTE_OBJECT`.
- Пассажирское Android-приложение (osmdroid) — live-карта, остановки, маршруты, трек.
- Debug mock по маршруту 301 (356 точек) + сид геометрии в seed-data.sql.

### 🔲 Осталось (потенциально)
- ETA / прогноз прибытия (движка светки расписания с движением нет).
- Push-уведомления о приближении ТС.
- Heading/bearing — направление движения (вычислять по двум последним точкам).
- Retention / реальное партиционирование `ASOP_GPS_TRACKING` по месяцам.
- Self-hosted тайл-сервер + офлайн-кэш osmdroid для продакшена (см. `doc/gps_maps.md`).

---

**См. также:** `doc/gps_maps.md` — источник тайлов, лицензии OSM, режим офлайн.

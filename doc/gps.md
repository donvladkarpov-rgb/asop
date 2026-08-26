# GPS-трекинг транспортных средств — анализ и планы развития

**Дата:** 26.08.2026
**Статус:** анализ фактического состояния + оценки работ
**Связанное:** `AGENTS.md` (промпт 011 — сессии/GPS), `doc/context.md` §8

---

## 1. Как устроено сейчас (фактическое состояние)

### 1.1. Цепочка доставки координат

| Звено | Реализация | Статус |
|---|---|---|
| Android `GpsTrackingService` | foreground service, `FusedLocationProviderClient`, точка каждые **30 с**, batch ≥10 форсирует sync | ✅ код есть |
| Офлайн-буфер | `PendingEventEntity(GPS_POSITION)` в Room → `SyncWorker` (15 мин периодик + one-shot при batch) → `POST /api/v1/sync/gps/positions` (mTLS) | ✅ |
| Gateway | `GpsCommandController` → Kafka `asop.gps.commands` (+ EventService PENDING, TTL 24 ч) | ✅ |
| Consumer | session-service `GpsCommandConsumer` (группа `session-service-v5`) → INSERT в `ASOP_GPS_TRACKING`, PostGIS `ST_GeogFromText`, `STATUS='MOVING'` | ✅ |
| Порядок событий | watermark-ordering по заголовку `X-Terminal-Seq` (промпт 012): APPLIED / ALREADY_APPLIED / DEFERRED | ✅ |
| Обратная связь | CommandResult → `asop.gps.events` → gateway `CommandEventConsumer` (partition подтверждена в логах) → EventService COMPLETED/FAILED | ✅ |
| DDL | `v001-init.sql:1357`: `GPS_COORD GEOGRAPHY(POINT,4326)` | ✅ |

### 1.2. Условия записи точки (важно)

Точка попадает в БД только при **всех** условиях одновременно:

1. На терминале включён тумблер **«Геопозиция»**;
2. Открыта **смена** (SHIFT);
3. Открыт **рейс** (TRIP) с выбранными **ТС и путём**.

`GpsTrackingService.kt:84-87`: `vehicleId = trip?.vehicleId ?: shift?.vehicleId`, `pathId` — аналогично; если хоть один null → позиция **молча отбрасывается** (не буферизуется).

⚠️ **Нюанс против исходного замысла**: fallback «из смены» фактически бесполезен — смена всегда открывается с `vehicleId=null/pathId=null` (`SessionFlowViewModel.confirmOpenShift`). Координаты собираются **только внутри открытого рейса**. Вне рейса трекер молчит by design.

### 1.3. Свежесть данных («live»-латентность)

- Онлайн: batch ≥10 точек ≈ отправка раз в **~5 мин** (10 × 30 с);
- Офлайн/нестабильная сеть: до периода SyncWorker — **15 мин**;
- Итого «сейчас» для потребителя карты ≈ задержка 1–10 минут. Для админки приемлемо, для пассажирского приложения — погранично (лечится immediate-flush, см. §4).

### 1.4. Живая проверка (26.08.2026)

- Стек поднят (21 контейнер), топики `asop.gps.commands`/`asop.gps.events` созданы, consumer'ы подписаны;
- `SELECT COUNT(*) FROM asop_gps_tracking` = **0** — данных нет, т.к. на F20 сервис не запущен и/или нет открытого рейса с ТС+путём.
- Механизм работает как система, но end-to-end не был прогнан с реальными данными.

### 1.5. Схема `ASOP_GPS_TRACKING`

```
POSITION_ID UUID PK          SESSION_ID UUID NULL (→ ASOP_SESSIONS, = SHIFT.id)
VEHICLE_ID  UUID NOT NULL    GPS_COORD GEOGRAPHY(POINT,4326)
PATH_ID     UUID NOT NULL    RECORDED_AT TIMESTAMPTZ NOT NULL
SPEED_KMH   NUMERIC(5,2)     STATUS VARCHAR ('MOVING', …)
```

Замечания к схеме:
- **Нет heading/bearing** — направление движения стрелкой не показать (можно вычислять по двум последним точкам, ~0,25 дн.);
- Нет индекса под live-запрос `(vehicle_id, recorded_at DESC)`;
- Объём: ~2880 точек/ТС/сутки при интервале 30 с — со временем потребуется retention/партиционирование.

---

## 2. Оценка: экран админки «Карта ТС в рейсе»

Фильтры: организатор перевозок / перевозчик / отдельное ТС (+глобальный фильтр региона web-admin).

| Работа | Оценка |
|---|---|
| Backend (session-service — владелец `ASOP_GPS_TRACKING`): `GET /api/v1/tracking/live?organizerId&carrierId&vehicleId&freshSec` — последняя точка на ТС (`DISTINCT ON (vehicle_id) … WHERE recorded_at > now() - :freshSec AND session_id IN (открытые TRIP)`), JOIN vehicles/trips; индекс `(vehicle_id, recorded_at DESC)`; маппинг `tracking` → session-service:8085 в `ServiceRegistry` | 0,5–1 дн. |
| Frontend (web-admin): Leaflet + react-leaflet (OSM, без ключей), страница «Мониторинг»: фильтры, авто-refresh 15–30 c (TanStack Query), маркеры по типу ТС, попапы (№ТС, маршрут, скорость, возраст точки), fit-bounds | 2–3 дн. |
| Полировка (empty/error states, follow-vehicle) | 0,5 дн. |

**Итого: 3–4 dev-дня.**
Опции/дельты:
- Яндекс.Карты / 2ГИС вместо OSM: **+0,5–1 дн.** (ключи, лицензии);
- Вычисление направления по двум последним точкам: **+0,25 дн.**

---

## 3. Оценка: приложение пассажира (карта + остановки + маршруты + ТС)

### 3.1. Дополнительно нужен backend (для любой платформы)

Пассажиры — публичный контур без JWT:

- публичные read-only endpoints: `GET /public/tracking/live`, `/public/stops/nearby|bbox`, `/public/routes-by-stop`;
- новая security-цепочка gateway (`permitAll` на `/api/v1/public/**`) + rate-limit;
- остановки — полигоны зон: для карты отдавать `ST_Centroid(zone_polygon)` (или добавить колонку точки — правка v001 + сид, ~0,5 дн.).

Оценка: **1–1,5 дн.**

### 3.2. MVP-функционал (без ETA)

- Карта: ТС в рейсе (тот же tracking/live) + слои остановок по видимой области (bbox);
- Тап по остановке → список маршрутов через неё (`path_transport_stops` JOIN paths/routes);
- Тап по маршруту → подсветка его ТС;
- Тап по ТС → карточка (маршрут, скорость, время последней точки).

### 3.3. Варианты платформы

| Вариант | Объём работ | Итого с backend |
|---|---|---|
| **Нативный Android** (Kotlin + Compose, новое приложение: каркас Hilt/Nav, карта osmdroid/MapLibre или Яндекс SDK, 4 экрана, polling, сборка/подпись) | 5,5–7,5 дн. | **~6,5–8 дн.** |
| **PWA** (React + Leaflet, много общего с админкой; ставится на телефон «на главный экран») | 2,5–3,5 дн. | **~3,5–5 дн.** |

Не включено (отдельные фазы):
- **Прогноз прибытия (ETA)**: нет рантайм-движка светки расписания с движением — **+3–5 дн.**;
- Push-уведомления о приближении ТС — отдельно.

---

## 4. Рекомендуемые сопутствующие доработки

| Задача | Зачем | Оценка |
|---|---|---|
| Immediate-flush GPS при стабильном онлайне (batch 4–5 или таймер ≤60 с) | свежесть для пассажира: 1–2 мин вместо 5–15 | 0,5–1 дн. |
| Heading по двум последним точкам | стрелки направления на карте | 0,25 дн. |
| Индекс + retention/партиционирование GPS-таблицы | рост ~2880 точек/ТС/сутки | 1–2 дн. |
| E2E-прогон трекера (смок: открыть смену→рейс с ТС/путём→«Геопозиция» → проверка БД) | сейчас 0 строк, цепочка живьём не подтверждена | 0,25–0,5 дн. |

## 5. Сводка

| Комбинация | Оценка |
|---|---|
| Только админ-карта | 3–4 дн. |
| Админ-карта + пассажир PWA + доработки из §4 | ~8–10 дн. |
| Админ-карта + нативное Android-приложение пассажира + доработки | ~11–14 дн. |

*Все оценки — для одного разработчика, знакомого со стеком. Ключевые открытые вопросы: платформа пассажира (PWA vs native), карт-провайдер (OSM vs Яндекс/2ГИС), нужна ли ETA.*

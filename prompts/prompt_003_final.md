# Prompt 003 Final — VERSION cursor + keyset pagination + terminal context + ContentProvider + test harness

**Порядок выполнения:** строго последовательно — Этап A (VERSION cursor) → Этап B (keyset pagination, после A) → Этап C (terminal context в sync-командах) → Этап D (ContentProvider) → Этап E (seed-скрипты + android-test).

---

## Контекст проекта

Проект **ASOP** — платформа оплаты проезда. Монорепо в `/home/vlad/IdeaProjects/asop`. Backend — Kotlin 2.0.21 + Spring Boot 3.3.5 (WebFlux, R2DBC), Kafka, Redis, MinIO, PostgreSQL. Android-терминал — Kotlin + Jetpack Compose + Room.

Схема данных — **источник истины `infrastructure/db-migrations/asop_schema.sql`** (42 таблицы-справочника `ASOP_*`). Рабочая миграция `infrastructure/db-migrations/migrations/` — копия `asop_schema.sql`, в рамках этой задачи **пересоздаётся** (копируется поверх, без `rm -rf`).

**Как связаны файлы миграции:**
- `infrastructure/db-migrations/asop_schema.sql` — **единственный источник DDL**, правим только его;
- `infrastructure/db-migrations/migrations/v001-init.sql` — копия `asop_schema.sql`, пересоздаётся: `cp infrastructure/db-migrations/asop_schema.sql infrastructure/db-migrations/migrations/v001-init.sql` (поверх старого, **без `rm -rf`**);
- `infrastructure/db-migrations/migrations/v001-init.yaml` — Liquibase changeSet (не менять);
- `infrastructure/db-migrations/db.changelog-master.yaml` — точка входа (не менять).

Сборка: `./gradlew build -x test` (проверять после правки). Android: `cd frontend/android-terminal && ./gradlew :app:assembleDebug`. Тестов нет. `fallbackToDestructiveMigration()` уже настроен в `AppModule.kt` — Room-миграции через бамп version.

**Маппинг таблиц к мастер-сервисам (фактический, из `ServiceRegistry.kt`):**

| Группа | Таблица | Сервис | ServiceRegistry resource |
|---|---|---|---|
| 0 | ASOP_REGIONS | admin-service | regions |
| 0 | ASOP_TERRITORIES | admin-service | territories |
| 0 | ASOP_ORGANIZERS | admin-service | organizers |
| 0 | ASOP_ORGANIZER_TERRITORIES | admin-service | organizer-territories |
| 1 | ASOP_ROLES | admin-service | roles |
| 1 | ASOP_CARD_TYPES | admin-service | card-types |
| 1 | ASOP_TARIFF_TYPES | admin-service | tariff-types |
| 1 | ASOP_SESSION_TYPES | admin-service | session-types |
| 1 | ASOP_EVENT_TYPES | admin-service | event-types |
| 1 | ASOP_TRANSACTION_TYPES | admin-service | transaction-types |
| 1 | ASOP_TRANSACTION_RESULTS | admin-service | transaction-results |
| 1 | ASOP_SERVICES | admin-service | services |
| 1 | ASOP_BENEFITS | admin-service | benefits |
| 1 | ASOP_BENEFIT_STEPS | admin-service | benefit-steps |
| 2 | ASOP_CARRIERS | carrier-service | carriers |
| 2 | ASOP_CONTRACTS | carrier-service | contracts |
| 2 | ASOP_CARDS_DISTRIBUTORS | carrier-service | cards-distributors |
| 2 | ASOP_CONTRACT_ROUTES | **route-service** | contract-routes |
| 2 | ASOP_VEHICLE_TYPES | **route-service** | vehicle-types |
| 2 | ASOP_VEHICLE_MODELS | **route-service** | vehicle-models |
| 2 | ASOP_VEHICLES | **route-service** | vehicles |
| 2 | ASOP_TIDS | carrier-service | tids |
| 3 | ASOP_USERS | user-service | admin-users |
| 3 | ASOP_USER_ROLES | user-service | user-roles |
| 3 | ASOP_USER_CARRIERS | user-service | user-carriers |
| 3 | ASOP_USER_REGIONS | user-service | user-regions |
| 4 | ASOP_FARE_ZONES | route-service | fare-zones |
| 4 | ASOP_TRANSPORT_STOPS | route-service | transport-stops |
| 4 | ASOP_ROUTES | route-service | routes |
| 4 | ASOP_PATHS | route-service | paths |
| 4 | ASOP_PATH_TRANSPORT_STOPS | route-service | path-transport-stops |
| 4 | ASOP_SCHEDULE | route-service | schedule |
| 4 | ASOP_PATH_SERVICES | route-service | path-services |
| 4 | ASOP_PATH_DISCOUNTS | route-service | path-discounts |
| 4 | ASOP_PATH_BENEFITS | route-service | path-benefits |
| 5 | ASOP_CARDS | card-service | cards |
| 5 | ASOP_CARD_MIFARES | card-service | card-mifares |
| 5 | ASOP_CARD_BANKS | card-service | card-banks |
| 5 | ASOP_CARD_TARIFFS | card-service | card-tariffs |
| 5 | ASOP_BLACKLISTS | card-service | blacklists |
| 5 | ASOP_USER_BENEFITS | card-service | user-benefits |
| 5 | ASOP_TARIFF_RATES | card-service | tariff-rates |

**⚠️ ВНИМАНИЕ:** ASOP_CONTRACT_ROUTES, ASOP_VEHICLE_TYPES, ASOP_VEHICLE_MODELS, ASOP_VEHICLES — **route-service** (не carrier-service!). Проверить по `backend/gateway-service/src/main/kotlin/ru/asop/gateway/config/ServiceRegistry.kt` и `backend/orchestrator-service/src/main/kotlin/ru/asop/orchestrator/service/MasterRegistry.kt`.

---

## Этап A: Замена дельта-курсора UPDATED_AT → VERSION (sequence) во всём стеке

**Проблема с текущим курсором `UPDATED_AT TIMESTAMPTZ`:** для bulk-вставок 100–200к строк `now()` одинаков в пределах транзакции. Keyset-пагинация (`updated_at > курсор` + `LIMIT`) ломается.

**Решение:** колонка `VERSION BIGINT` из **глобального sequence** через триггер. Sequence даёт строго уникальные возрастающие значения даже в одном INSERT. Единый глобальный watermark `lastVersion: Long` вместо `Map<tableName, Instant>`.

### A.1. БД — править `infrastructure/db-migrations/asop_schema.sql`

Все DDL-изменения ТОЛЬКО в `asop_schema.sql`. После правок: `cp infrastructure/db-migrations/asop_schema.sql infrastructure/db-migrations/migrations/v001-init.sql` (без `rm -rf`).

#### A.1.1. Глобальный sequence
```sql
CREATE SEQUENCE IF NOT EXISTS asop_delta_version_seq;
```

#### A.1.2. Колонка VERSION во всех 42 дельта-таблицах
В первый DO-блок (с `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`), после `DELETED_AT`:
```sql
EXECUTE format('ALTER TABLE %I ADD COLUMN IF NOT EXISTS VERSION BIGINT', _t);
```

#### A.1.3. Триггер простановки VERSION из sequence
```sql
CREATE OR REPLACE FUNCTION trg_fn_delta_version() RETURNS TRIGGER AS $body$
BEGIN
    NEW.version := nextval('asop_delta_version_seq');
    RETURN NEW;
END;
$body$ LANGUAGE plpgsql;
```
DO-блок (FOREACH по списку из 42 таблиц):
```sql
EXECUTE format('DROP TRIGGER IF EXISTS trg_delta_version_%s ON %I', _t, _t);
EXECUTE format('CREATE TRIGGER trg_delta_version_%s BEFORE INSERT OR UPDATE ON %I FOR EACH ROW EXECUTE FUNCTION trg_fn_delta_version()', _t, _t);
```

#### A.1.4. Soft-delete
`trg_fn_soft_delete` делает `UPDATE ... SET updated_at = now(), deleted_at = now()`. UPDATE пройдёт через `trg_delta_version_*` (BEFORE INSERT OR UPDATE) — version забампается автоматически. Убедиться, что все soft-delete-таблицы покрыты version-триггером.

#### A.1.5. Триггер UPDATED_AT — распространить на INSERT
Существующий `trg_fn_touch_updated()` (функция: `NEW.updated_at := now()`) навешан **только на `BEFORE UPDATE`**. Изменить в DO-блоке: `BEFORE UPDATE` → **`BEFORE INSERT OR UPDATE`**. Функцию менять не нужно.

#### A.1.6. CREATED_AT / UPDATED_AT — оставить
Они не мешают, остаются для диагностики. Дельта-курсор — VERSION.

#### A.1.7. Комментарии и справка
Обновить комментарий блока «DELTA SYNC SUPPORT»: дельта теперь по VERSION из sequence. Обновить `infrastructure/db-migrations/asop_schema.md` — отразить sequence/VERSION/триггер.

### A.2. Proto — ДВА файла, идентичных по содержимому

1. `backend/shared/asop-proto/src/main/proto/schema.proto`
2. `frontend/android-terminal/app/src/main/proto/schema.proto`

**НЕ заменять `updated_at` на `version`.** Поля `created_at`, `updated_at`, `deleted_at` остаются как есть. В каждое из 42 Row-сообщений **ДОБАВИТЬ** новое поле **после** `deleted_at` (т.е. `version` становится **последним** полем, `deleted_at` — предпоследним):
```proto
int64 version = N;   // N = номер поля deleted_at + 1
```
Номер индивидуален для каждого сообщения. `DeltaChunk`, `XxxFile` — не трогать. Проверить: `diff backend/shared/asop-proto/src/main/proto/schema.proto frontend/android-terminal/app/src/main/proto/schema.proto` — пуст.

### A.3. Мастер-сервисы — 42 дельта-контроллера

Параметр `updatedAtSince: Instant?` → **`versionSince: Long?`**. Фильтр: `WHERE version > :versionSince` (вместо `updated_at > :since`). Сортировка: `ORDER BY version ASC` (вместо `updated_at ASC`).

**⚠️ Три копии `DeltaSupport.kt`** (admin-service, carrier-service, card-service — по одной в каждом `config/`):
- `backend/admin-service/src/main/kotlin/ru/asop/admin/config/DeltaSupport.kt`
- `backend/carrier-service/src/main/kotlin/ru/asop/carrier/config/DeltaSupport.kt`
- `backend/card-service/src/main/kotlin/ru/asop/card/config/DeltaSupport.kt`

В **КАЖДОЙ** из трёх: `query(versionSince: Long?, ...)` и критерий с `"updated_at"` → `"version"`. Сортировка `Sort.by("version")`. **Не забыть ни одну копию!**

#### A.3.1. admin-service (14 контроллеров)
`backend/admin-service/.../controller/*.kt` — `versionSince: Long?` + `Criteria.where("version").greaterThan(...)`. `OrganizerTerritoryRepository` — если использует `updated_at` в @Query, заменить.

#### A.3.2. carrier-service (4 контроллера)
+ `config/DeltaSupport.kt` (своя копия — та же замена).

#### A.3.3. route-service (13 контроллеров + репозиторий)
`GenericRouteRepository.findDelta` — `version > :versionSince`, bind тип `Long`, `ORDER BY version ASC`. **⚠️ КРИТИЧНО:** в `selectClause` (строка ~106) — жёсткий список `"$baseSelect, created_at, updated_at, deleted_at"`. **Добавить `version`** в этот список. Проверить `RouteTableRegistry` / `ResourceInfo.selectColumns` — если жёсткий список колонок, добавить `version` для **всех 13 таблиц** route-service.

#### A.3.4. user-service (4 контроллера)
Ручная сборка SQL через `DatabaseClient`. `updated_at > :since` → `version > :versionSince`, bind `Long`, `ORDER BY version`.

#### A.3.5. card-service (7 контроллеров + `config/DeltaSupport.kt` + 7 репозиториев)
Репозитории `@Query` — `:versionSince IS NULL OR m.VERSION > :versionSince`, `ORDER BY m.VERSION ASC`, параметр `Long`. **JOIN-ы и `userIdsIn` не трогать.**

### A.4. Kafka контракт — `backend/shared/asop-kafka-contracts/.../DeltaEvents.kt`

```kotlin
data class DeltaSyncCommand(
    val eventId: UUID,
    val terminalId: UUID,
    val carrierId: UUID? = null,
    val regionId: UUID? = null,
    val lastVersion: Long? = null   // единый глобальный watermark (sequence)
)
```
(вместо `lastUpdatedAt: Map<String, Instant>`). `FullSyncCommand` — не менять. KDoc обновить.

### A.5. Orchestrator-service

`DeltaSyncService.kt` — везде `command.lastUpdatedAt[table]` → `command.lastVersion`. Тип `Instant?` → `Long?`. Параметр запроса к мастеру: `updatedAtSince` → `versionSince`. JSON-ключ: `node.get("version")?.asLong()`.

`DeltaCommandConsumer.kt` — лог: `command.lastUpdatedAt.size` → `command.lastVersion`.

`ProtoRowMapper.kt` — проверить, что proto-поле `version` (int64, JSON-ключ `"version"`) маппится через `JavaType.LONG` → `value.asLong()`. Скорее всего менять не нужно — но **проверить**.

`FullSyncService.kt` — **не менять** в рамках этого этапа (пагинацию см. Этап B).

### A.6. Gateway-service

**⚠️ ВАЖНО:** gateway **перестаёт резолвить** `carrierId`/`regionId` (терминал шлёт их в теле запроса). `TerminalResolver` больше не используется.

#### A.6.1. DTO — `backend/shared/api/gateway-api/.../request/DeltaSyncRequest.kt`
```kotlin
data class DeltaSyncRequest(
    val terminalId: UUID,
    val carrierId: UUID? = null,
    val regionId: UUID? = null,
    val lastVersion: Long? = null
)
```

#### A.6.2. DTO — `backend/shared/api/gateway-api/.../request/FullSyncRequest.kt`
```kotlin
data class FullSyncRequest(
    val terminalId: UUID,
    val carrierId: UUID? = null,
    val regionId: UUID? = null
)
```
(сейчас только `terminalId`).

#### A.6.3. `DeltaCommandService.kt`
`publishDelta`: `DeltaSyncCommand(eventId, request.terminalId, request.carrierId, request.regionId, request.lastVersion)`. `publishFull`: `FullSyncCommand(eventId, request.terminalId, request.carrierId, request.regionId)`.

#### A.6.4. `DeltaReferenceController.kt`
Убрать вызов `terminalResolver.resolve(...)` — `publishDelta`/`publishFull` принимают request напрямую. Убрать `terminalResolver` из конструктора.

#### A.6.5. `TerminalResolver.kt` + `TerminalContext` — **удалить целиком**
Проверить: `grep -rn "TerminalResolver\|TerminalContext" backend/gateway-service/` — не должно остаться ссылок.

### A.7. Android-терминал

#### A.7.1. Network DTO — `.../network/models/DeltaModels.kt`
```kotlin
data class DeltaSyncRequest(
    @Json(name = "terminalId") val terminalId: String,
    @Json(name = "carrierId") val carrierId: String? = null,
    @Json(name = "regionId") val regionId: String? = null,
    @Json(name = "lastVersion") val lastVersion: Long? = null
)
data class FullSyncRequest(
    @Json(name = "terminalId") val terminalId: String,
    @Json(name = "carrierId") val carrierId: String? = null,
    @Json(name = "regionId") val regionId: String? = null
)
```

#### A.7.2. SyncPreferences — **добавить carrierId, regionId, timezone** (перенесено из п.4 промпта 003 для немедленной работоспособности)

`frontend/android-terminal/app/src/main/java/ru/asop/terminal/db/SyncPreferences.kt`:
- Новые ключи: `KEY_CARRIER_ID = stringPreferencesKey("carrier_id")`, `KEY_REGION_ID = stringPreferencesKey("region_id")`, `KEY_TIMEZONE = stringPreferencesKey("timezone")`.
- Flows: `carrierId: Flow<String?>`, `regionId: Flow<String?>`, `timezone: Flow<String?>`.
- Setters: `setCarrierId(String?)`, `setRegionId(String?)`, `setTimezone(String?)`.
- Заполняются при регистрации (из `RegistrationScreen` / `TerminalViewModel.registerTerminal`). **Таймзона определяется автоматически** (`TimeZone.getDefault().id`), пользователь может изменить выбор через dropdown на экране регистрации.

#### A.7.3. Room — `SyncMetaEntity` → single-row (глобальный watermark)

```kotlin
@Entity(tableName = "sync_meta")
data class SyncMetaEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: Int = 0,                     // единственная строка
    @ColumnInfo(name = "last_version")
    val lastVersion: Long? = null,
    @ColumnInfo(name = "last_sync_at")
    val lastSyncAt: Long? = null
)
```
`SyncMetaDao` — `get(): Flow<SyncMetaEntity?>`, `upsert(entity)`. Убрать `getAll()`-списковый вариант.

**Room migration:** `version = 3` → `4`. `fallbackToDestructiveMigration()` уже настроен (`AppModule.kt:138`) — достаточно бампа версии.

#### A.7.4. Room — `ReferenceRowEntity` — добавить `version`

```kotlin
@ColumnInfo(name = "version")
val version: Long? = null
```
Добавить индекс `Index("version")`. `updatedAt`/`deletedAt` оставить.

#### A.7.5. `ReferenceSyncStore.kt`
- `toReferenceRow`: добавить чтение `version` (int64) через `FieldDescriptor`. `updatedAt` оставить (диагностический).
- `applyRows`: вместо per-table `MAX(updated_at)` — **один** `SyncMetaEntity(id=0, lastVersion = rows.mapNotNull { it.version }.maxOrNull() ?: return)`.
- Проверить `applyChunk`/`applyFile` — proto-поле `version` (int64) обрабатывается через descriptor.

#### A.7.6. `DeltaSyncWorker.kt`
```kotlin
val lastVersion = syncMetaDao.get().first()?.lastVersion
val carrierId = syncPreferences.carrierId.first()
val regionId = syncPreferences.regionId.first()
val response = gatewayApi.deltaSync(DeltaSyncRequest(terminalId, carrierId, regionId, lastVersion))
```
**ВАЖНО:** `carrierId`/`regionId` читаются из `SyncPreferences` (ключи добавлены в A.7.2). Если значение `null` (терминал ещё не привязан к перевозчику) — передавать как есть (мастер вернёт данные без фильтра или пусто — по контракту).

#### A.7.7. `DeltaChunkPollWorker.kt` / `FullDumpDownloadWorker.kt` / `ReferenceSyncViewModel.kt`
Согласовать с новым `SyncMetaEntity` (single-row) и `ReferenceRowEntity.version`. `DeltaSyncJobDao` — не трогать.
- `FullDumpDownloadWorker.kt` — при вызове `fullSync(FullSyncRequest(terminalId))` **дополнить `carrierId`/`regionId` из `SyncPreferences`** (те же `.first()`-чтения, что в A.7.6):
  ```kotlin
  val carrierId = syncPreferences.carrierId.first()
  val regionId = syncPreferences.regionId.first()
  val response = gatewayApi.fullSync(FullSyncRequest(terminalId, carrierId, regionId))
  ```
- `ReferenceSyncViewModel.fullSync` (кнопка «Полная выкачка» в UI) — тот же паттерн: читать `carrierId`/`regionId` из `SyncPreferences` перед `gatewayApi.fullSync(...)`.

#### A.7.8. TerminalRegisterRequest — terminal-api + gateway-api

- `backend/shared/api/terminal-api/.../request/TerminalRegisterRequest.kt` — добавить `val regionId: UUID? = null` (timezone и carrierId уже есть).
- `backend/shared/api/gateway-api/.../request/TerminalRegisterRequest.kt` — **УДАЛИТЬ файл целиком.** Это мёртвый код: gateway-api DTO нигде не импортируется (проверено grep), `ProxyController` не десериализует тело (`BodyInserters.fromDataBuffers(request.body)` — сырой passthrough). Регистрацию валидирует terminal-api DTO. Проверить после удаления: `grep -rn "gateway.dto.request.TerminalRegisterRequest" backend/` — пусто.
- Android `TerminalModels.kt` — `TerminalRegisterRequest` уже имеет `timezone`, добавить `regionId: String? = null`.

#### A.7.9. `TerminalViewModel.registerTerminal` — сохранять region/carrier/timezone

После успешной регистрации: `syncPreferences.setCarrierId(carrierId)`, `syncPreferences.setRegionId(regionId)`, `syncPreferences.setTimezone(timezone)`.

### A.8. Проверка этапа A

1. `diff <(grep -v '^--' infrastructure/db-migrations/asop_schema.sql) <(grep -v '^--' infrastructure/db-migrations/migrations/v001-init.sql)` — пуст (кроме комментариев).
2. `diff backend/shared/asop-proto/src/main/proto/schema.proto frontend/android-terminal/app/src/main/proto/schema.proto` — пуст.
3. `./gradlew build -x test` — BUILD SUCCESSFUL.
4. `cd frontend/android-terminal && ./gradlew :app:assembleDebug` — BUILD SUCCESSFUL.
5. `docker compose -f infrastructure/docker/docker-compose.yml down -v && up -d --build`. Проверить:
   ```sql
   SELECT column_name FROM information_schema.columns WHERE table_name='asop_regions' AND column_name='version';
   SELECT last_value FROM asop_delta_version_seq;
   INSERT INTO asop_regions (...) VALUES (...) без updated_at/version → updated_at заполнился now(), version из sequence.
   INSERT 200к строк через generate_series → SELECT count(DISTINCT version) = 200к.
   ```

---

## Этап B: Keyset-пагинация по VERSION в DeltaSyncService и FullSyncService

**Строго после Этапа A.**

### Проблема
`limit=10_000` жёстко зашит. Для 100–200к записей мастер вернёт только первые 10к, остальные потеряются.

### Решение — keyset-пагинация по `version`

### B.1. `DeltaSyncService.kt`

Методы `fetchDelta(ep, carrierId, regionId, versionSince)` и `fetchDeltaWithUserIds(ep, userIds, versionSince)`:

1. Первый запрос с `versionSince` из команды.
2. Если вернул **ровно `limit`** — ещё запрос, `versionSince = version` последней строки.
3. Повторять пока страница **< limit**.
4. Объединить все страницы.

**Реактивный паттерн (Project Reactor `expand`):**
```kotlin
fun fetchAllPages(initialVersionSince: Long?, ...): Flux<JsonNode> {
    return fetchPage(initialVersionSince, ...)
        .collectList()
        .flatMapMany { firstPage ->
            if (firstPage.size < LIMIT) {
                Flux.fromIterable(firstPage)
            } else {
                val cursor = firstPage.last().get("version")?.asLong()
                Flux.fromIterable(firstPage)
                    .concatWith(
                        if (cursor != null) fetchAllPages(cursor, ...)
                        else Flux.empty()
                    )
            }
        }
}
```
Или через `expand`:
```kotlin
fetchPage(versionSince).collectList()
    .expand { page ->
        if (page.size >= LIMIT) {
            val cursor = page.last().get("version")?.asLong()
            if (cursor != null) fetchPage(cursor).collectList() else Mono.empty()
        } else Mono.empty()
    }
    .flatMap { Flux.fromIterable(it) }
```

**Требования:**
- Без `.block()`, строго `Mono`/`Flux`.
- Курсор: `node.get("version")?.asLong()`. Нет `version` у строки → страница последняя (прервать).
- Строгая граница: `version > курсор` (ровно `version` последней строки в `versionSince`, ничего не прибавлять).
- `limit` = 10000 (размер страницы, не увеличивать).
- Фильтры `carrierId`/`regionId`/`userIdsIn` прокидываются в каждую страницу.
- `onErrorResume` на странице: warn + `Flux.empty()`, **прервать цикл** (не продолжать с пустым курсором).
- `fetchUserIds` — пагинация достанется автоматически.

### B.2. `FullSyncService.kt`

`fetchAll` делает 3 вида запросов (users / обычные / user-card). Добавить keyset-пагинацию по той же логике:
1. Вынести запрос страницы в `fetchPage(uriBuilder, versionSince)`.
2. Первый запрос без курсора (`versionSince=null`), всегда `includeDeleted=true`.
3. При полной странице (`size >= limit`) — повторить с `versionSince = version` последней строки.
4. Общий хелпер `fetchPage` уберёт дублирование для трёх видов.
5. Не менять: `ChunkingService`, `MasterRegistry`, `ProtoRowMapper`, контракты, ZIP/MinIO.

### B.3. Проверка этапа B
- `./gradlew :backend:orchestrator-service:build -x test` — BUILD SUCCESSFUL.
- Опционально: залить 100–200к строк, запустить дельта-синк — в логах orchestrator несколько страниц на таблицу, итог без потерь.

---

## Этап C: Terminal context в sync-командных запросах

Терминал сохраняет `regionId`, `carrierId`, `timezone` в `SyncPreferences` (уже добавлены в A.7.2). Добавить эти поля в **request DTO** всех 10 sync-командных эндпоинтов.

**Правило полей (общее для всех эндпоинтов):** добавлять **`regionId` и `timezone`** (nullable) **везде**. **`carrierId` — только там, где его ещё нет** (в `DebtCreateRequest` и `AuditTaskCreateRequest` он уже есть — не дублировать).

### C.1. Backend — request DTO в доменных API-модулях

DTO живут в **разных API-модулях** (не в gateway-api). Правки по каждому файлу:

| Эндпоинт | DTO-файл | Модуль | Что добавить |
|---|---|---|---|
| `/sync/sessions/open` | `backend/shared/api/session-api/.../request/SessionOpenRequest.kt` | session-api | regionId, timezone |
| `/sync/sessions/{id}/close` | `backend/shared/api/session-api/.../request/SessionCloseRequest.kt` | session-api | regionId, timezone |
| `/sync/transactions` | `backend/shared/api/gateway-api/.../request/TransactionCompleteRequest.kt` | gateway-api | regionId, carrierId, timezone |
| `/sync/cards/register` | `backend/shared/api/card-api/.../request/CardRegisterRequest.kt` | card-api | regionId, carrierId, timezone |
| `/sync/cards/{id}/block` | `backend/shared/api/card-api/.../request/CardBlockRequest.kt` | card-api | regionId, carrierId, timezone |
| `/sync/debts` | `backend/shared/api/debt-api/.../request/DebtCreateRequest.kt` | debt-api | regionId, timezone (**carrierId уже есть**) |
| `/sync/debts/{id}/recover` | `backend/shared/api/debt-api/.../request/DebtRecoverRequest.kt` | debt-api | regionId, timezone (**carrierId уже есть**) |
| `/sync/fiscal/receipts` | `backend/shared/api/fiscal-api/.../request/FiscalReceiptRequest.kt` | fiscal-api | regionId, carrierId, timezone |
| `/sync/audit/tasks` | `backend/shared/api/audit-api/.../request/AuditTaskCreateRequest.kt` | audit-api | regionId, timezone (**carrierId уже есть**) |
| `/sync/gps/positions` | `backend/shared/api/gateway-api/.../request/GpsPositionReport.kt` | gateway-api | regionId, carrierId, timezone |

Все новые поля — nullable (`UUID?`/`String?`), чтобы старые/простые случаи не ломались. Проверить, что gateway-контроллеры этих эндпоинтов принимают именно эти DTO (см. `import` в `*CommandController.kt`).

### C.2. Android — `SyncModels.kt` и др.
В `SessionOpenRequest`, `SessionCloseRequest`, `TransactionCompleteRequest`, `CardRegisterRequest`, `CardBlockRequest`, `DebtCreateRequest`, `DebtRecoverRequest`, `FiscalReceiptRequest`, `AuditTaskCreateRequest`, `GpsPositionReport` — добавить:
```kotlin
@Json(name = "regionId") val regionId: String? = null,
@Json(name = "carrierId") val carrierId: String? = null,
@Json(name = "timezone") val timezone: String? = null
```
(nullable — старые случаи не ломаются; carrierId не дублировать там, где уже есть). Значения заполняются из `SyncPreferences` при формировании запроса в `SyncApi`/workers/UI.

### C.3. Gateway command services
В `SessionCommandService`, `TransactionCommandService`, `CardCommandService`, `DebtCommandService`, `FiscalCommandService`, `AuditCommandService`, `GpsCommandService` — при отправке Kafka-сообщения **добавить headers**:
```kotlin
record.headers().add("X-Carrier-Id", request.carrierId?.toString()?.encodeToByteArray())
record.headers().add("X-Region-Id", request.regionId?.toString()?.encodeToByteArray())
record.headers().add("X-Timezone", request.timezone?.encodeToByteArray())
```
**Event DTOs** (`SessionOpenedEvent`, `TransactionCompletedEvent`, etc.) в `asop-kafka-contracts` — **НЕ менять**. Контекст передаётся в Kafka headers (как `X-Event-Id`, `X-Keycloak-Id`), консьюмеры могут читать (через `@Header`) или игнорировать.

### C.4. Документация
В `doc/architecture.md`, `doc/context.md`, `AGENTS.md` — зафиксировать: **gateway выполняет только авторизацию/аутентификацию/проксирование, бизнес-логики там нет**. Терминал сам передаёт business context (carrierId, regionId, timezone) во всех запросах.

---

## Этап D: ContentProvider в android-terminal

### D.1. ContentProvider

Создать `ContentProvider` в `android-terminal` для всех 6 Room-сущностей (`PendingEventEntity`, `SessionEntity`, `TransactionEntity`, `SyncMetaEntity`, `DeltaSyncJobEntity`, `ReferenceRowEntity`).

- **URI authority:** `ru.asop.terminal.provider`
- **Permission:** custom `<permission android:name="ru.asop.terminal.provider.READ" android:protectionLevel="signature"/>` — объявить в `android-terminal/AndroidManifest.xml`. Доступ только для приложений, подписанных тем же debug-ключом.
- `android-test` объявляет `<uses-permission android:name="ru.asop.terminal.provider.READ"/>`.
- Expose `query()` API: `contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)` для каждой таблицы.
- **`ReferenceRowEntity`** (composite PK `table_name + row_id`) — **без** integer `_id`. ContentProvider `query()` возвращает `Cursor` с колонками таблицы. Test app делает raw query и сравнивает значения — `_id` не обязателен для raw query.
- URI patterns:
  - `content://ru.asop.terminal.provider/pending_events`
  - `content://ru.asop.terminal.provider/sessions`
  - `content://ru.asop.terminal.provider/transactions`
  - `content://ru.asop.terminal.provider/sync_meta`
  - `content://ru.asop.terminal.provider/delta_sync_jobs`
  - `content://ru.asop.terminal.provider/reference_rows`

### D.2. ContentProvider implementation

```kotlin
class AsopContentProvider : ContentProvider() {
    override fun query(uri, projection, selection, selectionArgs, sortOrder): Cursor? {
        // match URI → Room DAO query → Cursor (via MatrixCursor or Room's SupportSQLiteQuery)
    }
}
```
Рекомендуется `MatrixCursor` (создаётся вручную, заполняется из DAO данных). Объявить в `AndroidManifest.xml`:
```xml
<provider
    android:name=".AsopContentProvider"
    android:authorities="ru.asop.terminal.provider"
    android:exported="true"
    android:readPermission="ru.asop.terminal.provider.READ" />
```

---

## Этап E: Тестирование — seed-скрипты + android-test

### E.1. Seed-скрипты (delta)

Написать `infrastructure/docker/seed-data-delta-1.sql`, `seed-data-delta-2.sql`, `seed-data-delta-3.sql`.

**Правила:**
- **Регионы и перевозчиков НЕ трогать** — использовать существующие из `seed-data.sql`: регион `('00000000-0000-0000-0000-000000000103', 'Республика Крым', ...)` и перевозчика `('00000000-0000-0000-0000-000000001403', 'ГУП "Крымавтотранс"', ...)`.
- Все bulk-данные генерировать привязанными к этому перевозчику/региону — чтобы фильтры мастеров возвращали их терминалу.
- Для GLOBAL-справочников (roles, card-types, tariff-types и т.п.) — любые объёмы.
- Учитывать FK: `ASOP_USERS` → `ASOP_USER_CARRIERS`/`ASOP_USER_REGIONS`; карточные таблицы → `ASOP_CARDS.user_id` на пользователей этого перевозчика.
- Объёмы: delta-1 ≈ 1–2к, delta-2 ≈ 100–200к, delta-3 ≈ 10–20к. Через `generate_series`. Триггеры НЕ отключать — sequence VERSION уникален.
- Не указывать `version` в INSERT — триггер проставит автоматически. Не указывать `updated_at`/`created_at` — `DEFAULT NOW()` заполнит.

### E.2. android-test приложение

Создать `frontend/android-test/` — новое Android-приложение (Kotlin + Jetpack Compose), подписывается тем же debug-ключом.

**Меню (3 пункта):**
- "Тест дельта инкремента 1"
- "Тест дельта инкремента 2"
- "Получить все данные"

**Ethalon JSON — генерируется SQL-запросом (не вручную!).** Для каждого среза:
```sql
-- Для таблицы asop_benefits (FILTERED по regionId):
-- Исключаем created_at/updated_at (timestamps недетерминированы при bulk-INSERT через generate_series — триггер ставит now(), точное значение неизвестно).
SELECT json_agg(row_to_json(t)) FROM (
  SELECT version, deleted_at, benefit_id, benefit_code, benefit_name, region_id, description, is_active
  FROM asop_benefits
  WHERE region_id = '00000000-0000-0000-0000-000000000103'
  ORDER BY version ASC
) t;

-- Для GLOBAL-таблицы asop_roles:
SELECT json_agg(row_to_json(t)) FROM (
  SELECT version, deleted_at, role_id, role_name, role_code
  FROM asop_roles
  WHERE deleted_at IS NULL
  ORDER BY version ASC
) t;
```
**Сравнение в android-test:** полный набор полей, **КРОМЕ** `created_at`/`updated_at` (не включать их в эталон — либо не выбирать в SQL, либо `jq 'del(.created_at, .updated_at)'` при пост-обработке). Сравнивать:
- `version` — да, точное совпадение (число из sequence, уникально);
- `deleted_at` — да (null для активных; для удалённых — проверить `IS NOT NULL` без точного timestamp);
- все business-поля (`name`, `type_id`, FK и т.д.) — точное совпадение;
- `created_at`/`updated_at` — **исключить** из сравнения (или сравнивать только `IS NOT NULL`, без значения).

Выгрузить через `psql -t -A` в `.json` файлы, зашить в `android-test/app/src/main/assets/`:
- `expected-1.json` — данные из `seed-data.sql` + `seed-data-delta-1.sql` (отфильтрованные по carrier `...1403` / region `...0103`)
- `expected-2.json` — из `seed-data.sql` + delta-1 + delta-2
- `expected-all.json` — из всех четырёх скриптов

Фильтрация из SQL-запросов повторяет логику мастер-сервисов: GLOBAL — все строки; FILTERED — по carrier/region; USER/CARD — по userIds (через JOIN user_carriers/user_regions на carrier `...1403` + region `...0103`).

**Процесс тестирования:**
1. Старт с чистого стека: `docker compose down -v && up -d --build`. Накатить `seed-data.sql`. Предусловие: терминал зарегистрирован и привязан к перевозчику `...1403` (даёт `terminalId` и `SyncPreferences` с регионом/перевозчиком/таймзоной).
2. Накатить `seed-data-delta-1.sql` → в **android-terminal** запустить дельта-синк: drawer → «Загрузить справочники» → **«Дельта сейчас»** (запускает `DeltaSyncWorker`) → дождаться статуса **COMPLETED** в карточке синхронизации (поллинг события + качание чанков) → в **android-test** нажать «Тест дельта инкремента 1» → проверить через ContentProvider, что данные в `reference_rows` совпадают с `expected-1.json`.
3. Накатить `seed-data-delta-2.sql` → в android-terminal снова «Дельта сейчас» → дождаться COMPLETED → в android-test нажать «Тест дельта инкремента 2» → сверить с `expected-2.json`.
4. Накатить `seed-data-delta-3.sql` → в **android-terminal** запустить **«Полная выкачка»** (`enqueueFullDump`, drawer → «Загрузить справочники») → дождаться скачивания ZIP из MinIO (COMPLETED) → в android-test нажать **«Получить все данные»** → сверить с `expected-all.json`.

**ВАЖНО:** android-test **не может** инициировать mTLS-синк (сертификат и `SyncApi` — в android-terminal). Поэтому синк всегда запускается вручную в android-terminal («Дельта сейчас» / «Полная выкачка»), а кнопки в android-test только проверяют результат через ContentProvider.

### E.3. Проверка этапа E

- `cd frontend/android-test && ./gradlew :app:assembleDebug` — BUILD SUCCESSFUL.
- Установить оба APK на эмулятор.
- Прогнать полный цикл: seed → delta-1 → test-1 → delta-2 → test-2 → delta-3 → full-dump → test-all.
- Данные в Room (через ContentProvider) должны совпадать с ethalon JSON.

---

## Итог: файлы, которые изменятся

| Категория | Файлы | Что меняется |
|---|---|---|
| БД | `asop_schema.sql` + `migrations/v001-init.sql` (копия) | sequence + VERSION + version-триггер + UPDATED_AT на INSERT |
| Proto | `schema.proto` (backend + Android, идентичны) | + `int64 version` в 42 Row-сообщениях |
| Master-сервисы | 42 контроллера + 3× `DeltaSupport.kt` + route `GenericRouteRepository` + card репозитории | `versionSince: Long?` |
| Kafka | `DeltaEvents.kt` | `lastVersion: Long?` |
| Orchestrator | `DeltaSyncService.kt` (cursor + pagination), `FullSyncService.kt` (pagination), `DeltaCommandConsumer.kt`, `ProtoRowMapper.kt` (check) | cursor + keyset |
| Gateway | `DeltaSyncRequest`, `FullSyncRequest`, `DeltaCommandService`, `DeltaReferenceController`, delete `TerminalResolver` | lastVersion + carrierId/regionId из request |
| Android DTO | `DeltaModels.kt`, `TerminalModels.kt`, `SyncModels.kt` (10 sync DTOs) | lastVersion + carrierId/regionId/timezone |
| Android Room | `AppDatabase` (v3→v4), `SyncMetaEntity` (single-row), `ReferenceRowEntity` (+version), `ReferenceSyncStore`, DAOs, workers | global watermark + version |
| Android prefs | `SyncPreferences.kt` | + carrierId, regionId, timezone |
| Android terminal-api | `TerminalRegisterRequest.kt` (terminal-api) + **удалить** gateway-api `TerminalRegisterRequest.kt` | + regionId (terminal-api); gateway-api DTO — мёртвый код, файл удалить целиком |
| Android ContentProvider | new `AsopContentProvider.kt` + `AndroidManifest.xml` | signature-permission, 6 tables |
| Gateway command services | 7× `*CommandService.kt` | Kafka headers X-Carrier-Id/X-Region-Id/X-Timezone |
| Test | `seed-data-delta-1/2/3.sql`, new `frontend/android-test/` | bulk data + ethalon JSON (SQL-generated) + ContentProvider checker |
| Docs | `AGENTS.md`, `doc/context.md`, `doc/architecture.md`, `asop_schema.md` | VERSION cursor, terminal context, gateway = auth/proxy only |

---

## Важно: НЕ трогать

- `ChunkingService`, `MasterRegistry` — не менять.
- `ProtoRowMapper` — только проверить int64 mapping (менять если не работает).
- `DeltaChunk`/`XxxFile`/`DeltaChunkMeta` proto-сообщения — не трогать.
- Механизм ZIP/MinIO в `FullSyncService` — не менять (только добавить цикл пагинации).
- Event DTOs в `asop-kafka-contracts` (`SessionOpenedEvent`, `TransactionCompletedEvent`, etc.) — НЕ менять. Контекст передаётся в Kafka headers.
- Не добавлять лишние комментарии (стиль проекта — краткие KDoc).
- `rm -rf migrations/` — НЕ делать. Копировать `asop_schema.sql` поверх `v001-init.sql` через `cp`.
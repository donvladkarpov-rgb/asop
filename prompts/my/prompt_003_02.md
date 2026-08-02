# Задача: заменить дельта-курсор с UPDATED_AT (timestamp) на VERSION (sequence) во всём дельта-стеке

## Контекст проекта

Проект **ASOP** — платформа оплаты проезда. Монорепо в `/home/vlad/IdeaProjects/asop`. Backend — Kotlin 2.0.21 + Spring Boot 3.3.5 (WebFlux, R2DBC), Kafka, Redis, MinIO, PostgreSQL. Android-терминал — Kotlin + Jetpack Compose + Room.

Схема данных — **источник истины `infrastructure/db-migrations/asop_schema.sql`** (41 таблица-справочник `ASOP_*`). Это консолидированный SQL-файл со всей схемой. Рабочая миграция `infrastructure/db-migrations/migrations/` (для Liquibase) — **копия** `asop_schema.sql`, и в рамках этой задачи она **полностью пересоздаётся с нуля** (старая удаляется, новая строится по изменённому `asop_schema.sql`).

**Как связаны файлы миграции** (важно понять перед началом):
- `infrastructure/db-migrations/asop_schema.sql` — **единственный источник DDL**, правим только его;
- `infrastructure/db-migrations/migrations/v001-init.sql` — копия `asop_schema.sql` (сейчас отличие — один комментарий про SUPERUSER в блоке DELTA SYNC SUPPORT), пересоздаётся из `asop_schema.sql`;
- `infrastructure/db-migrations/migrations/v001-init.yaml` — Liquibase changeSet, `sqlFile` → `v001-init.sql` (относительный путь);
- `infrastructure/db-migrations/db.changelog-master.yaml` — точка входа, `include` → `migrations/v001-init.yaml`.

**Процесс обновления БД в Docker:** `docker compose down -v` (полная очистка volume), затем `up -d` — Liquibase накатывает миграцию с нуля на пустую БД. Checksum-проблем нет, т.к. это чистый старт.

Сборка: `./gradlew build -x test` (проверять после правки), Android: `./gradlew -p frontend/android-terminal :app:assembleDebug` или в `frontend/android-terminal` — `./gradlew :app:assembleDebug`. Тестов в репозитории нет.

## Что такое дельта-синхронизация и зачем менять курсор

**Поток:** Android-терминал → `POST /api/v1/sync/references/delta` → gateway кладёт `DeltaSyncCommand` в Kafka `asop.delta.commands` → **orchestrator-service** опрашивает мастер-сервисы по `GET /api/v1/{resource}/delta`, сериализует записи в Protobuf, режет на чанки по 50 КБ в Redis, помечает событие COMPLETED → терминал скачивает чанки и атомарно накатывает в Room.

**Проблема с текущим курсором `UPDATED_AT TIMESTAMPTZ`:** для тестов нужны bulk-вставки 100–200к строк на таблицу. PostgreSQL `now()` одинаков в пределах одной транзакции, поэтому у всех строк одного INSERT будет **один и тот же `updated_at`**. Это ломает keyset-пагинацию (`updated_at > курсор` + `LIMIT`): после первой страницы из 10к строк следующий запрос `> T` вернёт 0, и оставшиеся 190к строк потеряются.

**Решение:** ввести в каждой дельта-таблице колонку `VERSION BIGINT`, заполняемую из **глобального sequence** через триггер. Sequence даёт строго уникальные возрастающие значения даже в одном INSERT — пагинация по `version > курсор` работает идеально. Дополнительно это позволяет упростить клиент: вместо `Map<tableName, Instant>` — **один глобальный watermark** `lastVersion: Long`.

## Общий план изменений (7 этапов)

---

## Этап 1: БД — править `infrastructure/db-migrations/asop_schema.sql`, затем пересоздать миграцию

**Все DDL-изменения вносим ТОЛЬКО в `asop_schema.sql`.** После правок — пересоздать миграцию (см. Этап 1.7). Номера строк ниже относятся к текущему `asop_schema.sql` (он почти идентичен старому `v001-init.sql`, различия в комментариях ±1 строка).

### 1.1. Глобальный sequence
```sql
CREATE SEQUENCE IF NOT EXISTS asop_delta_version_seq;
```

### 1.2. Колонка VERSION во всех 41 дельта-таблицах
Список таблиц уже зафиксирован в двух DO-блоках (строки ~1602-1614 и ~1752-1764). Расширить первый DO-блок: после `DELETED_AT` добавить
```sql
EXECUTE format('ALTER TABLE %I ADD COLUMN IF NOT EXISTS VERSION BIGINT', _t);
```

### 1.3. Триггер простановки VERSION из sequence
Триггер **на INSERT и UPDATE** проставляет `NEW.version := nextval('asop_delta_version_seq')` (значение, переданное приложением, игнорируется). Переиспользовать паттерн существующего `trg_fn_touch_updated()` (строки ~1662-1668). Создать новую функцию + DO-блок по всем 41 таблице:
```sql
CREATE OR REPLACE FUNCTION trg_fn_delta_version() RETURNS TRIGGER AS $body$
BEGIN
    NEW.version := nextval('asop_delta_version_seq');
    RETURN NEW;
END;
$body$ LANGUAGE plpgsql;
```
и в DO-блоке (FOREACH по списку из 41 таблицы):
```sql
EXECUTE format('DROP TRIGGER IF EXISTS trg_delta_version_%s ON %I', _t, _t);
EXECUTE format('CREATE TRIGGER trg_delta_version_%s BEFORE INSERT OR UPDATE ON %I FOR EACH ROW EXECUTE FUNCTION trg_fn_delta_version()', _t, _t);
```
Триггеры должны навешиваться **после** touch-триггера `trg_touch_updated_*` (порядок срабатывания BEFORE не важен — оба независимы: один ставит updated_at, другой version).

### 1.4. Soft-delete
`trg_fn_soft_delete` (строки ~1647-1658): сейчас делает `UPDATE ... SET updated_at = now(), deleted_at = now()`. Обновление пройдёт через `trg_delta_version_*` (BEFORE UPDATE) — version забампается автоматически. Ничего дополнительно делать не нужно, но **убедиться**, что soft-delete-таблицы покрыты version-триггером (все 41 в списке).

### 1.5. CREATED_AT / UPDATED_AT — оставить
Они не мешают, остаются для диагностики/человеческого чтения. Дельта-курсор переключается на VERSION, но колонки удалять не надо.

### 1.6. Комментарий DO-блока «DELTA SYNC SUPPORT»
Обновить (строки ~1587-1596): описать, что дельта теперь по VERSION из sequence.

### 1.7. Пересоздать миграцию `infrastructure/db-migrations/migrations/` с нуля
После того как `asop_schema.sql` изменён (этапы 1.1–1.6):

1. **Удалить старую миграцию целиком**: `rm -rf infrastructure/db-migrations/migrations` (удалить и `v001-init.sql`, и `v001-init.yaml` — всё).
2. **Создать каталог заново**: `mkdir infrastructure/db-migrations/migrations`.
3. **Скопировать изменённый источник**: `cp infrastructure/db-migrations/asop_schema.sql infrastructure/db-migrations/migrations/v001-init.sql`.
4. **Создать новый `v001-init.yaml`** — Liquibase changeSet, ссылающийся на копию (как было раньше):
```yaml
databaseChangeLog:
  - changeSet:
      id: v001-init
      author: asop
      changes:
        - sqlFile:
            path: v001-init.sql
            relativeToChangelogFile: true
            splitStatements: false
            stripComments: true
            encoding: UTF-8
```
5. **`db.changelog-master.yaml` не менять** — он уже делает `include: migrations/v001-init.yaml` (relativeToChangelogFile: true), путь сохраняется.
6. **Синхронизировать справку**: обновить `infrastructure/db-migrations/asop_schema.md` (и `asop_schema.html`/`.puml`/`.svg`, если генерируются вручную и есть задача) — отразить новые блоки sequence/триггера/колонки VERSION. Если генерация диаграмм — вне скоупа, хотя бы обновить `.md`.
7. **Проверка после правок `asop_schema.sql`**: `diff <(grep -v '^--' infrastructure/db-migrations/asop_schema.sql) <(grep -v '^--' infrastructure/db-migrations/migrations/v001-init.sql)` должен быть пуст (кроме допустимых отличий в комментариях).

**Важно:** старую миграцию удаляем ПОЛНОСТЬЮ (один changeset v001 на весь SQL). Никаких v002+ — вся схема в v001, как и было. Liquibase применяет её на пустую БД после `docker compose down -v`.

---

## Этап 2: Proto — ДВА файла, идентичных по содержимому

Оба файла **должны оставаться побайтово идентичными** (второй — копия для Android):

1. `backend/shared/asop-proto/src/main/proto/schema.proto`
2. `frontend/android-terminal/app/src/main/proto/schema.proto`

**НЕ заменять `updated_at` на `version`.** Поля `created_at`, `updated_at`, `deleted_at` **остаются как есть** (string, те же номера). Вместо этого в каждое из 41 Row-сообщения **ДОБАВИТЬ новое поле**:
```proto
int64 version = N;   // N = номер поля deleted_at + 1
```
`version` — int64 (число из sequence), `deleted_at` остаётся **последним** полем — новое поле добавляется **после** него.

**Как определить N:** в каждом Row-сообщении поле `deleted_at` сейчас — последнее и имеет максимальный номер (проверено автоматически для всех 41 сообщения). Номер для `version` = номер `deleted_at` + 1 (следующий свободный). Например, у `RegionsRow` `deleted_at = 15`, значит `version = 16`; у сообщений с `deleted_at = 5` → `version = 6`; и т.д. — номер индивидуален для каждого сообщения.

Пример (было):
```proto
message RegionsRow {
  ...
  string created_at = 13;
  string updated_at = 14;
  string deleted_at = 15;
}
```
(стало):
```proto
message RegionsRow {
  ...
  string created_at = 13;
  string updated_at = 14;
  string deleted_at = 15;
  int64 version = 16;
}
```

Сообщения `DeltaChunk`, `XxxFile`, `DeltaChunkMeta` и прочие (не Row) — не трогать.

---

## Этап 3: Мастер-сервисы — 42 дельта-контроллера + репозитории

Параметр запроса `updatedAtSince` → **`versionSince`**, тип `Instant` → `Long`. Семантика фильтра: `WHERE version > :versionSince` (вместо `updated_at > :since`).

### 3.1. admin-service (14 контроллеров)
Файлы: `backend/admin-service/src/main/kotlin/ru/asop/admin/controller/*.kt` (Region, Territory, Organizer, Role, CardType, TariffType, SessionType, EventType, TransactionType, TransactionResult, Service, Benefit, BenefitStep, OrganizerTerritory).

Каждый содержит `@GetMapping("/delta") fun listDelta(... updatedAtSince: Instant? ...)`.
Заменить на `versionSince: Long?`. В теле заменить `Criteria.where("updated_at").greaterThan(...)` → `Criteria.where("version").greaterThan(...)`.

Единый хелпер `backend/admin-service/src/main/kotlin/ru/asop/admin/config/DeltaSupport.kt` (строки ~14-23) — изменить сигнатуру `query(versionSince: Long?, ...)` и критерий с `"updated_at"` на `"version"`. Сортировка — `Sort.by("version")` (вместо `"updated_at"`).

`backend/admin-service/src/main/kotlin/ru/asop/admin/repository/OrganizerTerritoryRepository.kt` — если использует `updated_at` в @Query, заменить на `version` + тип `Long`.

### 3.2. carrier-service (4 контроллера)
Файлы: `backend/carrier-service/src/main/kotlin/ru/asop/carrier/controller/*.kt` (Carrier, Contract, CardsDistributor, Tid) + `config/DeltaSupport.kt` (своя копия) — та же замена, что в 3.1.

### 3.3. route-service (13 контроллеров + репозиторий)
Файлы: `backend/route-service/src/main/kotlin/ru/asop/route/controller/*.kt` (VehicleType, VehicleModel, Vehicle, FareZone, TransportStop, Route, Path, PathTransportStop, Schedule, PathService, PathDiscount, PathBenefit, ContractRoute) — у всех `@GetMapping("/delta")` вызывает `GenericRouteRepository.findDelta`.

`backend/route-service/src/main/kotlin/ru/asop/route/repository/GenericRouteRepository.kt`:
- `findDelta(info, versionSince: Long?, ...)` (строки ~97-119): условие `updated_at > :since` → `version > :versionSince`, bind тип `Long`, `ORDER BY version ASC`, в select-клаузе добавить `version` если используется явный `selectColumns` (строка ~106).
- Также проверить `RouteTableRegistry` / `ResourceInfo.selectColumns` — если там жёсткий список колонок, добавить `version`.
- Внутренние счётчики/флаги `REGION_ID_TABLES`/`CARRIER_ID_TABLES` не менять.

### 3.4. user-service (4 контроллера)
Файлы: `backend/user-service/src/main/kotlin/ru/asop/user/controller/{UserAdmin,UserRole,UserCarrier,UserRegion}Controller.kt`. У всех `listDelta(... updatedAtSince: Instant? ...)` с ручной сборкой SQL через `DatabaseClient`. Заменить:
- параметр `versionSince: Long?`,
- условие `updated_at > :since` → `version > :versionSince`,
- bind типа `Long`,
- `ORDER BY ... updated_at` → `version`.

### 3.5. card-service (7 контроллеров + репозитории)
Файлы: `backend/card-service/src/main/kotlin/ru/asop/card/controller/*.kt` (Card, CardMifare, CardBank, CardTariff, Blacklist, UserBenefit, TariffRate) + `config/DeltaSupport.kt` (ещё одна копия) + репозитории `repository/*Repository.kt`.

Репозитории содержат `@Query` вида (пример `CardMifareRepository.kt`):
```sql
... AND (:updatedAtSince IS NULL OR m.UPDATED_AT > :updatedAtSince)
  AND (:includeDeleted = TRUE OR m.DELETED_AT IS NULL)
ORDER BY m.UPDATED_AT ASC
LIMIT :limit
```
Заменить на `:versionSince IS NULL OR m.VERSION > :versionSince`, `ORDER BY m.VERSION ASC`, параметр-тип `Long`. **JOIN-ы и фильтры `userIdsIn` не трогать.**

### Проверка по всем мастерам
После правки все 42 контроллера должны принимать `versionSince: Long?` и отдавать записи, где в JSON присутствует ключ `version` (число) наряду с прежними полями.

---

## Этап 4: Orchestrator-service

### 4.1. Контракт Kafka — `backend/shared/asop-kafka-contracts/src/main/kotlin/ru/asop/kafka/events/delta/DeltaEvents.kt`
Заменить поле в `DeltaSyncCommand`:
```kotlin
val lastUpdatedAt: Map<String, Instant>
```
на
```kotlin
val lastVersion: Long? = null   // глобальный watermark (sequence)
```
`FullSyncCommand` (полная выгрузка) — **не менять** (не использует курсор). KDoc обновить.

### 4.2. `backend/orchestrator-service/src/main/kotlin/ru/asop/orchestrator/service/DeltaSyncService.kt`
Ключевой файл. Текущая логика (строки ~28-117):
- `process(command)`: `fetchUserIds(command)` → для каждой таблицы из `MasterRegistry.ALL` `fetchTable(...)` → `protoRowMapper.buildRowMessage` → `chunkingService.storeChunks` → `eventService.complete`.
- `fetchUserIds(command)`: первый запрос за `asop_users` (потом по нему каскадом фильтруются USER/CARD таблицы).
- `fetchTable(table, ep, command, userIds)`: выбирает `fetchDelta` или `fetchDeltaWithUserIds`.
- `fetchDelta(ep, carrierId, regionId, updatedAtSince)` и `fetchDeltaWithUserIds(ep, userIds, updatedAtSince)`: делают **один** HTTP-запрос с `updatedAtSince` и `limit=10_000`.

**Изменения:**
1. Везде `command.lastUpdatedAt[table]` → `command.lastVersion`. Тип `Instant?` → `Long?`.
2. Параметр запроса к мастеру: `updatedAtSince` → `versionSince` (значение `Long`).
3. **Добавить keyset-пагинацию** (это критично для 100-200к строк): зациклить fetch, пока мастер возвращает полную страницу (`== limit`, 10_000). Курсор для следующей страницы — `version` последней строки ответа. JSON-ключ: `node.get("version")?.asLong()`. Если у строки нет `version` — прервать цикл (защита от зацикливания).
4. Реализация — строго реактивная (`Mono`/`Flux`), без `.block()`. Идиома: материализовать страницу в `List` через `.collectList()`, проверить `size >= limit`, рекурсивно продолжить с новым курсором, вернуть `Flux.fromIterable(все страницы)`.
5. Сортировка и так `version ASC` на мастерах — не ре-сортить.
6. `onErrorResume` на каждой странице: warn + `Flux.empty()` (как сейчас), при ошибке прерывать цикл.
7. `fetchUserIds` — использовать то же новое поле `versionSince=command.lastVersion`; он тоже должен ходить через общий метод с пагинацией (для пользователей лимит скорее всего не превысится, но единообразие желательно).

### 4.3. `backend/orchestrator-service/src/main/kotlin/ru/asop/orchestrator/service/FullSyncService.kt`
Полная выгрузка — **не менять логику курсора** (её нет), но:
- проверить, что `protoRowMapper.buildRowMessage` корректно обрабатывает новое proto-поле `version` (int64) — см. этап 4.5;
- `limit=10_000` здесь тоже есть, но **это отдельная задача** (пагинация full-выгрузки) — в рамках этой задачи не трогать.

### 4.4. `backend/orchestrator-service/src/main/kotlin/ru/asop/orchestrator/kafka/DeltaCommandConsumer.kt`
Логирует `command.lastUpdatedAt.size` (строка ~21) — заменить на что-то вроде `command.lastVersion`.

### 4.5. `backend/orchestrator-service/src/main/kotlin/ru/asop/orchestrator/service/ProtoRowMapper.kt`
Использует descriptor API: для каждого поля proto-сообщения берёт JSON-ключ `snakeToCamel(field.name)` (строки ~19-31). Для `version` → JSON-ключ `"version"`, тип int — `convert()` уже поддерживает `JavaType.LONG`/`INT` (строки ~36-37). **Скорее всего менять не нужно**, но проверить, что поле int64 корректно маппится из JSON-числа.

### 4.6. `backend/orchestrator-service/src/main/kotlin/ru/asop/orchestrator/service/MasterRegistry.kt`
Не менять (классификация таблиц не зависит от курсора).

---

## Этап 5: Gateway-service

### 5.1. DTO — `backend/shared/api/gateway-api/src/main/kotlin/ru/asop/api/gateway/dto/request/DeltaSyncRequest.kt`
```kotlin
data class DeltaSyncRequest(
    val terminalId: UUID,
    val lastVersion: Long? = null
)
```
(вместо `lastUpdatedAt: Map<String, Instant>`).

### 5.2. `backend/gateway-service/src/main/kotlin/ru/asop/gateway/service/DeltaCommandService.kt`
В `publishDelta` (строки ~24-58): строит `DeltaSyncCommand(eventId, terminalId, carrierId, regionId, lastUpdatedAt = request.lastUpdatedAt)` → заменить на `lastVersion = request.lastVersion`. Логи `request.lastUpdatedAt.size` → `request.lastVersion`.

### 5.3. `backend/gateway-service/src/main/kotlin/ru/asop/gateway/controller/DeltaReferenceController.kt`
Строка ~50: `request.lastUpdatedAt.size` → `request.lastVersion`.

---

## Этап 6: Android-терминал

### 6.1. Network DTO — `frontend/android-terminal/app/src/main/java/ru/asop/terminal/network/models/DeltaModels.kt`
```kotlin
data class DeltaSyncRequest(
    @Json(name = "terminalId") val terminalId: String,
    @Json(name = "lastVersion") val lastVersion: Long? = null
)
```
`FullSyncRequest`/`DeltaMetaResponse` — не трогать.

### 6.2. Room-сущность — `.../db/entity/SyncMetaEntity.kt`
Сейчас: `lastUpdatedAt: String?` (по-табличная метка). Заменить на глобальный watermark:
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
**Внимание:** эта сущность переиспользуется в `SyncMetaDao`, `ReferenceSyncStore`, `DeltaSyncWorker`, `DeltaChunkPollWorker` — проверить все места использования и согласовать. Схема меняется — требуется **Room-миграция** (см. 6.5).

### 6.3. `.../db/entity/ReferenceRowEntity.kt`
**Добавить** новое поле `version: Long?` (не заменяя `updatedAt`):
```kotlin
@ColumnInfo(name = "version")
val version: Long? = null
```
Добавить индекс `Index("version")`. Поля `updatedAt`/`deletedAt` **оставить как есть** (updatedAt — диагностический).

### 6.4. `.../db/ReferenceSyncStore.kt`
- `toReferenceRow` (строки ~82-101): **добавить** чтение `version` (int64), не трогая `stringField(..., "updated_at")`:
```kotlin
val version = row.getField(desc.findFieldByName("version") ?: return ... )?.let { (it as Number).toLong() }
```
(через `FieldDescriptor`), тип `ReferenceRowEntity.version: Long?`.
- `applyRows` (строки ~63-80): вместо `MAX(updated_at)` по таблицам — **один** `SyncMetaEntity(id=0, lastVersion = rows.maxOf { it.version })` (глобальный максимум). `lastSyncAt = now`.
- `applyChunk`/`applyFile` — логика чтения полей не меняется (прото-поле `version`).

### 6.5. `.../db/dao/*` (SyncMetaDao, ReferenceRowDao)
- `SyncMetaDao`: изменить методы под новый `SyncMetaEntity` (одиночная строка): `get(): Flow<SyncMetaEntity?>`, `upsert(entity)`. Убрать `getAll()`-списковый вариант, если не нужен.
- `ReferenceRowDao`: **добавить** колонку `version` в SQL (upsert/applyBatch, индексы), `updated_at` оставить.

### 6.6. `.../worker/DeltaSyncWorker.kt`
Строки ~44-47:
```kotlin
val lastUpdatedAt = syncMetaDao.getAll().associate { it.tableName to (it.lastUpdatedAt ?: "") }.filterValues { it.isNotBlank() }
val response = gatewayApi.deltaSync(DeltaSyncRequest(terminalId, lastUpdatedAt))
```
→
```kotlin
val lastVersion = syncMetaDao.get().first()?.lastVersion
val response = gatewayApi.deltaSync(DeltaSyncRequest(terminalId, lastVersion))
```

### 6.7. `.../worker/DeltaChunkPollWorker.kt` и `.../worker/FullDumpDownloadWorker.kt`
Проверить, как читают/пишут `sync_meta` и `ReferenceRowEntity` — согласовать с новым полем `version` (Long) и новым `SyncMetaEntity` (одиночная строка). Поле `updatedAt` продолжает существовать, но не используется как курсор. `DeltaSyncJobDao` — не трогать (статусы заданий).

### 6.8. Room-миграция — `.../db/AppDatabase.kt`
`version = 3` → `4`. Миграция:
- `sync_meta`: пересоздать (drop + create) с новой структурой (одна строка id=0, last_version);
- `reference_rows`: **добавить** колонку `version` (BIGINT, nullable), `updated_at` оставить — через SQL `ALTER TABLE reference_rows ADD COLUMN version BIGINT` (Room-миграции по добавлению колонок поддерживаются нативно, без пересоздания таблицы; допускается `fallbackToDestructiveMigration()` **только если он уже настроен** — проверить, что в проекте используется `fallbackToDestructiveMigration()`, тогда просто бампнуть версию).
  **Уточнить по коду**: если в `AppDatabase` уже стоит `.fallbackToDestructiveMigration()` (как указано в AGENTS.md) — достаточно повысить версию; если нет — добавить миграцию.

---

## Этап 7: Seed-скрипты и документация

1. `infrastructure/docker/seed-data.sql` — проверить, что вставки не ломаются: триггер игнорирует переданное значение `version`, так что скрипты без колонки `version` валидны. **Добавить комментарий**, что `UPDATED_AT`/`CREATED_AT` теперь диагностические, дельта — по `VERSION`.
2. Будущие `seed-data-delta-1/2/3.sql` должны **вставлять строки отдельными транзакциями/пакетами** — но так как version присваивается sequence автоматически и уникален, спец-подготовка времени не нужна.
3. `AGENTS.md` — обновить раздел «Delta sync flow»: курсор `version` (sequence), единый watermark, описание триггера.
4. `doc/context.md` — если упоминает `UPDATED_AT` как дельта-курсор, поправить.

---

## Проверка

1. **Согласованность asop_schema.sql и миграции**: `diff <(grep -v '^--' infrastructure/db-migrations/asop_schema.sql) <(grep -v '^--' infrastructure/db-migrations/migrations/v001-init.sql)` — пуст (допустимы отличия только в строках-комментариях).
2. Бэкенд: `./gradlew build -x test` — должен собраться (105+ задач).
3. Android: `./gradlew -p frontend/android-terminal :app:assembleDebug` — BUILD SUCCESSFUL.
4. Proto-файлы идентичны друг другу: `diff backend/shared/asop-proto/src/main/proto/schema.proto frontend/android-terminal/app/src/main/proto/schema.proto` — пуст. В каждом из 41 Row-сообщения присутствует новое поле `int64 version = <deleted_at_номер + 1>`, а поля `created_at`/`updated_at`/`deleted_at` не изменены.
5. Интеграционная (обязательная, т.к. миграция пересоздаётся): `docker compose -f infrastructure/docker/docker-compose.yml down -v`, затем `docker compose -f infrastructure/docker/docker-compose.yml up -d --build`. Убедиться, что контейнер `liquibase` отработал успешно (в логах нет ошибок), и что в БД появились sequence `asop_delta_version_seq` и колонки `version`:
   ```sql
   SELECT column_name FROM information_schema.columns WHERE table_name='asop_regions' AND column_name='version';
   SELECT last_value FROM asop_delta_version_seq;
   ```
   При дельта-запросе в логах orchestrator-service — несколько последовательных страниц для таблицы с >10000 строк, итог без потерь.
6. Проверить SQL-уровень: вставка 200к строк в одну таблицу через `generate_series` → `SELECT count(DISTINCT version) FROM t` = 200к (все уникальны).

## Важно: НЕ трогать

- `ChunkingService`, `MasterRegistry`, `FullSyncService` (кроме проверки ProtoRowMapper-совместимости).
- Логику фильтрации `carrierId/regionId/userIdsIn` (это отдельная тема).
- `DeltaChunk`/`XxxFile`/`DeltaChunkMeta` proto-сообщения (только в `Row`-сообщениях **добавляется** новое поле `version`; `updated_at` не трогать).
- Полную выгрузку (`FullSync`, ZIP/MinIO) — не менять механизм.
- Не добавлять лишние комментарии в код (стиль проекта — краткие KDoc).

## Справка: текущие файлы, которые изменятся

| Файл | Что меняется |
|------|--------------|
| `infrastructure/db-migrations/asop_schema.sql` | **источник DDL**: sequence + колонка VERSION + триггер (этапы 1.1–1.6) |
| `infrastructure/db-migrations/migrations/` | **удаляется целиком**, пересоздаётся: `v001-init.sql` = копия `asop_schema.sql`, новый `v001-init.yaml` (этап 1.7) |
| `infrastructure/db-migrations/db.changelog-master.yaml` | не менять (уже включает `migrations/v001-init.yaml`) |
| `infrastructure/db-migrations/asop_schema.md` (+html/puml/svg) | справка — отразить sequence/VERSION/триггер |
| `backend/shared/asop-proto/src/main/proto/schema.proto` | 41× **добавить** поле `int64 version = <deleted_at+1>` (updated_at не трогать) |
| `frontend/android-terminal/app/src/main/proto/schema.proto` | то же (синхронно) |
| admin-service: 14 контроллеров + `config/DeltaSupport.kt` + `OrganizerTerritoryRepository` | `versionSince: Long?` |
| carrier-service: 4 контроллера + `config/DeltaSupport.kt` | то же |
| route-service: 13 контроллеров + `GenericRouteRepository.findDelta` | то же |
| user-service: 4 контроллера | то же |
| card-service: 7 контроллеров + `config/DeltaSupport.kt` + 7 репозиториев (@Query) | то же |
| `DeltaEvents.kt` (asop-kafka-contracts) | `lastUpdatedAt: Map<String, Instant>` → `lastVersion: Long?` |
| `DeltaSyncService.kt` (orchestrator) | versionSince + keyset-пагинация |
| `DeltaCommandConsumer.kt` (orchestrator) | лог |
| `DeltaSyncRequest.kt` (gateway-api) | `lastVersion: Long?` |
| `DeltaCommandService.kt`, `DeltaReferenceController.kt` (gateway) | передача lastVersion |
| Android: `DeltaModels.kt`, `SyncMetaEntity`, `ReferenceRowEntity`, `ReferenceSyncStore`, `SyncMetaDao`, `ReferenceRowDao`, `DeltaSyncWorker`, `DeltaChunkPollWorker`, `FullDumpDownloadWorker`, `AppDatabase` | новый курсор + Room v4 |
| `seed-data.sql`, `AGENTS.md`, `doc/context.md` | комментарии/документация |

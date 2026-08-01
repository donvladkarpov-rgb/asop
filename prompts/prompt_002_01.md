# Prompt 002.01 — Исправление недочётов Delta Sync

Перед тобой уже почти реализованная подсистема Delta Sync по `prompts/prompt_002.md` (DeepSeek сделал backend orchestrator, asop-proto module, gateway DeltaReferenceController, Android Room entities + workers, v001-init.sql миграция с soft-delete/triggers, MinIO в docker-compose). Но в ходе ревью найдены конкретные недочёты, которые мешают end-to-end запуску. Исправь их, ничего не откатывая.

---

## Контекст (читай AGENTS.md + doc/*.md)

- Backend собирается (`./gradlew build -x test` SUCCESS), Android `:app:compileDebugKotlin` SUCCESS, web-admin `tsc -b` 0 ошибок — **не сломай это**.
- В репо сейчас ~127 незакоммиченных файлов (Delta Sync WIP). Не коммитить автоматически — пользователь спросит отдельно.
- Все ID — UUIDv7 через `UuidUtils.newId()`.
- WebFlux: `Mono.block()` — ТОЛЬКО в Kafka-listener void-methods. `EventService.complete(...).subscribe()` — OK.
- Reactive R2DBC: `R2dbcEntityTemplate.insert()` для новых сущностей, НЕ `save()` (save bug).
- Не меняй архитектурные концепции сам: /api/v1/sync/** под mTLS (chain 1, authenticated), /api/v1/regions/** и /carriers/** permitAll (public справочники), остальные под JWT chain 2.

---

## ЗАДАЧА A — Backend: недостающие контроллеры для Delta Sync (КРИТИЧНО)

### A.1. Проблема

`backend/orchestrator-service/src/main/kotlin/ru/asop/orchestrator/service/MasterRegistry.kt` ссылается на ресурсы, **которых нет ни в одном сервисе**:

| MasterRegistry entry | ожидаемый ресурс | Gateway ServiceRegistry | Действующий controller |
|---|---|---|---|
| `asop_organizer_territories → admin("organizer-territories")` | `/api/v1/organizer-territories/delta` | **НЕТ mapping** | admin-service имеет только `OrganizerController` с sub-endpoints `/{id}/territories` |
| `asop_card_mifares → card("card-mifares")` | `/api/v1/card-mifares/delta` | **НЕТ mapping** | card-service имеет только `CardController.kt` |
| `asop_card_banks → card("card-banks")` | `/api/v1/card-banks/delta` | **НЕТ mapping** | нет |
| `asop_card_tariffs → card("card-tariffs")` | `/api/v1/card-tariffs/delta` | **НЕТ mapping** | нет |
| `asop_blacklists → card("blacklists")` | `/api/v1/blacklists/delta` | **НЕТ mapping** | нет |
| `asop_user_benefits → card("user-benefits")` | `/api/v1/user-benefits/delta` | **НЕТ mapping** | нет |
| `asop_tariff_rates → card("tariff-rates")` | `/api/v1/tariff-rates/delta` | **НЕТ mapping** | нет |

При запуске оркестратор получит **404** по всем 7 ресурсам → дельта неполна (терминал не получит ~7 справочников из ~40).

### A.2. Решение — создать недостающие контроллеры + Repository + Service для каждого ресурса

**В `card-service`** (package `ru.asop.card`) — создать Entity + Repository + Controller с `/delta` эндпоинтом для:

1. **`ASOP_CARD_MIFARES`** → ресурс `card-mifares` в ServiceRegistry. Controller `CardMifareController.kt` (`@RequestMapping("/api/v1/card-mifares")`). Entity `CardMifareEntity.kt` (@Table ASOP_CARD_MIFARES), Repository `CardMifareRepository` с `@Query` для /delta. См. существующий паттерн `CardController.kt` (`@GetMapping("/delta")` через `DeltaSupport`).
2. **`ASOP_CARD_BANKS`** → ресурс `card-banks`, `CardBankController/Entity/Repository`.
3. **`ASOP_CARD_TARIFFS`** → ресурс `card-tariffs`, `CardTariffController/Entity/Repository`.
4. **`ASOP_BLACKLISTS`** → ресурс `blacklists`, `BlacklistController/Entity/Repository` (внимание: у ASOP_BLACKLISTS нет `UPDATED_AT`, но миграция добавляет `CREATED_AT/UPDATED_AT/DELETED_AT` через DO-блок — entity должна включать эти поля).
5. **`ASOP_USER_BENEFITS`** → ресурс `user-benefits`, `UserBenefitController/Entity/Repository`.
6. **`ASOP_TARIFF_RATES`** → ресурс `tariff-rates`, `TariffRateController/Entity/Repository`.

**В `admin-service`** (package `ru.asop.admin`):

7. **`ASOP_ORGANIZER_TERRITORIES`** → ресурс `organizer-territories`, **новый** `OrganizerTerritoryController.kt` (НЕ расширять `OrganizerController` — separate top-level resource). Entity `OrganizerTerritoryEntity.kt` уже есть в admin-service (проверь, может DeepSeek уже создал; если есть — только controller). Composite PK `(organizer_id, territory_id)` — в /delta(query, userIdsIn for cascade) нужно фильтровать по `organizer_id IN (... SELECT organizer_id FROM ASOP_ORGANIZER_TERRITORIES WHERE territory_id IN (... territories нашего region))` — либо проще: отдавать целиком (мало строк). **Рекомендую отдавать целиком (без carrier/region filter)** — таблица маленькая, терминал сам фильтрует.

### A.3. Реализация Controller паттерна (по образцу CarrierController)

```kotlin
@RestController
class CardMifareController(
    private val r2dbcTemplate: R2dbcEntityTemplate,
    private val cardMifareRepository: CardMifareRepository
) {
    @GetMapping("/api/v1/card-mifares/delta")
    fun delta(
        @RequestParam(required = false) carrierId: UUID?,
        @RequestParam(required = false) regionId: UUID?,
        @RequestParam(required = false) userIdsIn: String?,
        @RequestParam(required = false) updatedAtSince: Instant?,
        @RequestParam(required = false, defaultValue = "false") includeDeleted: Boolean,
        @RequestParam(required = false, defaultValue = "10000") limit: Int
    ): Flux<JsonNode> = cardMifareRepository.findDelta(userIdsIn, updatedAtSince, includeDeleted, limit)
}
```

`findDelta` — через R2DBC `@Query` или `R2dbcEntityTemplate.select(Query.query(...))` с `DeltaSupport.query(...)` (admin DeltaSupport.kt уже есть — скопируй в card-service, или вынеси в `asop-common`).

### A.4. ServiceRegistry обновления

`backend/gateway-service/src/main/kotlin/ru/asop/gateway/config/ServiceRegistry.kt` — добавить 7 mappings:

```kotlin
"organizer-territories" to svc("admin-service", 8091),
"card-mifares" to svc("card-service", 8086),
"card-banks" to svc("card-service", 8086),
"card-tariffs" to svc("card-service", 8086),
"blacklists" to svc("card-service", 8086),
"user-benefits" to svc("card-service", 8086),
"tariff-rates" to svc("card-service", 8086),
```

Gateway `ProxyController` автоматически перешлёт `/api/v1/card-mifares/delta?...` в card-service. Но **внимание**: `MasterRegistry.ALL` оркестратора ссылается на `card-mifares` etc. через прямой WebClient (`masterWebClient.get().uri("/api/v1/{resource}/delta")`) — НЕ через gateway proxy. Оркестратор идёт напрямую к карточеским сервисам внутри сети,надо убедиться, что URI построение `'GET /api/v1/{resource}/delta'` с resource=card-mifares выдает правильный запрос к `card-service:8086/api/v1/card-mifares/delta`. Это работает, когда ресурс существует в MasterRegistry как valid MasterEndpoint. Действительно — MasterRegistry задаёт `(serviceHost, port, resource)`, WebClient идёт к `https://card-service:8086/api/v1/card-mifares/delta?...`, card-service должен маршрутизировать `@RequestMapping("/api/v1/card-mifares")` + `@GetMapping("/delta")`. Так что ServiceRegistry (для web-admin если понадобится) — secondary; primary — **controller внутри card-service**.

### A.5. Spring Component-scan

Проверь, что card-service `@SpringBootApplication(scanBasePackages = ["ru.asop"])` подхватит новые контроллеры в `ru.asop.card.controller.*`. Default — yes.

### A.6. Проверка после реализации

- `./gradlew :backend:card-service:compileKotlin` — сборка.
- `./gradlew :backend:admin-service:compileKotlin` — сборка.
- `./gradlew :backend:orchestrator-service:compileKotlin` — сборка.
- `./gradlew :backend:gateway-service:compileKotlin` — сборка.
- smoke: `curl -k https://localhost:8080/api/v1/card-mifares/delta?limit=10` (через gateway proxy после ServiceRegistry update) должен вернуть `[]` (если нет данных) или список.

---

## ЗАДАЧА B — PurgeJob: postgres user не SUPERUSER (КРИТИЧНО)

### B.1. Проблема

`backend/orchestrator-service/src/main/kotlin/ru/asop/orchestrator/service/PurgeJob.kt` вызывает `SET session_replication_role = 'replica'` чтобы отключить BEFORE DELETE триггеры. Но это требует привилегий **SUPERUSER** в PostgreSQL. В `infrastructure/docker/docker-compose.yml` env `POSTGRES_USER: asop` — стандартный docker-image создаёт user с CREATEROLE/CREATEDB, но **НЕ SUPERUSER**. Purge-job упадёт с `ERROR: permission denied to set parameter "session_replication_role"`.

В `infrastructure/db-migrations/migrations/v001-init.sql` строка 1594 комментарий "asop — SUPERUSER" — **лживый**, фактическиUSER не superuser.

### B.2. Решение (выбери ОДНО по простоте)

**Вариант 1 (проще, рекомендую)** — добавить init-script в postgres контейнер docker-compose, который исполнится после создания user'а, но до миграций:

`infrastructure/docker/postgres-superuser.sql`:
```sql
ALTER USER asop WITH SUPERUSER;
```

В `infrastructure/docker/docker-compose.yml` секция `postgres` монтировать `/docker-entrypoint-initdb.d/`:
```yaml
  postgres:
    mem_limit: 1024m
    image: postgis/postgis:14-3.4
    environment:
      POSTGRES_DB: asop
      POSTGRES_USER: asop
      POSTGRES_PASSWORD: asop
    ports:
      - "5432:5432"
    volumes:
      - postgres_data:/var/lib/postgresql/data
      - ./postgres-superuser.sql:/docker-entrypoint-initdb.d/01-superuser.sql:ro     # ← НОВОЕ
    healthcheck: ...
```

Docker postgres-image автоматически выполняет `.sql` scripts из `/docker-entrypoint-initdb.d/` при первом запуске (когда volume ещё пустой). `01-superuser.sql` выполнится после дефолтной database/user creation, поднимет `asop` до SUPERUSER. Пересоздание volume (`docker compose down -v`) даст свежий init.

**Вариант 2** — в `v001-init.sql` добавить `ALTER USER asop WITH SUPERUSER;` — но это исполнится от user'а asop (Liquibase подключается как asop) и потребует права ALTER USER, который обычный CREATE USER НЕ имеет. НЕЛЬЗЯ.

**Вариант 3** — Purge_job переписать на per-table `ALTER TABLE ... DISABLE TRIGGER trg_soft_delete_<table>` / `ENABLE TRIGGER` — это НЕ требует SUPERUSER (но требует owner'а таблиц, который `asop` является). Но этого громоздко (40+ таблиц в цикле). Использовать ТОЛЬКО если Вариант 1 невозможен.

**Рекомендую Вариант 1**: `infrastructure/docker/postgres-superuser.sql` + volume mount.

### B.3. Проверка

После `docker compose down -v && up -d --build`:
```bash
docker compose exec -T postgres psql -U asop -c "SELECT rolname, rolsuper FROM pg_roles WHERE rolname='asop';"
# должно показать rolsuper = true
```

---

## ЗАДАЧА C — Каскадный user-filter (перепроверка)

### C.1. Проблема

`backend/user-service/.../UserAdminController.kt` должен реализовать `/api/v1/admin-users/delta` так, чтобы:
- Принимать `carrierId`, `regionId` query-params.
- Возвращать только user'ов, у которых `user_id IN (SELECT user_id FROM ASOP_USER_CARRIERS WHERE carrier_id = :carrierId UNION SELECT user_id FROM ASOP_USER_REGIONS WHERE region_id = :regionId)`.
- + дельта-фильтр `UPDATED_AT > updatedAtSince` + soft-delete respect.

Проверь реализацию `UserAdminController.kt`ÿ `UserAdminRepository.findDelta(...)`. Если фильтр не реализован (отдаёт ALL users) — терминал получит **чужих user'ов** (безопасность).

### C.2. Что должно быть в card-service для CARD_TABLES

Оркестратор вызывает `fetchUserIds(command)` (carrierId+regionId каскад уже!) → получает `Set<UUID>` user-ов. Дальше `fetchDeltaWithUserIds(ep, userIds, updatedAtSince)` шлёт `?userIdsIn=<csv>`. card-service контроллеры (`CardMifareController` и т.д.) **должны реализовать userIdsIn парсинг** + filter `WHERE user_id IN (:userIdsIn)`.

### C.3. Задача

1. Прочитай `backend/user-service/src/main/kotlin/ru/asop/user/controller/UserAdminController.kt` и `UserAdminRepository.kt`. Проверь, что /delta эндпоинт:
   - Принимает `carrierId`, `regionId` параметры.
   - Реализует UNION-подзапрос: `user_id IN (SELECT user_id FROM asop_user_carriers WHERE carrier_id = :carrierId UNION SELECT user_id FROM asop_user_regions WHERE region_id = :regionId)`.
   - Если carrierId/regionId null — отдаёт ВСЕ users (для суперпользователей? в данной схеме терминального контекста всегда carrier+region — значит должны быть не null).

2. Прочитай card-service контроллеры (новые из задачи A) — проверь, что `userIdsIn` query-parameter парсится и подставляется в WHERE clause. Пример для CardMifareRepository:
   ```kotlin
   @Query("""
       SELECT * FROM ASOP_CARD_MIFARES
       WHERE (:updatedAtSince IS NULL OR UPDATED_AT > :updatedAtSince)
         AND (:includeDeleted = TRUE OR DELETED_AT IS NULL)
         AND (:userIdsInStr IS NULL OR user_id = ANY(string_to_array(:userIdsInStr, ',')::uuid[]))
       ORDER BY UPDATED_AT ASC
       LIMIT :limit
   """)
   fun findDelta(userIdsInStr: String?, updatedAtSince: Instant?, includeDeleted: Boolean, limit: Int): Flux<CardMifareEntity>
   ```
   (Внимание: ASOP_CARD_MIFARES владеет card_id, не user_id напрямую; м. нужно JOIN через ASOP_CARDS на user_id. В `ASOP_CARDS` — `user_id` колонка есть. Если в card_mifares нет напрямую user_id — implement через JOIN.)

3. Для `ASOP_BLACKLISTS` (карта block-статусов — PK `card_id`, no `user_id` колонка) — filter через JOIN ASOP_CARDS ON user_id. Аналогично для всех card_* таблиц.

### C.4. Если DeepSeek уже сделал правильно — не трогай

Проверь только, чтобыcascade работал. Энд-ту-энд: при sync запросе терминал carrierId=X, regionId=Y → orchestrator fetches users (только user_id из user_carriers WHERE carrier_id=X UNION user_regions WHERE region_id=Y) → fetches cards (WHERE user_id IN ...) → fetches card_mifares (JOIN card ON user_id) etc.

---

## ЗАДАЧА D — Android: protobuf-java → protobuf-javalite (minor)

### D.1. Проблема

`frontend/android-terminal/app/build.gradle.kts` сейчас:
```kotlin
implementation("com.google.protobuf:protobuf-java:3.25.5")
implementation("com.google.protobuf:protobuf-java-util:3.25.5")
```

Это **full protobuf-java** (~2 МБ в APK, использует reflection). Для mobile рекомендуется **`protobuf-javalite`** (~500 КБ, no reflection, designed for Android).

### D.2. Решение

В `frontend/android-terminal/app/build.gradle.kts`:
- Убрать `protobuf-java` и `protobuf-java-util`.
- Добавить `implementation("com.google.protobuf:protobuf-javalite:3.25.5")`.
- В protobuf block: `generateProtoTasks.all().forEach { it.builtins { create("java") { option("lite") } } }` (уже может быть, — проверь).

### D.3. Проверка

`cd frontend/android-terminal && ./gradlew :app:assembleDebug` — должно собраться. Сравнить размер APK до/после (не критично, но подтверждение экономии).

---

## ЗАДАЧА E — Android: ReferenceSyncStore применяет чанки корректно

### E.1. Проверка

Прочитай `frontend/android-terminal/app/src/main/java/ru/asop/terminal/db/ReferenceSyncStore.kt` (115 строк). Убедись:

1. `applyChunk(bytes: ByteArray)` парсит `DeltaChunk.parseFrom(bytes)` (protobuf).
2. Итерируется по всем подполям `DeltaChunk` (regions, territories, ...) — каждое поле = repeated `<Table>Row`.
3. Для каждой row — строит `ReferenceRowEntity(tableName, rowId, payloadJson, updatedAt, deletedAt)` и upserts через `referenceRowDao.upsertAll(...)`.
4. `payloadJson` — JSON-сериализация полей row (через Moshi).
5. Atomic транзакция через `RoomDatabase.runInTransaction { ... }` — apply ВСЕХ чанков в одну транзакцию (если их несколько в одном poll cycle).
6. После apply — обновить `sync_meta.lastUpdatedAt` = MAX(updated_at) по tableName во всех применённых row.
7. `deletedAt` ≠ null → row остаётся в `reference_rows` с `deleted_at` заполненным (soft-delete). UI/бизнес-логика терминала фильтрует `WHERE deleted_at IS NULL`.

### E.2. Если что-то из этого пропущено — почини

Доп. проверка — `DeltaChunk` proto message должен содержать `repeated <Table>Row` для ВСЕХ ~40 таблиц. Прочитай `backend/shared/asop-proto/src/main/proto/schema.proto` — должно быть ~40 `message <Table>Row` + итоговый `DeltaChunk` с 40 repeated-полями. Sync между backend и android proto-схемой обязателен (один и тот же `.proto` — Android копирует в `frontend/android-terminal/app/src/main/proto/schema.proto`).

### E.3. Android chunk polling worker

`frontend/android-terminal/app/src/main/java/ru/asop/terminal/worker/` — должен быть `DeltaChunkPollWorker.kt` (или подобное). Проверь:
1. Читает `delta_sync_jobs` WHERE status=PENDING.
2. For each eventId: poll `gatewayApi.getEventStatus(eventId)` → COMPLETED → read `resultData.totalChunks`.
3. Цикл 1..N: `gatewayApi.getDeltaChunk(eventId, n)` → bytes → store in list.
4. После получения всех чанков — `referenceSyncStore.applyAll(chunks)` атомарно.
5. При ошибкеудalить `delta_sync_jobs` row (return success — часовой DeltaSyncWorker перезапросит).
6. Timeout logic — если N не получено через 6 polling cycles × 5 минут = 30 мин — mark FAILED + delete row.

---

## ЗАДАЧА F — Build verification

После всех правок:
1. `./gradlew build -x test` — backend (всех модулей, + card/controller нов. + orchestrator compile) — SUCCESS.
2. `cd frontend/android-terminal && ./gradlew :app:assembleDebug` — SUCCESS.
3. `cd frontend/web-admin && npx tsc -b` — 0 ошибок (calc-ranges не нарушать).
4. `docker compose -f infrastructure/docker/docker-compose.yml down -v`
5. `docker compose -f infrastructure/docker/docker-compose.yml up -d --build`
6. Подождать 60 сек.
7. `docker compose -f infrastructure/docker/docker-compose.yml exec -T postgres psql -U asop -c "SELECT rolname, rolsuper FROM pg_roles WHERE rolname='asop';"` — должно вернуть `rolsuper = true` (postgres-superuser.sql сработал).
8. `docker compose logs --tail=30 orchestrator-service` — без StackTrace, "Purge complete" лог может появляться (но не каждые 60 секунд).
9. `curl -k https://localhost:8080/api/v1/card-mifares/delta?limit=10` → 200 + `[]` (через gateway proxy, mTLS auth-все /delta роуты НЕ permitAll都需要 auth — curl без auth получит 401, НО это правильно поведение). Чтобы проверить — нужно с mTLS-сертификатом (можно через Android logcat или skip — bash curl не умеет mTLS просто).
10. Создать тестовую запись в БД: `INSERT INTO ASOP_ROLES (ROLE_ID, ROLE_NAME) VALUES ('00000000-0000-0000-0000-000000000501', 'TEST_ROLE');` (через psql), потом `DELETE FROM ASOP_ROLES WHERE ROLE_ID = '...';` → row Должна остаться, `DELETED_AT` заполнен. Проверить: `SELECT role_id, deleted_at FROM ASOP_ROLES WHERE role_id = '...';`.
11. UI тест: после `adb install -r app-debug.apk` на эмуляторе, drawer → "Загрузить справочники" → должен initiate delta request, получить 202 + eventId, polling → apply chunks в Room. Проверить: `adb shell run_as ru.asop.terminal sqlite3 /data/data/ru.asop.terminal/databases/asop_terminal.db "SELECT count(*) FROM reference_rows;"` → >0 после sync.

---

## ПорядокРабот

1. **Задача A** — создать 6 недостающих контроллеров в card-service + 1 в admin-service (organizer-territories), ServiceRegistry обновить. Этоallow orchestrator не падать 404. Самая большая работа.
2. **Задача B** — postgres-superuser.sql + volume mount. Самая критичная (без неё PurgeJobpermission-denied).
3. **Задача C** — проверка cascade-фильтра users. Мелкая правка, если пропущено.
4. **Задача D** — protobuf-javalite replace. Minor.
5. **Задача E** — проверка Android apply логики. Если всё есть — ничего не делать.
6. Build verification (Задача F) — после всех правок.

## Финальные требования

- **Не коммитить** автоматически.
- Сохранить рабочие состояния (`.gradlew build` + `:app:compileDebugKotlin` + `tsc -b` — все SUCCESS).
- Не убирать уже сделанное (DeepSeek хорошую работу) — только add/fix.
- All new Kafka producer configs (audit/card/carrier/fiscal/session services new `KafkaProducerConfig.kt`) — НЕ сносить, они нужны для service→Kafka events публикации (CommandResult pattern — мастер-сервисы публикуют ack в `{domain}.events` для EventService → Android polling). Проверь только, что `KafkaProducerConfig` соответствует паттерну `HeaderKeys` и SSL settings как у terminal-service (truststore/keystore).
- Любые Вопросы — задавай вFinal PR description; можно делать разумные реализации-choice если промпт неоднозначен.

## НАЙДЕННЫЕ НЕДОСТАТКИ — SUMMARY

| # | Задача | Severity | Где | Что делать |
|---|---|---|---|---|
| A | Недостающие card-контроллеры + organizer-territories | критично | MasterRegistry ALL → card-service / admin-service | Создать 7 controller`ов (+ entity/repository) + ServiceRegistry обновить |
| B | Postgresuser не SUPERUSER для `SET session_replication_role` | критично | docker-compose / PurgeJob | postgres-superuser.sql + volume mount |
| C | Каскадный user-filter (UNION user_carriers + user_regions) для USER_TABLES + CARD_TABLES | потенциально | user-service `UserAdminController.findDelta` + card controllers /delta | Проверить и доправить если не реализовано |
| D | `protobuf-java` вместо `protobuf-javalite` на Android | minor | android build.gradle.kts | Заменить на javalite |
| E | ReferenceSyncStore apply логика — атомарность, sync_meta обновление | потенциально | android `ReferenceSyncStore.kt` | Проверить и доправить |
| F | Build verification после правок | итоговая | backend + android + docker | Прогнать и убедиться |

--- 

## ОПЦИОНАЛЬНО — если остаётся время

- Создать smoke-test скрипт `infrastructure/docker/test-delta-smoke.sh`: вставляет запись → триггер слёлся → DELETE → запись осталасьsoft-deleted → purge (через 6 месяцев mock date inserted) → запись удалена физически.
- Обновить `doc/smoke-tests.md` с.delta сценариями.
- Обновить `AGENTS.md` + `doc/context.md` с Orchestrator service + Delta flow diagram.
- `MinioWebClientConfig` — создать TLS-версию (если MinIO выставляется наружу по HTTPS без gateway проксирования). Сейчас gateway проксирует → minioWebClient использует plain HTTP к `http://minio:9000`. ОК, не трогать.
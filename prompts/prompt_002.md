# Prompt 002 — Incremental Delta Sync (Kafka + Redis + Protobuf + MinIO + Soft-Delete)

**Цель:** реализовать инкрементальную дельта-синхронизацию 40+ справочников между сервером и Android-терминалами по сценарию: Android раз в час → POST delta-request на gateway (mTLS) → 202 + eventId → Kafka → орkестратор опрашивает мастер-сервисы через REST, чанкует по 50 КБ (Protobuf `serializedSize`), кладёт чанки в Redis, пишет `asop:event:{eventId}` COMPLETED → Android поллит events, качает чанки из Redis, атомарно накатывает в Room. Полную выгрузку — через MinIO (S3 pre-signed URL, ZIP с `.pb` файлами). Плюс soft-delete по DELETED_AT + purge-job в оркестраторе (раз в час, удаление старше 6 месяцев, обход триггера через `session_replication_role='replica'`).

---

## Контекст проекта (читай AGENTS.md и doc/*.md перед стартом)

- Kotlin 2.0.21, Spring Boot 3.3.5 (WebFlux, R2DBC), PostgreSQL 14/PostGIS, Kafka 3.7.1 SSL, Keycloak 25.0.4, Redis 7 (ReactiveStringRedisTemplate), Bouncy Castle 1.78.1, Gradle 8.10.2.
- Docker: `infrastructure/docker/docker-compose.yml` — все сервисы on `asop-net`, `provision.sh` entrypoint запрашивает сертификат у crypto-service, truststore в `/tmp/certs/truststore.p12`.
- Liquibase: `infrastructure/db-migrations/migrations/v001-init.sql` (+ `.yaml` wrapper) — единый SQL. Для обновления схемы — `docker compose down -v` (volume wipe) → полностью пересоздаётся. **В этой задаче — пересоздать v001 полностью** (см. ниже раздел Schema).
- Gateway dual-auth: chain Order(1) mTLS (`/api/v1/terminals/**`, `/api/v1/sync/**`), chain Order(2) JWT. `POST /api/v1/terminals/cert-sign` permitAll (chicken-and-egg). `GET /api/v1/regions/**` и `GET /api/v1/carriers/**` — permitAll.
- EventService (gateway) — Redis-backed, key `asop:event:{eventId}`, TTL 24 ч, `ReactiveStringRedisTemplate` + Jackson. `createPending`/`complete`/`fail` — `Mono<Void>`. Поллинг `GET /api/v1/events/{eventId}` → 202 PENDING / 200 COMPLETED (body `EventStatus`, в `resultData` — JSON-строка с результатом) / 422 FAILED / 404.
- `UuidUtils.newId()` — UUIDv7 (RFC 9562), `ru.asop.common.util.UuidUtils` (asop-common). Все новые ID — через `UuidUtils.newId()`.
- `R2dbcEntityTemplate.insert()` для новых сущностей (не `save()` — save bug по AGENTS.md).
- `TransactionalOperator.transactional(mono)` для atomic R2DBC-операций (Spring `@Transactional` НЕ работает в WebFlux).
- Android: Kotlin + Jetpack Compose + Hilt + Room 2.6.1 + WorkManager 2.9.1 + Retrofit/OkHttp 4.12 + Moshi 1.15. Terminal serial = `Settings.Secure.ANDROID_ID`. `SyncPreferences` (DataStore `sync_preferences`) хранит `terminal_id`. `WorkScheduler` — `SyncWorker` 15 мин, `EventPollWorker` 5 мин,+mínimum `PeriodicWorkRequest` интервал — 15 минут.
- Kafka-продюсер gateway: `ReactiveKafkaProducerTemplate<String, Any>`, JSON-сериализация, header `X-Event-Id`, pattern `UuidUtils.newId() → ProducerRecord → eventService.createPending.then(kafkaTemplate.send).thenReturn(eventId)`.
- Промпт `prompts/prompt_1.md` содержит обоснование НЕ использовать Kafka/Redis для GET-справочников на прямых read-страницах web-admin — **но в этой задаче Delta Sync — это асинхронная выборка, Kafka/Redis нужны по дизайну**. Прошлый промпт не противоречит, _read-sync для справочников вDelta flow — новая подсистема_.

---

## Найденные неточности оригинального промпта (ИСПРАВЛЕНО)

1. **Маппинг таблиц к сервисам** — Vehicle-класс и contract-routes реально живут в **route-service** (а не carrier-service), согласно `backend/gateway-service/.../config/ServiceRegistry.kt`. В финальном маппинге ниже — фактический.
2. **`DELETED_AT` отсутствует во всех ~40 таблицах** на сегодня. Миграция добавляет колонку + триггер + индекс + создает функцию `set_deleted_at_and_updated_at()`. Общий BEFORE DELETE trigger: UPDATE SET DELETED_AT=NOW(), UPDATED_AT=NOW() WHERE PK = OLD.id; RETURN NULL.
3. **`UPDATED_AT` отсутствует в ~30 таблицах**. Миграция добавляет `UPDATED_AT TIMESTAMPTZ NOT NULL DEFAULT NOW()` + B-tree индекс во ВСЕ 40 таблиц (кроме `ASOP_ORGANIZER_TERRITORIES`, `ASOP_USER_ROLES/UserCarriers/UserRegions`, `ASOP_CONTRACT_ROUTES` — у них composite PK, но `UPDATED_AT` добавляется всё равно — нужно для delta-versioning).
4. **`ASOP_USERS` не имеет CREATED_AT/UPDATED_AT** — добавляем оба.
5. **`ASOP_BLACKLISTS` имеет только `BLOCKED_AT`** — добавляем `CREATED_AT NOT NULL DEFAULT NOW()`, `UPDATED_AT NOT NULL DEFAULT NOW()`, `DELETED_AT`.
6. **`ASOP_TIDS.TERMINAL_ID` — БЕЗ FK** — оставляем как есть (deferred в SQL), фильтр через carrierId (который FK есть).
7. **MinIO отсутствует в docker-compose** — добавляем.
8. **Protobuf отсутствует** в backend и Android — добавляем `com.google.protobuf:protobuf-java` (backend) и `com.google.protobuf:protobuf-javalite` (Android) + protoc-gradle-plugin.
9. **WorkManager minimum interval — 15 минут**; часовая периодичность — как `setPeriod(Duration.ofMinutes(60))` (с пометкой что Android Doze может растягивать).
10. **Purge обходит BEFORE DELETE триггер через `SET LOCAL session_replication_role = 'replica'`** в одной транзакции — официально поддержанный PG способ bypass всех триггеров.

---

## Финальный МАППИНГ таблиц к сервисам (фактический)

### Оркестратор → мастер-сервис (через WebClient REST, sync)

| Группа | Таблица | Сервис-владелец | API path в ServiceRegistry | Фильтр по carrier_id | Фильтр по region_id | Фильтр косвенный через user |
|---|---|---|---|---|---|---|
| 0 | ASOP_REGIONS | admin-service | `regions` | нет | нет (PK) | нет |
| 0 | ASOP_TERRITORIES | admin-service | `territories` | нет | **есть** (NOT NULL) | нет |
| 0 | ASOP_ORGANIZERS | admin-service | `organizers` | нет | нет** | нет |
| 0 | ASOP_ORGANIZER_TERRITORIES | admin-service | `organizers` ассоциации | нет | через JOIN territory | нет |
| 1 | ASOP_ROLES | admin-service | `roles` | нет | нет | нет |
| 1 | ASOP_CARD_TYPES | admin-service | `card-types` | нет | нет | нет |
| 1 | ASOP_TARIFF_TYPES | admin-service | `tariff-types` | нет | нет | нет |
| 1 | ASOP_SESSION_TYPES | admin-service | `session-types` | нет | нет | нет |
| 1 | ASOP_EVENT_TYPES | admin-service | `event-types` | нет | нет | нет |
| 1 | ASOP_TRANSACTION_TYPES | admin-service | `transaction-types` | нет | нет | нет |
| 1 | ASOP_TRANSACTION_RESULTS | admin-service | `transaction-results` | нет | нет | нет |
| 1 | ASOP_SERVICES | admin-service | `services` | нет | **есть** (NOT NULL) | нет |
| 1 | ASOP_BENEFITS | admin-service | `benefits` | нет | **есть** (NOT NULL) | нет |
| 1 | ASOP_BENEFIT_STEPS | admin-service | `benefit-steps` | нет | через JOIN benefit | нет |
| 2 | ASOP_CARRIERS | carrier-service | `carriers` | PK | **есть** (NOT NULL) | нет |
| 2 | ASOP_CONTRACTS | carrier-service | `contracts` | **есть** (nullable FK) | нет | нет |
| 2 | ASOP_CONTRACT_ROUTES | route-service | `contract-routes` | через JOIN contract | через JOIN route | нет |
| 2 | ASOP_VEHICLE_TYPES | route-service | `vehicle-types` | нет | нет | нет |
| 2 | ASOP_VEHICLE_MODELS | route-service | `vehicle-models` | нет | нет | нет |
| 2 | ASOP_VEHICLES | route-service | `vehicles` | **есть** (nullable FK) | нет | нет |
| 3 | ASOP_USERS | user-service | `admin-users` | через `userId IN (SELECT user_id FROM asop_user_carriers WHERE carrier_id = ?) UNION userId IN (SELECT user_id FROM asop_user_regions WHERE region_id = ?)` | — | **есть косвенно** |
| 3 | ASOP_USER_ROLES | user-service | `user-roles` | через JOIN user_carriers/user_regions → user_id | — | **есть косвенно** |
| 3 | ASOP_USER_CARRIERS | user-service | `user-carriers` | **есть** (NOT NULL FK) | — | нет |
| 3 | ASOP_USER_REGIONS | user-service | `user-regions` | нет | **есть** (NOT NULL FK) | нет |
| 4 | ASOP_FARE_ZONES | route-service | `fare-zones` | нет | **есть** (NOT NULL) | нет |
| 4 | ASOP_TRANSPORT_STOPS | route-service | `transport-stops` | нет | **есть** (NOT NULL) | нет |
| 4 | ASOP_ROUTES | route-service | `routes` | нет | **есть** (NOT NULL) | нет |
| 4 | ASOP_PATHS | route-service | `paths` | нет | **есть** (NOT NULL) | нет |
| 4 | ASOP_PATH_TRANSPORT_STOPS | route-service | `path-transport-stops` | нет | **есть** (NOT NULL) | нет |
| 4 | ASOP_SCHEDULE | route-service | `schedule` | нет | **есть** (NOT NULL) | нет |
| 4 | ASOP_PATH_SERVICES | route-service | `path-services` | **есть** (nullable FK) | нет | нет |
| 4 | ASOP_PATH_DISCOUNTS | route-service | `path-discounts` | **есть** (nullable FK) | нет | нет |
| 4 | ASOP_PATH_BENEFITS | route-service | `path-benefits` | через JOIN path+benefit (path → region) | через JOIN path | нет |
| 5 | ASOP_CARDS | card-service | `cards` | через `userId IN (users нашего carrier+region)` | через userId | **есть косвенно** |
| 5 | ASOP_CARD_MIFARES | card-service | через JOIN card | через card → user | **есть косвенно** | **есть косвенно** |
| 5 | ASOP_CARD_BANKS | card-service | через JOIN card | — | **есть косвенно** | **есть косвенно** |
| 5 | ASOP_CARD_TARIFFS | card-service | через JOIN card | — | **есть косвенно** | **есть косвенно** |
| 5 | ASOP_BLACKLISTS | card-service | через JOIN card | — | **есть косвенно** | **есть косвенно** |
| 5 | ASOP_USER_BENEFITS | card-service | `user-benefits` | через JOIN benefit (benefit → region) + через userId | через benefit.region_id | **есть косвенно** |
| 5 | ASOP_TARIFF_RATES | card-service | `tariff-rates` | **есть** (nullable FK) | нет | нет |
| 5 | ASOP_TIDS | carrier-service | `tids` | **есть** (NOT NULL FK) | через JOIN carrier | нет |

**Таблицы "без фильтра"** (передаются целиком, но с учётом `DELETED_AT` и `updatedAt > $lastUpdatedAt`): ASOP_ROLES, ASOP_CARD_TYPES, ASOP_TARIFF_TYPES, ASOP_SESSION_TYPES, ASOP_EVENT_TYPES, ASOP_TRANSACTION_TYPES, ASOP_TRANSACTION_RESULTS, ASOP_VEHICLE_TYPES, ASOP_VEHICLE_MODELS, ASOP_ORGANIZERS, ASOP_ORGANIZER_TERRITORIES.

**Таблицы с косвенным user-filter** (через `userId IN (SELECT user_id FROM asop_user_carriers WHERE carrier_id = $) UNION (SELECT user_id FROM asop_user_regions WHERE region_id = $)`): ASOP_USERS, ASOP_USER_ROLES, ASOP_CARDS, ASOP_CARD_MIFARES, ASOP_CARD_BANKS, ASOP_CARD_TARIFFS, ASOP_BLACKLISTS, ASOP_USER_BENEFITS.

---

## ЗАДАЧА 1 — Schema migration (Liquibase with-fresh v001)

### 1.1. Удалить старую миграцию, создать новую

- Удалить `infrastructure/db-migrations/migrations/v001-init.yaml` и `v001-init.sql` (старые).
- Создать **новый** `infrastructure/db-migrations/migrations/v001-init.sql` с обновлённой схемой (Полный DDL всех ~67 таблиц + soft-delete + триггеры + индексы).
- Создать `infrastructure/db-migrations/migrations/v001-init.yaml` (один changeset с `sqlFile`, `splitStatements: false`, `stripComments: true`, `relativeToChangelogFile: true` — как было раньше).
- При `docker compose down -v` БД полностью пересоздаётся — таблица `databasechangelog` создаётся Liquibase сама. ДОПОЛНИТЕЛЬНО чистить её не нужно.
- Синхронизировать справочную копию `infrastructure/db-migrations/asop_schema.sql` (новый DDL) и обновить `infrastructure/db-migrations/asop_schema.md` (описание таблиц + CRC поля).

### 1.2. НОВЫЕ обязательные поля для каждой из ~40 справочников

Для КАЖДОЙ таблицы из маппинга выше (полный список в промпте):

```sql
-- если CREATED_AT нет или nullable → ALTER
ALTER TABLE <table>
    ADD COLUMN IF NOT EXISTS CREATED_AT TIMESTAMPTZ NOT NULL DEFAULT NOW();
ALTER TABLE <table>
    ADD COLUMN IF NOT EXISTS UPDATED_AT TIMESTAMPTZ NOT NULL DEFAULT NOW();
ALTER TABLE <table>
    ADD COLUMN IF NOT EXISTS DELETED_AT TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_<table>_updated_at ON <table> (UPDATED_AT);
CREATE INDEX IF NOT EXISTS idx_<table>_deleted_at ON <table> (DELETED_AT) WHERE DELETED_AT IS NOT NULL;
```

Если `UPDATED_AT` в схеме уже есть (11 таблиц на сегодня) — не добавлять повторно, но индекс добавить. Если `UPDATED_AT` есть, но nullable → `ALTER COLUMN ... SET NOT NULL` (если данные позволяют) или оставить nullable. Для новых таблиц — объявить `UPDATED_AT NOT NULL DEFAULT NOW()` прямо в CREATE TABLE.

`ASOP_USERS` — добавить оба `CREATED_AT` и `UPDATED_AT` (NOT NULL DEFAULT NOW()).

`ASOP_BLACKLISTS` — добавить `CREATED_AT NOT NULL DEFAULT NOW()` + `UPDATED_AT NOT NULL DEFAULT NOW()` + `DELETED_AT`. Существующий `BLOCKED_AT` — оставить как есть (он хранит дату блокировки, не deletion).

`ASOP_ORGANIZER_TERRITORIES` (junction), `ASOP_USER_ROLES/UserCarriers/UserRegions`, `ASOP_CONTRACT_ROUTES` — тоже получить `CREATED_AT/UPDATED_AT/DELETED_AT` + индексы (для delta-versioning).

### 1.3. BEFORE DELETE триггер (soft-delete)

Общий подход — создать одну функцию и привязать к каждой таблице:

```sql
CREATE OR REPLACE FUNCTION asop_set_deleted_at() RETURNS TRIGGER AS $body$
BEGIN
    UPDATE <table>
    SET DELETED_AT = NOW(), UPDATED_AT = NOW()
    WHERE <pk_col> = OLD.<pk_col>;
    RETURN NULL;  -- отменяет физический DELETE
END;
$body$ LANGUAGE plpgsql;

CREATE TRIGGER trg_<table>_no_delete
    BEFORE DELETE ON <table>
    FOR EACH ROW EXECUTE FUNCTION asop_set_deleted_at();
```

⚠️ ПРОБЛЕМА: PG не поддерживает generic function для нескольких таблиц — функция должна знать имя PK-колонки. Решение: либо **генерировать по одной функции на таблицу** (проще, DRY через bash/python-генератор в самом SQL через DO-блок), либо **использовать `EXECUTE` c динамическим SQL** через `TG_TABLE_NAME` и определение PK через `pg_constraint`. Рекомендация — **для каждой таблицы создать свою функцию** (ровно как было в v001 для `set_timestamps`), но все они идентичны, differing only by UPDATE target. Один DO-блок может сгенерировать и FUNCTION, и TRIGGER для всех таблиц.

Пример DO-блока (генерирует триггеры для всех таблиц из whitelist):

```sql
DO $body$ DECLARE
    t TEXT;
    pk TEXT;
    tables TEXT[] := ARRAY[
        'ASOP_REGIONS','ASOP_TERRITORIES','ASOP_ORGANIZERS','ASOP_ORGANIZER_TERRITORIES',
        'ASOP_ROLES','ASOP_CARD_TYPES','ASOP_TARIFF_TYPES','ASOP_SESSION_TYPES','ASOP_EVENT_TYPES',
        'ASOP_TRANSACTION_TYPES','ASOP_TRANSACTION_RESULTS','ASOP_SERVICES','ASOP_BENEFITS','ASOP_BENEFIT_STEPS',
        'ASOP_CARRIERS','ASOP_CONTRACTS','ASOP_CONTRACT_ROUTES','ASOP_VEHICLE_TYPES','ASOP_VEHICLE_MODELS','ASOP_VEHICLES',
        'ASOP_USERS','ASOP_USER_ROLES','ASOP_USER_CARRIERS','ASOP_USER_REGIONS',
        'ASOP_FARE_ZONES','ASOP_TRANSPORT_STOPS','ASOP_ROUTES','ASOP_PATHS','ASOP_PATH_TRANSPORT_STOPS','ASOP_SCHEDULE',
        'ASOP_PATH_SERVICES','ASOP_PATH_DISCOUNTS','ASOP_PATH_BENEFITS',
        'ASOP_CARDS','ASOP_CARD_MIFARES','ASOP_CARD_BANKS','ASOP_CARD_TARIFFS','ASOP_BLACKLISTS','ASOP_USER_BENEFITS','ASOP_TARIFF_RATES',
        'ASOP_TIDS'
    ];
BEGIN
    FOREACH t IN ARRAY tables LOOP
        -- получить PK колонку
        SELECT k.column_name INTO pk
        FROM information_schema.key_column_usage k
        JOIN information_schema.table_constraints c USING (constraint_name, table_schema)
        WHERE c.constraint_type = 'PRIMARY KEY' AND c.table_name = t;

        EXECUTE format($f$
            CREATE OR REPLACE FUNCTION %I.asop_soft_delete_%I() RETURNS TRIGGER AS $fn$
            BEGIN
                UPDATE %I SET DELETED_AT = NOW(), UPDATED_AT = NOW() WHERE %I = OLD.%I;
                RETURN NULL;
            END;
            $fn$ LANGUAGE plpgsql;
            CREATE TRIGGER trg_%I_no_delete BEFORE DELETE ON %I
                FOR EACH ROW EXECUTE FUNCTION %I.asop_soft_delete_%I();
        $f$, 'public', lower(t), t, t, pk, pk, lower(t), t, 'public', lower(t));
    END LOOP;
END $body$;
```

Это создаёт ~40 функций + 40 триггеров. Проверь, что composite-PK таблицы (`ASOP_CONTRACT_ROUTES` = `(CONTRACT_ID, ROUTE_ID)`, `ASOP_ORGANIZER_TERRITORIES = (ORGANIZER_ID, TERRITORY_ID)`) тоже корректно обрабатываются — для них нельзя `WHERE pk = OLD.pk`, нужно `WHERE col1 = OLD.col1 AND col2 = OLD.col2`. Для них либо прописать руками (в DO-блоке IF/THEN), либо exclude из generic-DO и реализовать отдельными CREATE FUNCTION.

### 1.4. Существующие данные — обратная совместимость

В v001 — все таблицы создаются с полями сразу. Старый `v001-init.sql` удаляется, новая миграция — "fresh install". Если сохранять seed roles/function — перенести из старого `v001-init.sql` (функция `gen_uuid_v7()`, `set_timestamps()`, `update_timestamps()`, роли).

### 1.5. Актуализировать справочные файлы

- `infrastructure/db-migrations/asop_schema.sql` — справочная копия нового SQL.
- `infrastructure/db-migrations/asop_schema.md` — обновить описание: для каждой таблицы указать новые поля `CREATED_AT/UPDATED_AT/DELETED_AT` + наличие `BEFORE DELETE` триггера + назначение soft-delete.
- (опционально) `asop_schema.puml` + `.svg` — если понесёт, но хотябы .md.

---

## ЗАДАЧА 2 — Оркестратор (новый микросервис `:backend:orchestrator-service`)

### 2.1. Регистрация в `settings.gradle.kts`

```kotlin
include(":backend:orchestrator-service")
```

### 2.2. Структура модуля `backend/orchestrator-service/`

Порт: **8094** (бнuac TimeInterval). Стек:
- Spring Boot 3.3.5 WebFlux (старter `spring-boot-starter-webflux`).
- **БЕЗ R2DBC** — оркестратор не пишет в БД.
- `spring-kafka` (consumer для `asop.delta.commands` и `asop.delta.full.commands`).
- `spring-boot-starter-data-redis-reactive` — `ReactiveStringRedisTemplate` (общий с gateway — см. ниже про shared config).
- AWS S3 SDK (`software.amazon.awssdk:s3:2.29.x` + `software.amazon.awssdk:s3-presigner`).
- Protobuf (`com.google.protobuf:protobuf-java:3.25.x`).
- Jackson (для JSON `EventStatus`-структуры).
- `Dockerfile` (eclipse-temurin:21-jre), `provision.sh` entrypoint — аналогично другим сервисам.

### 2.3. `ReactiveRedisConfig` перенести в shared-common или дублировать

EventService сейчас в `gateway-service` (Redis + Jackson). Orchestrator должен уметь вызывать `eventService.complete(eventId, resultData)` — чтобы Android через существующий `GET /api/v1/events/{eventId}` узнал COMPLETED. Решение:

**Вынести `EventService` + `EventStatus` + `EventState` в `asop-common`** (новый `ru.asop.common.event.EventService`, `EventStatus`, `EventState`). Обновить gateway-service: делегировать в shared- класс (с rebinding через `ReactiveStringRedisTemplate` bean). Orchestrator-service подключает `asop-common` и использует тот же `EventService` bean. Это сделает key формат `asop:event:{eventId}` симметричным.

### 2.4. Purge scheduler (служба для очистки soft-deleted)

`PurgeJob.kt` — Spring `@Scheduled(fixedRate = 3_600_000 /* hourly */)` или `@Scheduled(cron = "0 0 * * * *")`. Раз в час:
1. Получить connection к Postgres (для purge нужем R2DBC `DatabaseClient`, т.о. **добавить `spring-boot-starter-data-r2dbc`** only forpurge — oркестратор читает БД только для purge).
2. Для каждой таблицы из whitelist:
   ```sql
   BEGIN;
   SET LOCAL session_replication_role = 'replica';  -- отключает BEFORE DELETE триггеры
   DELETE FROM <table> WHERE DELETED_AT IS NOT NULL AND DELETED_AT < NOW() - INTERVAL '6 months';
   SET LOCAL session_replication_role = 'origin';
   COMMIT;
   ```
3. Логи: сколько строк удалено по таблицам.

⚠️ `SET LOCAL session_replication_role = 'replica'` требует привилегий superuser — в Postgres docker `asop`-user из env `POSTGRES_USER=asop` не суперюзер по умолчанию. Решение: либо сделать `asop` user superuser в init-скрипте Postgres (`ALTER USER asop WITH SUPERUSER;` в `postgres/init.sql` — или в `v001-init.sql` post-create script), либо создать отдельную роль `asop_purge` с правом `SET session_replication_role`. Простое — **сделать `asop` superuser** (dev-only, в prod. починить). Добавить в v001-init.sql или в docker-compose env `POSTGRES_USER`-as-superuser.
Альтернатива — `DISABLE TRIGGER trg_x_no_delete` по списку таблиц в purge-сессии (НЕ требует superuser), но более громоздко. **Рекомендую `session_replication_role` с superuser-asop** (dev-friendly).

### 2.5. Docker-compose

Добавить в `infrastructure/docker/docker-compose.yml`:
- сервис `orchestrator-service` (asop-net, mem_limit 256m, env как у других + DB_HOST для purge), depends_on crypto-service + postgres + kafka + redis, ports закомментированы (внутр.), `provision.sh` entrypoint.
- Оркестратор: env `KAFKA_BOOTSTRAP_SERVERS=kafka:9093`, `REDIS_HOST=redis`, `DB_HOST=postgres`, `DB_USER=asop`, `DB_PASSWORD=asop` (для purge R2DBC), `S3_ENDPOINT=http://minio:9000`, `S3_ACCESS_KEY=asop`, `S3_SECRET_KEY=asop-secret`, `S3_BUCKET=asop-sync` (точка доступа внутренняя HTTP — между контейнерами).

### 2.6. Kafka-консьюмер

`DeltaCommandConsumer.kt` — `@KafkaListener(topics = ["${asop.kafka.topics.delta-commands}"], groupId = "orchestrator-delta")`. DTO команды `DeltaSyncCommand` (в `asop-kafka-contracts`):

```kotlin
data class DeltaSyncCommand(
    val eventId: UUID,            // = X-Event-Id header, correlation
    val terminalId: UUID,
    val carrierId: UUID,
    val regionId: UUID,
    val lastUpdatedAt: Map<String, Instant>  // tableName → последний известный UPDATED_AT на терминале
)
```

Consumer читает header `X-Event-Id`. Поток (reactive):
1. Создать `Mono.fromCallable { eventId }`.
2. Parallel/sequential по all tables: WebClient GET на соответствующий сервис `GET /api/v1/{resource}?carrierId={UUID}&regionId={UUID}&updatedAtSince={lastUpdatedAt}&includeDeleted=true` (модификация существующих list endpoints — см. задачу 3).
3. Каждый сервис возвращает `Flux<T>` (или JSON array) с записями, у которых `UPDATED_AT > lastUpdatedAt` (включая soft-deleted с `DELETED_AT`). `includeDeleted=true` флаг — позиr — что это delta-stream, не обычный CRUD-list.
4. Оркестратор сериализyет записи в Protobuf `.proto` message (см. задачу 5).
5. Чанкование по 50KB (см. задачу 6).
6. Чанки → Redis: ключ `asop:event:{eventId}:chunk:{n}` (TTL 24ч), meta: `asop:event:{eventId}:meta` = JSON `{"totalChunks": N, "totalBytes": M, "createdAt":"..."}` TTL 24ч.
7. `eventService.complete(eventId, resultData = "{\"totalChunks\": N, \"totalBytes\": M}")` — Android поллит `GET /api/v1/events/{eventId}`, видит COMPLETED, читает `resultData.totalChunks`, качает чанки.
8. При ошибке — `eventService.fail(eventId, errorMessage)` и лог.

`FullSyncCommandConsumer.kt` — `@KafkaListener(topics = ["${asop.kafka.topics.delta-full-commands}"], groupId = "orchestrator-delta-full")`. Аналогично, но:
1. Получает ВСЕ записи (без delta-filter, без updatedAtSince) со всех сервисов (но с carrier/region фильтром).
2. Для каждой таблицы — отдельный `.pb` файл в ByteArrayOutputStream.
3. ZIP всех файлов → поток загружается в MinIO bucket `asop-sync` ключ `full_{eventId}.zip` (TTL в S3 lifecycle 24ч configured на bucket).
4. Pre-signed GET URL (TTL 24 hours) → `eventService.complete(eventId, resultData = "{\"s3Url\":\"https://...\"}")`.
5. Android скачивает по URL через OkHttp → unzip → parse `.pb` → apply to Room.

### 2.7. application.yml (orchestrator)

```yaml
server:
  port: 8094
spring:
  application:
    name: orchestrator-service
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:kafka:9093}
    # + ssl as gateway
    consumer:
      group-id: orchestrator
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.ErrorHandlingDeserializer
      properties:
        spring.json.trusted.packages: "*"
        spring.json.value.default.type: ru.asop.kafka.events.delta.DeltaSyncCommand
        spring.deserializer.value.delegate.class: org.springframework.kafka.support.serializer.JsonDeserializer
  # Redis — inherited via asop-common EventService
  r2dbc:
    url: r2dbc:postgresql://${DB_HOST:postgres}:5432/${DB_NAME:asop}
    username: ${DB_USER:asop}
    password: ${DB_PASSWORD:asop}
asop:
  kafka:
    topics:
      delta-commands: asop.delta.commands
      delta-full-commands: asop.delta.full.commands
  s3:
    endpoint: ${S3_ENDPOINT:http://minio:9000}
    access-key: ${S3_ACCESS_KEY:asop}
    secret-key: ${S3_SECRET_KEY:asop-secret}
    bucket: ${S3_BUCKET:asop-sync}
  purge:
    enabled: true
    interval-ms: 3600000
    retention-months: 6
```

### 2.8. `KafkaTopic` updates (`asop-common`)

```kotlin
const val DELTA_COMMANDS = "asop.delta.commands"
const val DELTA_FULL_COMMANDS = "asop.delta.full.commands"
```

---

## ЗАДАЧА 3 — Обновить существующие API мастер-сервисов для Delta

Для каждого сервиса (admin, carrier, route, user, card) — добавить возможность отдавать разницу. **Не дублировать существующие list endpoints** — расширять их query-параметрами.

### 3.1. Общий query-параметр паттерн

Все list endpoints (например `GET /api/v1/regions`) должны поддерживать:
```
?carrierId=UUID&regionId=UUID&updatedAtSince=ISO8601&includeDeleted=true&limit=10000
```

- `updatedAtSince` — возвращать только записи с `UPDATED_AT > updatedAtSince` (default — без фильтра).
- `includeDeleted=true` — возвращать записи с `DELETED_AT IS NOT NULL` (default `false`).
- Ответ: обычный JSON-array (или `Flux<...>`), без paging wrapper.

### 3.2. Реализация через R2DBC `@Query`

Для каждой таблицы — добавить в соответствующий Repository (в.ceile service) метод:
```kotlin
@Query("""
    SELECT * FROM ASOP_REGIONS
    WHERE (:updatedAtSince IS NULL OR UPDATED_AT > :updatedAtSince)
      AND (:includeDeleted = TRUE OR DELETED_AT IS NULL)
    ORDER BY UPDATED_AT ASC
    LIMIT :limit
""")
fun findDelta(updatedAtSince: Instant?, includeDeleted: Boolean, limit: Int): Flux<RegionEntity>
```

Аналогично для таблиц с `carrierId`/`regionId` фильтром — добавять `AND (:carrierId IS NULL OR CARRIER_ID = :carrierId)` И т.п. Для пользовательского фильтра (User/Card) — JOIN через user_carriers+user_regions.

### 3.3. Добавить контроллеры для delta (или расширить list)

Вариант A (минимально-inвазивный) — новые endpoints:
```
GET /api/v1/{resource}/delta?carrierId=&regionId=&updatedAtSince=&includeDeleted=true&limit=10000
```

Так как ServiceRegistry уже маппит `{resource} → сервис`, gateway ProxyController автоматически пробросит эти запросы в нужный сервис. Orchestrator дёргает `WebClient GET https://admin-service:8091/api/v1/regions/delta?...`.

Реализовать `/delta` подroуты в каждом соответствующем `*Controller.kt` мастер-сервиса (admin/carrier/route/user/card). Не дублировать DTO — переиспользовать существующие `Response` DTOs (Regulon response, Contractor response). **DTO добавить поле `updatedAt: Instant` and `deletedAt: Instant?`** И update сервисные `toResponse()` mappers.

### 3.4. Обновить DTO всех Response-классов

В backend/shared/api/{domain}-api — добавить в каждый Response DTO:
```kotlin
data class <X>Response(
    ... existing fields ...,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant? = null
)
```

обновить `toResponse()` mappers в сервисах. Это влияет и на web-admin (TypeScript `interface`s) — добавить поля в types (non-breaking — optional).

### 3.5. Каскадный фильтр пользователей (user-service)

Для таблиц `ASOP_USERS`, `ASOP_USER_ROLES`, `ASOP_CARDS`, `ASOP_CARD_MIFARES`, `ASOP_CARD_BANKS`, `ASOP_CARD_TARIFFS`, `ASOP_BLACKLISTS`, `ASOP_USER_BENEFITS`:

Оркестратор первым делом запрашивает user-service `/api/v1/admin-users/delta?carrierId=X&regionId=Y&updatedAtSince=...` — user-service возвращает только тех users, у которых `user_id IN (SELECT user_id FROM ASOP_USER_CARRIERS WHERE carrier_id = :carrierId) UNION (SELECT user_id FROM ASOP_USER_REGIONS WHERE region_id = :regionId)`. Получив `Set<UUID> userIds`, оркестратор дёргает card-service `/api/v1/cards/delta?userIdsIn=...&updatedAtSince=...`.

В `CardRepository` — добавить `(cardUid IN (...) OR userId IN (:userIds))` filter через `@Query`. И для user_service `asop_user_roles` — `WHERE user_id IN (:userIds)`.

(Альтернатива: card-service сам делает JOIN — но кросс-сервисный JOIN невозможен, card-service не должен знать о user_carrier. Поэтому orchestrator первым шагом запрашивает userIds.)

---

## ЗАДАЧА 4 — Gateway: новые endpoint'ы

### 4.1. Дельта-команда (async через Kafka)

`POST /api/v1/sync/references/delta` — mTLS (chain Order 1, `/api/v1/sync/**`).

Body: `DeltaSyncRequest { terminalId: UUID (from X.509 CN principal OR body) }`. Фактически терминал шлёт тело `{ lastUpdatedAt: Map<String, Instant> }`, terminalId/carrierId/regionId определяются на gateway из mTLS principal (CN = terminalSerial) → lookup в `terminal-service`?? Слишком дорого. Проще — body: `{ terminalId: UUID, lastUpdatedAt: Map<String, Instant> }` и terminal сам шлёт свой `terminalId`. Но терминал-то знает свой terminalId! Gертez — terminal шлёт eventId-agnostic запрос, gateway:
1. mTLS principal CN → terminalSerial.
2. internal `GET /api/v1/terminals?serial=<CN>` через WebClient (но сейчас list по serial'у нет — есть `findByTerminalSerial`, терминал уже знает свой `id` (TerminalId) из syncPreferences и может прислать в body). Решение: **body содержит `terminalId: UUID` + `lastUpdatedAt: Map<String, Instant>`**, gateway доверяет терминалу (mTLS principal CN=serial должен corresponded terminalSerial в БД — но можно упростить и доверять). Дальше gateway resolve `carrierId, regionId` через `WebClient GET https://terminal-service:8084/api/v1/terminals/{id}`.
3. Создать `DeltaCommandService.publish(request, terminalId, carrierId, regionId)` — по аналогии с `CertCommandService`:
   - `eventId = UuidUtils.newId()`
   - команда `DeltaSyncCommand(eventId, terminalId, carrierId, regionId, lastUpdatedAt)`
   - `ProducerRecord(asop.delta.commands, terminalId.toString(), command)` + `X-Event-Id` header
   - `eventService.createPending(eventId, "asop.delta.commands").then(kafkaTemplate.send(record)).thenReturn(eventId)`
4. Контроллер: `ResponseEntity.accepted().header("X-Event-Id", eventId.toString()).body(AcceptedResponse(...))`.

### 4.2. Full-dump команда

`POST /api/v1/sync/references/full` — аналогично, body `{ terminalId: UUID }`. Гейтвей → `asop.delta.full.commands` → orchestrator. Аналогичный 202 + eventId. Orchestrator所为 zip-load pre-signed URL → event COMPLETED with `resultData.s3Url`.

### 4.3. Читалка чанков (sync, mTLS)

`GET /api/v1/sync/references/{eventId}/meta` → body `{"totalChunks":N,"totalBytes":M,"createdAt":"...", "s3Url":null}` (для delta) или `{"s3Url":"https://..."}` (для full). Gateway читает Redis `asop:event:{eventId}:meta`. 200 (found) / 404 (not yet / expired).

`GET /api/v1/sync/references/{eventId}/chunks/{n}` → raw `application/x-protobuf` bytes из Redis `asop:event:{eventId}:chunk:{n}`. 200 (bytes) / 404.

`SecurityConfig` chain Order 1 матчеры расширить: `.pathMatchers(HttpMethod.GET, "/api/v1/sync/references/**").authenticated()` (mTLS).

### 4.4. Web-admin `permitAll` NO для /sync/references

**NE** `permitAll` — это mTLS terminal-only. Никакого анонимного доступа.

### 4.5. Spring Boot compression для HTTP responses

В `application.yml` gateway добавить:
```yaml
server:
  compression:
    enabled: true
    mime-types: application/x-protobuf,application/json
    min-response-size: 10240
```
(Spring Boot сам сжимает Protobuf-ответы gzip if `Accept-Encoding: gzip`.)

---

## ЗАДАЧА 5 — Protobuf схема (`.proto`)

### 5.1. Общая `schema.proto`

Создать в новом модуле `:backend:shared:asop-proto` (package `ru.asop.proto`):

```proto
syntax = "proto3";
package asop.v1;
option java_package = "ru.asop.proto.v1";
option java_multiple_files = true;

message RegionRow { string id = 1; string municipal_division = 2; ... string updated_at = 30; string deleted_at = 31; }
// повторять для каждой из ~40 таблиц... — это огромная proto
// каждое row-сообщение содержит ВСЕ колонки соответствующей таблицы

message DeltaChunk {
    repeated RegionRow regions = 1;
    repeated TerritoryRow territories = 2;
    // ... 40 fields, each repeated
    int32 chunk_index = 90;
    int32 total_chunks = 91;
    string event_id = 92;
}
```

Каждый чанк содержит `repeated <Table>Row` для нескольких таблиц одновременно (в одном чанке могут باشد записи из разных таблиц — как в твоём промпте: "С сервера может в ответ прийти по одному запросу с одним идентификатором много чанков ответа, на разные справочники и с разными страницами этих справочников").

### 5.2. Генерация классов

- backend: `protoc-gen-java` через `com.google.protobuf:protobuf-gradle-plugin:0.9.4`, генерит в `backend/shared/asop-proto/build/generated/main/java`.
- Android: `protobuf-javalite` через плагин `com.google.protobuf` (`protobuf-gradle-plugin`), lite-runtime (минимальный size на мобиле).
- Android `build.gradle.kts`: плагин `id("com.google.protobuf")` + dep `protobuf-java` / `protobuf-javalite`.

### 5.3. Структура full-dump `.pb` файлов

ZIP архив содержит несколько `.pb` файлов — один по типу таблицы:
```
full_{eventId}.zip
  ├── regions.pb       (repeated RegionRow)
  ├── territories.pb   (repeated TerritoryRow)
  └── ...
```
Каждый `.pb` это отдельное Protobuf message `repeated <Table>Row` (НЕ DeltaChunk wrapper — file-per-table). Один `repeated RegionRow regions = 1` message `RegionsFile { repeated RegionRow rows = 1; }`.

---

## ЗАДАЧА 6 — Чанкование оркестратором (50 KB Protobuf)

```kotlin
fun chunkBySize(rows: List<DeltaRow>, maxBytes: Int = 50_000): List<DeltaChunk> {
    val chunks = mutableListOf<DeltaChunk>()
    var buffer = mutableListOf<DeltaRow>()
    for (row in rows) {
        buffer.add(row)
        val draft = DeltaChunk.newBuilder().addAllRows(buffer.reversedRowsPerTable()).build()
        if (draft.serializedSize > maxBytes) {
            buffer.removeAt(buffer.size - 1)  // извлек последнюю запись
            chunks.add(DeltaChunk.newBuilder().addAllRows(buffer).build())
            buffer = mutableListOf(row)  // "лишняя" запись начинает новый chunk
        }
    }
    if (buffer.isNotEmpty()) {
        chunks.add(DeltaChunk.newBuilder().addAllRows(buffer).build())
    }
    return chunks
}
```

Реализация — аккуратно с `serializedSize` (метод `MessageLite.serializedSize()` в protobuf-java, non-alloc). После построения каждого чанка — `toByteArray()` и запись в Redis `asop:event:{eventId}:chunk:{n}` с TTL 24h.

After all chunks written — write meta `asop:event:{eventId}:meta` = JSON `{"totalChunks": N, "totalBytes": M}` (TTL 24h). После этого `eventService.complete(eventId, resultData=metaJson)`.

⚠️ ATOMICность: orchhatt должен write-all-chunks сначала, и **только после** этого `complete` (иначе Android увидит COMPLETED раньше времени). Use `Mono.defer().then()`.

---

## ЗАДАЧА 7 — MinIO infrastructure

### 7.1. `docker-compose.yml`

Добавить сервисы:

```yaml
  minio:
    mem_limit: 256m
    image: quay.io/minio/minio:latest
    command: server /data --console-address ":9001"
    environment:
      MINIO_ROOT_USER: asop
      MINIO_ROOT_PASSWORD: asop-secret
    ports:
      - "9000:9000"
      - "9001:9001"
    volumes:
      - minio_data:/data
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:9000/minio/health/ready"]
      interval: 10s
      timeout: 5s
      retries: 5
    networks: [asop-net]

  minio-init:
    mem_limit: 64m
    image: minio/mc:latest
    depends_on: { minio: { condition: service_healthy } }
    entrypoint: ["/bin/sh","-c"]
    command: ["mc alias set asop http://minio:9000 asop asop-secret && mc mb asop/asop-sync && mc anonymous set download asop/asop-sync"]
    networks: [asop-net]
```

`volumes:` добавить `minio_data:`.

### 7.2. MinIO TLS cert (для HTTPS pre-signed URL)

Для HTTPS на MinIO — монтировать сертификат в `/root/.minio/certs` (`public.crt` + `private.key`). Либо:
- crypto-service генерит cert для `minio` SAN — добавить в `provision.sh` cert-init для minio (по аналогии с ключcloak certs-init). В v1 упрощение: HTTP inside Docker, pre-signed URL — внутренняя адресация `http://minio:9000/...` — но Android стучится НАРУЖУ, нужен публичный HTTPS.
- Решение: **gateway проксирует** `GET /api/v1/sync/references/{eventId}/download` → redirect/internal-proxy to MinIO pre-signed URL. Android видит HTTPS, gateway перевыдаёт stream откуда MinIO. Так Android не нуждается в доверии MinIO self-signed cert.

**Записать собственный вариант** (как реkomendость) — **gateway проксирует download**, по `GET /api/v1/sync/references/{eventId}/download` gateway читает `asop:event:{eventId}:meta.s3Url` (pre-signed internal URL), делает `WebClient get` from MinIO → `Mono<DataBuffer>` → stream to Android. Android видит HTTPS. Это безопаснее, чем раскрывать MinIO наружу (нет публичного endpoint, Android не нуждается в trust-store для MinIO cert).

В минусе — gateway проксирует большие body через себя. Для full-dump (потенциально 10–100 МБ) это ОК для dev. Простой вариант лучше.

---

## ЗАДАЧА 8 — Android Delta Sync client

### 8.1. `build.gradle.kts` добавки

```kotlin
plugins {
    id("com.google.protobuf") version "0.9.4"
}
dependencies {
    implementation("com.google.protobuf:protobuf-javalite:3.25.5")
    // или protobuf-java когда Desktop-only — но javalite меньше для mobile.
}
protobuf {
    protoc { artifact = "com.google.protobuf:protoc:3.25.5" }
    generateProtoTasks {
        all().forEach { it.builtins { create("java") { option("lite") } } }
    }
}
```

`src/main/proto/delta.proto` — тот же `.proto` что и backend (symmetrize, один source).

### 8.2. Room entities — для КАЖДОЙ таблицы из маппинга

**Бамп Room version** с 2 →N. Все `@Database` entities добавить. ~40 таблиц → ~40 entities + DAOs.

Каждый `@Entity` копирует схему из `asop_schema.sql` адаптируя под SQLite:
- TIMESTAMP хранить как `Long` (epoch millis) или ISO8601 String — recommended вкратце String (Room умеет `TypeConverter`).
- UUID хранить как String.
- PostGIS polygons — НЕ хранить (текстовый GeoJSON Stringしておけば).
- `DELETED_AT` хранить как nullable String — при apply, терминал помечает строку deleted.
- Index на `UPDATED_AT` (Room `@Index`).

Example:
```kotlin
@Entity(tableName = "asop_regions", indices = [Index("updated_at")])
data class RegionEntity(
    @PrimaryKey @ColumnInfo(name = "region_id") val regionId: String,
    @ColumnInfo(name = "municipal_division") val municipalDivision: String,
    ...,
    @ColumnInfo(name = "updated_at") val updatedAt: String,
    @ColumnInfo(name = "deleted_at") val deletedAt: String? = null
)
```

DAO — упрощённо:
```kotlin
@Dao interface RegionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<RegionEntity>)
    @Query("SELECT MAX(updated_at) FROM asop_regions")
    suspend fun getMaxUpdatedAt(): String?
    @Query("SELECT * FROM asop_regions WHERE deleted_at IS NULL")
    fun observeAll(): Flow<List<RegionEntity>>
}
```

### 8.3. SyncMeta storage

Создать таблицу `sync_meta`:
```kotlin
@Entity(tableName = "sync_meta")
data class SyncMetaEntity(
    @PrimaryKey val tableName: String,
    val lastUpdatedAt: String? = null,
    val lastSyncAt: Long? = null
)
```
DAO: `getMaxUpdatedAtMap(): Map<String, String?>` (SELECT tableName, lastUpdatedAt).

### 8.4. WorkScheduler — новый DeltaWorker

`worker/DeltaSyncWorker.kt` — `@HiltWorker CoroutineWorker`:
1. Read `syncPreferences.terminalId` —若无 return `Result.success()`.
2. Read `syncMetaDao.getMaxUpdatedAtMap()` → `Map<String, Instant>`.
3. `gatewayApi.deltaSync(DeltaSyncRequest(terminalId, lastUpdatedAt))` → `Response<AcceptedResponse>` (202 + eventId).
4. Сохранить eventId + timestamp в Room (новая таблица `delta_sync_jobs` with PK eventId, status PENDING).
5. Return success (работу закончит `DeltaChunkPollWorker`).

`worker/WorkScheduler.kt` — добавить `schedulePeriodicDeltaSync()`:
```kotlin
PeriodicWorkRequestBuilder<DeltaSyncWorker>(Duration.ofMinutes(60))
    .setConstraints(Constraints.Builder().setRequiredNetworkType(CONNECTED).build())
    .build()
```
+ `Keep` policy.
+ `OneShot` для manual trigger из drawer "Загрузить справочники" через `triggerDeltaSync()` → enqueue One.

### 8.5. DeltaChunkPollWorker — pulling chunks из Redis

`worker/DeltaChunkPollWorker.kt` (5 минут периодический, симметрично EventPollWorker):
1. Read всех `delta_sync_jobs` со status=PENDING.
2. For each eventId: `gatewayApi.getEventStatus(eventId)` → если COMPLETED → read `resultData.totalChunks`.
3. Цикл от 1 до N: `gatewayApi.getChunk(eventId, n)` → `ResponseBody.bytes()` (protobuf).
4. Parse `DeltaChunk.parseFrom(bytes)`.
5. Aggregate до "all chunks received".
6. **Atomic transaction** в Room через `RoomDatabase.runInTransaction { ... }` — upsert all entities from all chunks + mark completed.
7. Если в любой точке exception (или chunk missing, или protocol error) — **forget the whole eventId**: delete `delta_sync_jobs` row → return success (через час DeltaSyncWorker снова запустит delta-запрос).
8. Timeout: если в течение ~24 ч eventId не пришёл COMPLETED — mark failed+delete (analog Android retry logic).

### 8.6. GatewayApi — новые endpoints

```kotlin
@POST("api/v1/sync/references/delta")
suspend fun deltaSync(@Body request: DeltaSyncRequest): Response<AcceptedResponse>

@POST("api/v1/sync/references/full")
suspend fun fullSync(@Body request: FullSyncRequest): Response<AcceptedResponse>

@GET("api/v1/sync/references/{eventId}/meta")
suspend fun getDeltaMeta(@Path("eventId") eventId: String): DeltaMetaResponse

@GET("api/v1/sync/references/{eventId}/chunks/{n}")
suspend fun getDeltaChunk(@Path("eventId") eventId: String, @Path("n") n: Int): Response<ResponseBody>

@GET("api/v1/sync/references/{eventId}/download")
suspend fun downloadFullDump(@Path("eventId") eventId: String): Response<ResponseBody>
```

DTOs (Moshi):
- `DeltaSyncRequest { terminalId: String, lastUpdatedAt: Map<String, String> }` (ISO8601 = String).
- `FullSyncRequest { terminalId: String }`.
- `DeltaMetaResponse { totalChunks: Int, totalBytes: Long, s3Url: String? }`.

### 8.7. Drawer menu: "Загрузить справочники"

В `TerminalNavHost.kt` stub-пункт "Загрузить справочники" уже есть (placeholder). Заменить `onClick`:
- AlertDialog: "Запросить полную выкачку справочников? (это займёт время и трафик)" → Да → `viewModel.requestFullSync()` → POST `/api/v1/sync/references/full`.
- Plus: кнопка "Delta синхронизировать сейчас" → `viewModel.requestDeltaSync()`.
- Помимо этого — Delta Worker уже раз в час автоматически. Manual — only для отладки /fallback.

### 8.8. Full-dump download

`FullDumpDownloadWorker.kt` — `OneTime` worker:
1. Polling `getEventStatus(eventId)` → COMPLETED with `resultData.s3Url` (через gateway proxy) ИЛИ `getDeltaMeta` возвращает `s3Url`.
2. `gatewayApi.downloadFullDump(eventId)` → `ResponseBody` (zip stream).
3. Save to cache file.
4. `ZipInputStream` parse → each entry `regions.pb` → `RegionsFile.parseFrom(...)` → upsert в соотв. Room DAO.
5. Atomic transaction.
6. Если ошибка — forget eventId.

---

## ЗАДАЧА 9 — Backend DeltaSyncService в gateway

Сервис `backend/gateway-service/src/main/kotlin/ru/asop/gateway/service/DeltaCommandService.kt` — по аналогии с `CertCommandService`. Методы: `publishDelta(terminalId, lastUpdatedAt)` и `publishFull(terminalId)`. Stream: `eventId → eventService.createPending → kafkaTemplate.send → thenReturn eventId`. Eсть `kafkaTemplate` уже инжeстится.

`backend/gateway-service/src/main/kotlin/ru/asop/gateway/controller/DeltaCommandController.kt`:
- `@PostMapping("/api/v1/sync/references/delta")` — mTLS principal `TerminalContextHolder` (получив terminalSerial из X.509 CN). Проще — body contains terminalId, gateway resolves through TerminalService WebClient (terminal-service:8084/api/v1/terminals/{id}) если carrierId/regionId unknown.
- Returns 202 + `X-Event-Id`.

### 9.1. resolve terminalId → carrierId, regionId

Либо:
- **Просто вариант** (рекомендable — меньше latency): body request содержит `terminalId` — gateway доверяет (mTLS principal CN=terminalSerial должен correspond terminal'у с этим id — первый danach, на случай dev'а не проверяем). Gateway делает quick WebClient `GET https://terminal-service:8084/api/v1/terminals/{id}` → достаёт `carrierId`, `regionId`, шлёт в Kafka.
- **Строгий вариант**: validate mTLS CN (terminalSerial) → `GET terminal-service /api/v1/terminals?serial=<CN>` (но serial-list endpoint's нет — добавлять).
- Выбрать **просто вариант** — dev-time.

### 9.2. Redis read endpoints (gateway)

`backend/gateway-service/.../controller/DeltaReadController.kt`:
- `@GetMapping("/api/v1/sync/references/{eventId}/meta")` — read `asop:event:{eventId}:meta` из Redis. 200/404.
- `@GetMapping("/api/v1/sync/references/{eventId}/chunks/{n}")` — read `asop:event:{eventId}:chunk:{n}` из Redis, отдаёт raw bytes (Content-Type `application/x-protobuf`).
- `@GetMapping("/api/v1/sync/references/{eventId}/download")` — read `asop:event:{eventId}:meta`, если `s3Url` есть → reads `s3Url` через `WebClient` (minio HTTP internal), streams в Android. 200/404.
- Каждый read endpoint в `SecurityConfig` chain Order 1 — `.pathMatchers(HttpMethod.GET, "/api/v1/sync/references/**").authenticated()` (mTLS).

---

## ЗАДАЧА 10 — DTO в `asop-kafka-contracts`

Новый файл `backend/shared/asop-kafka-contracts/src/main/kotlin/ru/asop/kafka/events/delta/DeltaEvents.kt`:

```kotlin
data class DeltaSyncCommand(
    val eventId: UUID,
    val terminalId: UUID,
    val carrierId: UUID,
    val regionId: UUID,
    val lastUpdatedAt: Map<String, Instant>  // tableName → lastUpdatedAt
)
data class FullSyncCommand(
    val eventId: UUID,
    val terminalId: UUID,
    val carrierId: UUID,
    val regionId: UUID
)
```

---

## ЗАДАЧА 11 — ServiceRegistry

`backend/gateway-service/.../config/ServiceRegistry.kt` — добавить mapping для `/sync/references` НЕТ НЕТ! Не нужно mapping — /sync/references — это явный gateway controller, не proxy. ServiceRegistry не трогать.

Оркестратор `orchestrator-service` НЕ нужно в ServiceRegistry (к нему нет синхронных клиентских запросов — только Kafka consumer).

---

## ЗАДАЧА 12 — Обновить `KafkaTopic`

В `backend/shared/asop-common/src/main/kotlin/ru/asop/common/kafka/KafkaTopic.kt`:
```kotlin
const val DELTA_COMMANDS = "asop.delta.commands"
const val DELTA_FULL_COMMANDS = "asop.delta.full.commands"
```

---

## ЗАДАЧА 13 — Build verification и Docker test

### 13.1. Backend
- `./gradlew build -x test` — все модули (+ orchestrator-service, asop-proto) собираются.
- `./gradlew bootJar` для всех сервисов → JARs.

### 13.2. Docker
- `docker compose -f infrastructure/docker/docker-compose.yml down -v`
- `docker compose -f infrastructure/docker/docker-compose.yml up -d --build`
- Проверить логи: gateway, orchestrator-service, redis, minio, postgres — нет StackTrace.
- MinIO init создаст bucket `asop-sync`.
- `docker compose exec -T postgres psql -U asop -d asop -c "\d ASOP_REGIONS"` — проверить наличие `CREATED_AT, UPDATED_AT, DELETED_AT` + триггер `trg_asop_regions_no_delete`.
- Smoke-test: `INSERT INTO ASOP_ROLES ...; DELETE FROM ASOP_ROLES; SELECT * FROM ASOP_ROLES;` → строка остается с `DELETED_AT` заполненным.

### 13.3. Android
- `cd frontend/android-terminal && ./gradlew :app:assembleDebug` — должно собраться с proto-plugin и lite-runtime.
- Установить APK на эмулятор.
- Под监y manual trigger "Загрузить справочники" в drawer → смотреть логи `adb logcat | grep okhttp|asop|delta` → 202 → polling → chunks падут в Room.

### 13.4. End-to-end sanity
- Установить APK, дождаться polling events COMPLETED.
- `adb logcat` — должны увидеть `GET /api/v1/sync/references/{eventId}/chunks/1` 200.
- Room tables заполнены: `adb shell run_as ru.asop.terminal cat /data/data/ru.asop.terminal/databases/asop_terminal.db` (или `sqlite3` через adb).

---

## ЗАДАЧА 14 — Документация

- `AGENTS.md`:
  - Стек: добавить MinIO/S3, Protobuf.
  - Module structure: обновить счётчик (+ orchestrator-service + asop-proto).
  - Добавить секцию "Delta Sync Architecture" (Ты ce Kafka topics, Redis keys, MinIO, chunking alRetrym, full-dump via ZIP).
  - Обновить Endpoints: добавить gateway `POST /api/v1/sync/references/delta|full`, `GET /api/v1/sync/references/{eventId}/meta|chunks|download`.
  - Android-секция: DeltaSyncWorker раз в час, DeltaChunkPollWorker, drawer "Загрузить справочники".
- `doc/context.md`:
  - Технологический стек: MinIO, Protobuf.
  - Сервисы и порты: orchestrator-service 8094, minio 9000/9001.
  - Обновить "Базa данных": soft-delete DELETED_AT, BEFORE DELETE триггеры, purge job раз в час 6 months retention.
  - Добавить раздел "Delta Sync" в порядок 3 (Gateway) — описать поток Android→Gateway→Kafka→Orchestrator→Redis→Android→Room.
- `doc/architecture.md`:
  - Технологический стек: добавить строки для Redis, MinIO, Protobuf.
  - Обновить тех Aпп Rochitect's страницу — orchestrator-service в общем списке сервисов.
  - Обновить Kafka topics list — добавить `asop.delta.commands` / `asop.delta.full.commands`.
- `doc/auth.md`:
  - Оркестратор внутри Docker — TLS, no authn от клиентов, доверяет gateway по Kafka (как другие сервисы).
- `doc/todo.md`:
  - Новые задачи 9. Delta Sync ✅.
- `infrastructure/db-migrations/asop_schema.sql` — синхронизировать с `migrations/v001-init.sql`.
- `infrastructure/db-migrations/asop_schema.md` — обновить описание таблиц (добавить CREATED_AT/UPDATED_AT/DELETED_AT note + триггер).
- `infrastructure/docker/todo.md` — если есть (можно пропустить).

---

## Общий порядок выполнения

1. **Schema migration** (1.1–1.5) — DDL, индексы, триггеры. Проверить через `docker compose down -v && up -d --build && check \\d ASOP_ROLES`.
2. **Protobuf module** (5) — `.proto` + gradle plugin + asap-proto module. Сборка `gradlew :backend:shared:asop-proto:build` должна генерить Java.
3. **EventService shared** (2.3) — перенести EventService/EventStatus/EventState в asop-common, gateway+orchestrator делят.
4. **Orchestrator microservice** (2) — создать `:backend:orchestrator-service`, Kafka consumers, WebClient к мастер-сервисам, Redis writes (chunks+meta), S3 SDK, purge scheduler. Прописать в settings.gradle.kts + docker-compose.
5. **Master-services delta endpoints** (3) — добавить `/delta` роуты + DTO `updatedAt/deletedAt` + Repository @Query с join-userId cascade для user/card. ОК. Не сломать текущий web-admin функционал.
6. **Gateway endpoints** (4, 9) — `DeltaCommandController`, `DeltaReadController`, SecurityConfig matchers, Spring compression.
7. **Kafka topics** (10, 12) — new constants + DeltaSyncCommand/FullSyncCommand in asop-kafka-contracts.
8. **MinIO docker-compose + S3 SDK** (7) — init container `minio/mc` for bucket creation, gateway proxy for download.
9. **Android** (8) — proto plugin + lite runtime, ~40 Room entities, SyncMeta + DeltaSyncJobs tables, workers, GatewayApi endpoints, drawer "Загрузить справочники" actions.
10. **Build + Docker test** (13) — full pipeline.
11. **Docs** (14) — AGENTS.md, doc/*, asop_schema.*.

После каждой большой группы — `./gradlew build -x test` (backend) для проверки компиляции и feedback loop. Ошибку → фикс, если прото не compiles manually — разблок Generation path.

---

## Финальные требования

1. **Не коммитить автоматически** — пользователь явно попросит commit после ревью.
2. **Все ID — UUIDv7** через `UuidUtils.newId()`.
3. **Никаких `Mono.block()` в WebFlux** — только в Kafka-listener void-methods. `EventService.complete(...).subscribe()` — OK.
4. **со保全 backward-compat web-admin**: добавлять поля в DTO как optional (nullable в Kotlin, `?` в TypeScript). Не ломать существующие list endpoints — `/delta` подroуты **дополнительно**.
5. **Cosmetic**: не дублировать区委generated proto-code — генерация через gradle task. Не коммитить генерированные классы (`backend/shared/asop-proto/build/`).
6. **Purge scheduler test**: проверить, что `DELETE FROM ASOP_ROLES WHERE DELETED_AT < NOW() - INTERVAL '6 months'` после `SET LOCAL session_replication_role = 'replica'` действительно снимает строку, а триггер НЕ срабатывает.
7. **Soft-delete user-facing**: web-admin при delete (DELETE /{id}) должен вызвать серверный endpoint, который сделает soft-DELETE (через триггер). НЕ ломать существующие DeleteMapping — сервер клиенскому HTTP DELETE просто сработает через триггер (вернёт 204, а запись получит `DELETED_AT`).
8. **Android** — `Room database version = 3`, fallbackToDestructiveMigration оставляется (данные терминала справочников не ценны — это cache).
9. **Build verification**: как только backend собрался → `docker compose up -d --build` → `docker compose logs -f --tail=50 orchestrator-service gateway-service minio postgres` — нет StackTrace. Если есть — фикс.

## Важные нюансы — не упустить

- **Purge_postgres user должен быть SUPERUSER** для `SET session_replication_role`. Решение: в `docker-compose.yml` у `postgres` env `POSTGRES_USER=asop POSTGRES_DB=asop` добавить в init-скрипт (или в v001.sql после schema создания, но до redeploy) `ALTER USER asop WITH SUPERUSER;` — для dev окружения это безопасно.
- **Before DELETE trigger на 40 таблиц** — DRY через `DO $body$` блок, который перeбиберет таблицы и для composite-PK делает исключения.
- **Оркестратор R2DBC — ТОЛЬКО для purge** (DatabaseClient). WebFlux WebClient — для sync-REST к мастер-сервисам. Kafka consumer — async.
- **`includeDelated=true`** — означать `include_all_with_deleted` (исторический параметр) — выбери 明amt naming в API, но не "includeDeleted" (confusing). Рекомендую `withDeleted=true` для orchestrator.
- **Android DeltaChunkPollWorker timeout** — ~30 минут с retry каждые 5 минут = 6 попыток. После fail — забыть eventId и дождаться нового hourly delta запроса.
- **FlowDiagram** для AGENTS.md/doc/context.md:

```
Android [hourly] ─POST /api/v1/sync/references/delta─▶ Gateway (mTLS) ─202 + X-Event-Id
                                                             │ eventService.createPending
                                                             ▼
                                                       Kafka asop.delta.commands
                                                             │
                                                             ▼
                              Orchestrator ─WebClient GET /delta?carrierId&regionId&updatedSince&includeDeleted─▶ admin/carrier/route/user/card services
                                        │
                                        │ Chunks (Protobuf serializedSize > 50KB)
                                        ▼
                              Redis asop:event:{eventId}:chunk:1..N + asop:event:{eventId}:meta
                                        │
                                        │ eventService.complete(eventId, resultData=meta)
                                        ▼
                              Redis asop:event:{eventId} COMPLETED
                                        ▲
                                        │
Android polling ─GET /api/v1/events/{eventId}── Gateway ── Redis ─ 202→PENDING / 200→COMPLETED with totalChunks=N
Android downloads ─GET /api/v1/sync/references/{eventId}/chunks/{n}── Gateway ── Redis chunks
Android writes atomically в Room. Done.
```

Full-dump аналогично, но orchestrator uploads ZIP в MinIO, в meta пишет pre-signed `s3Url`, Android качает через `download` endpoint (gateway proxies MinIO).

---

## УТОЧНЕНИЯ уже answered (from user's dialog)

1. **Маппинг owner-services**: принять реальный — vehicle/contract-routes в route-service.
2. **Fores user/card**: сильный каскадный фильтр — терминал видит только своих users (carrier+region) и их карты.
3. **Global справочники (ROLES, CARD_TYPES...)**: передавать целиком (с soft-delete respect).
4. **Orchestration → Redis**: оркестрол self-cafe EventService.complete (через shared EventService в asop-common).
5. **Android chunk pull**: `GET /api/v1/sync/references/{eventId}/chunks/{n}` — отдельный endpoint, NOT in EventStatus.resultData.
6. **Оркестратор stack**: WebFlux + WebClient + Kafka + Redis + S3 (async), R2DBC only for purge.
7. **MinIO + pre-signed URL przez gateway proxy** — Android видит HTTPS, gateway перевыдаёт stream из MinIO.
8. **No upper cap on delta chunks** — бесконечный until `eventService` TTL expires (24ч).
9. **Purge trigger bypass**: `SET LOCAL session_replication_role = 'replica'` (postgres user must be SUPERUSER for dev).

## Тables fall-through decision matrix (final)

### Group A — Передать целиком (no filter):
ASOP_ROLES, ASOP_CARD_TYPES, ASOP_TARIFF_TYPES, ASOP_SESSION_TYPES, ASOP_EVENT_TYPES, ASOP_TRANSACTION_TYPES, ASOP_TRANSACTION_RESULTS, ASOP_VEHICLE_TYPES, ASOP_VEHICLE_MODELS, ASOP_ORGANIZERS, ASOP_ORGANIZER_TERRITORIES, ASOP_PATH_BENEFITS (через path-region слишком сложно — передавать целиком, terminal сам отбрасывает неактуальное).

### Group B — Filtered by region_id directly:
ASOP_TERRITORIES, ASOP_SERVICES, ASOP_BENEFITS, ASOP_BENEFIT_STEPS (через JOIN benefit), ASOP_FARE_ZONES, ASOP_TRANSPORT_STOPS, ASOP_ROUTES, ASOP_PATHS, ASOP_PATH_TRANSPORT_STOPS, ASOP_SCHEDULE, ASOP_REGIONS (=передать регион терминала + его territories, или весь ROLES ведь нoney).

### Group C — Filtered by carrier_id directly or via JOIN carrier:
ASOP_CARRIERS, ASOP_TIDS, ASOP_CONTRACTS (nullable carrierId + null carrierId ones всё равно отдать), ASOP_VEHICLES, ASOP_PATH_SERVICES, ASOP_PATH_DISCOUNTS, ASOP_TARIFF_RATES, ASOP_USER_CARRIERS, ASOP_CONTRACT_ROUTES (through contract).

### Group D — Cascade через user (orchestrator fetches userIds first):
ASOP_USERS (WHERE user_id IN users_of_my_carrier+region), ASOP_USER_ROLES (WHERE user_id IN ...), ASOP_USER_REGIONS (WHERE user_id IN ...), ASOP_CARDS, ASOP_CARD_MIFARES, ASOP_CARD_BANKS, ASOP_CARD_TARIFFS, ASOP_BLACKLISTS, ASOP_USER_BENEFITS (WHERE user_id IN ... OR benefit_id IN ...

[truncated для сокращения length — complete matrix already in Groups A–D выше]

### Group E — Filtered by region_id (terminal's region):
ASOP_USER_REGIONS.

### Group F — Both carrier_id AND region_id filter (logic dependent on type):
ASOP_TIDS (carrier_id) / ASOP_TERRITORIES (region_id).
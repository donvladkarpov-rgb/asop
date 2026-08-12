# Промпт 010 — Реальные SQL-фильтры для 7 таблиц дельты

## Контекст

Текущая проблема: 7 таблиц в `MasterRegistry` декларируют фильтр `regionId`/`carrierId`, но **SQL их игнорирует**. Терминалы получают данные соседних регионов.

**Решение**: Применить SQL-фильтры через JOIN там, где регион достижим через FK-цепочку. Не переводить в `GLOBAL_TABLES` — оставить фильтрацию по существу; backend будет использовать уже передаваемые orchestrator'ом параметры.

## Scope

**7 таблиц получают реальный SQL-фильтр через JOIN (region-only):**

| # | DB table | region source | Strategy |
|---|----------|---------------|----------|
| 1 | `asop_territories` | native `region_id` column | direct Criteria |
| 2 | `asop_organizers` | FK chain через `ASOP_ORGANIZER_TERRITORIES → ASOP_TERRITORIES.region_id` | `EXISTS` subquery |
| 3 | `asop_organizer_territories` | FK to `ASOP_TERRITORIES.region_id` | direct JOIN |
| 4 | `asop_benefit_steps` | FK to `ASOP_BENEFITS.region_id` | direct JOIN |
| 5 | `asop_contract_routes` | FK → `ASOP_CONTRACT_ROUTES.route_id → ASOP_ROUTES.region_id` | JOIN 2 hops |
| 6 | `asop_user_roles` | FK to `ASOP_USERS` → `EXISTS ASOP_USER_REGIONS.region_id` | JOIN + EXISTS |
| 7 | `asop_path_benefits` | FK to `ASOP_PATHS.route_id → ASOP_ROUTES.region_id` | JOIN 2 hops |

**Не трогаем**: `MasterRegistry.kt` (orchestrator уже передаёт `regionId`/`carrierId`, теперь backend их обработает).

**Не трогаем**: `asop_schema.sql` / `v001-init.sql` (все FK уже существуют).

## Файлы для изменений

### Backend

**admin-service:**
- `controller/TerritoryController.kt` — добавить `Criteria.where("region_id")` в `listDelta`
- `controller/OrganizerController.kt` + новый `repository/OrganizerRepository.kt` — `@Query` с `EXISTS` через `ASOP_ORGANIZER_TERRITORIES → ASOP_TERRITORIES`
- `controller/OrganizerTerritoryController.kt` + `repository/OrganizerTerritoryRepository.kt` — `@Query` JOIN `ASOP_TERRITORIES`
- `controller/BenefitStepController.kt` + `repository/BenefitStepRepository.kt` — `@Query` JOIN `ASOP_BENEFITS`

**route-service:**
- `controller/ContractRouteController.kt` + новый `repository/ContractRouteRepository.kt` — `@Query` JOIN `ASOP_ROUTES`
- `controller/PathBenefitController.kt` + новый `repository/PathBenefitRepository.kt` — `@Query` JOIN `ASOP_PATHS → ASOP_ROUTES`

**user-service:**
- `controller/UserRoleController.kt` + `repository/UserRoleRepository.kt` — `@Query` JOIN `ASOP_USERS + EXISTS ASOP_USER_REGIONS`

### Proto

- `backend/shared/asop-proto/src/main/proto/schema.proto` — добавить `asop_user_roles = 46`, `message UserRolesRow`, `message UserRolesFile` (отсутствует в proto — тишина на терминале)

### Android

- `frontend/android-terminal/.../sync/ReferenceSyncStore.kt` — добавить `"asop_user_roles"` в `COMPOSITE_KEY_TABLES` (PK = `userId|roleId`)

## SQL-фиксы (точные)

### 1. `asop_territories` — direct WHERE

```kotlin
// TerritoryController.kt listDelta
@GetMapping("/delta")
fun listDelta(
    @RequestParam(required = false) regionId: java.util.UUID?,
    @RequestParam(required = false) versionSince: Long?,
    @RequestParam(required = false) includeDeleted: Boolean,
    @RequestParam(required = false, defaultValue = "10000") limit: Int
): Flux<TerritoryEntity> {
    val extra = if (regionId != null) Criteria.where("region_id").`is`(regionId) else null
    return template.select(TerritoryEntity::class.java)
        .matching(DeltaSupport.query(versionSince, includeDeleted, limit, extra))
        .all()
}
```

### 2. `asop_organizers` — EXISTS

```kotlin
// OrganizerRepository.kt
@Query("""
    SELECT o.organizer_id   AS "organizerId",
           o.organizer_name AS "organizerName",
           o.created_at     AS "createdAt",
           o.updated_at     AS "updatedAt",
           o.deleted_at     AS "deletedAt",
           o.version        AS "version"
    FROM ASOP_ORGANIZERS o
    WHERE (:versionSince IS NULL OR o.VERSION > :versionSince)
      AND (:includeDeleted = TRUE OR o.DELETED_AT IS NULL)
      AND (
        :regionId IS NULL
        OR EXISTS (
          SELECT 1 FROM ASOP_ORGANIZER_TERRITORIES ot
          JOIN ASOP_TERRITORIES t ON t.TERRITORY_ID = ot.TERRITORY_ID
          WHERE ot.ORGANIZER_ID = o.ORGANIZER_ID
            AND t.REGION_ID = :regionId
        )
      )
    ORDER BY o.VERSION ASC
    LIMIT :limit
""")
fun findDelta(
    versionSince: Long?,
    includeDeleted: Boolean,
    limit: Int,
    regionId: UUID?
): Flux<OrganizerEntity>
```

### 3. `asop_organizer_territories` — JOIN 1 hop

```kotlin
@Query("""
    SELECT ot.ORGANIZER_ID AS "organizerId",
           ot.TERRITORY_ID AS "territoryId",
           ot.CREATED_AT    AS "createdAt",
           ot.UPDATED_AT    AS "updatedAt",
           ot.DELETED_AT    AS "deletedAt",
           ot.VERSION       AS "version"
    FROM ASOP_ORGANIZER_TERRITORIES ot
    JOIN ASOP_TERRITORIES t ON t.TERRITORY_ID = ot.TERRITORY_ID
    WHERE (:versionSince IS NULL OR ot.VERSION > :versionSince)
      AND (:includeDeleted = TRUE OR ot.DELETED_AT IS NULL)
      AND (:regionId IS NULL OR t.REGION_ID = :regionId)
    ORDER BY ot.VERSION ASC
    LIMIT :limit
""")
fun findDelta(versionSince: Long?, includeDeleted: Boolean, limit: Int, regionId: UUID?): Flux<OrganizerTerritoryEntity>
```

### 4. `asop_benefit_steps` — JOIN 1 hop

```kotlin
@Query("""
    SELECT bs.STEP_ID             AS "stepId",
           bs.BENEFIT_ID          AS "benefitId",
           bs.STEP_ORDER          AS "stepOrder",
           bs.TRIP_THRESHOLD_FROM AS "tripThresholdFrom",
           bs.TRIP_THRESHOLD_TO   AS "tripThresholdTo",
           bs.DISCOUNT_SHARE      AS "discountShare",
           bs.PERIOD_TYPE         AS "periodType",
           bs.CREATED_AT          AS "createdAt",
           bs.UPDATED_AT          AS "updatedAt",
           bs.DELETED_AT          AS "deletedAt",
           bs.VERSION             AS "version"
    FROM ASOP_BENEFIT_STEPS bs
    JOIN ASOP_BENEFITS b ON b.BENEFIT_ID = bs.BENEFIT_ID
    WHERE (:versionSince IS NULL OR bs.VERSION > :versionSince)
      AND (:includeDeleted = TRUE OR bs.DELETED_AT IS NULL)
      AND (:regionId IS NULL OR b.REGION_ID = :regionId)
    ORDER BY bs.VERSION ASC
    LIMIT :limit
""")
fun findDelta(versionSince: Long?, includeDeleted: Boolean, limit: Int, regionId: UUID?): Flux<BenefitStepEntity>
```

### 5. `asop_contract_routes` — JOIN 1 hop per route

```kotlin
@Query("""
    SELECT cr.CONTRACT_ID   AS "contractId",
           cr.ROUTE_ID      AS "routeId",
           cr.CREATED_AT    AS "createdAt",
           cr.UPDATED_AT    AS "updatedAt",
           cr.DELETED_AT    AS "deletedAt",
           cr.VERSION       AS "version"
    FROM ASOP_CONTRACT_ROUTES cr
    JOIN ASOP_ROUTES r ON r.ROUTE_ID = cr.ROUTE_ID
    WHERE (:versionSince IS NULL OR cr.VERSION > :versionSince)
      AND (:includeDeleted = TRUE OR cr.DELETED_AT IS NULL)
      AND (:regionId IS NULL OR r.REGION_ID = :regionId)
    ORDER BY cr.VERSION ASC
    LIMIT :limit
""")
fun findDelta(versionSince: Long?, includeDeleted: Boolean, limit: Int, regionId: UUID?): Flux<ContractRouteEntity>
```

### 6. `asop_user_roles` — JOIN + EXISTS region

```kotlin
@Query("""
    SELECT ur.USER_ID    AS "userId",
           ur.ROLE_ID    AS "roleId",
           ur.CREATED_AT AS "createdAt",
           ur.UPDATED_AT AS "updatedAt",
           ur.DELETED_AT AS "deletedAt",
           ur.VERSION    AS "version"
    FROM ASOP_USER_ROLES ur
    JOIN ASOP_USERS u ON u.USER_ID = ur.USER_ID
    WHERE (:versionSince IS NULL OR ur.VERSION > :versionSince)
      AND (:includeDeleted = TRUE OR ur.DELETED_AT IS NULL)
      AND (
        :regionId IS NULL
        OR EXISTS (
          SELECT 1 FROM ASOP_USER_REGIONS ur2
          WHERE ur2.USER_ID = u.USER_ID
            AND ur2.REGION_ID = :regionId
        )
      )
    ORDER BY ur.VERSION ASC
    LIMIT :limit
""")
fun findDelta(versionSince: Long?, includeDeleted: Boolean, limit: Int, regionId: UUID?): Flux<UserRoleEntity>
```

### 7. `asop_path_benefits` — JOIN 2 hops

```kotlin
@Query("""
    SELECT pb.PATH_BENEFIT_ID AS "pathBenefitId",
           pb.PATH_ID         AS "pathId",
           pb.BENEFIT_ID      AS "benefitId",
           pb.CREATED_AT      AS "createdAt",
           pb.UPDATED_AT      AS "updatedAt",
           pb.DELETED_AT      AS "deletedAt",
           pb.VERSION         AS "version"
    FROM ASOP_PATH_BENEFITS pb
    JOIN ASOP_PATHS p ON p.PATH_ID = pb.PATH_ID
    JOIN ASOP_ROUTES r ON r.ROUTE_ID = p.ROUTE_ID
    WHERE (:versionSince IS NULL OR pb.VERSION > :versionSince)
      AND (:includeDeleted = TRUE OR pb.DELETED_AT IS NULL)
      AND (:regionId IS NULL OR r.REGION_ID = :regionId)
    ORDER BY pb.VERSION ASC
    LIMIT :limit
""")
fun findDelta(versionSince: Long?, includeDeleted: Boolean, limit: Int, regionId: UUID?): Flux<PathBenefitEntity>
```

## Контроллеры (единый паттерн)

Каждый `*Controller.listDelta` через **уже существующий путь** `regionId` param + новый Repository:

```kotlin
// Pattern example
@GetMapping("/provider-path/delta")
fun listDelta(
    @RequestParam(required = false) versionSince: Long?,
    @RequestParam(required = false, defaultValue = "false") includeDeleted: Boolean,
    @RequestParam(required = false) regionId: java.util.UUID?,
    @RequestParam(required = false, defaultValue = "10000") limit: Int
): Flux<XxxEntity> = repository.findDelta(versionSince, includeDeleted, limit, regionId)
```

## Proto additions

```proto
// DeltaChunk:
optional repeated UserRolesRow asop_user_roles = 46;

message UserRolesRow {
  string user_id = 1;
  string role_id = 2;
  int64  created_at = 3;
  int64  updated_at = 4;
  int64  deleted_at = 5;
  int64  version = 6;
}

message UserRolesFile { repeated UserRolesRow rows = 1; }
```

## Android addition

```kotlin
// ReferenceSyncStore.kt COMPOSITE_KEY_TABLES
private val COMPOSITE_KEY_TABLES = setOf(
    "asop_organizer_territories",
    "asop_user_roles",              // NEW
    "asop_contract_routes",
    "asop_user_carriers",
    "asop_user_regions"
)
```

## Acceptance criteria

1. После `docker compose up -d --build`:
   - Terminal с `regionId=0103` отправляет `POST /sync/references/delta`.
   - В ответе `DeltaChunk.asop_organizer_territories` НЕ содержит rows с `territory.region_id=0101` или `0102`.
2. Proto поле #46 (`asop_user_roles`) присутствует в чанках; composite key `(user_id, role_id)`.
3. Smoke-test: `ASOP_USERS` row для SUPER_ADMIN Admin — терминал получает только admin roles из своего региона.

## Тестирование

```bash
# Phase 1 — Backend
./gradlew :backend:admin-service:bootJar :backend:route-service:bootJar :backend:user-service:bootJar :backend:shared:asop-proto:build

# Phase 2 — Reboot
docker compose -f infrastructure/docker/docker-compose.yml up -d --build admin-service route-service user-service orchestrator-service

# Phase 3 — Smoke
# запрос delta с regionId=0103 на терминале → проверить что asop_organizer_territories содержит только territory_ids с region_id=0103, НЕ 0101/0102

# Phase 4 — Verify proto field
# В Redis chunks проверить что появился field #46 (asop_user_roles) с правильным PostgreSQL limit/offset
```

## Комментарии для git history

```
Промпт 010: SQL-фильтр region для 7 таблиц дельты через JOIN

- asop_territories: direct Criteria region_id
- asop_organizers: EXISTS через organizer_territories→territories
- asop_organizer_territories: JOIN territories.region_id
- asop_benefit_steps: JOIN benefits.region_id
- asop_contract_routes: JOIN routes.region_id
- asop_user_roles: JOIN users + EXISTS user_regions.region_id
- asop_path_benefits: JOIN paths→routes.region_id

proto: добавлен asop_user_roles = 46 + UserRolesRow/File (был missing)
android: COMPOSITE_KEY_TABLES += "asop_user_roles"

Файлы изменены:
- backend/admin-service (Territory, Organizer, OrganizerTerritory, BenefitStep controllers + repositories)
- backend/route-service (ContractRoute, PathBenefit controllers + новые repositories)
- backend/user-service (UserRole controller + repository)
- backend/shared/asop-proto (schema.proto)
- frontend/android-terminal (ReferenceSyncStore)
```

# DeepSeek prompt: ASOP_TIDS в админке + меню терминала + timezone/regions/carriers при регистрации

## Проект

ASOP — Kotlin 2.0.21 + Spring Boot 3.3.5 (WebFlux, R2DBC) + PostgreSQL 14/PostGIS + Kafka + Keycloak 25.0.4 + Redis 7; всё в Docker (`infrastructure/docker/docker-compose.yml`). Ветка `develop`. Рабочая директория — `/home/vlad/IdeaProjects/asop`. **Обязательно** прочитай `AGENTS.md` и `doc/context.md` перед началом — там все архитектурные договорённости.

Ключевые соглашения (краткое из важного):
- **Все ID = UUIDv7** через `UuidUtils.newId()` (`:backend:shared:asop-common`, `ru.asop.common.util.UuidUtils`).
- **WebFlux, R2DBC, реактивщина везде**. `@Transactional` НЕ работает — использовать `TransactionalOperator.transactional(mono)`. `ReactiveCrudRepository.save()` с непустым `@Id` делает UPDATE; для новых сущностей — `R2dbcEntityTemplate.insert()`.
- **Liquibase** — единый `infrastructure/db-migrations/migrations/v001-init.sql` (~1587 строк, 67 таблиц). БД пересоздаётся через `docker compose down -v`, так что DDL можно дополнять в существующем файле (но предпочтительно — новый `v003-*.sql` changeset, подключённый через `db.changelog-master.yaml`; вместе с `asop_schema.sql` справочной копией).
- **API-модули** (`backend/shared/api/{domain}-api`) содержат только controller-интерфейс + DTO. Реализация в `{domain}-service`. Package: API = `ru.asop.api.{domain}`, service = `ru.asop.{domain}`.
- **Gateway dual-auth**: цепь Order(1) mTLS для `/api/v1/terminals/**` и `/api/v1/sync/**`; цепь Order(2) JWT для всего остального. Терминал аутентифицируется по mTLS (X.509), не имеет JWT.
- **Gateway async writes** (POST с явным контроллером): команда → Kafka, `202` + `X-Event-Id`, статус в Redis (key `asop:event:{eventId}`, TTL 24 ч). EventService реактивный (`Mono<Void>`).
- **Gateway sync proxy**: `GET` и необработанные `POST/PUT/DELETE` `ProxyController` пересылает в backend-сервисы; маппинг ресурса → base URL сервиса в `config/ServiceRegistry.kt`.
- **Android**: Kotlin + Jetpack Compose + Hilt + Room + WorkManager + Retrofit/OkHttp + Moshi. mTLS-auth через X.509 сертификат. `Settings.Secure.ANDROID_ID` = `terminalSerial`. `terminalId` (UUIDv7, ПК терминала в БД) персистится в DataStore через `SyncPreferences.setTerminalId(...)`.

---

# КОНТЕКСТ ТЕКУЩЕГО КОДА (читай перед правками)

## 1. ASOP_TIDS — текущее состояние

### DDL (`infrastructure/db-migrations/migrations/v001-init.sql` строки 839–855)
```sql
CREATE TABLE ASOP_TIDS
(
    TID_ID        UUID        NOT NULL,  -- UUIDv7
    CARRIER_ID    UUID        NOT NULL,
    TERMINAL_ID   UUID,
    TID_VALUE     VARCHAR(20) NOT NULL,
    STATUS        VARCHAR(20) DEFAULT 'UNUSED',
    ASSIGNED_AT   TIMESTAMPTZ,
    UNASSIGNED_AT TIMESTAMPTZ,
    CREATED_AT    TIMESTAMPTZ   NOT NULL,
    UPDATED_AT    TIMESTAMPTZ   NOT NULL,
    CONSTRAINT pk_tids PRIMARY KEY (TID_ID),
    CONSTRAINT fk_tids_carrier FOREIGN KEY (CARRIER_ID) REFERENCES ASOP_CARRIERS (CARRIER_ID),
    CONSTRAINT uq_tids_value UNIQUE (TID_VALUE),
    CONSTRAINT chk_tid_status CHECK (STATUS IN ('UNUSED', 'ASSIGNED', 'REVOKED'))
);
COMMENT ON TABLE ASOP_TIDS IS 'Пул TID. 1:N к перевозчику.';

-- later (lines 1562–1566):
DO $body$ BEGIN
    ALTER TABLE ASOP_TIDS ADD CONSTRAINT fk_tids_terminal
        FOREIGN KEY (TERMINAL_ID) REFERENCES ASOP_TERMINALS (TERMINAL_ID);
EXCEPTION WHEN duplicate_object THEN NULL;
END $body$;
```

`ASOP_TERMINALS` (строки 858–890) имеет колонки `CARRIER_ID UUID` (nullable, FK to `ASOP_CARRIERS`) и `TID_ID UUID` (nullable, FK to `ASOP_TIDS`). Двойная optional-связь terminal↔tid.

### Backend — НИЧЕГО НЕТ
Всё `backend/` не содержит ни entity, ни repository, ни service, ни DTO, ни API-модуля, ни controller для TID. Substring-попадания "tid" — ложнопозитивные (`certId`, `terminalId`). **Нужно создать TID-стек с нуля.**

### Web-admin — тоже нет
В `frontend/web-admin/src/` нет ни `TidApi`/`tidApi`, ни `TidsPage`. Есть только read-only `TerminalsPage` (`src/pages/Terminals.tsx`, 43 строки), который даже не показывает TID/carrier колонки.

## 2. Carrier- и Region-API на бэкенде — что уже есть

### `CarrierApi` (`backend/shared/api/carrier-api/.../controller/CarrierApi.kt`)
```kotlin
@RequestMapping("/api/v1/carriers")
interface CarrierApi {
    @GetMapping
    fun listCarriers(): Flux<CarrierResponse>     // ← БЕЗ @RequestParam!
    @PostMapping
    fun createCarrier(@Valid @RequestBody request: CarrierCreateRequest, principal: Mono<Principal>): Mono<ResponseEntity<CarrierResponse>>
    @PutMapping("/{id}")  fun updateCarrier(...)
    @GetMapping("/{id}")   fun getCarrier(...)
    @DeleteMapping("/{id}") fun deleteCarrier(...)
}
```

`CarrierResponse`: `id: UUID, carrierName: String, inn: String, regionId: UUID, createdAt: Instant, updatedAt: Instant`.
`CarrierCreateRequest`: `carrierName: String (@NotBlank), inn: String (@Pattern ^\d{10}$|^\d{12}$), regionId: UUID (@NotNull)`.
`CarrierUpdateRequest.regionId: UUID?` (nullable, для "не менять").

`CarrierRepository` это bare `ReactiveCrudRepository<CarrierEntity, UUID>` — **НЕТ `findByRegionId(...)`**. `CarrierService.findAll()` возвращает **ВСЕ перевозчики без фильтра**.

### `RegionApi` (`backend/shared/api/reference-api/.../controller/RegionApi.kt`)
```kotlin
@GetMapping
fun listRegions(): Mono<ResponseEntity<List<RegionResponse>>>
```
`RegionResponse`: `id: UUID, municipalDivision: String, adminDivision?, federalDistrict?, ifnsFlCode?, ifnsUlCode?, okatoCode?, oktmoCode?, oktmoBudgetCode?, fiasId?, registryRecordId?`. **timezone в DTO НЕТ** — хотя `RegionEntity.timezone: String = "UTC"` по умолчанию есть.

`TerritoryApi.listTerritories(@RequestParam(required = false) regionId: UUID?)` — фильтр по `regionId` поддержан (вызывает `repository.findByRegionId(regionId)`).

### Gateway proxy
`ServiceRegistry.kt` маппит `"carriers" → carrier-service:8087`, `"regions" → admin-service:8091`, `"territories" → admin-service:8091`. `ProxyController` пересылает `GET /api/v1/regions`, `GET /api/v1/carriers`. **Но** это пути chain-2 (JWT), а терминал под mTLS (chain-1) имеет доступ только к `/api/v1/terminals/**` и `/api/v1/sync/**` — `/regions` и `/carriers` для mTLS-терминала недоступны.

## 3. Terminal DTO и текущая регистрация

### `TerminalApi` (`backend/shared/api/terminal-api/.../controller/TerminalApi.kt`)
```kotlin
@PostMapping("/register")
fun registerTerminal(@Valid @RequestBody request: TerminalRegisterRequest, principal: Mono<Principal>): Mono<ResponseEntity<TerminalRegisterResponse>>

@GetMapping("/{id}")
fun getTerminal(@PathVariable id: UUID): Mono<ResponseEntity<TerminalResponse>>

@PutMapping("/{id}/status")
fun changeTerminalStatus(...)
```

### `TerminalRegisterRequest` — ТЕКУЩИЕ ПОЛЯ
```kotlin
data class TerminalRegisterRequest(
    @field:NotBlank @field:Size(max = 64) val terminalSerial: String,
    @field:Size(max = 16) val terminalNumber: String? = null,
    @field:Size(max = 100) val terminalModel: String? = null,
    val carrierId: UUID? = null,
    val terminalId: UUID? = null
)
```
**`timezone` НЕТ; `tidId` НЕТ.**

### `TerminalResponse`
```kotlin
data class TerminalResponse(
    val id: UUID,
    val terminalSerial: String,
    val terminalNumber: String?,
    val terminalModel: String?,
    val carrierId: UUID?,
    val status: String,
    val createdAt: Instant,
    val updatedAt: Instant
)
```
**`timezone` НЕТ; `tidId` НЕТ.**

### `TerminalRegisterResponse`
```kotlin
data class TerminalRegisterResponse(
    val terminal: TerminalResponse,
    val operationStatus: String,
    val errorMessage: String? = null
)
```

### `TerminalEntity` (`backend/terminal-service/.../model/TerminalEntity.kt`)
```kotlin
@Table("ASOP_TERMINALS")
data class TerminalEntity(
    @Id val terminalId: UUID,
    val carrierId: UUID?,
    val terminalNumber: String? = null,
    val terminalSerial: String,
    val terminalModel: String? = null,
    val status: String = "WAREHOUSE",
    val timezone: String? = null,                 // ← есть, но DTO не пробрасывает
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
)
```
**`tidId` В ENTITY НЕТ** — нужно добавить поле + маппинг на колонку `TID_ID`.

### `TerminalService.register(request)` (`backend/terminal-service/.../service/TerminalService.kt`)
```kotlin
fun register(request: TerminalRegisterRequest): Mono<TerminalRegisterResponse> {
    val now = Instant.now()
    return resolveTerminal(request)
        .flatMap { entity ->
            val updated = entity.copy(
                terminalSerial = request.terminalSerial,
                terminalNumber = request.terminalNumber,
                terminalModel = request.terminalModel,
                carrierId = request.carrierId,
                updatedAt = now
            )
            terminalRepository.save(updated)
                .map { TerminalRegisterResponse(terminal = it.toResponse(), operationStatus = "SUCCESS") }
        }
}
```
`resolveTerminal` (private): findById(terminalId) → switchIfEmpty findByTerminalSerial → switchIfEmpty insert new (`UuidUtils.newId()`, `status = "WAREHOUSE"`).

**`timezone` игнорируется в register()** — никогда не проставляется в `entity.copy(...)`. Нужно прокинуть из request.

## 4. Android-терминал — текущая структура

### Nav: `ui/TerminalNavHost.kt` (54 строки)
```kotlin
NavHost(navController, startDestination = "provisioning") {
    composable("provisioning") { ProvisioningScreen(onProvisioned = { navController.navigate("registration") }) }
    composable("registration") { RegistrationScreen(onRegistered = { navController.navigate("main") }) }
    composable("main") { MainScreen() }
}
```
При старте `LaunchedEffect(Unit)` skip-логика: если `certReady && terminalId != null` → `loadTerminal(id)` + `main`; если только `certReady` → `registration`; иначе `provisioning`.

### `ProvisioningScreen.kt` (76 строк)
Кнопки: "Сгенерировать ключи и запросить сертификат" → `viewModel.autoProvision()` (вызывает `certificateService.provision(androidId)`), "Импортировать PKCS#12" (TODO заглушка). `LaunchedEffect(state)` → `onProvisioned()` на `UiState.Ready`.

### `RegistrationScreen.kt` (91 строка)
`OutlinedTextField` "ANDROID ID (серийный номер)" read-only = `viewModel.androidId`. "Модель" (опц.), "Инвентарный номер" (обязательно). Кнопка "Зарегистрировать" → `viewModel.registerTerminal(model.ifBlank { null }, number.ifBlank { null })`. Никакого выбора региона/перевозчика нет.

### `MainScreen.kt` (201 строка)
`Scaffold` с `TopAppBar` (pending badge + sync switch). `LazyColumn` 3 карты: terminal info, sync status, GPS toggle. **Нет drawer, нет navigation rail, нет меню** — проверено grep'ом по `ModalDrawerSheet|NavigationSuite|NavigationRail|DrawerState` (0 совпадений).

### `TerminalViewModel.kt` (103 строки)
`UiState`: Idle / Provisioning / Registering / Ready / Registered(terminal) / Error(message).
- `autoProvision()` → `certificateService.provision(androidId)`.
- `registerTerminal(model, number)` — строит `TerminalRegisterRequest(terminalSerial = androidId, terminalNumber = number, terminalModel = model, terminalId = savedTerminalId)`, шлёт на `gatewayApi.registerTerminal(...)`, сохраняет `response.terminal.id` через `syncPreferences.setTerminalId(...)`.
- `loadTerminal(id)` → `gatewayApi.getTerminal(id)`.

`GatewayApi` (Android, `network/GatewayApi.kt`):
```kotlin
@POST("api/v1/terminals/register") suspend fun registerTerminal(@Body request: TerminalRegisterRequest): TerminalRegisterResponse
@GET("api/v1/terminals/{id}") suspend fun getTerminal(@Path("id") id: String): TerminalResponse
@PUT("api/v1/terminals/{id}/status") suspend fun changeTerminalStatus(...): TerminalResponse
@GET("api/v1/events/{eventId}") suspend fun getEventStatus(...): Response<EventStatusResponse>
```
**Нет endpoints для regions/carriers/territories.**

`network/models/TerminalModels.kt` (Android DTO):
```kotlin
data class TerminalRegisterRequest(
    @Json("terminalSerial") val terminalSerial: String,
    @Json("terminalNumber") val terminalNumber: String? = null,
    @Json("terminalModel")  val terminalModel: String? = null,
    @Json("carrierId")     val carrierId: String? = null,
    @Json("terminalId")    val terminalId: String? = null
)
data class TerminalRegisterResponse(...terminal: TerminalResponse, operationStatus, errorMessage?)
data class TerminalResponse(id, terminalSerial, terminalNumber?, terminalModel?, carrierId?, status, createdAt, updatedAt)
```
**Нет `timezone` field.**

### `SyncPreferences.kt` (63 строки, DataStore `"sync_preferences"`)
Персистит: `terminal_id`, `current_session_id`, `last_sync_time`, `sync_enabled`. **Нет region/carrier caching.**

### Auth Android
mTLS chain Order 1 на gateway открывает только `/api/v1/terminals/**` и `/api/v1/sync/**`. Так что `/api/v1/regions` и `/api/v1/carriers` сейчас недоступны с mTLS-терминала (отвечаютсяJWT-required → 401). Нужно либо открыть mTLS-доступ к этим путям, либо сделать отдельный mTLS-proxy endpoint-семейство (например, добавить `/api/v1/sync/regions` и `/api/v1/sync/carriers` на gateway, проксирующих в admin/carrier сервис).

## 5. web-admin — структура для добавления TID-страницы

### `src/api/client.ts` (27 строк) — axios инстанс
`baseURL = import.meta.env.VITE_API_URL || '/api/v1'`, Bearer-токен из `getAccessToken()` (oidc-client-ts), 401 → redirect `/login`.

### `src/hooks/useCommand.ts` — async-командный хук
`useCommand({ pollIntervalMs, maxPolls })` → `{ execute, pollResult, isPolling, eventId, cancel }`. `execute(cmdFn)` отправляет POST, ожидает `202 + AcceptedResponse`, потом polling `GET /api/v1/events/{eventId}`.

### `src/layouts/Sidebar.tsx` — меню (76 строк меню + CollapsibleSection)
Раздел **"Контрагенты"** (массив `contractorItems`):
```ts
const contractorItems = [
  { to: '/carriers',         label: 'Перевозчики',         icon: '🚌' },
  { to: '/cards-distributors', label: 'Дистрибьюторы карт', icon: '📦' },
  { to: '/contracts',         label: 'Договоры',           icon: '📄' },
  { to: '/contract-routes',   label: 'Связи договор-маршрут', icon: '🔗' },
];
```
Этот раздел `defaultOpen=true`. **Новый пункт "TID" нужно добавить рядом с "Перевозчики"** — по требованию пользователя.

### `src/pages/Carriers.tsx` (133 строки) — паттерн async-create CRUD
Использует `useQuery` для списка (`getCarriers`), `useQuery` для регионов (`getRegions`, для dropdown), `useCommand` для создания (async), `useMutation` для update/delete (sync). Пример для копирования.

### `src/pages/Regions.tsx` (89 строк) — канонический sync-CRUD паттерн
`useQuery` список + три `useMutation` (create/update/delete, sync), инвалидирует `['regions']`. Идеальный шаблон для TID-страницы (но TID-пул принадлежит перевозчику — нужно показать carrier-name колонку; crud-операции могут быть sync, как_regions).

### `src/api/carriers.ts` / `src/api/reference.ts` — API-модули (один файл на ресурс, ~15-30 строк `apiClient.get/post/put/delete`).

### `src/types/reference.ts` — DTO TypeScript
Region/Carrier/Territory/Organizer — `interface`-объекты.

### Роутинг: `src/App.tsx` (81–118) — `<Route path="..." element={<...Page />} />`

---

---

# ⚠️ АРХИТЕКТУРНОЕ ПРАВИЛО — НЕ ИСПОЛЬЗОВАТЬ KAFKA/REDIS ДЛЯ READ-ONLY СПРАВОЧНИКОВ

EventService (Redis `asop:event:{eventId}`) и Kafka-команды предназначены **только для write-операций**: async-команды (POST/PUT/DELETE), запись в БД, ack-подтверждение с результирующим `resultData`. Максимальный размер `resultData` по дизайну — десятки КБ (например, PEM-цепочка в cert-saga).

**Запрещено** тащить полный справочник регионов/перевозчиков через:
- `resultData` в EventService→Redis (TTL 24 ч, размер payload'a раздует Redis)
- Kafka-команду (producer → broker → consumer → redis → events endpoint) — лишний round-trip, ограничение `max.message.bytes` default 1 МБ на broker/топик (4000+ Carrier'ов ~1.25–2.5 МБ — не влезет), конфигурация broker'а и продюсера ломается, нет use-case для async.

**Только sync-proxy**:
- Android терминал → `GET /api/v1/regions` → gateway `ProxyController` → admin-service `RegionController.listRegions()` → R2DBC `findAll()` → JSON list сразу в ответ. Без Kafka, без Redis, без 202+polling.
- Android терминал → `GET /api/v1/carriers?regionId=…` → gateway `ProxyController` → carrier-service `CarrierController.listCarriers(regionId)` → `findByRegionId(regionId)` (после реализации задачи D.2) → JSON list сразу в ответ.

Проверка: для справочников вплата запрос/ответ = обычный HTTP GET. Никаких `useCommand`/`createPending`/`eventId`/polling. EventService не должен видеть eventId'ов, связанных с GET-запросами справочников.

**Исключение** — если позже потребуется delta-sync или push-update справочника (Carrier добавился → реализовать рассылку): тогда отдельная Kafka тема `asop.reference.updates` (не command-topics, не EventService). В рамках этой задачи — **не реализуем**, только sync GET.

---

# ЗАДАЧИ

## Задача A: TID administration в web-admin (пункт меню рядом с перевозчиками)

### A.1. Backend — создать TID-стек с нуля

Создать новый API-модуль `:backend:shared:api:tid-api` (package `ru.asop.api.tid`) и добавить новую реализацию в **carrier-service** (TID принадлежит перевозчику 1:N, FK `ASOP_TIDS.CARRIER_ID → ASOP_CARRIERS`).

Не забыть: зарегистрировать модуль в `settings.gradle.kts` (в `include(":backend:shared:api:tid-api")`) и добавить `implementation(project(":backend:shared:api:tid-api"))` в `backend/carrier-service/build.gradle.kts`.

#### A.1.1. DTO (в `tid-api`):
```kotlin
data class TidCreateRequest(
    @field:NotNull val carrierId: UUID,
    @field:NotBlank @field:Size(max = 20) val tidValue: String
    // terminalId не задаётся при создании; STATUS всегда 'UNUSED' по умолчанию
)
data class TidUpdateRequest(
    val carrierId: UUID? = null,    // сменить владельца-перевозчика
    val tidValue: String? = null,   // @Size(max = 20)
    val status: String? = null,     // UNUSED | ASSIGNED | REVOKED
    val terminalId: UUID? = null    // назначить/отвязать терминал (null — отвязать)
)
data class TidResponse(
    val id: UUID,
    val carrierId: UUID,
    val terminalId: UUID?,
    val tidValue: String,
    val status: String,
    val assignedAt: Instant?,
    val unassignedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant
)
```

#### A.1.2. `TidApi` controller-интерфейс:
```kotlin
@RequestMapping("/api/v1/tids")
interface TidApi {
    @GetMapping
    fun listTids(@RequestParam(required = false) carrierId: UUID?): Flux<TidResponse>
    @GetMapping("/{id}")
    fun getTid(@PathVariable id: UUID): Mono<ResponseEntity<TidResponse>>
    @PostMapping
    fun createTid(@Valid @RequestBody request: TidCreateRequest): Mono<ResponseEntity<TidResponse>>
    @PutMapping("/{id}")
    fun updateTid(@PathVariable id: UUID, @Valid @RequestBody request: TidUpdateRequest): Mono<ResponseEntity<TidResponse>>
    @DeleteMapping("/{id}")
    fun deleteTid(@PathVariable id: UUID): Mono<ResponseEntity<Void>>
}
```
Внимание: `listTids(carrierId)` **поддерживает фильтр по `carrierId`** — нужно для UI страницы TID (показать TIDs конкретного перевозчика). Это sync-CRUD (POST/PUT/DELETE — sync, как у Regions).

#### A.1.3. Реализация в `carrier-service`:
- `model/TidEntity.kt`: `@Table("ASOP_TIDS")` data class, `@Id val tidId: UUID`, остальные колонки из DDL. Для новых сущностей использовать `R2dbcEntityTemplate.insert()` (НЕ `save()` — save bug по AGENTS.md). В `createdAt`/`updatedAt` проставить `Instant.now()` (колонки `NOT NULL`, дефолт `now()` тоже работает).
- `repository/TidRepository.kt`: `@Repository interface TidRepository : ReactiveCrudRepository<TidEntity, UUID> { fun findByCarrierId(carrierId: UUID): Flux<TidEntity> }`.
- `service/TidService.kt`: `list(carrierId)` → если `carrierId != null` → `findByCarrierId`, иначе `findAll`. `create` → `insert` via `r2dbcTemplate`. `update` — найти, применить поля, `save()` (save == UPDATE). При смене `status` или `terminalId` корректно обновлять `ASSIGNED_AT`/`UNASSIGNED_AT` (например, при `ASSIGNED` — `Instant.now()`; при `UNUSED/REVOKED` — `unassignedAt = now()`). `delete` → `deleteById`.
- `controller/TidController.kt`: implements `TidApi`, делегирует в service.
- `ExceptionHandler` (carrier-service уже имеет `@RestControllerAdvice` mapping `IllegalArgumentException`/`IllegalStateException`/`DataIntegrityViolationException` → 400 — использовать для валидаций).
- **Carrier-service build.gradle.kts**: добавить `implementation(project(":backend:shared:api:tid-api"))` (и сюда, и в build.gradle.kts tid-api, и в settings.gradle.kts — зарегистрировать новый модуль).

#### A.1.4. Gateway proxy
В `backend/gateway-service/.../config/ServiceRegistry.kt` добавить `"tids" to svc("carrier-service", 8087)`. Тогда `GET/POST/PUT/DELETE /api/v1/tids/**` автоматически пойдёт через `ProxyController` в carrier-service. Никакой отдельный async-Kafka controller не нужен (TID sync-CRUD).

#### A.1.5. Схема: никаких новых миграций не требуется — таблица `ASOP_TIDS` уже в `v001-init.sql` (839–855 + FK 1562–1566). Только проверить, что `CREATED_AT`/`UPDATED_AT` колонки — они есть (`NOT NULL` без DEFAULT, но сервис проставляет `Instant.now()`).

### A.2. web-admin — страница + пункт меню

#### A.2.1. `src/api/tids.ts` (новый, по образцу `src/api/reference.ts`):
```ts
export const getTids = (carrierId?: string) =>
  apiClient.get<Tid[]>('/tids', carrierId ? { params: { carrierId } } : undefined).then(r => r.data);
export const getTid = (id: string) => apiClient.get<Tid>(`/tids/${id}`).then(r => r.data);
export const createTid = (data: Pick<Tid, 'carrierId' | 'tidValue'>) => apiClient.post<Tid>('/tids', data).then(r => r.data);
export const updateTid = (id: string, data: Partial<Tid>) => apiClient.put<Tid>(`/tids/${id}`, data).then(r => r.data);
export const deleteTid = (id: string) => apiClient.delete(`/tids/${id}`);
```
(Sync CRUD — НЕ использовать `useCommand`/async — просто `useMutation`.)

#### A.2.2. `src/types/reference.ts` — добавить интерфейс:
```ts
export interface Tid {
  id: string;
  carrierId: string;
  terminalId?: string;
  tidValue: string;
  status: 'UNUSED' | 'ASSIGNED' | 'REVOKED';
  assignedAt?: string;
  unassignedAt?: string;
  createdAt: string;
  updatedAt: string;
}
```

#### A.2.3. `src/pages/Tids.tsx` (новый, паттерн — `Regions.tsx` sync CRUD + фильтр carrierId по образцу `Territories.tsx`):
- Список TID в таблице: `TID_VALUE | Carrier (name) | Terminal (id, optional, link на /terminals) | STATUS | ASSIGNED_AT | UNASSIGNED_AT | actions (edit/delete)`.
- Carrier-filter dropdown сверху (использует `getCarriers` из `api/carriers.ts`), необязателен — если не выбран, показывать все.
- Форма создания/редактирования (inline card, как в `Regions.tsx`): поля `carrierId` (select из списка — обязателен), `tidValue` (текст ∈ VARCHAR(20), обязательно), `status` (select для редактирования — для создания всегда `UNUSED`), `terminalId` (optional, UUID-строка — для назначения терминала).
- API-вызовы через `useMutation` (НЕ async useCommand). Инвалидация `['tids']` после операции.

#### A.2.4. Sidebar (`src/layouts/Sidebar.tsx`), массив `contractorItems` — **добавить пункт сразу после "Перевозчики"**:
```ts
const contractorItems = [
  { to: '/carriers',          label: 'Перевозчики',         icon: '🚌' },
  { to: '/tids',              label: 'TID (пулы)',          icon: '🔑' },   // ← новый пункт рядом с перевозчиками
  { to: '/cards-distributors', label: 'Дистрибьюторы карт', icon: '📦' },
  { to: '/contracts',         label: 'Договоры',           icon: '📄' },
  { to: '/contract-routes',   label: 'Связи договор-маршрут', icon: '🔗' },
];
```

#### A.2.5. `src/App.tsx` — добавить роут:
```tsx
<Route path="tids" element={<TidsPage />} />
```

---

## Задача B: Android-меню (постоянный доступ к сертификату/регистрации/привязке)

### B.1. Drawer/Navigation (текущего нет — нужно создать)

Реализовать **NavigationDrawer** (Jetpack Compose `ModalNavigationDrawer` или `PermanentNavigationDrawer` для tablet, `ModalDrawerSheet` со списком). Либо (проще для phone-формфактора) — иконка hamburger в TopAppBar. Пользователь явно требует "перманентный доступ". Рекомендую:

**`ModalNavigationDrawer`** в `MainActivity` (или в новом `TerminalScaffold` wrapper, обёртывающем все экраны), со списком:
- **"Сгенерировать сертификат"** → переход на `provisioning` экран ( stimulates re-provisioning; явно перeвызов `autoProvision()` кнопкой — НЕ авто-вызов).
- **"Зарегистрировать терминал"** → переход на `registration` экран.
- **"Привязать терминал к перевозчику"** → переход на новый экран `assign-carrier` (см. задачу C).
- (Опционально) текущий статус: серийный номер (ANDROID_ID), terminalId (если есть), статус терминала.

NavHost переходит с линейного `provisioning → registration → main` на граф, где `main` (dashboard) — стартовый, а три экрана выше доступны из drawer-меню (popUpTo main, чтобы не ломать backstack).

### B.2. Меню "Создать сертификат" — сохраняет текущий `ProvisioningScreen` флоу
Кнопка на `ProvisioningScreen` остаётся. Drawer-пункт просто навигирует на `provisioning`. Важно: при уже готовом сертификате повторная генерация должна спросить confirmation (alert "Сертификат уже установлен. Пере-выпустить?") и, при подтверждении: `mtlsManager` очистить cert (удалить PEM из SharedPreferences + удалить alias в AndroidKeyStore), потом `autoProvision()`. Это заменяет/supersedes существующий сертификат.

### B.3. Меню "Зарегистрировать терминал" — текущий `RegistrationScreen` остаётся (с доработками Задачи D).

---

## Задача C: Android — экран "Привязать терминал к перевозчику" (новый)

Пользователь хочет: "перевозчика выбирать из списка из таблицы перевозчиков на терминале". То есть на терминале хранится/кэшируется список перевозчиков (стянуть с сервера), пользователь выбирает одного — backend обновляет `ASOP_TERMINALS.CARRIER_ID` для текущего terminalId.

### C.1. Backend — частично переиспользовать существующее
`TerminalService.register(...)` уже умеет обновлять `carrierId` для существующего terminal. Но для чистовой операции "только привязать" проще:
- **Вариант 1 (предпочтительный)**: добавить отдельный endpoint `PUT /api/v1/terminals/{id}/carrier` с телом `{ "carrierId": "UUID" }` (или `null` для отвязки). Реализовать в `TerminalApi` + `TerminalController` + `TerminalService.assignCarrier(id, carrierId)`. Это безопаснее, чем дёргать регистрицию только ради carrierId.
- **Вариант 2**: переиспользовать `POST /api/v1/terminals/register` (уже принимает `carrierId`), но это тяжеловесно для UI (UX = "выбрать перевозчика и нажать Save", а не "ре-регистрироваться").

Реализовать **Вариант 1**.

### C.2. Android — новый экран `AssignCarrierScreen`
- В `GatewayApi.kt`: добавить `@PUT("api/v1/terminals/{id}/carrier") suspend fun assignCarrier(@Path("id") id: String, @Body request: TerminalCarrierAssignRequest): TerminalResponse`. Создать DTO `TerminalCarrierAssignRequest(@Json("carrierId") val carrierId: String?)`.
- Получить список перевозчиков **в регионе терминала** — нужен endpoint list с фильтром. См. задачу D (сейчас `GET /api/v1/carriers` без фильтра).
- UI: dropdown "Перевозчик" (selected default = текущий `terminalInfo.carrierId`, если задан), кнопка "Сохранить" → `viewModel.assignCarrier(carrierId)`, который вызывает `gatewayApi.assignCarrier(terminalId, ...)`, обновляет `terminalInfo`.
- Список перевозчиков (модель `CarrierOption { id, carrierName, inn, regionId }`) — получить через новый `GatewayApi` endpoint `GET /api/v1/sync/carriers?regionId=...` (см. задачу D про mTLS-проксирование).

### C.3. Persistence (опционально)
Можно закэшировать выбранный carrierId в `SyncPreferences` (`setCarrierId(carrierId)`) — не критично, но полезно для offline-init. Решить по ходу.

---

## Задача D: Android-регистрация — timezone + region + carrier

### D.1. Backend `_timezone_` в TerminalRegisterRequest/Response и entity

`TerminalEntity` уже имеет `timezone: String?`. Только DTO не пробрасывает.

- `TerminalRegisterRequest`: добавить `val timezone: String? = null` (валидация `@Size(max = 50)` — колонка `VARCHAR(50)`).
- `TerminalService.register(...)`: в `entity.copy(...)` добавить `timezone = request.timezone ?: entity.timezone` (сохранять существующий, если request не передал).
- `TerminalResponse`: добавить `val timezone: String?`.
- `TerminalEntity.toResponse()`: прокинуть `timezone = timezone`.
- Android `TerminalModels.kt` (`TerminalRegisterRequest`/`TerminalResponse`): зеркально добавить `@Json("timezone") val timezone: String? = null`.

### D.2. Backend — region filtered carriers + Terminal timezone hint

#### D.2.1. Carrier-фильтр по regionId (нужно сделать)
В `CarrierApi.listCarriers()` добавить `@RequestParam(required = false) regionId: UUID?`. В `CarrierService` — метод `findAll(regionId: UUID?)`. В `CarrierRepository` — `findByRegionId(regionId: UUID): Flux<CarrierEntity>`. В `CarrierController.listCarriers(regionId)` — если не null, фильтровать. Аналогично `TerritoryApi` (там уже работает).

#### D.2.2. Timezone из региона (опционально, для UX-comfort)
`Region.timezone` (= "UTC" по умолчанию). Если пользователь на Android выбрал регион — предложить timezone по умолчанию из `RegionResponse.timezone`. Для этого нужно:
- Добавить `timezone: String?` в `RegionResponse` (сейчас отсутствует — entity имеет, DTO нет). Опционально — если не делать, просто hardcode-предлагать device timezone через `TimeZone.getDefault().id` на Android (это проще).

Решение: **поле `timezone` в `RegionResponse` НЕ добавлять** (минимизировать surface). Android на стороне берёт `TimeZone.getDefault().id` как default для picker'а. Если позже понадобится region→timezone — добавим.

### D.3. Gateway — открыть mTLS-доступ к справочникам для терминала

Терминал аутентифицируется только mTLS (chain Order 1 → `/api/v1/terminals/**` и `/api/v1/sync/**`). `/api/v1/regions`/`/api/v1/carriers` под chain 2 (JWT), mTLS-терминал получит 401.

**Решение (выбрать одно):**

- **Вариант A (минимальный surface, рекомендуется)**: в `ServiceRegistry.kt` уже есть маппинги `regions`, `carriers`. В `ProxyController` (chain 2 — JWT) пропускаем auth-проверку для `GET` запросов на `regions`/`carriers` (chain 2 уже `permitAll` для `OPTIONS /**`, но для `GET /api/v1/regions` нужно JWT). Добавить в `SecurityConfig` chain 1 (mTLS): **расширить matchers** — добавить `pathMatchers(HttpMethod.GET, "/api/v1/regions/**", "/api/v1/carriers/**").permitAll()` (либо `authenticated()` через mTLS principal, если хотим логгировать какой терминал читает справочник). Это безопасно: GET справочников регионов/перевозчиков — не sensitive data. Терминал просто читает public реестр.
- В `SecurityConfig` Order 1 (mTLS) `paths` добавить: `/api/v1/regions`, `/api/v1/carriers` (GET only) → `permitAll`. Всё остальное для этих ресурсов остаётся chain 2 (JWT).

**Реализовать Вариант A.**

### D.4. Android — GatewayApi эндпоинты + RegistrationScreen UI

#### D.4.1. `network/GatewayApi.kt` — добавить:
```kotlin
@GET("api/v1/regions")
suspend fun listRegions(): List<RegionResponse>

@GET("api/v1/carriers")
suspend fun listCarriers(@Query("regionId") regionId: String?): List<CarrierResponse>
```

#### D.4.2. `network/models/ReferenceModels.kt` (новый):
```kotlin
@JsonClass(generateAdapter = true)
data class RegionResponse(
    @Json(name = "id") val id: String,
    @Json(name = "municipalDivision") val municipalDivision: String,
    @Json(name = "adminDivision") val adminDivision: String? = null,
    @Json(name = "federalDistrict") val federalDistrict: String? = null
    // другие поля (ifnsFlCode, okatoCode, fiasId, ...) опциональны — для picker нужно только id + municipalDivision
)

@JsonClass(generateAdapter = true)
data class CarrierResponse(
    @Json(name = "id") val id: String,
    @Json(name = "carrierName") val carrierName: String,
    @Json(name = "inn") val inn: String,
    @Json(name = "regionId") val regionId: String
)
```

#### D.4.3. `TerminalViewModel` — загрузка справочников:
```kotlin
val regions: StateFlow<List<RegionResponse>> = ...
val carriers: StateFlow<List<CarrierResponse>> = ...
fun loadReferenceData() {
    viewModelScope.launch {
        _regions.value = gatewayApi.listRegions()
    }
}
fun loadCarriersForRegion(regionId: String) {
    viewModelScope.launch {
        _carriers.value = gatewayApi.listCarriers(regionId)
    }
}
```

##### D.4.4. `RegistrationScreen.kt` — добавить поля (сверху вниз):
1. ANDROID ID (read-only) — есть.
2. **Регион** (ExposedDropdownMenuBox или RadioGroup) — обязателен. Загружается `viewModel.loadReferenceData()` при входе на экран. После выбора региона автоматически грузятся перевозчики в этом регионе.
3. **Перевозчик** (ExposedDropdownMenuBox) — обязателен. Доступен только после выбора региона. Список = перевозчики в выбранном регионе.
4. **Часовой пояс** (ExposedDropdownMenuBox из `java.util.TimeZone.getAvailableIDs()` ИЛИ просто текстовое поле с дефолтом `TimeZone.getDefault().id`). Опционально или обязательно — решить при реализации; пользователь сказал "добавить часовой пояс терминала и передать его на сервер" — **сделать обязательным**, default = device timezone.
5. Модель (опционально) — есть.
6. Инвентарный номер (обязательно) — есть.

Кнопка "Зарегистрировать" дизейблится, пока не выбраны region/carrier/timezone/inventory.

#### D.4.5. `TerminalViewModel.registerTerminal(regionId, carrierId, timezone, model, number)`:
- Передавать в `TerminalRegisterRequest` новое поле `carrierId = selectedCarrierId` и `timezone = selectedTimezone`.
- existing `terminalSerial = androidId`, `terminalId = savedTerminalId` остаются.

---

# ПРОВЕРКА И СБОРКА

## Backend
- `./gradlew build -x test` — все модули (теперь +1 tid-api) должны собраться.
- `docker compose -f infrastructure/docker/docker-compose.yml down -v`
- `docker compose -f infrastructure/docker/docker-compose.yml up -d --build`
- `docker compose -f infrastructure/docker/docker-compose.yml logs -f --tail=50 gateway-service carrier-service terminal-service admin-service redis` — нет StackTrace, Gateway подключился к Redis (см. "Event cleanup scheduled" / нет redis-ошибок).

## Smoke-тесты ручные

1. **web-admin TID**: логин в админку (https://localhost:3443), открыть "Контрагенты → TID (пулы)". Создать TID для существующего перевозчика (сначала нужен перевозчик — создать через "Перевозчики"). Убедиться, что `STATUS = UNUSED`. Отредактировать → set `STATUS = ASSIGNED` + `terminalId` (если есть терминал). Удалить.

2. **Android-меню**: собрать APK (`cd frontend/android-terminal && ./gradlew :app:assembleDebug`), установить на эмуляторе. Drawer должен открываться (swipe-from-left либо hamburger в TopAppBar). Пункты:
   - "Сгенерировать сертификат" → ProvisioningScreen → кнопка генерирует ключ + отправляет cert-sign → poll → success.
   - "Зарегистрировать терминал" → RegistrationScreen, видны dropdown'ы регион → перевозчик → timezone → инв.номер → модель.
   - "Привязать терминал к перевозчику" → AssignCarrierScreen → выбрать перевозчика → Save → backend `PUT /api/v1/terminals/{id}/carrier`.

3. **Регистрация с регионом/перевозчиком/timezone**: открыть TerminalService → проверить, что в БД `ASOP_TERMINALS.CARRIER_ID`, `ASOP_TERMINALS.TIMEZONE` сохранены. Через `TerminalResponse` вернуть эти поля.

4. **Логи**: `docker compose -f infrastructure/docker/docker-compose.yml logs -f gateway-service` — `GET /api/v1/regions` из mTLS-сессии терминала НЕ должен возвращать 401.

## Документация
- **AGENTS.md**: обновить раздел endpoints (добавить TID-страницу, `/api/v1/tids/**`, `PUT /api/v1/terminals/{id}/carrier`, timezone в DTO). Обновить секцию Android (drawer-меню, новый экран assign-carrier, region/carrier picker на registration, timezone).
- **doc/context.md**: обновить "Endpoints (реализовано)" таблицы (Carrier-service, Terminal-service). Добавить пункт про Android drawer-меню в раздел 9 "Frontend".
- **doc/architecture.md**: обновить тех. стек (если ничего не меняется — пропустить), обновить таблицы endpoints и Android-описание.
- **doc/todo.md**: отметить новые задачи, которые выполнены.
- **infrastructure/db-migrations/asop_schema.sql**: справочная копия DDL — если что-то добавлял в `v001-init.sql` (НЕ должен в этой задаче — таблица уже есть), синхронизировать. В этой задаче схема не меняется.

---

# УТОЧНЕНИЯ (если что-то непонятно — спроси у пользователя в PR-description, не додумывай)

1. **Дизайн drawer**: `ModalNavigationDrawer` (swipe-from-left) или hamburger-button в TopAppBar — на твоё усмотрение. Рекомендую `ModalNavigationDrawer` (стандартный Material 3 паттерн).
2. **Territory**: «выбрать регион → выкачать справочник перевозчиков в регионе». Если требовалась территория (sub-region) как дополнительный уровень — уточнить у пользователя. Сейчас задача только про region → carrier.
3. **TID + terminal link в админке**: "TID (пулы)" страница должна позволять назначить TID конкретному терминалу (по `tidId` на `ASOP_TERMINALS`), но это можно сделать и со стороны терминала (PUT `/api/v1/terminals/{id}/status` — нет, нужен `PUT /api/v1/terminals/{id}/tid`). Если нужно — реализовать. По умолчанию — **только CRUD на TID с optional terminalId** (можно привязать через update tid, указав `terminalId`).
4. **Безопасность mTLS для /regions, /carriers**: открыл GET для этих ресурсов под chain 1 (mTLS) `permitAll` — не `authenticated()`. Альтернатива — `authenticated()` с mTLS principal (X.509 CN). Выбрано `permitAll` как проще и безопаснее (только GET, public справочники).

---

# ИТОГ

| Задача | Где меняется |
|--------|-------------|
| A.1 TID backend | новый `tid-api` модуль + `carrier-service` (entity/repo/service/controller) + `settings.gradle.kts` + `ServiceRegistry` |
| A.2 TID админка | `web-admin` новый `src/pages/Tids.tsx`, `src/api/tids.ts`, type в `reference.ts`, Sidebar + App.tsx |
| B Drawer/menú в Android | `MainActivity` или wrapper-scaffold, `ModalNavigationDrawer`, обновить `TerminalNavHost` |
| C Assign carrier screen | Android `AssignCarrierScreen`, `TerminalViewModel.assignCarrier`, backend `PUT /api/v1/terminals/{id}/carrier` + `TerminalCarrierAssignRequest` DTO |
| D.1 Timezone в DTO | `TerminalRegisterRequest`/`Response`/entity mapping/`TerminalService.register` |
| D.2 Carrier filter по regionId | `CarrierApi` + `CarrierController`/`CarrierService`/`CarrierRepository` |
| D.3 Gateway security | `SecurityConfig` chain 1 — `permitAll` GET `/api/v1/regions`, `/api/v1/carriers` |
| D.4 Android registration UI | `RegistrationScreen` (region/carrier/timezone), `GatewayApi` (listRegions/listCarriers), `ReferenceModels.kt` |
| Docs | `AGENTS.md`, `doc/context.md`, `doc/architecture.md`, `doc/todo.md` |

Порядок выполнения: **A → D.1 + D.2 + D.3 → C → B → D.4 → сборка → docs**. После каждой большой группы — `./gradlew build -x test` для проверки компиляции.

## Финальные требования

1. **Не коммитить автоматически** — пользователь явно попросит commit через диалог с языковой моделью после ревью.
2. **Стиль кода**: следуй соседним сервисам и страницам. Kotlin — идиоматичный, без beacon-комментариев (только meaningful javadoc на API). React — follow `Regions.tsx`/`Carriers.tsx` паттернам.
3. **Все ID — UUIDv7** через `UuidUtils.newId()`.
4. **Никаких `Mono.block()` в WebFlux** — только в Kafka-listener void-method (если нужно — как `CertCommandService`).
5. **Build verification** обязателен: `./gradlew build -x test` и Android `:app:compileDebugKotlin` — оба должны проходить. Если что-то падает — починить и пере-верить.
6. **Запусти `docker compose -f infrastructure/docker/docker-compose.yml up -d --build`** после backend-правок — проверь логи gateway/redis/terminal/carrier/admin на отсутствие StackTrace. Это сделай в конце.
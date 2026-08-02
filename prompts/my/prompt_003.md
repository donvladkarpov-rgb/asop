# Доработка

**Порядок выполнения:** сначала 1) и 5) (003_02 → 003_01, строго последовательно), затем 2)–4), в конце — Тестирование.

1) выполни промпт prompts/my/prompt_003_02.md — замена дельта-курсора `UPDATED_AT (timestamp)` → `VERSION (sequence)` во всём дельта-стеке (БД + пересоздание миграции + proto + 42 мастера + контракты Kafka + gateway + Android Room).

2) Для поля `UPDATED_AT` в таблицах/справочниках сделать триггер, который автоматически заполняет поле датой при INSERT и UPDATE. Значение, прописанное в INSERT или UPDATE, игнорировать.
   - Текущее состояние: уже есть `trg_fn_touch_updated()` (`asop_schema.sql:1661`), но триггеры `trg_touch_updated_*` вешаются только на `BEFORE UPDATE` (`asop_schema.sql:1765`). На INSERT он не срабатывает.
   - Требование: заменить `BEFORE UPDATE` на `BEFORE INSERT OR UPDATE` (функция уже перезаписывает `NEW.updated_at := now()`, т.е. игнорирует переданное значение).
   - **Этот пункт входит в этап 1 промпта 003_02** (правки в `asop_schema.sql`), отдельно его выполнять не нужно — в 003_02 уже заложено.

3) Сделать `ContentProvider` внутри android-terminal (с query API) для всех таблиц, которые есть в его базе (6 сущностей Room: `PendingEventEntity`, `SessionEntity`, `TransactionEntity`, `SyncMetaEntity`, `DeltaSyncJobEntity`, `ReferenceRowEntity`).
   - **Доступ закрыть `android:permission="<signature-permission>"`**: экспортировать провайдер только для приложений, подписанных тем же ключом. android-test подписывается тем же debug-ключом — доступ будет работать.

4) На терминале при регистрации пользователь вводит регион, перевозчика и таймзону. Необходимо их сохранить в базе терминала и передавать во **всех** запросах к серверу. Ресолв региона и перевозчика из gateway убрать. В `doc/architecture.md`, `doc/context.md` и `AGENTS.md` зафиксировать, что gateway только для авторизации, аутентификации и проксирования, бизнес-логики там нет и быть не может.

   Объём (терминал → gateway):
   - **Регистрация**: `TerminalRegisterRequest` в **трёх местах** — Android (`TerminalModels.kt`), gateway-api (`backend/shared/api/gateway-api/.../dto/request/TerminalRegisterRequest.kt`), terminal-api (`backend/shared/api/terminal-api/.../dto/request/TerminalRegisterRequest.kt`). В gateway-api и Android добавить `regionId: UUID?/String?` (в terminal-api `regionId` тоже добавить, если нет). `timezone` уже есть в Android/terminal-api, в gateway-api — добавить. `carrierId` уже есть везде. Gateway-прокси просто транзитом передаёт тело — DTO gateway должно принимать все поля, которые шлёт терминал.
   - **Сохранение на терминале**: `SyncPreferences` хранит `regionId`, `carrierId`, `timezone` (новые ключи + геттеры/сеттеры). Заполняются при регистрации (из `RegistrationScreen`, где пользователь уже вводит регион/перевозчика/таймзону).
   - **Delta/full** (`/api/v1/sync/references/delta`, `/full`): `DeltaSyncRequest`/`FullSyncRequest` получают `carrierId`, `regionId`. Терминал берёт их из `SyncPreferences` (это уже заложено в 003_02, этапы 5–6 — согласовать, не дублировать). Gateway **перестаёт** вызывать `TerminalResolver` — берёт `carrierId/regionId` прямо из запроса (сделано в 003_02).
   - **Все 10 sync-командных эндпоинтов** (`/api/v1/sync/sessions/open`, `/close`, `/transactions`, `/cards/register`, `/cards/{id}/block`, `/debts`, `/debts/{id}/recover`, `/fiscal/receipts`, `/audit/tasks`, `/gps/positions`): в request-DTO добавить `regionId`, `carrierId`, `timezone` (nullable, чтобы старые/простые случаи не ломались). Android-модели (`SyncModels.kt` и др.) — соответствующие поля. Сервисы-получатели могут игнорировать эти поля (команды не резолвятся gateway'ем, они лишь несут контекст терминала).
   - **Gateway**: `TerminalResolver.kt` и `TerminalContext` больше не нужны (использовались только в `DeltaReferenceController`/`DeltaCommandService` для delta/full) — удалить файл/класс. `DeltaCommandService.publishDelta/publishFull` принимают `carrierId/regionId` из request (сделано в 003_02).
   - **Документация**: в `doc/architecture.md`, `doc/context.md`, `AGENTS.md` явно зафиксировать, что gateway выполняет только авторизацию/аутентификацию/проксирование и не резолвит бизнес-контекст терминала.

5) выполни промпт prompts/my/prompt_003_01.md — keyset-пагинация по `VERSION` в `DeltaSyncService` **и** `FullSyncService` (строго после 003_02).

# Тестирование

1) С целью тестирования работы, описанной в prompts/prompt_002.md и в prompts/prompt_002_01.md, напиши SQL-скрипты, подобные `infrastructure/docker/seed-data.sql`, но другие, отдельные: `infrastructure/docker/seed-data-delta-1.sql`, `infrastructure/docker/seed-data-delta-2.sql`, `infrastructure/docker/seed-data-delta-3.sql`, для заполнения таблиц/справочников:

### [0. Регионы и Территории (ФИАС/ГАР)](#0-регионы-и-территории-фиасгар)

* [`ASOP_REGIONS`](#asop_regions)  [admin-service]
* [`ASOP_TERRITORIES`](#asop_territories)  [admin-service]
* [`ASOP_ORGANIZERS`](#asop_organizers)  [admin-service]
* [`ASOP_ORGANIZER_TERRITORIES`](#asop_organizer_territories) [admin-service]

### [1. Справочники](#1-справочники)

* [`ASOP_ROLES`](#asop_roles)   [admin-service]
* [`ASOP_CARD_TYPES`](#asop_card_types) [admin-service]
* [`ASOP_TARIFF_TYPES`](#asop_tariff_types) [admin-service]
* [`ASOP_SESSION_TYPES`](#asop_session_types) [admin-service]
* [`ASOP_EVENT_TYPES`](#asop_event_types) [admin-service]
* [`ASOP_TRANSACTION_TYPES`](#asop_transaction_types) [admin-service]
* [`ASOP_TRANSACTION_RESULTS`](#asop_transaction_results) [admin-service]
* [`ASOP_SERVICES`](#asop_services) [admin-service]
* [`ASOP_BENEFITS`](#asop_benefits) [admin-service]
* [`ASOP_BENEFIT_STEPS`](#asop_benefit_steps) [admin-service]

### [2. Перевозчики, Договоры и ТС](#2-перевозчики-договоры-и-тс)

* [`ASOP_CARRIERS`](#asop_carriers) [carrier-service]
* [`ASOP_CONTRACTS`](#asop_contracts) [carrier-service]
* [`ASOP_CONTRACT_ROUTES`](#asop_contract_routes) [carrier-service]
* [`ASOP_VEHICLE_TYPES`](#asop_vehicle_types) [carrier-service]
* [`ASOP_VEHICLE_MODELS`](#asop_vehicle_models) [carrier-service]
* [`ASOP_VEHICLES`](#asop_vehicles) [carrier-service]

### [3. Пользователи и Безопасность](#3-пользователи-и-безопасность)

* [`ASOP_USERS`](#asop_users) [user-service]
* [`ASOP_USER_ROLES`](#asop_user_roles) [user-service]
* [`ASOP_USER_CARRIERS`](#asop_user_carriers) [user-service]
* [`ASOP_USER_REGIONS`](#asop_user_regions) [user-service]

### [4. Маршруты и Пути](#4-маршруты-и-пути)

* [`ASOP_FARE_ZONES`](#asop_fare_zones) [route-service]
* [`ASOP_TRANSPORT_STOPS`](#asop_transport_stops) [route-service]
* [`ASOP_ROUTES`](#asop_routes) [route-service]
* [`ASOP_PATHS`](#asop_paths) [route-service]
* [`ASOP_PATH_TRANSPORT_STOPS`](#asop_path_transport_stops) [route-service]
* [`ASOP_SCHEDULE`](#asop_schedule) [route-service]
* [`ASOP_PATH_SERVICES`](#asop_path_services) [route-service]
* [`ASOP_PATH_DISCOUNTS`](#asop_path_discounts) [route-service]
* [`ASOP_PATH_BENEFITS`](#asop_path_benefits) [route-service]

### [5. Карты, Льготы, Тарифы](#5-карты-льготы-тарифы)

* [`ASOP_CARDS`](#asop_cards) [card-service]
* [`ASOP_CARD_MIFARES`](#asop_card_mifares) [card-service]
* [`ASOP_CARD_BANKS`](#asop_card_banks) [card-service]
* [`ASOP_CARD_TARIFFS`](#asop_card_tariffs) [card-service]
* [`ASOP_BLACKLISTS`](#asop_blacklists) [card-service]
* [`ASOP_USER_BENEFITS`](#asop_user_benefits) [card-service]
* [`ASOP_TARIFF_RATES`](#asop_tariff_rates) [card-service]

* [`ASOP_TIDS`](#asop_tids) [carrier-service]

**Правила генерации тестовых данных (важно!):**
- **Регионы и перевозчиков НЕ трогать** — не создавать новых, не редактировать существующие строки `ASOP_REGIONS`/`ASOP_CARRIERS`. В delta-скриптах эти две таблицы **не заполняются** (остаются только те 3 региона и перевозчики, что уже есть в `seed-data.sql`). Использовать существующие: регион `('00000000-0000-0000-0000-000000000103', 'Республика Крым', ...)` и перевозчика `('00000000-0000-0000-0000-000000001403', 'ГУП "Крымавтотранс"', '9102123456', '00000000-0000-0000-0000-000000000103', NOW(), NOW())`.
- **Тестовый терминал настроен на этого перевозчика** (id `00000000-0000-0000-0000-000000001403`) и его регион (`00000000-0000-0000-0000-000000000103`). Все bulk-данные (ТС, пользователи, карты, маршруты, TID и т.д.) генерировать привязанными к этому перевозчику/региону — чтобы фильтры мастеров (`carrierId`/`regionId`/`userIdsIn`) возвращали их терминалу в полном объёме (иначе терминал их не получит и пагинация не протестируется).
- Для таблиц без фильтра по перевозчику/региону (GLOBAL-справочники: roles, card-types, tariff-types и т.п. — смотри `MasterRegistry.kt`) можно генерировать любые объёмы.
- Учитывать FK: например, `ASOP_USERS` должна быть связана с перевозчиком через `ASOP_USER_CARRIERS`/`ASOP_USER_REGIONS`, карточные таблицы — через `ASOP_CARDS.user_id` на пользователей этого перевозчика.
- Объёмы (на таблицу): delta-1 ≈ 1 000–2 000, delta-2 ≈ 100 000–200 000, delta-3 ≈ 10 000–20 000 записей. Для `ASOP_REGIONS`/`ASOP_CARRIERS` объёмы не применимы (не заполняем). Генерация — через `generate_series`, ускоряющие приёмы (запрет триггеров можно НЕ использовать — sequence VERSION всё равно уникален, пагинация не ломается).

2) Напиши в папке `frontend` ещё одно Android-приложение для тестовых целей, назови `android-test`, сделай там меню с тремя пунктами:
   - **"Тест дельта инкремента 1"**
   - **"Тест дельта инкремента 2"**
   - **"Получить все данные"**

   Подписывается тем же debug-ключом, что и android-terminal (доступ к `ContentProvider` по signature-permission, см. п.3 «Доработки»).

   **Эталон для проверки:** собрать отфильтрованные по региону `00000000-0000-0000-0000-000000000103` и перевозчику `00000000-0000-0000-0000-000000001403` данные из всех seed-скриптов и зашить их прямо в приложение в любом удобном виде (например JSON-файл в assets) **в трёх срезах** (эталон растёт инкрементально):
   - `expected-1.json` — отфильтрованные данные из `seed-data.sql` + `seed-data-delta-1.sql`;
   - `expected-2.json` — из `seed-data.sql` + `seed-data-delta-1.sql` + `seed-data-delta-2.sql`;
   - `expected-all.json` — из всех четырёх скриптов.
   Фильтрация должна повторять логику мастер-сервисов: GLOBAL-таблицы — все строки; таблицы с фильтром по `carrierId`/`regionId` — строки перевозчика `...1403`/региона `...0103`; USER/CARD-таблицы — строки, связанные с пользователями этого перевозчика. По какому правилу какая таблица фильтруется — смотри `MasterRegistry.kt` и дельта-контроллеры мастеров.

   **Процесс тестирования:**
   - **Предусловие:** терминал должен быть зарегистрирован (cert-sign → регистрация, см. prompt_002) и привязан к перевозчику `...1403` (это даёт `terminalId` и записанные в `SyncPreferences` регион/перевозчика — п.4). Без этого delta/full-запросы терминала не имеют контекста.
   - Старт с чистого стека (см. `prompts/prompt_002.md`), накатываем `seed-data.sql`.
   - Накатываем `seed-data-delta-1.sql` → нажимаем **"Тест дельта инкремента 1"** → проверяем, что в БД android-terminal (через `ContentProvider`) присутствуют отфильтрованные данные ровно как в `expected-1.json` (`seed-data.sql` **и** `seed-data-delta-1.sql`).
   - Накатываем `seed-data-delta-2.sql` → нажимаем **"Тест дельта инкремента 2"** → проверяем отфильтрованные данные как в `expected-2.json` (`seed-data.sql` + delta-1 + delta-2).
   - Накатываем `seed-data-delta-3.sql` → в **android-terminal** (сам терминал) запускаем полную выкачку через S3 (кнопка «Полная выкачка» / `enqueueFullDump`) → в **android-test** нажимаем **"Получить все данные"** → проверяем данные как в `expected-all.json` (полный итог: `seed-data.sql` + delta-1 + delta-2 + delta-3).
   - Данные проверяются через `ContentProvider` внутри android-terminal (query API из п.3).

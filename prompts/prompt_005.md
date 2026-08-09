# Промпт 005: Активация карт на терминале

## Принятые решения (подтверждены пользователем)

- **AID ASOP-приложения на карте**: `0xA05A01` (3 байта, мнемоника ASOP). Файлы создаются **внутри** этого приложения, не в master PICC (AID 000000).
- **Подпись cardIdentity**: `RSA-PSS + SHA-256` приватным ключом server-key из crypto-service (`./data/server-key.p12`, RSA-2048). `PSSParameterSpec("SHA-256","MGF1",MGF1ParameterSpec.SHA256,32,1)`. Подписываются **canonical JSON-байты** cardIdentity (компактный, ключи в фиксированном порядке, UTF-8 без пробелов).
- **Порядок операций**: сервер → карта. Сначала подпись+регистрация на сервере, потом прошивка карты. Если сервер недоступен — карта не трогается. Если запись на карту не удалась после успешной серверной регистрации — запись в БД остаётся, карту можно перерегистрировать повторно (процедура идентификации найдёт её по UID).
- **Обновление ролей**: при перерегистрации массив `roles` **полностью перезаписывается** новым значением (не добавление к существующим).
- **Поиск пользователя**: по ФИО (текстовый ввод → фильтр по `reference_rows` / admin-users → выбор из результатов).
- **Self-авторизация разрешена**: держатель карты может регистрировать карту своего же типа (кроме root-админа — его регистрирует только root логин/пароль).
- **Анонимная пассажирская карта**: тоже получает подписанный cardIdentity (userId пустой, role `PASSENGER_ANONYMOUS`), пишется в ASOP-приложение и в БД — единый механизм валидации.

---

## 1. Карты и роли

### 1.1. Иерархия карт (14 типов)

```
Рут админ всей системы АСОП
└── Админ региона
    ├── Админ организатора перевозок
    │   ├── Админ перевозчика → Диспетчер перевозчика → Водитель
    │   ├── Админ дистрибьютора карт → Диспетчер дистрибьютора карт
    │   └── Админ КРС → Диспетчер КРС → {Бригадир КРС, Сотрудник КРС}
    ├── Пассажир персонализированный
    └── Пассажир не персонализированный
```

### 1.2. Роли в справочнике `ASOP_ROLES` (код = `ROLE_NAME`)

Существующие: `SUPER_ADMIN`, `CARRIER_ADMIN`, `CONTROLLER_ADMIN`, `DISTRIBUTOR_ADMIN`, `DISPATCHER`, `DRIVER`, `CONTROLLER`, `PASSENGER`.

**Добавить** (INSERT `ON CONFLICT DO NOTHING`, UUIDv7): `REGION_ADMIN`, `ORGANIZER_ADMIN`, `KRS_ADMIN`, `CARRIER_DISPATCHER`, `DISTRIBUTOR_DISPATCHER`, `KRS_DISPATCHER`, `KRS_FOREMAN`, `KRS_CONTROLLER`, `PASSENGER_ANONYMOUS`.

### 1.3. `ASOP_CARD_MIFARES.CARD_ROLE` — расширить CHECK-список

Новое множество (замена `chk_card_role`): `SUPER_ADMIN, REGION_ADMIN, ORGANIZER_ADMIN, CARRIER_ADMIN, DISTRIBUTOR_ADMIN, KRS_ADMIN, CARRIER_DISPATCHER, DISTRIBUTOR_DISPATCHER, KRS_DISPATCHER, DRIVER, KRS_FOREMAN, KRS_CONTROLLER, PASSENGER, PASSENGER_ANONYMOUS`.

### 1.4. Маппинг типов карт на роли

| # | Тип карты | CARD_ROLE | ROLE_NAME |
|---|---|---|---|
| 1 | root админ | SUPER_ADMIN | SUPER_ADMIN |
| 2 | админ региона | REGION_ADMIN | REGION_ADMIN |
| 3 | админ организатора | ORGANIZER_ADMIN | ORGANIZER_ADMIN |
| 4 | админ перевозчика | CARRIER_ADMIN | CARRIER_ADMIN |
| 5 | админ дистрибьютора | DISTRIBUTOR_ADMIN | DISTRIBUTOR_ADMIN |
| 6 | админ КРС | KRS_ADMIN | KRS_ADMIN |
| 7 | диспетчер перевозчика | CARRIER_DISPATCHER | CARRIER_DISPATCHER |
| 8 | диспетчер дистрибьютора | DISTRIBUTOR_DISPATCHER | DISTRIBUTOR_DISPATCHER |
| 9 | диспетчер КРС | KRS_DISPATCHER | KRS_DISPATCHER |
| 10 | водитель | DRIVER | DRIVER |
| 11 | бригадир КРС | KRS_FOREMAN | KRS_FOREMAN |
| 12 | сотрудник КРС | KRS_CONTROLLER | KRS_CONTROLLER |
| 13 | пассажир персонализированный | PASSENGER | PASSENGER |
| 14 | пассажир анонимный | PASSENGER_ANONYMOUS | (нет роли — анонимная) |

---

## 2. Матрица авторизации регистрации карт

Строки = регистрируемая карта, столбцы = кто авторизует (предъявляет свою карту или root логин/пароль). `+` = может авторизовать. **Self-авторизация разрешена** (держатель карты своего типа), кроме root-админа.

| Регистрируемая \ Авторизующая | root login | SUPER_ADMIN | REGION | ORGANIZER | CARRIER_ADMIN | DISTRIBUTOR_ADMIN | KRS_ADMIN | CARRIER_DISP | DISTRIBUTOR_DISP | KRS_DISP | DRIVER | KRS_FOREMAN | KRS_CONTROLLER |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| root админ | + | | | | | | | | | | | | |
| админ региона | | + | + | | | | | | | | | | |
| админ организатора | | + | + | + | | | | | | | | | |
| админ перевозчика | | + | + | + | + | | | | | | | | |
| админ дистрибьютора | | + | + | + | | + | | | | | | | |
| админ КРС | | + | + | + | | | + | | | | | | |
| диспетчер перевозчика | | + | + | + | + | | | + | | | | | |
| диспетчер дистрибьютора | | + | + | + | | + | | | + | | | | |
| диспетчер КРС | | + | + | + | | | + | | | + | | | |
| водитель | | + | + | + | + | | | + | | | + | | |
| бригадир КРС | | + | + | + | | | + | | | + | | + | |
| сотрудник КРС | | + | + | + | | | + | | | + | | + | + |
| пассажир перс. | | + | + | + | + | + | + | + | + | + | + | + | + |
| пассажир неперс. | | + | + | + | + | + | + | + | + | + | + | + | + |

**Логика авторизации:**
1. Root админ: логин/пароль → Keycloak password grant (direct grant, client `asop-admin`) → проверка роли `SUPER_ADMIN` → `rootUserId`
2. Все остальные: идентификация уже зарегистрированной персонализированной карты → чтение `cardIdentity.roles` → проверка по матрице
3. Сервер дополнительно проверяет авторизацию в `POST /api/v1/sync/cards/activate` (по `operatorRoles` или `authorizedByRoot`)

---

## 3. Заполнение полей cardIdentity

| Карта | region | organizer | carrier | cardsDistributor | auditService | userId |
|---|---|---|---|---|---|---|
| root админ | — | — | — | — | — | + (root-юзер) |
| админ региона | + | — | — | — | — | + |
| админ организатора | + | + | — | — | — | + |
| админ перевозчика | + | + | + | — | — | + |
| админ дистрибьютора | + | + | — | + | — | + |
| админ КРС | + | + | — | — | + | + |
| диспетчер перевозчика | + | + | + | — | — | + |
| диспетчер дистрибьютора | + | + | — | + | — | + |
| диспетчер КРС | + | + | — | — | + | + |
| водитель | + | + | + | — | — | + |
| бригадир КРС | + | + | — | — | + | + |
| сотрудник КРС | + | + | — | — | + | + |
| пассажир перс. | — | — | — | — | — | + |
| пассажир неперс. | — | — | — | — | — | — |

`+` = обязательно, `—` = пусто (null). `roles` = роль привязанного пользователя (для персонализированных) / `PASSENGER_ANONYMOUS` для анонимной.

---

## 4. Структура cardIdentity (canonical JSON)

```json
{
  "cardId": "UUIDv7 (генерируется на Android, UTC)",
  "uid": "7-байтный UID карты (hex-строка)",
  "regionId": "UUID или пусто",
  "organizerId": "UUID или пусто",
  "carrierId": "UUID или пусто",
  "cardsDistributorId": "UUID или пусто",
  "auditServiceId": "UUID или пусто",
  "userId": "UUID или пусто",
  "roles": ["ROLE_NAME"]
}
```

**Canonical JSON**: компактный (без пробелов), ключи в фиксированном порядке (как указано выше), UTF-8. Подписываются **точные байты**, которые Android положит в файл на карту. Сервер и Android формируют canonical JSON идентично.

Сохраняется на карту в ASOP-приложении (AID `0xA05A01`):
- **File 0**: cardIdentity canonical JSON (UTF-8, Standard Data File)
- **File 1**: digital signature (base64, Standard Data File)

Доступ к файлам: чтение и запись только после аутентификации с 3DES-ключом slot 0 ASOP-приложения.

---

## 5. Схема БД — `asop_schema.sql` + changeset `v002-card-identity`

### 5.1. `ASOP_CARDS` — добавить nullable-колонки (FK)

```sql
REGION_ID UUID NULL → ASOP_REGIONS
ORGANIZER_ID UUID NULL → ASOP_ORGANIZERS
CARRIER_ID UUID NULL → ASOP_CARRIERS
CARDS_DISTRIBUTOR_ID UUID NULL → ASOP_CARDS_DISTRIBUTORS
AUDIT_SERVICE_ID UUID NULL → ASOP_AUDIT_SERVICES
```

(`USER_ID` уже есть.) Индексы на новые колонки. `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` (idempotent).

### 5.2. `ASOP_CARD_MIFARES` — добавить

```sql
IDENTITY_JSON TEXT,        -- canonical JSON cardIdentity
IDENTITY_SIGNATURE TEXT,   -- base64 RSA-PSS-SHA256
```

Расширить `chk_card_role` (п.1.3). `UID` уже BYTEA (7 байт).

### 5.3. `ASOP_CARD_TYPES` — добавить тип `MIFARE_DESFIRE` (seed).

### 5.4. `ASOP_ROLES` — добавить 9 новых ролей (п.1.2), seed INSERT `ON CONFLICT DO NOTHING`.

### 5.5. Миграция

- DDL редактируется в `asop_schema.sql` (первичный источник), копируется в `migrations/v001-init.sql` (или новый changeset `v002-card-identity` — на усмотрение реализатора, главное idempotent).
- Deploy: `docker compose down -v` (полное пересоздание БД при изменении v001).

---

## 6. crypto-service — подпись cardIdentity

### 6.1. `POST /api/v1/smart-cards/sign`
- Body: `{ "identityJson": "<canonical JSON string>" }`.
- Подпись: `Signature.getInstance("RSASSA-PSS")`, `PSSParameterSpec("SHA-256","MGF1",MGF1ParameterSpec.SHA256,32,1)`, ключ = RSA-2048 server-key (существующий `ServerKeyService`).
- Response: `{ "signatureBase64": "..." }`.
- В `ServerKeyService` добавить `sign(bytes)` / `verify(bytes, sig)`.
- `SmartCardApi`/`SmartCardController` расширить методом `sign`.
- Публичный ключ отдаётся существующим `GET /api/v1/keys/public`.

---

## 7. card-service — регистрация карт

### 7.1. `POST /api/v1/cards/activate` (sync, через gateway)

Body:
```json
{
  "cardIdentity": { ... },
  "identitySignature": "...",
  "operatorRoles": ["..."],
  "authorizedByRoot": false,
  "rootUserId": "UUID?"
}
```

- **Проверка авторизации** по матрице (п.2): `operatorRoles` или `authorizedByRoot` против целевой `cardIdentity.roles[0]`.
- **Проверка подписи**: `verify` публичным ключом сервера (кеш из crypto `/keys/public`). Невалидная подпись → 400.
- **Upsert** по `cardId`:
  - INSERT если `cardId` нет; если `UID` уже существует (но cardId другой) → 409 «карта уже зарегистрирована».
  - UPDATE если карта существует (re-registration: не менять `cardId`/`uid`, `roles` **полностью перезаписать**).
- Пишет: `ASOP_CARDS` (cardId, cardTypeId=MIFARE_DESFIRE, userId, region/organizer/carrier/distributor/audit, registeredAt, registeredByUserId) + `ASOP_CARD_MIFARES` (uid, cardRole, identityJson, identitySignature, keyVersion).
- Response: `{ "cardId", "cardRole", "registeredAt" }`.
- `@RestControllerAdvice` как в route/carrier-service (`IllegalArgumentException` → 400, `DataIntegrityViolationException` → 400).

---

## 8. Gateway

### 8.1. `ServiceRegistry`
- `"smart-cards"` → crypto-service:8081 (sync proxy).

### 8.2. Sync-endpoints под mTLS `/api/v1/sync/**` (chain Order 1)
- `POST /api/v1/sync/smart-cards/sign` — проброс на crypto `/api/v1/smart-cards/sign` (WebClient), возвращает `{signatureBase64}`.
- `POST /api/v1/sync/cards/activate` — проброс на card-service `/api/v1/cards/activate`.
- `POST /api/v1/sync/auth/root` — RootAuthController: принимает `{username, password}`, WebClient → Keycloak token endpoint (`/realms/asop/protocol/openid-connect/token`, client `asop-admin`, grant_type=password), проверяет `realm_access.roles` содержит `SUPER_ADMIN`, мапит `sub` → `ASOP_USERS.KEYCLOAK_ID` → `userId` (через user-service или прямой R2DBC-запрос), возвращает `{userId}`; иначе 403.
- `SecurityConfig`: пути `/api/v1/sync/**` уже покрыты mTLS chain (Order 1).

### 8.3. Keycloak
- `BootstrapService.ensureOidcClient()`: включить `directAccessGrantsEnabled=true` для `asop-admin` (сейчас public, direct grant не гарантирован — Keycloak 25.0.4 bug: нужно явно включать).

---

## 9. Android (frontend/android-terminal)

### 9.1. Меню
- В drawer добавить пункт **«Активация карт»** → подменю с 14 типами (п.1.1), сгруппировано: «Персонал АСОП» и «Пассажиры».
- Навигация: route `card-activate/{type}` (enum), новый `CardActivationScreen`.

### 9.2. Общий flow экрана (для каждого типа)
1. **Проверка сети** (`NetworkMonitor`/`ConnectivityManager`) — без сети блокируем с сообщением.
2. **Авторизация**:
   - Цель = root админ → форма логин/пароль → `POST /api/v1/sync/auth/root` → `rootUserId`.
   - Иначе → приложить **авторизующую** (свою) карту: идентификация по ключам (п.9.5), прочитать cardIdentity, взять `roles`. Role-чек vs матрица (п.2) локально (сервер проверит ещё раз).
3. **Приложить целевую карту** → handshake с нулевым 3DES ключом:
   - **Нулевой ключ OK** → карта новая:
     a. Выбрать самый свежий ключ из `terminal_keys` (по `KEY_ID` UUIDv7 DESC; при дублях из dev-режима — берём первый).
     b. Заполнить cardIdentity (dropdown'ы п.9.3, `cardId`=UUIDv7, `uid`=UID карты, `roles`=[роль из маппинга]).
     c. Сформировать **canonical JSON** (компактный, фиксированный порядок ключей).
     d. `POST /api/v1/sync/smart-cards/sign` → подпись.
     e. `POST /api/v1/sync/cards/activate` → регистрация на сервере.
     f. Если (d) или (e) неудачны → ошибка, **карта не трогается**.
     g. **Прошивка карты** (после успешной серверной регистрации):
        1. Auth с нулевым 3DES ключом (slot 0, master PICC)
        2. `ChangeKey` (0xC4) slot 0: нулевой ключ → новейший 3DES ключ из `terminal_keys`
        3. `CreateApplication` (0xCA) AID=`0xA05A01`, keySettings=(changeKey=true, configChangeable=true), numKeys=1
        4. `SelectApplication` (0x5A) AID=`0xA05A01`
        5. Auth с новым 3DES ключом (slot 0, ASOP-приложение)
        6. `CreateStdDataFile` (0x6D) file=0 (cardIdentity), access=auth-required, size=~2048
        7. `WriteData` (0x8D) file=0: canonical JSON (UTF-8)
        8. `CreateStdDataFile` (0x6D) file=1 (signature), access=auth-required, size=~512
        9. `WriteData` (0x8D) file=1: base64 signature
        10. Верифицировать чтением (`ReadData` 0xBD file=0 + file=1)
        11. Если прошивка не удалась → запись на сервере остаётся, показать "Карта зарегистрирована на сервере, но не записана. Повторите прикладывание карты".
   - **Нулевой ключ НЕ OK** → карта зарегистрированная:
     a. Процедура идентификации (п.9.5) → найден рабочий 3DES ключ
     b. `SelectApplication` (0x5A) AID=`0xA05A01` → Auth с найденным ключом
     c. `ReadData` (0xBD) file=0 → старый cardIdentity JSON
     d. Не менять `cardId`/`uid`; обновить поля по таблице (п.3), `roles` **полностью перезаписать**.
     e. Canonical JSON → `POST /sync/smart-cards/sign` → новая подпись.
     f. `POST /sync/cards/activate` (UPDATE по cardId).
     g. `ChangeKey` (0xC4) slot 0: найденный ключ → новейший 3DES ключ.
     h. `WriteData` (0x8D) file=0: новый cardIdentity JSON (перезапись).
     i. `WriteData` (0x8D) file=1: новая подпись (перезапись).
4. Результат: экран успеха/ошибки.

### 9.3. Dropdown'ы с поиском (последовательные, зависят от полей типа)
- `regionId`: из `reference_rows` (ASOP_REGIONS, уже синкается). Далее каскадно:
- `organizerId`: ASOP_ORGANIZERS (фильтр по regionId).
- `carrierId`: ASOP_CARRIERS (фильтр по regionId).
- `cardsDistributorId`: ASOP_CARDS_DISTRIBUTORS.
- `auditServiceId`: ASOP_AUDIT_SERVICES (новый справочник — см. п.10, нужно добавить в дельта-синк).
- `userId`: текстовый ввод ФИО → фильтр по admin-users (ASOP_USERS из reference_rows) → выбор из результатов.

### 9.4. DESFire writer (новый код)

Новый класс `nfc/DesfireCardWriter.kt` (IsoDep, native-фрейминг как в `DesfireAuthProbe`):

| Метод | Опкод | Описание |
|---|---|---|
| `changeKey(keyNo, oldKey, newKey)` | **0xC4** | Смена 3DES-ключа слота |
| `createApplication(aid, keySettings, numKeys)` | **0xCA** | Создание ASOP-приложения (AID 0xA05A01) |
| `createStdDataFile(fileNo, accessRights, fileSize)` | **0x6D** | Создание Standard Data File |
| `writeData(fileNo, data)` | **0x8D** | Запись данных в файл |
| `readData(fileNo, size)` | **0xBD** | Чтение данных из файла |

> **ВАЖНО (исправлены ошибки DeepSeek)**: `CreateStdDataFile` = **0x6D** (не 0xCD), `WriteData` = **0x8D** (не 0x3D). Предыдущие зонды (`DesfireCardReader`, `DesfireAuthProbe`) — read-only, не ломать. Writer — отдельный модуль, только в flow активации.

### 9.5. Процедура идентификации ранее зарегистрированной карты

1. Расшифровать 3DES-ключи из `terminal_keys` (Keystore-AES через `TerminalKeyCryptor`, ключ не покидает Keystore).
2. Сортировка: `ORDER BY KEY_ID DESC` (UUIDv7, новейшие первыми).
3. Для каждого ключа:
   a. `SelectApplication` master PICC (`0x5A 00 00 00`)
   b. `AuthenticateISO` (`0x1A`, keyNo=0, расшифрованный 3DES ключ)
   c. Если handshake прошёл → идентификация успешна, запомнить рабочий ключ
   d. Если не прошёл → следующий ключ
4. Если найден рабочий ключ:
   a. `ChangeKey` (0xC4) slot 0: рабочий ключ → новейший 3DES ключ (первый из списка)
   b. Auth с новейшим ключом → подтверждение
   c. Return: новейший ключ = рабочий
5. Если ни один ключ не подошёл → ошибка "Карта не идентифицирована. Возможно, она не зарегистрирована в системе или ключ устарел."

### 9.6. Network/DI
- `GatewayApi`/`SyncApi`: + 3 endpoint'а (`sign`, `activate`, `auth/root`).
- Матрица авторизации для локального role-чека — статическая таблица (константа в Kotlin).
- UUIDv7 на Android: добавить зависимость `uuid-creator` (`com.github.f4b6a3:uuid-creator:5.x`) или использовать эквивалент.

---

## 10. audit-service (справочник КРС)

- `ASOP_AUDIT_SERVICES` уже есть в БД (ISSUER_TYPE ORGANIZER/CARRIER), но controller/дельта отсутствуют.
- Добавить `GET /api/v1/audit-services` (CRUD + `/delta`, DeltaSupport) в audit-service.
- В `MasterRegistry` оркестратора: `"asop_audit_services" to audit("audit-services")`.
- В proto `schema.proto` ×2: `AsopAuditServicesRow`/`AsopAuditServicesFile`, поле в `DeltaChunk` (следующий свободный номер).
- Это даст Android список КРС для `auditServiceId` в dropdown.

---

## 11. Тестовые данные — `infrastructure/docker/seed-data.sql`

По Крыму (регион 103 уже есть):
- Организатор 303 (есть), перевозчик 1403 (есть).
- **Дистрибьютор Крыма** (новый, ASOP_CARDS_DISTRIBUTORS).
- **КРС Крыма** (новая ASOP_AUDIT_SERVICES, ISSUER_TYPE=ORGANIZER, ORGANIZER_ID=303).
- **Пользователи** всех ролей с user-roles / user-carriers / user-regions: админ региона, админ организатора, админ перевозчика, админ дистрибьютора, админ КРС, диспетчеры (3), водитель, бригадир, сотрудник КРС, пассажир с льготой (user-benefits + `PENSIONER_CR`).
- Тип карты `MIFARE_DESFIRE`, новые роли (п.1.2).

---

## 12. Порядок реализации

1. DDL: новые роли в `ASOP_ROLES`, расширить CHECK в `ASOP_CARD_MIFARES.CARD_ROLE`, новые колонки в `ASOP_CARDS`/`ASOP_CARD_MIFARES`, тип `MIFARE_DESFIRE`, seed-data Крыма.
2. crypto-service: `POST /api/v1/smart-cards/sign` (RSA-PSS-SHA256, server-key) + `sign()`/`verify()` в `ServerKeyService`.
3. card-service: `POST /api/v1/cards/activate` (проверка авторизации + подписи + upsert).
4. gateway: `POST /api/v1/sync/auth/root` (Keycloak password grant), ServiceRegistry `smart-cards`, sync-пробросы.
5. Keycloak: `directAccessGrantsEnabled=true` в `BootstrapService.ensureOidcClient()`.
6. audit-service: controller + `/delta` для `audit-services`; orchestrator `MasterRegistry` + proto.
7. Android: `DesfireCardWriter.kt` (ChangeKey 0xC4, CreateApplication 0xCA, CreateStdDataFile 0x6D, WriteData 0x8D, ReadData 0xBD).
8. Android: UI — меню "Активация карт", `CardActivationScreen`, dropdown'ы, поиск по ФИО.
9. Android: процедура регистрации (нулевая карта) + процедура идентификации + обновление.
10. Тестовые данные (seed-data.sql).

---

## 13. Факты из кодовой базы

- `ASOP_CARDS`: `CARD_ID UUID PK`, `CARD_TYPE_ID UUID NOT NULL FK`, `USER_ID UUID NULL FK`, `REGISTERED_AT`, `REGISTERED_BY_USER_ID`, soft-delete, VERSION.
- `ASOP_CARD_MIFARES`: `CARD_ID UUID PK FK`, `UID BYTEA UNIQUE`, `CARD_ROLE VARCHAR(30) CHECK(...)`, PKI-поля (CERTIFICATE_SERIAL, PUBLIC_KEY_HASH, KEY_VERSION, VALID_FROM/UNTIL, REVOKED_AT).
- `ASOP_ROLES`: 8 ролей — нужно добавить 9 новых (REGION_ADMIN, ORGANIZER_ADMIN, KRS_ADMIN, CARRIER_DISPATCHER, DISTRIBUTOR_DISPATCHER, KRS_DISPATCHER, KRS_FOREMAN, KRS_CONTROLLER, PASSENGER_ANONYMOUS).
- `ASOP_CARD_MIFARES.CARD_ROLE` CHECK: 11 значений — нужно заменить на 14 (п.1.3).
- `ASOP_AUDIT_SERVICES`: таблица есть (ISSUER_TYPE ORGANIZER/CARRIER), controller/дельта отсутствуют.
- Terminal sync flow: `/api/v1/sync/**` под mTLS (Chain Order 1) — endpoints активации идут здесь.
- NFC: `DesfireCardReader` + `DesfireAuthProbe` — **read-only**, не ломать. Writer — отдельный модуль.
- `terminal_keys` (Room): 3DES-ключи, зашифрованные Keystore-AES, `ORDER BY KEY_ID DESC`.
- `server-key.p12` в crypto-service — RSA-2048, используется для 3DES encrypt/decrypt, будет использован и для подписи cardIdentity (RSA-PSS).
- `NetworkMonitor` уже есть на Android — использовать для проверки сети.
- `BootstrapService.ensureOidcClient()` создаёт client `asop-admin` — нужно добавить `directAccessGrantsEnabled=true` (Keycloak 25.0.4 bug: явно включать).
- Конвенции AGENTS.md: UUIDv7, `TransactionalOperator`, `R2dbcEntityTemplate.insert`, дельта-триггеры, error-handling 400/409/403, `@RestControllerAdvice`.
- DESFire native опкоды (см. `doc/specifications/DESFireEV1.md`): ChangeKey=0xC4, CreateApplication=0xCA, CreateStdDataFile=**0x6D** (не 0xCD!), WriteData=**0x8D** (не 0x3D!), ReadData=0xBD, SelectApplication=0x5A, AuthenticateISO=0x1A, GetMoreFrames=0xAF.

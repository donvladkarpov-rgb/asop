# Промпт 009: Единый entityUuid=userId на VCM1-картах (clean-break модель)

## Цель
Унифицировать VCM1-layout: `entityUuid` (16 байт на карте, block 2) всегда = `ASOP_USERS.user_id`,
кроме роли `PASSENGER_ANONYMOUS` где `entityUuid` = all-zeros.
Все dropdown'ы region/organizer/carrier/distributor/КРС остаются **только в UI-форме** для сужения поиска
оператора, **но не записываются на карту**. Backend assumes routing by `ASOP_USER_*` relations,
а не по полям ASOP_CARDS.

## Принципы (на основе решения)
1. **Clean break**: SAC1 legacy больше НЕ читается. Только VCM1. Существующие 02B206F1, 1F69CDB5
   остаются валидными (на них уже VCM1 c `entity={type:userid}` — совместимо).
2. **Multi-role restriction**: bitmask может содержать несколько битов, **только если** все они соответствуют
   одному entityType (например, DRIVER(bit 9) + CARRIER_DISPATCHER(bit 6) = оба CARRIER — OK;
   DRIVER + KRS_FOREMAN — ЗАПРЕТ, сервер throws).
3. **Минимальная схема на карте**: `magic[4] + bitmask[2] + reserved[10] + cardId[16] + entityUuid[16]` — без изменений.
4. **PASSENGER (персональный)**: optional region filter (`needsRegion=false`, но dropdown показывается,
   при выборе — сужает список; если не выбран — все пользователи).
5. **PASSENGER_ANONYMOUS**: единственная роль без userId. entity=null.
   Cascade dropdown'ы могут быть пустыми — это нормально, активация блокируется только если целевая роль
   требует userId, но в списке никого нет.

## Таблица контракта (минимальная и полная)

### entity на КАРТЕ (16 байт, block 2)
| Роль                                 | entityType | entityId (UUID)            | проверка |
|--------------------------------------|------------|----------------------------|----------|
| SUPER_ADMIN                          | userId     | ASOP_USERS.user_id         | обязательно |
| REGION_ADMIN                         | userId     | ASOP_USERS.user_id         | обязательно |
| ORGANIZER_ADMIN                      | userId     | ASOP_USERS.user_id         | обязательно |
| CARRIER_ADMIN                        | userId     | ASOP_USERS.user_id         | обязательно |
| DISTRIBUTOR_ADMIN                    | userId     | ASOP_USERS.user_id         | обязательно |
| KRS_ADMIN                            | userId     | ASOP_USERS.user_id         | обязательно |
| CARRIER_DISPATCHER                   | userId     | ASOP_USERS.user_id         | обязательно |
| DISTRIBUTOR_DISPATCHER               | userId     | ASOP_USERS.user_id         | обязательно |
| KRS_DISPATCHER                       | userId     | ASOP_USERS.user_id         | обязательно |
| DRIVER                               | userId     | ASOP_USERS.user_id         | обязательно |
| KRS_FOREMAN                          | userId     | ASOP_USERS.user_id         | обязательно |
| KRS_CONTROLLER                        | userId     | ASOP_USERS.user_id         | обязательно |
| PASSENGER (персональный)             | userId     | ASOP_USERS.user_id         | обязательно |
| PASSENGER_ANONYMOUS                  | none       | 16 zero bytes (all 00)     | всегда NULL |

**Следствие**: на КАРТЕ исчезает различие regionId/carrierId/KRS — невозможно «по карте» понять, к какому
перевозчику привязан водитель. Эта информация доступна через JOIN ASOP_CARDS → ASOP_USERS → ASOP_USER_*
связи в БД. UI терминала при чтении карты не показывает carrier из блока 2 — он показывает
ASOP_USERS.last_name_initial + patronymic_initial + role из bitmask, а регион/перевозчик
подтягиваются из справочников по user_id.

### Поля UI-формы (operator-side, для сужения поиска)
Только для поиска конкретного **userId**. Не сохраняются на карте.

| Роль                          | regionId | organizerId | carrierId | cardsDistributorId | auditServiceId | userId |
|-------------------------------|----------|-------------|-----------|--------------------|----------------|--------|
| Root админ                     |  --------- | --------- | --------- | --------- | --------- | **обязательно** (root-self по умолчанию) |
| Админ региона                  | **фильтр** | --------- | --------- | --------- | --------- | обязательно |
| Админ организатора перевозок   | **фильтр** | **фильтр** | --------- | --------- | --------- | обязательно |
| Админ перевозчика               | **фильтр** | **фильтр** | **фильтр** | --------- | --------- | обязательно |
| Админ дистрибутора карт         | **фильтр** | **фильтр** | --------- | **фильтр** | --------- | обязательно |
| Админ КРС                      | **фильтр** | **фильтр** | --------- | --------- | **фильтр** | обязательно |
| Диспетчер перевозчика           | **фильтр** | **фильтр** | **фильтр** | --------- | --------- | обязательно |
| Диспетчер дистрибутора карт     | **фильтр** | **фильтр** | --------- | **фильтр** | --------- | обязательно |
| Диспетчер КРС                  | **фильтр** | **фильтр** | --------- | --------- | **фильтр** | обязательно |
| Водители                       | **фильтр** | **фильтр** | **фильтр** | --------- | --------- | обязательно |
| Бригадир КРС                   | **фильтр** | **фильтр** | --------- | --------- | **фильтр** | обязательно |
| Сотрудник КРС                  | **фильтр** | **фильтр** | --------- | --------- | **фильтр** | обязательно |
| Пассажир (персональный)        | **_опц._** | --------- | --------- | --------- | --------- | обязательно |
| Пассажир анонимный             | --------- | --------- | --------- | --------- | --------- | --------- |

«фильтр» = dropdown активен, при выборе сужает список кандидатов userId.
«обязательно» = финальный dropdown userId. Если пустой → кнопка «Активировать карту» **disabled**.
«_опц._» = dropdown может быть пуст (выбор опционален). Если пустой → показаны ВСЕ пользователи.
«---------» = dropdown отсутствует в UI.

## Поведение каскада в UI

### Принцип работы dropdown'ов
1. **По умолчанию** все dropdown'ы пустые → terminal показывает всех пользователей с контекстным поиском по ФИО.
2. **При выборе regionId** → список пользователей фильтруется через `ASOP_USER_REGIONS WHERE region_id = :regionId`.
3. **При выборе organizerId** → AND `ASOP_ORGANIZER_TERRITORIES + ASOP_ORGANIZERS` (организатор привязан к region).
4. **При выборе carrierId** → AND `ASOP_USER_CARRIERS WHERE carrier_id = :carrierId`.
5. …и так далее.
6. **Сброс более глубокого фильтра** при изменении менее глубокого:
   сменил region → сбрасываются organizerId, carrierId, distributorId, auditServiceId, userId;
   сменил carrier → сбрасываются distributorId, auditServiceId, userId;
   userId сбрасывается при любом изменении region/organizer/carrier/distributor/audit.

### Поведение для `PASSENGER` (опц. region filter)
- dropdown `regionId` показывает ВСЕ регионы.
- Если выбрано → пользователь фильтруется через `ASOP_USER_REGIONS`.
- Если пусто (выбрано «Все регионы») → ВСЕ пользователи, фильтр по ФИО.
- userId обязателен.

### Пустой результат после каскада
- **`PASSENGER_ANONYMOUS`** + role выбран + карта приложена → НЕ блокируем (entity=null). Это особый случай.
- **Любая другая роль** + cascade пустой → button «Активировать карту» disabled, показать «Сотрудник не найден. Добавьте в /web-admin /admin-users с нужным region/organizer/carrier/КРС».
- **Регрессия к root-login**: для SUPER_ADMIN для целей обхода можно запросить root-credentials даже если сужение пустое (но это уже особый flow оператора-security).

### Self-attached fallback (роль SUPER_ADMIN)
- Когда оператор выбирает тип карты `Root админ` (SUPER_ADMIN), он уже сам root
  (с confirmed-credentials через root-login). userId = `rootUserId` из `_state.rootUserId`.
  Dropdown для userId НЕ показывается, берётся автоматически.
- regionId/organizerId/etc. НЕ показываются (только что имеет смысл кэшировать тут: ничего).

## Backend изменения

### DTO (card-api/.../request/CardActivateRequest.kt)
```kotlin
data class CardActivateRequestClassic(
    @field:NotBlank val technology: String,      // "CLASSIC"
    @field:NotNull val cardId: UUID,              // client-generated UUIDv7 (server may override)
    @field:NotBlank val uid: String,              // MIFARE UID hex
    @field:NotNull val bitmask: Int,              // UInt16 (0..0x3FFF)
    @field:NotBlank val entityType: String,       // ONLY "userId" | "none" после clean-break
    val entityId: UUID? = null,                   // userId (UUID); null ТОЛЬКО для entityType="none"
)
enum class EntityTypeContract { USER("userId"), NONE("none") }
```

### Server-side validation в `CardActivationService.activateClassic()`
```kotlin
// 1. bitmask range check (0..0x3FFF)
if (bitmask !in 0..0x3FFF) throw new ResponseStatusException(BAD_REQUEST, "bitmask out of range")

// 2. primary role = highest-set bit (ordinal-position)
val primaryRole = highestBitRole(bitmask) ?: throw "bitmask has no set bit"

// 3. multi-role check: если >1 бит — все роли должны иметь ОДИН primaryEntityType.
if (Integer.bitCount(bitmask) > 1) {
    val types = allRolesForBitmask(bitmask)
        .map { it.primaryEntityType }
        .toSet()
    if (types.size > 1) throw new ResponseStatusException(BAD_REQUEST,
        "multi-role cards require same entityType across all roles: $types")
}

// 4. entityType ↔ role symmetry
val expected = when (primaryRole) {
    "PASSENGER_ANONYMOUS" -> "none"
    else -> "userId"
}
if (vcm1.entityType.lowercase() != expected) throw new ResponseStatusException(BAD_REQUEST,
    "entityType=$entityType inconsistent with primary role=$primaryRole (expected $expected)")

// 5. entityId validation
when (expected) {
    "userId" -> {
        requireNotNull(vcm1.entityId) { "userId required for role=$primaryRole" }
        require(userIdExists(vcm1.entityId!!)) { "userId ${vcm1.entityId} not found in ASOP_USERS" }
    }
    "none" -> require(vcm1.entityId == null) { "entityId must be null for PASSENGER_ANONYMOUS" }
}

// 6. INSERT/UPDATE asop_card_mifares + asop_cards:
val card = buildWithUserId(cardId, primaryRole, vcm1.entityId ?: ZERO_UUID, now)
// buildWithUserId пишет USER_ID = vcm1.entityId, остальные *_ID колонки = NULL
```

### ASOP_CARDS schema (НЕ меняем!)
Колонки `REGION_ID/ORGANIZER_ID/CARRIER_ID/CARDS_DISTRIBUTOR_ID/AUDIT_SERVICE_ID` остаются
в таблице для **legacy-записей** (DESFire-карты старого activation-flow).
Для VCM1-карт пишем **только `USER_ID`**, остальные — `NULL`.
Query-time routing теперь всегда через userId: `WHERE user_id IN (...)`, и затем JOIN
к ASOP_USER_REGIONS / ASOP_USER_CARRIERS / ASOP_USER_ORGANIZERS / etc.

### `identity_json` schema
```json
{
  "format": "VCM1",
  "formatVersion": 1,
  "bitmask": 1,
  "activatedAt": "...",
  "entity": {
    "type": "userid",
    "id": "019ff4b7-35a9-72d8-883d-bbf2d0cbadb7"
  }
}
```
Для PASSENGER_ANONYMOUS → `"entity": null` или отсутствует field.

## Android (терминал) изменения

### `AsopCardType.kt`
```kotlin
enum class AsopCardType(
    val label: String,
    val role: String,
    val primaryEntityType: EntityType,   // НОВОЕ
) {
    SUPER_ADMIN       ("Root админ",      "SUPER_ADMIN",         EntityType.USER),
    REGION_ADMIN      ("Админ региона",   "REGION_ADMIN",        EntityType.USER),
    ORGANIZER_ADMIN   ("Админ организатора","ORGANIZER_ADMIN",  EntityType.USER),
    CARRIER_ADMIN     ("Админ перевозчика","CARRIER_ADMIN",      EntityType.USER),
    DISTRIBUTOR_ADMIN ("Админ дистрибутора","DISTRIBUTOR_ADMIN", EntityType.USER),
    KRS_ADMIN         ("Админ КРС",       "KRS_ADMIN",           EntityType.USER),
    CARRIER_DISPATCHER   ("Диспетчер перевозчика","CARRIER_DISPATCHER",   EntityType.USER),
    DISTRIBUTOR_DISPATCHER("Диспетчер дистрибутора","DISTRIBUTOR_DISPATCHER", EntityType.USER),
    KRS_DISPATCHER       ("Диспетчер КРС","KRS_DISPATCHER",    EntityType.USER),
    DRIVER            ("Водители",        "DRIVER",              EntityType.USER),
    KRS_FOREMAN       ("Бригадир КРС",    "KRS_FOREMAN",         EntityType.USER),
    KRS_CONTROLLER    ("Сотрудник КРС",   "KRS_CONTROLLER",      EntityType.USER),
    PASSENGER         ("Пассажир персональный","PASSENGER",      EntityType.USER),
    PASSENGER_ANONYMOUS("Пассажир анонимный","PASSENGER_ANONYMOUS", EntityType.NONE);
}
```
Удалить флаги `needsRegion / needsOrganizer / ... / needsUser` — вместо них одна primaryEntityType.
PASSENGER: dropdown для region **опционально** (показывается), но если не выбрано — список полный.

### `EntityType.kt` — сократить enum до 2 значений
```kotlin
enum class EntityType(val fieldName: String, val serverValue: String) {
    USER("userId", "userId"),
    NONE("none", "none");
    companion object { fun fromFieldName(name: String) = entries.firstOrNull { it.fieldName == name } }
}
```
Удалить REGION/ORGANIZER/CARRIER/CARDS_DISTRIBUTOR/AUDIT_SERVICE.
(Backend уже не использует эти строки.)

### `CardActivationViewModel.kt`
```kotlin
// resolveEntityFields() → переименовать в buildUserIdSelection:
//   Возвращает Triple<userId(UUID?), isAnonymousPassenger: Boolean, needCascadeReset: Boolean>
//   - Для всех ролей кроме PASSENGER_ANONYMOUS: требуется userId, cascades фильтруют
//   - Для PASSENGER_ANONYMOUS: возвращает Pair(null, true)

// runActivation():
//   1. vcm1 = CardActivateClassicRequest(
//        technology = "CLASSIC",
//        cardId = clientCardId (UUIDv7),
//        uid = uid,
//        bitmask = 1 shl roleEnum.ordinal,
//        entityType = "userId" OR "none",
//        entityId = userIdForAnonymous(null) else s.selectedUserId
//    )
//   2. POST syncApi.activateCardVcm1(activateReq with wrapped {vcm1={...}})
//   3. handle server response (Standard flow)

// ReferenceForm UI structure (cascade):
// Step 0: все dropdown'ы пустые, userList = All users, search by ФИО.
// Step 1: regionId dropdown → фильтрует userList по ASOP_USER_REGIONS.
// Step 2 (если нужен organizerId): сужает по ASOP_USER_REGIONS + ASOP_ORGANIZER_TERRITORIES.
// Step 3 (если нужен carrierId/distributorId/auditServiceId): аналогично.
// Step 4: userSearchField поиск по ФИО поверх фильтрованного списка.
// Каждый сброс менее глубокого фильтра → reset глубоких + reset selectedUserId.
```

### `CardActivationScreen.kt`
ReferenceForm Composable перестроить:
- DropDown для `Region` (всегда показывается согласно схеме, except PASSENGER_ANONYMOUS).
- DropDown для `Organizer` (только если `needsOrganizer`).
- DropDown для `Carrier` (только если `needsCarrier`).
- DropDown для `Distributor` (только если `needsDistributor`).
- DropDown для `AuditService` (только если `needsAudit`).
- `UserSearchField` — финальный обязательный dropdown.
- Кнопка «Активировать карту» disabled пока `selectedUserId == null && role != PASSENGER_ANONYMOUS`.

### `MifareClassicCardWriter.kt`
`runWrite()` в VCM1-flow:
- `pendingWriteVcm1 = CardIdentityVcm1(cardId = serverCardId, bitmask, entity = EntityRef(USER, serverUserId))`
  для всех ролей кроме PASSENGER_ANONYMOUS.
- Для `PASSENGER_ANONYMOUS`: `entity = null`, на карту пишем 16 zero bytes.

### `MifareClassicReader.kt` (read-side)
VCM1 parse → находит `cardId + bitmask + entityRaw: ByteArray(16)`.
Для UI показывает:
- magic/role/badge из bitmask.
- `lastName И.` + `patronymic` из `ASOP_USERS` по entityRaw (если ! all-zeros).
- Тип агрегации (carrier/organizer/КРС) из ASOP_USER_* по user_id.
- Для `all-zero entityRaw` → показать «Пассажир анонимный», других полей не показывать.

## Flow-проверки (Acceptance Criteria)

### AC1: чистая активация новой роли (например, DRIVER)
1. Терминал показывает Drawer → Активация карт → Водитель.
2. NetworkCheck OK → Step.RootForm (авторизация root) → ввод admin/admin → Step.ReferenceForm.
3. UI показывает:
   - DropDown «Регион» (все 3 региона).
   - DropDown «Организатор» (фильтруется по выбранному region).
   - DropDown «Перевозчик» (фильтруется по region+organizer).
   - UserSearchField (финальный).
4. Оператор выбирает: `Южный / Крым` → `ГУП "Крымавтотранс"` → dropdown users сужается до водителей этого перевозчика.
5. Выбирает водителя → тап «Активировать карту».
6. POST `/api/v1/sync/cards/activate-vcm1` с `entityType=userId, entityId=<selectedUserId>`.
7. Сервер: bitmask=0x0200 → role=DRIVER → entityType=userId → пишет USER_ID=… → returns OK.
8. UI сигналит `successBeep`, чек-операции на экране.
9. После второго тапа карты `writer.writeVcm1` пишет: magic=VCM1, bitmask=0x0200, cardId=serverCardId, entityRaw=userId.
10. ASOP_CARD_MIFARES.IDENTITY_JSON=`{"format":"VCM1", entity.type="userid", entity.id=…}`.
11. ASOP_CARDS.USER_ID=selectedUserId, остальные *_ID=NULL.

### AC2: пустой userList после каскада
1. Выбран region+organizer+carrier, в результатах 0 users.
2. Кнопка «Активировать карту» disabled.
3. Toast/alert «Сотрудник не найден. Добавьте в /admin-users».
4. Можно сбросить cascading вручную (тап «Очистить фильтры»).

### AC3: PASSENGER_ANONYMOUS без userId
1. Тип «Пассажир анонимный».
2. Никаких dropdown'ов кроме опционального region (можно пропустить).
3. UI кнопка «Активировать карту» enabled даже если userList визуально пустой (для этой роли ok).
4. POST `entityType="none", entityId=null`.
5. ASOP_CARDS.USER_ID=NULL. VCM1 entityRaw=16 zero bytes.

### AC4: multi-role bitmask cross entityType (запрет)
1. Оператор через UI НЕ может создать cross-entityType bitmask (UI не позволяет multi-role).
   Поддерживается только если backend выдал через future endpoint.
2. Если карта прочитана с cross-entityType bitmask → CardReadScreen warning, не активировать как есть.

### AC5: legacy card (02B206F1 already VCM1 SUPER_ADMIN userId)
- Чтение работает по новой схеме: `entity.type="userid"`, ищем user в ASOP_USERS — пользователь находится.
- Активация повторно: VCM1 OVERRIDE, serverCardId=existing, новый оператор должен заново подтвердить userId.

### AC6: legacy SAC1 карта
- Не активируется через этот flow. Ошибка: «Карта в формате SAC1 — не поддерживается. Используйте VCM1».
  (User должен перепрошить в VCM1 через отдельный admin-script, OUT OF SCOPE промпт 009.)

### AC7: PASSENGER с опц. region
1. Тип → PASSENGER → DropDown «Регион» (опц.) + UserSearchField (обяз.).
2. Без region — dropdown users = все пользователи системы (~7000).
3. С region — JOIN ASOP_USER_REGIONS. Если у юзера несколько регионов → он появится в любом из них.
4. userId обязательно → выбирается → тап «Активировать» → POST `entityType=userId`.

## Файлы для изменения
```
backend/shared/api/card-api/src/main/kotlin/ru/asop/api/card/dto/request/CardActivateRequest.kt
backend/card-service/src/main/kotlin/ru/asop/card/service/CardActivationService.kt
backend/card-service/src/main/kotlin/ru/asop/card/controller/CardController.kt   (роутинг не меняется)
backend/shared/asop-proto/src/main/proto/asop.proto                             (опционально для proto)

frontend/android-terminal/app/src/main/java/ru/asop/terminal/activation/AsopCardType.kt
frontend/android-terminal/app/src/main/java/ru/asop/terminal/activation/EntityType.kt
frontend/android-terminal/app/src/main/java/ru/asop/terminal/activation/CardIdentityVcm1.kt   (entity = userId-or-null)
frontend/android-terminal/app/src/main/java/ru/asop/terminal/network/models/CardActivationModels.kt
frontend/android-terminal/app/src/main/java/ru/asop/terminal/ui/screen/CardActivationViewModel.kt
frontend/android-terminal/app/src/main/java/ru/asop/terminal/ui/screen/CardActivationScreen.kt
frontend/android-terminal/app/src/main/java/ru/asop/terminal/nfc/MifareClassicCardWriter.kt
frontend/android-terminal/app/src/main/java/ru/asop/terminal/nfc/MifareClassicReader.kt

AGENTS.md    (документация — обновить секцию ASOP_KEYS и MIFARE Classic)
prompts/prompt_009.md → DONE
```

## Verify после применения
1. Build backend (3 модуля): `./gradlew :backend:card-service:bootJar :backend:gateway-service:bootJar`
2. Build APK: `./gradlew :app:assembleDebug`
3. Deploy: `docker compose -f infrastructure/docker/docker-compose.yml up -d --build card-service gateway-service`
4. Install APK: `adb -s of8e6020 install -r app/build/outputs/apk/debug/app-debug.apk`
5. Run acceptance scenarios AC1-AC7.
6. Проверить ASOP_CARD_MIFARES.IDENTITY_JSON для свежих активаций: `entity.type IN ("userid", ABSENT)`.
7. ASOP_CARDS.USER_ID заполнен для всех ролей кроме PASSENGER_ANONYMOUS.

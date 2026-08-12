# Промпт 008: MIFARE Classic — компактный unsigned VCM1 формат

## Контекст и мотивация

На clone-карте с broken CRYPTO1 read-side стабилен только **4 сектора**
(192 байта = 12 data-блоков × 16). SAC1 с RSA-PSS подписью (256 байт) не помещается.
Этот промпт вводит **новый компактный формат CardIdentity на MIFARE Classic** —
без подписи, multi-role через битовую маску.

**Принцип**: VCM1 = "Versioned Compact Mifare 1". Clean break — старые SAC1 карты
не поддерживаются (при первом тапе требуется re-activation).

## Архитектура

### Layout на карте (192 байта)

| Блок | Сектор | Размер | Содержание |
|---|---|---|---|
| block 0 | sector 1 (addr 4) | 16 | `"VCM1"` magic [0..3] + `bitmask` UInt16 LE [4..5] + reserved zeros [6..15] |
| block 1 | sector 1 (addr 5) | 16 | `cardId` UUID v7 binary (16 raw bytes) |
| block 2 | sector 1 (addr 6) | 16 | `entityUuid` UUID v7 binary (16 raw bytes) — для роли, выбранной highest-bit-wins; zeroed для PASSENGER_ANONYMOUS |
| block 3 | sector 2 (addr 8) | 16 | zeroed — резерв (для будущих VALID_FROM/VALID_UNTIL) |
| block 4 | sector 2 (addr 9) | 16 | zeroed — резерв |
| block 5 | sector 2 (addr 10) | 16 | zeroed — резерв |
| blocks 6..11 | sectors 3–4 (addr 12..28) | 96 | zeroed — резерв на будущее |

**Используется**: 48 байт (3 блока в sector 1: magic+bitmask, cardId, entityUuid). Sector 2 и sectors 3-4 — резерв. Sector 0 — стандартный MIFARE manufacturer block (UID).

### UUID encoding

- **16 байт binary**, byte order согласно RFC 4122 (big-endian MSB-first).
- На карте — 16 raw bytes MSB-first.
- При выводе в JSON/log — `String.format("%02x", byte[i])` × 16, либо `UUID.fromString(hex.toString())`.
- UUID v7 включает timestamp (48 бит unix ms) — для удобства сортировки и диагностики.

### Multi-role: битовая маска + entityUuid (single-slot)

**Bitmask** = UInt16 (2 bytes LE):
- 14 активных бит: `bit i = (1 << (AsopCardType.ordinal(i)))`
- bit 14–15: reserved (всегда 0)
- Генерируется UI при выборе ролей; по нему сервер восстанавливает entityUuid.

**cardId и entityUuid обе лежат в sector 1** (block 1 и block 2 соответственно).

### Mapping ролей → entity field

| Bit | Role | Entity field (источник UUID'а) |
|---|---|---|
| 0 | SUPER_ADMIN | userId |
| 1 | REGION_ADMIN | regionId |
| 2 | ORGANIZER_ADMIN | organizerId |
| 3 | CARRIER_ADMIN | carrierId |
| 4 | DISTRIBUTOR_ADMIN | cardsDistributorId |
| 5 | KRS_ADMIN | auditServiceId |
| 6 | CARRIER_DISPATCHER | carrierId |
| 7 | DISTRIBUTOR_DISPATCHER | cardsDistributorId |
| 8 | KRS_DISPATCHER | auditServiceId |
| 9 | DRIVER | carrierId |
| 10 | KRS_FOREMAN | auditServiceId |
| 11 | KRS_CONTROLLER | auditServiceId |
| 12 | PASSENGER | userId |
| 13 | PASSENGER_ANONYMOUS | (zeroed — anonymous mode) |

### Multi-role правила ("single-slot highest-bit-wins")

`entityUuid` — **единственный** UUID на карте. Multi-role поддерживается через
bitmask, но UUID хранится только ОДИН — для role, выбранной highest-bit-wins.

Сканируем bitmask с ordinal 0 до 13 до первой set-bit:

1. **Найдена первая set-bit** → пишем UUID соответствующего entity-field в `entityUuid`.
2. **Same-entity-type остальные roles в bitmask** объединяются на стороне сервера при
   регистрации (например, для DRIVER + CARRIER_DISPATCHER = carrierId для обеих — роли
   в `ASOP_USER_ROLES` хранятся отдельно, а entityUuid ссылается на тот же carrierId).
3. **Если найдены разные entity-types** (напр. SUPER_ADMIN + DRIVER → userId + carrierId):
   `entityUuid` хранит UUID для highest-bit role (USER_ID). Второй UUID
   **НЕ записывается на карту**; серверная БД выводит связь из связующих таблиц
   (например, `ASOP_USER_CARRIERS`).
4. **PASSENGER_ANONYMOUS (bit 13)**: `entityUuid` = zeroed. Только cardId в block 1 валиден.

### Примеры

| Bitmask (hex) | Bits | Выбранный role (highest-bit) | entityUuid field |
|---|---|---|---|
| `0x0001` | SUPER_ADMIN | SUPER_ADMIN | userId |
| `0x0240` | CARRIER_DISPATCHER + DRIVER | CARRIER_DISPATCHER (ordinal 6, выше priority) | carrierId |
| `0x0241` | SUPER_ADMIN + CARRIER_DISPATCHER + DRIVER | SUPER_ADMIN (ordinal 0) | userId |
| `0x2000` | PASSENGER_ANONYMOUS | PASSENGER_ANONYMOUS | zeroed |

## Серверная сторона

### DTO card-service (`CardActivateRequestClassic` — новый DTO):

```kotlin
data class CardActivateRequestClassic(
    val technology: String,        // "CLASSIC"
    val cardId: String,            // UUID v7, генерируется Android-терминалом локально
    val bitmask: Int,              // UInt16
    val entityType: String,        // "userId"|"regionId"|"carrierId"|...
    val entityId: String?,         // UUID, null для PASSENGER_ANONYMOUS
)
```

### Эндпоинт `/api/v1/cards/activate`

**Body параметры**:
- `technology = "CLASSIC"`
- `cardId` — UUID v7 (Android-терминал сгенерировал локально по UTC дате-времени)
- `uid` — MIFARE UID карты (4-7 bytes hex), обязательное поле
- `bitmask: UInt16`
- `entityType: String` + `entityId: UUID?`

**Серверная логика нового потока (UID-based master cardId)**:

```
1. Поиск карты в БД по UID (ASOP_CARD_MIFARES.UID):
   - Если запись найдена (карта уже активирована ранее):
       a. Берём существующий card_id из ASOP_CARDS.
       b. Сравниваем с переданным cardId:
          - совпадает → OK
          - НЕ совпадает → СЕРВЕР ИСПОЛЬЗУЕТ СВОЙ serverCardId, терминал обязан
            перезаписать block 1 (cardId) на серверный
       c. Возвращаем ответ: cardId = serverCardId (НЕ echo!)
   - Иначе (новая карта):
       a. Создаём ASOP_CARDS с переданным cardId (это первый и валидный).
       b. Возвращаем cardId = переданный.
2. Валидируем:
   - bitmask ∈ [0, 0x3FFF] (14 бит)
   - entityType соответствует хотя бы одной роли в bitmask
   - Если entityType="userId" → проверить, что user_id существует в ASOP_USERS
     (кроме PASSENGER_ANONYMOUS).
3. Insert/Update ASOP_CARD_MIFARES с новым IDENTITY_JSON (VCM1 format),
   IDENTITY_SIGNATURE = NULL, CARD_ROLE = primaryRoleName (highest-bit).
4. Дополнительные роли в bitmask (начиная со второй set-bit):
   записываются как ASOP_USER_ROLES, ASOP_USER_CARRIERS, ASOP_USER_REGIONS
   соответственно; серверная БД логика берёт secondary entity-type из связующих таблиц
   (например, для DRIVER + CARRIER_ADMIN оба пишут user_roles: DRIVER + CARRIER_ADMIN,
   а carrierId доступен как ASOP_USER_CARRIERS[user_id]).
5. Если активация карты на терминале меняет serverCardId — возвращаем его в response.
   Терминал обязан перезаписать block 1 (иначе активация будет считаться неполной).
```

**Назначение**: сервер является **master authority** для cardId. Терминал-генерирует локально
только как **placeholder**; сервер проверяет по UID и при необходимости корректирует.
Это гарантирует:
- Карты не могут иметь «свой» cardId, не привязанный к серверу-БД.
- При tap терминал шлёт `cardId` вместе с `uid`; серверный endpoint `/sync/cardauth`
  проверяет `(uid, cardId)` whitelist.
- Если clone-карта скопирована на другой carrier, перезаписанный cardId не пройдёт
  серверный whitelist-чек.

**НЕ делаем**: client-generated cardId = final. ALLOWED: client-generated как черновик,
сервер override.

### DTO ответ (`CardActivationResponseClassic`):

```kotlin
data class CardActivationResponseClassic(
    val cardId: String,
    val bitmask: Int,
    val entityType: String?,
    val entityId: String?,
    val serverTime: Long,
    val sequenceNumber: Long,
)
```

Сервер может вернуть список дополнительных ролей и их entity-связей, если
bitmask содержит >1 роли. Для MVP — только primary (highest-bit) entity.

### `IDENTITY_JSON` формат в БД (ASOP_CARD_MIFARES):

```json
{
  "format": "VCM1",
  "formatVersion": 1,
  "cardId": "019ff470-...",
  "bitmask": 66,
  "entity": { "type": "carrierId", "id": "019ff289-..." },
  "activatedAt": "2026-08-12"
}
```

`IDENTITY_SIGNATURE` колонка остаётся в БД как nullable TEXT (`NULL` для VCM1-карт).

## Android изменения

### 1. `frontend/android-terminal/app/src/main/proto/card_identity.proto`

Не создаём отдельный VCM1 proto — слишком мало данных (48 байт). Читаем/пишем
как прямые byte arrays с фиксированным layout. proto3 излишен для 192 байт.

Создаём Kotlin-структуру в `activation/`:

```kotlin
data class CardIdentityVcm1(
    val cardId: UUID,
    val bitmask: Int,                // UInt16
    val entity: EntityRef?,          // single slot — highest-bit-wins UUID; null для ANONYMOUS
) {
    val VCM1_MAGIC = "VCM1".toByteArray(Charsets.US_ASCII)
    const val MAGIC_SIZE = 4
    const val BITMASK_OFFSET = 4
    const val BITMASK_SIZE = 2
    const val CARD_ID_OFFSET = 16       // block 1 (sector 1)
    const val ENTITY_OFFSET = 32        // block 2 (sector 1)
    const val BLOCK_SIZE = 16
    const val USED_BLOCK_COUNT = 3      // sector 1 only

    fun encodeAsBytes(): ByteArray { ... }
    companion object {
        fun decodeFromBytes(buf: ByteArray): CardIdentityVcm1? { ... }
    }
}

data class EntityRef(val type: EntityType, val id: UUID)
enum class EntityType {
    USER, REGION, ORGANIZER, CARRIER, CARDS_DISTRIBUTOR, AUDIT_SERVICE;
    fun toFieldName(): String = when(this) {
        USER -> "userId"
        REGION -> "regionId"
        ORGANIZER -> "organizerId"
        CARRIER -> "carrierId"
        CARDS_DISTRIBUTOR -> "cardsDistributorId"
        AUDIT_SERVICE -> "auditServiceId"
    }
    companion object {
        fun forAsopCardTypeOrdinal(ordinal: Int): EntityType? = when(ordinal) {
            0, 12 -> USER              // SUPER_ADMIN, PASSENGER
            1 -> REGION                // REGION_ADMIN
            2 -> ORGANIZER             // ORGANIZER_ADMIN
            3, 6, 9 -> CARRIER         // CARRIER_ADMIN, *_DISPATCHER, DRIVER
            4, 7 -> CARDS_DISTRIBUTOR  // DISTRIBUTOR_*
            5, 8, 10, 11 -> AUDIT_SERVICE  // KRS_*
            else -> null               // PASSENGER_ANONYMOUS = 13
        }
    }
}
```

### 2. `frontend/android-terminal/app/src/main/java/ru/asop/terminal/nfc/MifareClassicCardWriter.kt`

**Удалить**:
- `SAC1_MAGIC`, `SAC1_VERSION`, `buildSac1Payload`, `parseSac1Payload`, `Sac1Payload`,
  весь код SAC1.
- Все упоминания `secondaryEntity` / `primaryEntity` в layout.

**Добавить**:
- Объект `Vcm1Layout` — константы offsets, magic bytes, encode/decode.
- **`writeVcm1(tag, vcm1, keyA, keyB): WriteResult`** — пишет ТОЛЬКО sector 1 (3 data-блока, 48 байт):
  - block 0: VCM1 magic + bitmask + reserved
  - block 1: cardId UUID v7 binary
  - block 2: entityUuid (entity, выбранного highest-bit-wins) или zeros для PASSENGER_ANONYMOUS
  - Sector 2 и sectors 3-4 **не трогаем** (no-op writeIdentity, только sector 1).
  - Использовать existing `multiPassWrite(..., isExisting=false)` для retry/fail-handling.

- **`readVcm1(tag, candidateKeys): CardIdentityVcm1?`**:
  - Auth sector 1 + probe.
  - Read 3 data-блока sector 1 (48 bytes).
  - Validate "VCM1" magic → null если mismatch (старая SAC1 карта).
  - Parse bitmask + cardId + entity → return.
  - Если SAC1 magic — return null + log "old SAC1 card detected".

**Cleanup existing private methods**: `writeIdentity`, `writeIdentityInternal` deprecated —
заменить на `writeVcm1Internal`. Multi-pass, hard-reset, auth-probe остаются как есть
(их логика переиспользуется в writeVcm1Internal).

**Поскольку writeIdentity писал все 15 sectors (полная re-provisioning), а writeVcm1
пишет только 1 sector — это упрощает и ускоряет активацию.** Если карта была ранее
записана SAC1-format во все sectors, при переходе на VCM1 sectors 2-15 могут быть
перезаписаны нулями (на writeId стороне cleanup). На стороне read VCM1 читает
только sector 1, остальные игнорирует.

### 3. `frontend/android-terminal/app/src/main/java/ru/asop/terminal/ui/screen/CardActivationViewModel.kt`

**Изменения**:
1. `processClassicTargetCard`:
   - Если readVcm1 вернул null и SAC1 magic → `message = "Карта устаревшего формата (SAC1), требуется re-activation"`.
   - Если readVcm1 OK → заполнить prefs (bitmask, primary/secondary).

2. `provisionClassicCard` (новая signature):
   ```kotlin
   private suspend fun provisionClassicCard(
       tag: Tag,
       vcm1: CardIdentityVcm1,
   ): String?
   ```
   - ВЫЗОВ: `writer.writeVcm1(tag, vcm1, keyA, keyB)`.

3. **Новый поток активации**:
   ```
   User submits form → build CardIdentityVcm1 (bitmask + primary + secondary)
   → POST /api/v1/cards/activate {technology="CLASSIC", cardId, bitmask, ...}
   → response.cardId (serverUuid, может отличаться от client)
   → если serverCardId != clientCardId:
       - обновить vcm1.cardId = serverCardId
   → writer.writeVcm1(tag serverCardId-based vcm1, ...)
   → success UI
   ```

4. **Удалить**: всю логику signature/signing для Classic (`pendingWriteSignature`, signCardIdentity, etc).

### 4. `frontend/android-terminal/app/src/main/java/ru/asop/terminal/activation/AsopCardType.kt`

Добавить extensions:

```kotlin
companion object {
    /** Highest-set bit (lowest ordinal) → primary role → entityUuid на карте. */
    fun primaryRoleForBitmask(bitmask: Int): AsopCardType? {
        for (i in 0..13) {
            if ((bitmask shr i) and 1 == 1) return entries.getOrNull(i)
        }
        return null
    }
    /** Все bits в bitmask, dropped first one. Для UI: показать все роли пользователя. */
    fun allRolesForBitmask(bitmask: Int): List<AsopCardType> =
        (0..13).filter { (bitmask shr it) and 1 == 1 }.mapNotNull { entries.getOrNull(it) }
    /** Bitmask → entity-type → серверная связь через ASOP_USER_ROLES / ASOP_USER_CARRIERS. */
    fun bitsForEntityTypeAndId(entity: EntityType, entityId: String): Int {
        var bits = 0
        for (role in entries) {
            if (EntityType.forAsopCardTypeOrdinal(role.ordinal) == entity) {
                bits = bits or (1 shl role.ordinal)
            }
        }
        return bits
    }
}
```

### 5. `frontend/android-terminal/app/src/main/java/ru/asop/terminal/ui/screen/CardReadViewModel.kt`

`onTagDiscovered` для Classic:
- Вызвать `MifareClassicReader.read(tag, keys)`.
- Распарсер видит `classicInfo.vcm1Identity` (rename из `sac1Identity`).
- **НЕТ signature verify** для Classic VCM1 — unsigned format.
- UI показывает: bitmask + entity tags + validFrom/Until (позже).

### 6. `frontend/android-terminal/app/src/main/java/ru/asop/terminal/nfc/MifareClassicReader.kt`

- `ReadResult.classicInfo.sac1Identity` → переименовать в `vcm1Identity: CardIdentityVcm1?`.
- `parseSac1FromDump` → удалить.
- Добавить `parseVcm1FromDump(blocksMap)` → возвращает `CardIdentityVcm1?` или null.

## Web-admin (`/Cards` страница)

### Изменения:
1. **Удалить column "Подпись"** для Classic-карт (signed column hidden, всегда "—").
2. **Добавить column "Bitmask"** с иконкой + role-tags:

   ```
   SUPER_ADMIN + CARRIER_DISPATCHER + DRIVER
   [⚡SUPER_ADMIN] [🎯CARRIER_DISPATCHER] [🚗DRIVER]   bitmask=0x0241
   ```

3. **Filter sidebar**: добавить chip-фильтр по каждой роли (14 фиксированных).
   Можно AND-combine (выбрать `SUPER_ADMIN` + `CARRIER_DISPATCHER` → найти карты у которых
   ОБА бита set).
4. **Card detail**: показывать primary entity (например "Перевозчик: ГУП Крымавтотранс")
   и список связанных ролей из server-side ASOP_USER_ROLES/CARRIERS/REGIONS.

## Тесты

### Unit tests (`MifareClassicCardWriterTest`):

1. `writeVcm1_SuperAdminOnly_serializesBitmask=0x0001` — записать, прочитать, проверить.
2. `writeVcm1_MultiRole_dispatcherAndDriver_mergesToCarrier` — записать bitmask=0x0240,
   прочитать cardId + entity=carrierId (highest-bit DISPATCHER wins).
3. `writeVcm1_DifferentEntityTypes_superAdminDriver_writesSuperAdmin` — записать bitmask=0x0241,
   прочитать entity=userId (SUPER_ADMIN highest-bit wins).
4. `writeVcm1_PassengerAnonymous_entityZeroed` — bitmask=0x2000, entity=zero UUID.
5. `roundTrip_cardId_uuidV7` — UUID v7 → байты → UUID без потерь.
6. `decode_rejectsOldSac1Card` — byte buffer начинается с "SAC1" → returns null.
7. `writeVcm1_writesOnlySector1` — после write остальные 11 data-блоков не меняются
   (или zero если была чистая карта).
8. `primaryRoleForBitmask_emptyIfAllZero` — bitmask=0 → null.
9. `primaryRoleForBitmask_picksLowestOrdinal` — bitmask=0x0300 (bits 8,9) → returns KRS_DISPATCHER (ordinal 8), not DRIVER (ordinal 9).

### Integration:

1. Tap clone карта (old SAC1) → UI "устаревший формат, re-activation".
2. UI form filled → activate → server response with new cardId (или
   override client-generated) → writeVcm1 → tap card → readVcm1 → matches server cardId.

## Liquibase migration `v002-classic-vcm1.sql`

```sql
COMMENT ON COLUMN ASOP_CARD_MIFARES.IDENTITY_JSON IS
  'VCM1 JSON (промпт 008): {format:"VCM1", cardId, bitmask, entity{type,id}}. Для DESFire PKI карт — старый формат с signature.';
COMMENT ON COLUMN ASOP_CARD_MIFARES.IDENTITY_SIGNATURE IS
  'RSA-PSS signature для DESFire PKI карт. NULL для Classic VCM1 (unsigned).';

-- Валидация формата VCM1 для IDENTITY_JSON when present.
ALTER TABLE ASOP_CARD_MIFARES
  ADD CONSTRAINT chk_identity_json_vcm1_or_null CHECK (
    IDENTITY_JSON IS NULL OR
    IDENTITY_JSON LIKE '{"format":"VCM1"%'
  );

COMMENT ON COLUMN ASOP_CARD_MIFARES.CARD_ROLE IS
  'Primary role string (для DESFire — PKI principal; для Classic — primary bitmask role).';
```

## AGENTS.md / doc updates

- Заменить все ссылки на SAC1 в AGENTS.md на VCM1.
- Удалить секцию про RSA-PSS verify (Classic) — заменить на:
  "**Classic VCM1 is unsigned**; сервер-side cardId-based whitelist обеспечивает защиту".
- Добавить раздел "Server cardId master-flow": UID-lookup преимущество над
  client-generated cardId; терминал обязан перезаписать block 1 в случае server override.
- Обновить раздел про активацию карт: новый DTO `CardActivateRequestClassic` с bitmask+entities.

## Известные ограничения

1. **Без цифровой подписи**: пользователь теоретически может скопировать layout блоков 0-2
   (48 байт, sector 1) на другую карту, если бы simultaneous read+write была возможна
   (на clone-карте одновременно не работает). Серверный **UID-based cardId whitelist** —
   основная защита.
2. **Multi-role = single-slot**: на карте лежит только ОДИН entityUuid для highest-bit role.
   Для связей secondary-routes (если разные entity-types в bitmask) — серверная БД через
   `ASOP_USER_CARRIERS/ASOP_USER_REGIONS` восстанавливает связи по user_id.
3. **VALID_FROM/VALID_UNTIL — НЕ реализованы** (зарезервирован block 3 внутри sector 2,
   и sectors 3-4 целиком). Добавятся позже.
4. **MIFARE UID — единственный server-trusted identifier** (UID уникален и неизменен,
   хранится в `ASOP_CARD_MIFARES.UID` в БД, читается из sector 0 manufacturer block).
5. **Sectors 2-4 zeroed / не используются** в VCM1 картах. Sector 1 — единственный carriers.
6. **Backward compat**: SAC1 карты не поддерживаются — требуется re-activation при первом тапе.

## Порядок внедрения

1. Создаём Kotlin-структуры `CardIdentityVcm1` + `EntityType` в `activation/`.
2. `MifareClassicCardWriter` → writeVcm1 / readVcm1 / cleanup SAC1.
3. `CardActivationViewModel` → provisioning в VCM1.
4. `card-service` + `card-api` → новые DTOs, endpoint contract, server flow с UID-lookup.
5. Liquibase v002 + обновлённый IDENTITY_JSON format validation.
6. `web-admin` /Cards → bitmask column + role tags, filter sidebar.
7. Unit + integration tests.
8. AGENTS.md update.

## Резюме изменений (после уточнений пользователя)

| Аспект | Решение |
|---|---|
| UUID encoding | 16 bytes binary (MSB-first, RFC 4122) |
| Multi-role | single-slot highest-bit-wins (только ОДИН entityUuid) |
| entityUuid поле | переименовано в `entity` (без "primary" префикса) |
| secondary entity | удалён, нет места для второго UUID |
| Card_id и entity | **оба в sector 1**: block 1 (cardId) + block 2 (entityUuid) |
| Used blocks | **3** (только sector 1, blocks 0-2) |
| Sectors 2-4 | zeroed / no-op writeIdentity (write border 1 sector only) |
| Sectors 3-4 | zeroed (резерв на будущее) |
| Backward compat | clean break — SAC1 не support |
| Server storage | IDENTITY_JSON (VCM1 compact) + IDENTITY_SIGNATURE nullable |
| Server authority | Сервер держит master cardId — перезаписывает client-генерированный при tap |
| VALID_FROM/UNTIL | reserved (позже) |

**Преимущество компактного варианта**: writeIdentity пишет только 1 sector (48 байт) —
быстрее на unstable clone, надёжнее даже если sectors 2-4 read нестабильны. Только
sector 1 надёжно читается (как мы выяснили диагностически).
</content>
</invoke>
# Промпт 006: Ренейм ASOP_3DES_KEYS → ASOP_KEYS + MIFARE Classic read

## 1. Ренейм ASOP_3DES_KEYS → ASOP_KEYS

Переименовать таблицу `ASOP_3DES_KEYS` и все связанные сущности в `ASOP_KEYS` / `AsopKeys`. Новый admin-эндпоинт — `/api/v1/asop-keys` (не `keys` — коллизия с crypto-service в ServiceRegistry).

### БД
`infrastructure/db-migrations/asop_schema.sql` + `migrations/v001-init.sql` (зеркально):
- Таблица `ASOP_3DES_KEYS` → `ASOP_KEYS`
- `pk_3des_keys` → `pk_asop_keys`
- Индексы `ix_asop_3des_keys_*` → `ix_asop_keys_*`
- Триггеры `trg_*_asop_3des_keys` → `trg_*_asop_keys`
- COMMENT'ы на таблицу и колонки

### admin-service
- `ThreeDesKeyEntity` → `KeyEntity` (`@Table("ASOP_KEYS")`)
- `ThreeDesKeyRepository` → `KeyRepository`
- `ThreeDesKeyController` → `KeyController` (`@RequestMapping("/api/v1/asop-keys")`)
- `ThreeDesKeyResponse` → `KeyResponse`
- `GenerateResponse` (inner data class) — оставить как есть (локальный DTO)

### gateway
`ServiceRegistry`: `"three-des-keys"` → `"asop-keys"` (admin-service:8091). Маппинг `"keys"` → crypto-service не трогать.

### orchestrator
- `MasterRegistry.kt`: `"asop_3des_keys"` → `"asop_keys"`, `admin("three-des-keys")` → `admin("asop-keys")`
- `ThreeDesKeyService.kt` → `KeyService.kt` (класс, конструктор, KDoc, `TABLE = "asop_keys"`, `CFG_*` константы)
- `KeyRotationScheduler.kt`: параметр `threeDesKeyService: ThreeDesKeyService` → `keyService: KeyService`, URI `three-des-keys` → `asop-keys`, все логи
- `DeltaSyncService.kt` / `FullSyncService.kt`: импорт `KeyService`, `protoClassName` → `"AsopKeysFile"`
- `PurgeJob.kt`: `ThreeDesKeyService.TABLE` → `KeyService.TABLE`
- `OrchestratorProperties.kt`: `ThreeDesKeys` → `Keys`, поле `threeDesKeys` → `keys`
- `application.yml`: `asop.three-des-keys.*` → `asop.keys.*`

### crypto-service
- `ServerKeyService.kt`: `encrypt3desKey` → `encryptKey`, `decrypt3desKey` → `decryptKey`, `generate3desKey` → `generateKey`. KDoc — обновить.
- `ServerKeyController.kt`: заменить вызовы
- `application.yml`: env `DEV_3DES_KEY_BASE64` → `DEV_ASOP_KEY_BASE64`, `DEV_3DES_KEY_MODE_ENABLED` → `DEV_ASOP_KEY_MODE_ENABLED`. YAML-ключи (`dev-key-base64`, `dev-mode-enabled`) оставить. Dev-ключ остаётся 24-байтным: `AAECAwQFBgcICQoLDA0ODxAREhMUFRYX`.
- `RootCaProperties.kt`: KDoc `ServerKeyConfig` — обновить (убрать "3DES")

### crypto-api
- `ServerKeyResponses.kt`: `Generate3desKeyResponse` → `GenerateKeyResponse`
- `ServerKeyApi.kt`: `generate3desKey()` → `generateKey()`

### proto (две копии — синхронно)
`backend/shared/asop-proto/src/main/proto/schema.proto` + `frontend/android-terminal/app/src/main/proto/schema.proto`:
- `message Asop3desKeysRow` → `message AsopKeysRow`
- `message Asop3desKeysFile` → `message AsopKeysFile`
- Поле в `DeltaChunk`: `repeated Asop3desKeysRow asop_3des_keys = 43;` → `repeated AsopKeysRow asop_keys = 43;` (номер 43 сохранить)

### Android
- `ReferenceSyncStore.kt`: `THREE_DES_TABLE = "asop_3des_keys"` → `KEYS_TABLE = "asop_keys"`, все ветки `applyChunk`/`applyFile`, импорт `Asop3desKeysFile` → `AsopKeysFile`
- `TerminalKeyEntity.kt` / `TerminalKeyCryptor.kt`: KDoc (заменить «3DES» на «ASOP_KEYS материал»)
- `AppDatabase.kt`: импорты/entity list

### web-admin
- `api/threeDesKeys.ts` → `api/asopKeys.ts` (путь `/asop-keys`)
- `pages/ThreeDesKeys.tsx` → `pages/AsopKeys.tsx` (заголовок «Ключи АСОП»)
- `types/reference.ts`: `ThreeDesKey` → `AsopKey`
- `App.tsx`: роут `/asop-keys`, импорт `AsopKeysPage`
- `layouts/Sidebar.tsx`: label «Ключи АСОП»
- `api/index.ts`: экспорт `asopKeys` вместо `threeDesKeys`

### docker-compose.yml
`DEV_3DES_KEY_BASE64` → `DEV_ASOP_KEY_BASE64`, `DEV_3DES_KEY_MODE_ENABLED` → `DEV_ASOP_KEY_MODE_ENABLED` (строки 179-180, crypto-service)

### Документация
- `AGENTS.md` — вся секция про 3DES-ключи (строки ~141-154): `ASOP_3DES_KEYS` → `ASOP_KEYS`, `ThreeDesKeyService` → `KeyService`, `three-des-keys` → `asop-keys`, `threeDesKeys.*` → `keys.*`
- `doc/context.md` — секция про 3DES-ключи (строки ~462-483)

---

## 2. KEY_MATERIAL — ключи произвольной длины (без изменения генерации)

Поле `KEY_MATERIAL` (`TEXT`) хранит RSA-шифротекст (base64), длина которого определяется RSA-ключом (RSA-2048 OAEP-SHA256 вмещает до 190 байт plaintext). **DDL не меняется.**

Изменить валидацию в `ServerKeyService.kt` (строки 113, 132):
- `require(plain.size == 24)` → `require(plain.isNotEmpty() && plain.size <= 190)`
- KDoc: «хранит ключи произвольной длины; генерируются 24-байтные для DESFire 3K3DES; MIFARE Classic использует первые 12 байт (Key A = [0..5], Key B = [6..11])»

Генерацию не менять — `ByteArray(24)` с `SecureRandom`. Dev-ключ 24-байтный.

---

## 3. Правило префиксов — один пул ASOP_KEYS

Один пул 24-байтных ключей, потребитель берёт нужное количество байт:
- **MIFARE Classic**: Key A = `keyMaterial[0..5]` (6 байт), Key B = `keyMaterial[6..11]` (6 байт)
- **DESFire**: весь 24-байтный 3K3DES-ключ целиком
- **AES (будущее)**: первые 16 байт

---

## 4. Android «Прочитать карту» — MIFARE Classic (read-only MVP)

Добавить поддержку MIFARE Classic в меню «Прочитать карту».

### Обнаружение карты
`CardReadViewModel.onTagDiscovered`: если `MifareClassic.get(tag) != null` — читать Classic-веткой; иначе — существующий DESFire.

### Новый файл: `nfc/MifareClassicReader.kt`
```kotlin
data class ClassicReadResult(
    val type: String,           // "MIFARE Classic 1K" / "4K"
    val uid: String,            // hex
    val sectorsTotal: Int,
    val sectorsAuthenticated: List<Int>,  // секторы с успешным auth
    val keyTypeBySector: Map<Int, String>, // "A" / "B" / "factory" / "ASOP"
    val blocks: Map<Int, ByteArray>,       // blockIndex -> 16 байт данных
    val notes: List<String>
)
fun read(tag: Tag, asopKeys: List<ByteArray>): ClassicReadResult
```

### Кандидаты ключей (порядок перебора)
1. Заводские (из `doc/specifications/MifareClassic.md`):
   - `FF FF FF FF FF FF` (transport / `MifareClassic.KEY_DEFAULT`)
   - `A0 A1 A2 A3 A4 A5` (MIFARE Application Directory)
   - `D3 F7 D3 F7 D3 F7` (NFC Forum)
2. Из ASOP_KEYS (`terminalKeyDao.getActive(10)`, KEY_ID DESC):
   - `keyCryptor.decrypt(e.keyMaterialEnc)` → 24 байта
   - Key A = `decrypted.copyOfRange(0, 6)`
   - Key B = `decrypted.copyOfRange(6, 12)`
   - Уникальные, отсеять короче 12 байт

### Аутентификация и чтение
- `MifareClassic.get(tag)` → `connect()` → `setTimeout(500)`
- Для **каждого сектора** (0..`sectorCount-1`):
  - Перебирать Key A кандидатов, затем Key B
  - `mfc.authenticateSectorWithKeyA(sector, key)` / `authenticateSectorWithKeyB(sector, key)`
  - При первом успешном auth: читать все блоки сектора (`mfc.readBlock(blockIndex)`)
  - Запомнить тип ключа ("A"/"B", "factory"/"ASOP")
  - Переходить к следующему сектору
- **Все доступные секторы** — не останавливаться после первого
- Общий таймаут — **30 секунд**
- **Read-only**: никаких `writeBlock`/`formatSector`

### UI
`CardReadScreen.kt` — расширить отображение для MIFARE Classic:
- Тип (Classic 1K/4K), UID (hex)
- Секторов доступно: N из M
- Маска найденного ключа (первые 2 байта + «…», не показывать полный ключ)
- Дамп блоков: hex-строки по 16 байт, сектор заголовок
- Блок 0 (UID + BCC) — отдельной строкой

### Совместимость
Некоторые Android-устройства (Broadcom NFC) не поддерживают MIFARE Classic — `MifareClassic.get(tag)` вернёт `null`. Samsung S24 FE с NXP PN-series — поддерживает. Если `MifareClassic.get(tag) == null` — fallback на DESFire-ветку.

---

## 5. Верификация

- `./gradlew bootJar` — сборка бэкенда
- `cd frontend/android-terminal && ./gradlew :app:assembleDebug` — сборка терминала
- `cd frontend/web-admin && npm run build` — сборка web-admin
- `git grep -ic "3des\|3DES\|Asop3des\|three.des\|three_des\|THREE_DES"` — проверить, что остались только исторические KDoc/комментарии
- `git grep "DEV_3DES"` — не должно быть ни одного совпадения

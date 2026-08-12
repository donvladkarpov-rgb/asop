# Промпт: активация MIFARE Classic на терминале (auto-detect Classic/DESFire)

## Цель
Реализовать активацию карт АСОП на **MIFARE Classic (1K/4K)** в дополнение к существующему DESFire-флоу. При прикладывании карты терминал **определяет технологию** (`MifareClassic.get(tag) != null` → CLASSIC, иначе DESFire) и маршрутизирует в соответствующий flow. DESFire-функциональность сохраняется полностью.

## Не менять
- DESFire-активация: `CardActivationViewModel` (шаги DESFire) и `DesfireCardWriter` — без изменений.
- Серверная подпись `POST /api/v1/sync/smart-cards/sign` (crypto-service) и `CardActivationService` (логика авторизации/подписи/upsert).
- `ReferenceForm` (регион/организатор/перевозчик/дистрибьютор/КРС/пользователь) — общая, работает из `reference_rows`, технологии не знает.
- Ренейм ключей не требуется: **Key A = `keyMaterial[0..5]`**, **Key B = `keyMaterial[6..11]`** (уже так в `MifareClassicReader.buildCandidateKeys`).

## 1. Детекция технологии (CardActivationViewModel)
```kotlin
enum class CardTech { DESFIRE, CLASSIC, UNSUPPORTED }
fun detectTech(tag: Tag): CardTech = when {
    MifareClassic.get(tag) != null -> CardTech.CLASSIC // как в CardReadViewModel
    IsoDep.get(tag) != null        -> CardTech.DESFIRE
    else                           -> CardTech.UNSUPPORTED
}
```
Обработка dual-tech (редко): если есть и `MifareClassic`, и `IsoDep` — пробуем Classic auth factory-ключом на секторе 0; успех → CLASSIC, иначе DESFIRE. При `UNSUPPORTED` — сообщение «Карта не поддерживается».

Диспетчер вызывается в трёх местах:
- `Step.AuthForm` (авторизующая карта);
- `Step.TargetCard` (целевая карта);
- ре-прикладывание после серверной регистрации (`runWrite`) — запомнить `pendingWriteTech: CardTech` при анализе целевой карты.

## 2. Новый модуль `nfc/MifareClassicCardWriter.kt`
Использовать Android tech API `MifareClassic` (`authenticateSectorWithKeyA/B`, `readBlock`, `writeBlock`), а не raw NfcA-опкоды.

API:
- `detectState(tag, candidateKeys): ClassicState` — NEW / EXISTING(identity proto + prev fields) / UNRECOGNIZED (для п.4).
- `newCard(tag, payload, keyA, keyB)` — новая фабричная карта: для каждого сектора identity-области auth factory-ключом → записать trailer `KeyA|AccessBits|KeyB` → записать блоки данных payload.
- `reflash(tag, payload, workingKeys)` — существующая: auth ASOP-ключами по секторам области → перезаписать payload (при ротации ключей — обновить trailer).
- `readStream(tag, keys): ByteArray?` — авторизовать первый сектор области, читать блоки пока не наберётся полный payload (по len в header).
- Верификация **read-back обязательна** (прочитать записанное и сравнить).
- Access bits: **transport-профиль** (см. §7.1) — data-блоки RW-by-KeyA, trailer — KeyA write-only, KeyB read+write. После записи factory-ключи на области НЕ работают — проверка: повторный auth factory-ключом даёт отказ.
- Retry-цикл 3×300 ms при `IOException` («Tag was lost»), как в `DesfireCardWriter` — clone-карты Classic теряют поле.

## 3. Формат payload на Classic (proto + raw-подпись 256 Б)
```
Magic "SAC1" (4 байта ASCII) | version (1 байт = 0x01) | lenProto (2, LE) | lenSig (2, LE = 256)
| protoBytes (CardIdentity proto, CardIdentityCodec.serialize) | signatureRaw (256 байт)
```
Линейная упаковка в 16-байтные блоки данных identity-области; хвост блока добивается незначащими байтами. Конец payload определяется по `lenProto + lenSig`, сканировать дальше не нужно.

**Identity-область:** секторы 1..15 (data-блоки). Влезает полный identity (~600 Б) на 1K, для 4K достаточно тех же секторов. **Сектор 0 не трогать** (manufacturer block: UID+BCC, перезапись блокирует карту).

`SignatureVerifier`: добавить `verifyRaw(protoBytes: ByteArray, signatureRaw: ByteArray): Boolean` (RSA-PSS-SHA256, тот же `PSSParameterSpec("SHA-256","MGF1",MGF1ParameterSpec.SHA256,32,1)`; canonical JSON строится из proto тем же `buildCanonicalString`, отличие от `verify(...)` только в отсутствии base64-декодирования подписи). Существующий `verify(base64)` не трогать.

## 4. Определение «новая / existing» для Classic
На первом секторе области (сектор 1):
1. auth **factory-ключами** (`FF FF FF FF FF FF`, `A0 A1 A2 A3 A4 A5`, `D3 F7 D3 F7 D3 F7` — список уже в `MifareClassicReader`) → **new**;
2. иначе auth **ASOP-ключами** из `terminal_keys` (decrypt через `TerminalKeyCryptor`, Key A/B по п.2) → **existing**: читать стрим `readStream`, парсить proto, предзаполнить `previous*` поля (как `processWithIsoDep`);
3. ни то, ни другое либо magic≠"SAC1" → ошибка «Карта не ASOP или ключи устарели».

## 5. Авторизация — полный паритет (Classic-операторы тоже)
`identifyAuthCard` маршрутизировать по `detectTech`:
- **DESFire** — как сейчас (`identifyCard` → IsoDep + identity file 0).
- **CLASSIC** — новый `identifyClassicCardRoles(tag)`: перебор ASOP-ключей → auth сектора 1 → `readStream` → proto → `roles`. Если карта с factory-ключами (не ASOP) → «Авторизующая карта не активирована». Далее `CardActivationMatrix.canAuthorize(roles, targetRole)` — один общий путь.

`ZERO_KEY` (24 нуля) используется только в DESFire-ветке; для Classic «новая карта» определяется по factory-ключам.

## 6. Целевая карта Classic — новый `processTargetCardClassic(tag)`
По `detectState`:
- NEW: `targetCardUid` (= tag.id hex), `targetCardMode="new"`, `workingKeyForCard=null`, `pendingWriteTech=CLASSIC`, → `loadReferenceData()`.
- EXISTING: `mode="existing"`, `workingKeyForCard=asopKey`, prev-поля → `loadReferenceData()`.
- UNRECOGNIZED: сообщение об ошибке, `busy=false`.

`provisionCard` маршрутизировать по `pendingWriteTech`:
- CLASSIC + new → `MifareClassicCardWriter.newCard(tag, payload, keyA, keyB)` (keyA/B = фрагменты новейшего ключа из `terminal_keys`).
- CLASSIC + existing → `reflash(tag, payload, workingKey)`.
- DESFIRE — как сейчас (`writeIdentity`/`reflashComplete`).

Таймаут прошивки: для Classic достаточно `withTimeout(60_000)` (write быстрый).

## 7. Сервер — поле технологии в ASOP_CARD_MIFARES (БЕЗ нового CARD_TYPE) + proto
Никаких новых строк в `ASOP_CARD_TYPES`. Добавить в `ASOP_CARD_MIFARES`:
```sql
CARD_TECH VARCHAR(10) NOT NULL DEFAULT 'DESFIRE',
CONSTRAINT chk_card_tech CHECK (CARD_TECH IN ('DESFIRE', 'CLASSIC'))
```
Колонка — в `asop_schema.sql` и зеркально в `migrations/v001-init.sql` (единый changeset v001, правка тела — Liquibase checksum меняется, при `docker compose down -v` пересоздаётся).

Проброс: в `CardActivateRequest` (asop-card-api DTO) добавить optional `technology: String?`; терминал шлёт `"CLASSIC"`/`"DESFIRE"`. В `CardActivationService.insertNew`/`updateExisting` писать `cardTech` (default `"DESFIRE"`) в `CardMifareEntity` (добавить поле). `buildCard` оставить `cardTypeId = MIFARE_DESFIRE_TYPE` — тип карты в `ASOP_CARDS` не меняется, технология живёт в `ASOP_CARD_MIFARES.CARD_TECH`.

**Proto:** `CardMifaresRow.card_tech = 21` (новое поле) в **обоих** schema.proto:
- `backend/shared/asop-proto/src/main/proto/schema.proto`
- `frontend/android-terminal/app/src/main/proto/schema.proto`

DeltaSupport в card-service (для `asop_card_mifares`) мапит `row.cardTech` ↔ entity; orchestrator (`MasterRegistry` + `DeltaSyncService`/`FullSyncService`) вкладывает `card_tech` в `CardMifaresRow`. На терминале `ReferenceSyncStore.applyChunk`/`applyFile` (autogen-deserialize) читают новое поле, в `reference_rows.payloadJson` оно сохраняется. Существующие записи с `card_tech` отсутствующим в proto (старый бинарник) остаются совместимыми — proto3 optional-семантика.

## 7.1. Access bits (transport-профиль)
**Trailer-блок** (16 байт): `KeyA(6) | AccessBits(4) | KeyB(6)` = `?? ?? ?? ?? ?? ?? FF 07 80 69 ?? ?? ?? ?? ?? ??`.
- bytes 6..9 = `FF 07 80 69` (C1=0, C2=0, C3=0 + inverted `C1⊕C2⊕C3` = `0x69`).
- Эффект: KeyA **write-only** (читать нельзя даже владельцу), KeyB — read+write; data-блоки RW-by-KeyA и RW-by-KeyB.

**Data-блоки** (16 байт каждый): просто payload; access bits для них задаёт тот же trailer (по умолчанию RW-by-KeyA).

**Проверка после записи:** повторный auth factory-ключом на этом секторе ⇒ `Auth fail` (ключ больше не `FF…`). Это индикатор успешной смены ключа.

**NB:** MifareClassic 1K — 16 секторов × 4 блока (3 data + 1 trailer). 4K — 40 секторов (sectors 0–31 по 4 блока, 32–39 по 16 блоков). Identity пишем в **секторы 1..15** (общее для 1K/4K), не задействуя sector 0 (UID+BCC) и sector 16+ (4K-only, зарезервировано под расширение).

## 7.2. Web-admin badge
`frontend/web-admin/src/pages/Cards.tsx`: Chip слева от `cardId`:
- `cardTech === "CLASSIC"` → MUI `<Chip color="success" label="Classic" size="small" />` (зелёный)
- иначе (включая `null`/`undefined` у legacy-записей) → `<Chip color="primary" label="DESFire" size="small" />` (синий)

`frontend/web-admin/src/types/reference.ts` (или в card api types): опц. поле `cardTech?: string` в типе карточки. API endpoint `/api/v1/cards` уже возвращает `card_mifares`-связку — добавить `card_tech` в card-service controller DTO (если ещё не прошёл через entity→DTO mapping).

## 8. Изменения файлов (список)
**Android:**
- `frontend/android-terminal/.../nfc/MifareClassicCardWriter.kt` — новый.
- `frontend/android-terminal/.../nfc/MifareClassicReader.kt` — дополнить `readStream(tag, candidateKeys)` для `MifareClassicCardWriter`/`identifyClassicCardRoles` (переиспользование `buildCandidateKeys`).
- `frontend/android-terminal/.../ui/screen/CardActivationViewModel.kt` — detectTech, маршрутизация auth/target/write, pendingWriteTech, identifyClassicCardRoles, processTargetCardClassic, payload-сборка (SAC1) в provisionCard, CARD_TECH в CardActivateRequest.
- `frontend/android-terminal/.../db/SignatureVerifier.kt` — `verifyRaw(protoBytes, signatureRaw)`.
- `frontend/android-terminal/.../network/models/CardActivationModels.kt` — `technology` в `CardActivateRequest`.
- `frontend/android-terminal/app/src/main/proto/schema.proto` — `CardMifaresRow.card_tech = 21`.

**Backend:**
- `backend/shared/api/card-api/...` — `CardActivateRequest` DTO (поле `technology: String? = null`).
- `backend/card-service/.../CardActivationService.kt` + `CardMifareEntity` (`cardTech: String = "DESFIRE"`) + repository + DeltaSupport mapping для `asop_card_mifares`.
- `backend/shared/asop-proto/src/main/proto/schema.proto` — `CardMifaresRow.card_tech = 21`.
- `backend/orchestrator-service/.../MasterRegistry.kt` + `DeltaSyncService.kt`/`FullSyncService.kt` — проброс `card_tech` в `CardMifaresRow` при сборке дельты.

**Web-admin:**
- `frontend/web-admin/src/pages/Cards.tsx` — Chip DESFire/Classic.
- `frontend/web-admin/src/types/reference.ts` — `cardTech?: string` в типе карточки.

**Схема БД:**
- `infrastructure/db-migrations/asop_schema.sql` + `migrations/v001-init.sql` — колонка `CARD_TECH` + CHECK (единый changeset v001).

**Документация:**
- `AGENTS.md` / `doc/context.md` — раздел активации (Classic-ветка, формат SAC1, identity-область секторы 1..15, transport access bits, ключи KeyA/B из 24-байтового `terminal_keys`, `CARD_TECH` + proto `card_tech=21`).

## 9. Верификация
- Backend: `./gradlew bootJar` — BUILD SUCCESSFUL.
- Android: `cd frontend/android-terminal && ./gradlew :app:assembleDebug` — BUILD SUCCESSFUL.
- Smoke на физической карте Classic 1K: активация новой карты (factory→ASOP keys, read-back), перерегистрация (reflash), повторное чтение ролей с Classic-операторской карты; отрицательная проверка — Classic с чужими ключами даёт понятную ошибку.
- Проверить, что `ASOP_CARD_MIFARES.CARD_TECH='CLASSIC'` записался через `POST /api/v1/sync/cards/activate` (в терминале или в БД).
- **Регенерация ethalon `android-test`:** после изменений proto (`CardMifaresRow.card_tech=21`) пересоздать `frontend/android-test/app/src/main/assets/expected-*.json` через `infrastructure/docker/generate-ethalon.sh` — старый эталон не парсится новым proto-биндингом (opencard schema mismatch).

## 10. Открытые риски (зафиксировать в коде KDoc)
- Запись access bits может «заблокировать» сектор при ошибке → перед реальной записью делать read-back; при неудаче не переходить к следующим секторам.
- Clone-карты Classic (дешёвые) могут терять поле — retry-цикл до 3 раз при IOException (как в `DesfireCardWriter`).
- Liquibase checksum `v001-init` меняется при добавлении `CARD_TECH` — `docker compose down -v` обязателен между запусками (соответствует конвенции AGENTS.md).
- Proto3 optional-семантика: старые терминалы без `card_tech=21` в своём schema.proto будут получать пустую строку при delta-sync — не критично (только отображается в web-admin), но при появлении таких терминалов в проде стоит явно/documentbackwards-compat.

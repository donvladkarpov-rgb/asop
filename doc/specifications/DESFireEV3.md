# MIFARE DESFire EV3 — работа с картой (спецификация)

Практическая справка по работе с контактными бесконтактными картами **NXP MIFARE DESFire** (D40/EV1/EV2/EV3) через NFC на Android-терминале. Собрано по итогам реальной отладки и проверено на карте (см. `../context.md` и `frontend/android-terminal/.../nfc/DesfireCardReader.kt`, `DesfireAuthProbe.kt`).

## 1. Семейство и поколения

| Поколение | Артикул NXP (datasheet) | Криптоканалы (опкоды) |
|-----------|--------------------------|-----------------------|
| D40 | MF3ICD40 | 2K3DES (`0x0A`), 3K3DES (`0x1A`), без AES |
| **EV1** | MF3ICD21/41/81 | AES (`0xAA`), 3K3DES (`0x1A`), 2K3DES (`0x0A`), ReadSignature |
| EV2 | MF3ICD22/42/82 | + AuthenticateEV2First (`0x71`), CMAC, SUN |
| **EV3** | MF3DHx3 (2K/4K/8K) | + ECC-ключи, GetCardCertificate (`0x65`) |

Все поколения читаются одними и теми же командами PICC-уровня; различие — в поддерживаемой криптографии (EV2/EV3-only команды).

### Откуда берутся коды и как их получить

**Поколение (EV1/EV2/EV3)** — из **реальной карты**, команда `GetVersion` (`0x60`): байт «HW subtype» (`byte[2]`).

```kotlin
val ver = transceive(0x60)                                 // 28 байт
val subtype = ver[2].toInt() and 0xFF
val generation = when (subtype) { 0x01 -> "EV1"; 0x02 -> "EV2"; 0x03 -> "EV3"; else -> "?" }
```

- `0x01` = EV1, `0x02` = EV2, `0x03` = EV3. Это единственный надёжный способ узнать поколение в рантайме.
- Некоторые клоны сообщают искажённые значения (субтип `0x01` при заявленном «EV3») — см. раздел «Признаки негенуинной карты».

**Размер памяти** — тоже из `GetVersion`, байт «HW storage size» (`byte[5]`), декодируется как `2^(код >> 1)` байт (см. раздел 4).

**Артикулы `MF3ICD…` / `MF3DH…`** — это **внутренние коды заказа NXP из datasheet**, а не то, что карта сообщает. Читатель их «не получает с карты» — они нужны только для сопоставления с продавцом/поставщиком. По ним поколение/память **не определяется на карте**, используются базовые данные из `GetVersion`.

**Опкоды криптографии (`0x0A`, `0xAA`, `0x1A`, `0x71`) и командные опкоды (`0x60`, `0x5A`, …)** — из **таблицы команд функциональной спецификации NXP** (MIFARE DESFire Command Table). Полная спецификация — под NDA, поэтому опкоды сверены по открытым реализациям (TalkToYourDESFireCard, NFCjLib) и **проверены на реальной карте** в этом проекте. Ниже в разделах 5–6 все опкоды воспроизведены с проверкой на живом железе.

## 2. Технология доступа

- DESFire работает по **ISO/IEC 14443-4** (Layer 4), на Android — `IsoDep` (classic APDU-обёртки не нужны, DESFire использует native-фрейминг).
- Если `IsoDep` нет в techList — карта в Layer 2, фолбэк через `NfcA` с native-фреймингом.
- Команда: `[INS] [параметры]`. Ответ: `[статус] [данные]`.

```kotlin
// Android: отправка и разбор
val iso = IsoDep.get(tag); iso.connect(); iso.timeout = 3000
val resp = iso.transceive(byteArrayOf(0x60)) // GetVersion
```

## 3. Формат ответа и многофреймовость

DESFire использует два фрейминга: **native** (основной, Layer 4) и **wrapped** (ISO 7816, `90 INS …`). Приложение работает в native — статус возвращается первым байтом ответа.

### Native-фрейминг (первый байт ответа)

| Байт 0 ответа | Значение |
|---------------|----------|
| `0x00` | Успех, данные сразу после |
| `0xAF` | Дополнительный кадр — запросить продолжение командой `0xAF` |
| `0x0C` `0x1C` `0x1E` `0x40` `0x7E` `0x9D` `0x9E` `0xAE` `0xFD` | Ошибка (одиночный байт-статус, см. таблицу ниже) |

### Wrapped-фрейминг (ISO 7816) — отдельный формат

`[data] [0x91] [SW2]`. `0x91` = префикс wrapped-ответа (не статус!), `91 00` = успех. Приложение не использует; в логах встречается при `transceive(byteArrayOf(0x90, INS, 0, 0, 0))`.

### Коды ошибок (native, одиночный байт; либо SW2 в wrapped-режиме)

| Код | Смысл |
|-----|-------|
| `0x00` | Operation OK (успех) |
| `0x0C` | Нет изменений (конец дампа/подписи) |
| `0x1C` | **Illegal Command Code** — команда не поддерживается чипом (например `GetCardCertificate` на EV1/EV2) |
| `0x1E` | Нарушение целостности (CRC) |
| `0x40` | **No such key** — слот ключа не существует (не путать с `0xAE`) |
| `0x7E` | Ошибка длины |
| `0x9D` | Нет прав / приложение не найдено |
| `0x9E` | Out of EEPROM |
| `0xAE` | Ошибка аутентификации (ключ существует, но неверный/не того типа) |
| `0xC1` | Файл не найден |
| `0xFD` | Требуется аутентификация |

> Частая путаница: `0x40` (No such key — слота нет) vs `0xAE` (AuthError — слот есть, ключ неверный). `0x1C` = «чип не знает команду», а не «приложение/файл не найдено» (последнее = `0x9D`/`0xC1`).

Пример ответа на `0x60`: `00 04 01 01 33 00 1A 05 …` — первый байт `00` (успех), дальше 28 байт версии.

## 4. GetVersion (0x60) — формат 28 байт

| Смещение | Байты | Поле |
|----------|-------|------|
| 0 | 1 | HW vendor (0x04 = NXP) |
| 1 | 1 | HW type (0x01 = DESFire; 0x02 Plus; 0x08 DESFire Light) |
| 2 | 1 | HW subtype (0x01 EV1, 0x02 EV2, 0x03 EV3) |
| 3 | 1 | HW major version |
| 4 | 1 | HW minor version |
| 5 | 1 | **HW storage size (код)** |
| 6 | 1 | HW protocol (0x01 = ISO14443-A) |
| 7–13 | 7 | SW vendor/type/subtype/version/version/storage/protocol (аналогично HW) |
| 14–20 | 7 | UID |
| 21–25 | 5 | Batch number |
| 26 | 1 | Дата выпуска: **неделя (BCD)** |
| 27 | 1 | Дата выпуска: **год-2000 (BCD)** |

### Размер памяти
Код хранится как **`2^(code >> 1)`** байт; бит 0 кода означает `= ` (ровно) или `> ` (не менее).

| Код | Размер |
|-----|--------|
| `0x12` | 512 Б |
| `0x16` | 2 КБ |
| `0x18` | 4 КБ |
| `0x1A` | **8 КБ** |
| `0x1C` | 16 КБ |

```kotlin
fun storageLabel(code: Int) = if (code >= 2 && code <= 0x3F) {
    val bytes = 1L shl (code shr 1)
    val rel = if (code and 1 == 0) "=" else ">"
    "$rel${bytes / 1024} КБ (код 0x%02X)".format(code)
} else "нестандартный код 0x%02X".format(code)
```

### Дата выпуска (BCD)
Два байта BCD: байт 26 = неделя, байт 27 = год с 2000.

```kotlin
fun bcd(b: Int) = ((b shr 4) and 0x0F) * 10 + (b and 0x0F)
val week = bcd(d[26].toInt() and 0xFF)      // 0x34 -> неделя 34
val year = 2000 + bcd(d[27].toInt() and 0xFF) // 0x25 -> 2025
```

## 5. Команды PICC-уровня (без аутентификации)

| Опкод | Команда | Описание |
|-------|---------|----------|
| `0x60` | GetVersion | 28-байтная версия |
| `0x6E` | GetFreeMemory | 3 байта свободной памяти |
| `0x6A` | GetApplicationIDs | Список AID (тройки байт) |
| `0x51` | GetCardUID | 7 байт UID (EV1+, требует auth) |
| `0x45` | GetKeySettings | Байт настроек ключей (например `0F 01`) |
| `0x64` | GetKeyVersion | `64 KeyNo` → `00 <1 байт версии>` (EV1+; EV0 ответил бы `0x1C`) |
| `0x5A` | SelectApplication | `5A AID(3)` — выбрать приложение, `5A 00 00 00` = мастер PICC |
| `0xCA` | CreateApplication | Создать приложение (`AID(3) [KeySettings] [NumKeys]`) |
| `0xC4` | ChangeKey | Сменить ключ слота |
| `0x6F` | GetFileIDs | Список ID файлов |
| `0x6C` | GetValue | Прочитать Value-файл (НЕ SelectApplication!) |
| `0x3C` | ReadSignature | Подпись originality (EV1+), чтение без аутентификации. Формат — ECDSA/ANSSIG (не RSA). На клонах часто возвращает `0x0C`/`0x1C`. |
| `0x65` | GetCardCertificate | Сертификат NXP (только EV2/EV3); на EV1 → `0x1C` (Illegal Command Code) |

**Осторожно с опкодами**: `0x6C` — это GetValue, а **не** SelectApplication (`0x5A`); `0x6F` — GetFileIDs, а **не** GetKeySettings (`0x45`). `0x1A` — AuthenticateISO 3K3DES, а **не** AuthenticateAES (`0xAA`). В старых материалах эти опкоды путают.

```kotlin
// Пример: свободная память (00 20 00 -> 8192)
val free = (b0.toLong() shl 16) or (b1.toLong() shl 8) or b2.toLong()
```

## 6. Аутентификация

Ключи лежат в **слотах 0–13**; тип ключа в слоте фиксирован (DES / 2K3DES / 3K3DES / AES / ECC). Дефолтные заводские ключи — нулевые (`00…00`, 16 байт для 3K3DES/AES). Слот 0 — мастер-ключ.

| Метод | Опкод | Ключ | Когда |
|-------|-------|------|-------|
| AuthenticateISO (2K3DES) | `0x0A` | 16 байт | D40, старые |
| **AuthenticateISO (3K3DES)** | `0x1A` | 16 байт | EV1-дефолт, legacy |
| AuthenticateAES | `0xAA` | 16 байт AES | EV1+ (после ChangeKey на AES) |
| AuthenticateEV2First | `0x71` | 16 байт AES | EV2/EV3; требуется для `readSignatureFull`, CMAC-операций |

### 3K3DES-рукопожатие (0x1A) — полный протокол
Ключ `K` = 16 нулевых байт, `E_K` = 3DES-CBC (IV=0), `RotLeft(x)` = циклический сдвиг на 1 байт.

1. `1A KeyNo` → `AF <8>` = `E_K(RndB)`
2. Расшифровать `RndB`, сгенерировать `RndA` (8 байт)
3. `AF <E_K(RndA ‖ RotLeft(RndB))>` (IV = ответ карты из шага 1)
4. Карта → `E_K(RotLeft(RndA))` (IV = последние 8 байт шага 3)
5. Сверить результат с `RotLeft(RndA)`

```kotlin
// Шаги 1-2
val step1 = unwrapFrame(transceive(byteArrayOf(0x1A, keyNo))) // 8 байт RndB' (зашифр.)
val rndB = tripleDesCbcDecrypt(step1, iv = zeros(8))          // IV=0
val rndA = randomBytes(8)
// Шаги 3-5
val x = rndA + rotLeft(rndB)                    // RndA + RotLeft(RndB), 16 байт
val c = tripleDesCbcEncrypt(x, iv = step1)
val resp = transceive(byteArrayOf(0xAF) + c)    // 8 байт: E_K(rotLeft(rndA))
require(resp == tripleDesCbcEncrypt(rotLeft(rndA), iv = c.takeLast(8)))
```

> Внимание: порядок `rndA + rotLeft(rndB)`, не `rndB + rotLeft(rndA)` — ошибка в порядке даст `0xAE` на step2 даже с правильным ключом.

### Типизация ключей — частый источник ошибок
Если в слоте лежит 3DES-ключ, а вы шлёте `0xAA` (AES) — карта отвечает `0xAE` (authentication error). Это нормальное поведение, а не баг: `0xAA` заработает после `ChangeKey` слота на AES-ключ.

## 7. Проверка подлинности (originality)

Два независимых механизма:

| Проверка | Механизм | Статус на клоне |
|----------|----------|-----------------|
| **Asymmetric** | RSA-подпись NXP (32 байта, читается `0x3C`), проверяется открытым ключом NXP | Может «пройти» — на EV1 подпись не привязана к UID, клон копирует валидную подпись |
| **Symmetric** | SUN/CMAC по уникальному ключу чипа (EV2/EV3) | **Всегда fail** на клоне |

**Вывод**: на EV1 симметричная проверка не поддерживается в принципе (это фича EV2/EV3) — её отсутствие не признак подлинности. Настоящий EV2/EV3 проходит **обе** проверки.

## 8. Признаки негенуинной карты (генуинность)

Оригинальные NXP-карты:

- HW vendor `0x04`, subtype `0x01–0x03`, **HW-версия ≤ 9**;
- протокол `0x01` (ISO14443-A);
- на неизвестную команду отвечают **`0x1C`** (Illegal Command Code);
- байт памяти — корректный код (см. раздел 4).

Наблюдаемый фингерпринт клона (Fudan FM11HD08NS и подобные):

| Поле | Клон | Оригинал |
|------|------|----------|
| HW-версия | `51.0` (`0x33`) | ≤ 9 |
| Протокол | `0x05` | `0x01` |
| Неизвестная команда `0x77` | `0x7E` | `0x1C` |
| GetCardCertificate `0x65` | `0x1C` (нет) | Есть (EV2/EV3) |
| Симметричная проверка | fail | pass (EV2/EV3) |

## 9. Особенности EV3 (ECC, SUN)

- **ECC-ключи**: пара генерируется на карте, сертификат NXP через `GetCardCertificate` (`0x65`). На EV3 возвращает сертификат; на EV1/EV2 и клонах → `0x1C` (Illegal Command Code — чип не знает команду). Позволяет схему: ECC-пара → серт от NXP/ASOP → подпись челленджа картой → проверка на валидаторе.
- **SUN** (Secure Unique NFC): короткое аутентифицированное сообщение для валидаторов (транспорт).
- **Proximity check**: проверка физической близости карты (`0x7A`-семейство, см. `authenticateAesEv2FirstProximity` в TalkToYourDESFireCard).
- EV1 эти команды не знает; ECC-схема возможна только на генуинном EV2/EV3.

> Примечание: точный опкод генерации ECC-пары (`GenerateKeyPair`) в открытых библиотеках не найден и требует NDA-спецификации NXP.

## 10. Минимальная последовательность для определения карты

```kotlin
// 1. GetVersion -> поколение, память, дата
val ver = transceive(0x60)          // 28 байт
val gen = when (ver[2].toInt() and 0xFF) { 1 -> "EV1"; 2 -> "EV2"; 3 -> "EV3" }
// 2. GetFreeMemory, GetApplicationIDs
// 3. Проба криптографии: SelectApplication(5A 000000) -> 3K3DES(1A) [EV1] / EV2First(71) [EV2/3]
// 4. GetCardCertificate(65) — только EV2/EV3
// 5. Verdict: клон если HW-версия > 9, протокол != 01, отклик != 0x1C на неизвестную
```

## 11. Источники

- NXP datasheet EV3: `https://www.nxp.com/docs/en/data-sheet/MF3D_H_X3_SDS.pdf` (полная функциональная спецификация — под NDA)
- TalkToYourDESFireCard (протоколы, EV3-примеры): `https://github.com/MichaelsPlayground/TalkToYourDESFireCard`
- MifareDesfireEv3TutorialNFCjLib: `https://github.com/AndroidCrypto/MifareDesfireEv3TutorialNFCjLib`
- NFC TagInfo by NXP: `https://play.google.com/store/apps/details?id=com.nxp.taginfolite`

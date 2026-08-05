# MIFARE DESFire EV1 — работа с картой (спецификация)

Практическая справка по работе с **NXP MIFARE DESFire EV1** через NFC на Android-терминале. Собрано по итогам реальной отладки (в т.ч. на клоне, продаваемом как «EV3»). См. также `DESFireEV3.md` (общее семейство, форматы) и код `frontend/android-terminal/.../nfc/DesfireCardReader.kt`, `DesfireAuthProbe.kt`.

> Зачем выделять EV1 отдельно: если приходится работать именно с EV1 — это значит, что **ECC/сертификаты/SUN (EV2/EV3-фичи) недоступны**, и подлинность глубоко не проверить. Ниже — что EV1 умеет, на что опираться и какой сценарий реализовать.

## 1. Что такое EV1 и его место в семействе

| Поколение | Артикул NXP | Криптоканалы |
|-----------|-------------|--------------|
| D40 | MF3ICD40 | 2K3DES (`0x0A`), 3K3DES (`0x1A`), без AES, без ReadSignature |
| **EV1** | **MF3ICD21/41/81** | **3K3DES (`0x1A`), 2K3DES (`0x0A`), AES (`0xAA`), ReadSignature** |
| EV2 | MF3ICD22/42/82 | + EV2First (`0x71`), CMAC, SUN |
| EV3 | MF3DHx3 | + ECC, GetCardCertificate |

EV1 — «золотая середина»: уже имеет AES-канал и читаемую подпись NXP, но **не имеет** EV2/EV3-криптографии.

### Чего на EV1 НЕТ (важно для планирования)
- `AuthenticateEV2First` (`0x71`), `GetCardCertificate` (`0x65`), ECC-ключи, SUN, CMAC-обмен и `readSignatureFull` — **не поддерживаются** (отвечают ошибкой `0x1C`).
- Симметричная (symmetric) проверка подлинности — только EV2/EV3.

### Как получить поколение
Из `GetVersion` (`0x60`), байт «HW subtype» `byte[2]`: `0x01` = EV1.

```kotlin
val ver = transceive(0x60)
val generation = when (ver[2].toInt() and 0xFF) { 0x01 -> "EV1"; 0x02 -> "EV2"; 0x03 -> "EV3" }
```

Артикулы `MF3ICD21/41/81` (2K/4K/8K) — коды заказа из datasheet NXP, карта их не сообщает; на карте поколение/память читаются только из `GetVersion`.

## 2. Технология доступа

- ISO/IEC 14443-4 (Layer 4), на Android — `IsoDep`. Native-фрейминг, classic-APDU-обёртки не нужны.
- Фолбэк при отсутствии `IsoDep` в techList — `NfcA` (Layer 2, native).
- Команда: `[INS] [параметры]`; ответ: `[статус] [данные]`.

```kotlin
val iso = IsoDep.get(tag); iso.connect(); iso.timeout = 3000
val resp = iso.transceive(byteArrayOf(0x60)) // GetVersion
```

## 3. Формат ответа и коды ошибок

DESFire использует два фрейминга: **native** (основной, Layer 4) и **wrapped** (ISO 7816, `90 INS …`). Приложения на Android работают в native — статус возвращается первым байтом ответа.

### Native-фрейминг (первый байт ответа)

| Байт 0 ответа | Значение |
|---------------|----------|
| `0x00` | Успех, данные следом |
| `0xAF` | Дополнительный кадр — продолжить командой `0xAF` |
| `0x0C` `0x1C` `0x1E` `0x40` `0x7E` `0x9D` `0x9E` `0xAE` `0xFD` | Ошибка (одиночный байт-статус, см. таблицу ниже) |

### Wrapped-фрейминг (ISO 7816) — отдельный формат

`[data] [0x91] [SW2]`. Здесь `0x91` = префикс wrapped-ответа (не статус!), `91 00` = успех. Этот формат приложение не использует; указан для справки — он встречается в логах при `transceive(byteArrayOf(0x90, INS, 0, 0, 0))`.

### Коды ошибок (native, одиночный байт; либо SW2 в wrapped-режиме)

| Код | Смысл |
|-----------|-------|
| `0x00` | Operation OK (успех) |
| `0x0C` | Нет изменений (конец дампа/подписи) |
| `0x1C` | **Illegal Command Code** — команда не поддерживается чипом (например `GetCardCertificate` на EV1) |
| `0x1E` | Нарушение целостности (CRC) |
| `0x40` | **No such key** — слот ключа не существует (не путать с `0xAE`) |
| `0x7E` | Ошибка длины |
| `0x9D` | Нет прав / приложение не найдено |
| `0x9E` | Out of EEPROM |
| `0xAE` | Ошибка аутентификации (ключ существует, но неверный/не того типа) |
| `0xC1` | Файл не найден |
| `0xFD` | Требуется аутентификация |

> Частая путаница: `0x40` (No such key — слота нет) vs `0xAE` (AuthError — слот есть, ключ неверный). `0x1C` = «чип не знает команду», а не «приложение/файл не найдено» (последнее = `0x9D`/`0xC1`).

## 4. GetVersion (0x60) — 28 байт

| Смещение | Байты | Поле |
|----------|-------|------|
| 0 | 1 | HW vendor (0x04 = NXP) |
| 1 | 1 | HW type (0x01 = DESFire) |
| 2 | 1 | **HW subtype (EV1 = 0x01)** |
| 3 | 1 | HW major version |
| 4 | 1 | HW minor version |
| 5 | 1 | HW storage size (код) |
| 6 | 1 | HW protocol (0x01 = ISO14443-A) |
| 7–13 | 7 | SW версия (аналогично HW) |
| 14–20 | 7 | UID |
| 21–25 | 5 | Batch number |
| 26 | 1 | Дата выпуска: неделя (BCD) |
| 27 | 1 | Дата выпуска: год-2000 (BCD) |

### Размер памяти
`размер = 2^(код >> 1)` байт; бит 0 кода = `=` (ровно) / `>` (не менее).

| Код | Размер |
|-----|--------|
| `0x12` | 512 Б |
| `0x16` | 2 КБ |
| `0x18` | 4 КБ |
| `0x1A` | 8 КБ |
| `0x1C` | 16 КБ |

```kotlin
fun storageLabel(code: Int) = if (code in 2..0x3F)
    "${if (code and 1 == 0) "=" else ">"}${(1L shl (code shr 1)) / 1024} КБ (код 0x%02X)".format(code)
else "нестандартный код 0x%02X".format(code)
```

### Дата выпуска (BCD)
`неделя = bcd(byte[26])`, `год = 2000 + bcd(byte[27])`, где `bcd(b) = (b>>4)*10 + (b&0x0F)`.

## 5. Команды PICC-уровня (без аутентификации)

| Опкод | Команда | Описание |
|-------|---------|----------|
| `0x60` | GetVersion | 28-байтная версия |
| `0x6E` | GetFreeMemory | 3 байта свободной памяти |
| `0x6A` | GetApplicationIDs | Список AID (тройки байт) |
| `0x51` | GetCardUID | 7 байт UID (EV1+, требует auth) |
| `0x45` | GetKeySettings | Настройки ключей (2 байта, напр. `0F 01`) |
| `0x64` | GetKeyVersion | `64 KeyNo` → `00 <1 байт версии>` (EV1+; EV0 ответил бы `0x1C`) |
| `0x5A` | SelectApplication | `5A AID(3)`; `5A 00 00 00` = мастер PICC |
| `0xCA` | CreateApplication | Создать приложение |
| `0xC4` | ChangeKey | Сменить ключ слота |
| `0x6F` | GetFileIDs | Список ID файлов |
| `0x6C` | GetValue | Прочитать Value-файл (НЕ SelectApplication!) |
| `0x3C` | ReadSignature | Подпись originality (EV1+), чтение без аутентификации. Формат — ECDSA/ANSSIG, не RSA. На клонах часто возвращает `0x0C` (NoChanges) или `0x1C` — подпись недоступна. |
| `0x65` | GetCardCertificate | Сертификат NXP (только EV2/EV3); на EV1 → `0x1C` (Illegal Command Code) |

**Осторожно с опкодами**: `0x6C` — GetValue, **не** SelectApplication (`0x5A`); `0x6F` — GetFileIDs, **не** GetKeySettings (`0x45`); `0x1A` — AuthenticateISO 3K3DES, **не** AuthenticateAES (`0xAA`).

Пример: свободная память `00 20 00` → `0x002000` = 8192 байт (8K).

## 6. Аутентификация на EV1

Ключи — в слотах 0–13, тип ключа в слоте фиксирован. **Заводской дефолт EV1 — 3K3DES-ключ `00…00` (16 байт)**. Слот 0 — мастер-ключ.

| Метод | Опкод | Ключ | Назначение |
|-------|-------|------|------------|
| AuthenticateISO 2K3DES | `0x0A` | 16 байт | старые/D40 |
| **AuthenticateISO 3K3DES** | `0x1A` | 16 байт | **дефолт EV1** |
| AuthenticateAES | `0xAA` | 16 байт AES | после `ChangeKey` слота на AES |

### 3K3DES-рукопожатие (`0x1A`) — полный протокол
`K` = 16 нулевых байт, `E_K` = 3DES-CBC (IV=0), `RotLeft(x)` = циклический сдвиг на 1 байт.

1. `1A KeyNo` → `AF <8>` = `E_K(RndB)`
2. Расшифровать `RndB`, сгенерировать `RndA` (8 байт)
3. `AF <E_K(RndA ‖ RotLeft(RndB))>` (IV = ответ карты из шага 1)
4. Карта → `E_K(RotLeft(RndA))` (IV = последние 8 байт шага 3)
5. Сверить с `RotLeft(RndA)`

```kotlin
val step1 = unwrapFrame(transceive(byteArrayOf(0x1A, keyNo))) // E_K(RndB)
val rndB = tripleDesCbcDecrypt(step1, iv = zeros(8))
val rndA = randomBytes(8)
val x = rndA + rotLeft(rndB)                       // RndA + RotLeft(RndB), 16 байт
val c = tripleDesCbcEncrypt(x, iv = step1)
val resp = transceive(byteArrayOf(0xAF) + c)        // E_K(rotLeft(rndA))
require(resp == tripleDesCbcEncrypt(rotLeft(rndA), iv = c.takeLast(8)))
```

> Внимание: порядок `rndA + rotLeft(rndB)`, не `rndB + rotLeft(rndA)` — ошибка в порядке даст `0xAE` на step2 даже с правильным ключом.

> Проверено: на этой EV1-клоне 3K3DES-рукопожатие нулевым ключом **проходит** — это признак рабочего (хоть и клона) DESFire.

### Типизация ключей
Если в слоте 3DES, а шлёте `0xAA` (AES) — карта отвечает `0xAE`. Это норма: `0xAA` заработает после `ChangeKey` на AES-ключ. Путать типы — главный источник «не подключается».

## 7. Проверка подлинности (originality) на EV1

| Проверка | Механизм | На EV1 |
|----------|----------|--------|
| **Asymmetric** | Подпись NXP (`0x3C` ReadSignature), проверяется открытым ключом NXP. Формат — ECDSA/ANS.96 (не RSA). На клонах часто возвращает `0x0C`/`0x1C` — подпись недоступна или отсутствует. | Есть, но **не привязана к UID** — клон может скопировать валидную подпись с другой карты |
| **Symmetric** | SUN/CMAC по уникальному ключу | **На EV1 отсутствует в принципе** |

**Вывод:** на EV1 нельзя достоверно подтвердить подлинность криптографически. Прохождение asymmetric-проверки **не** доказывает оригинальность (подпись копируема); отсутствие symmetric-проверки — тоже не признак клона (это фича EV2/EV3).

## 8. Признаки клона EV1 (генуинность)

Явный сигнал — некорректный `GetVersion` и реакция на зонды:

- HW vendor `0x04`, HW-версия **≤ 9**, протокол `0x01`;
- на неизвестную команду (напр. `0x77`) оригиналы отвечают **`0x1C`**;
- `GetCardCertificate` (`0x65`) → `0x1C` на любом EV1 (нормально даже для оригинала).

Наблюдаемый фингерпринт клона (Fudan FM11HD08NS и подобные — перепродают с EV1-силиконом):

| Поле | Клон | Оригинал |
|------|------|----------|
| HW-версия | `51.0` (`0x33`) | ≤ 9 |
| Протокол | `0x05` | `0x01` (ISO14443-A) |
| Неизвестная `0x77` | `0x7E` | `0x1C` |
| Заявленный subtype | `0x01` (EV1) при продаже «EV3»; hwSubtype ≠ swSubtype (склеены поля разных поколений) | соответствует поколению |

**Память не является признаком клона**: код `0x1A` = `2^(0x1A>>1)` = 8 КБ — корректное значение. Утверждения про «64 МБ» были следствием ошибки декодера (`2^code` вместо `2^(code>>1)`); правильная формула — `2^(code>>1)`. Признаки подлинности искать в HW-версии/протоколе/отклике на неизвестную команду, а не в байте памяти.

## 9. Что можно сделать на EV1 (реальный сценарий)

Так как ECC/сертификаты недоступны, рабочий путь — **AES-профилирование**:

1. `GetVersion` → поколение/память.
2. `SelectApplication` (`5A 00 00 00`) в мастер PICC.
3. `AuthenticateISO` 3K3DES (`0x1A`) дефолтным ключом — вход.
4. `CreateApplication` (`0xCA AID KeySettings NumKeys`).
5. `ChangeKey` (`0xC4`) слот приложения на **AES-ключ**.
6. `AuthenticateAES` (`0xAA`) этим ключом — работа с файлами: `CreateFile`, `WriteData`, `ReadData`, `GetValue` и т.д.

> Ограничение: `CreateApplication`/`ChangeKey` **изменяют карту** (создадут приложение). Для диагностики это допустимо; для боевого сценария ключ‑бордер лучше продумать заранее.

## 10. Минимальная последовательность определения карты

```kotlin
val ver = transceive(0x60)              // поколение (byte[2]), память (byte[5]), дата
val free = transceive(0x6E)             // свободная память
transceive(0x5A, 0x00, 0x00, 0x00)      // выбрать мастер PICC
// 3K3DES-рукопожатие (0x1A) — вход; см. код в разделе 6
transceive(0x65)                        // GetCardCertificate — на EV1 0x1C
// Verdict: клон если HW-версия > 9 / протокол != 01 / отклик != 0x1C на 0x77
```

## 11. Источники

- NXP datasheet EV1/MIFARE DESFire: `https://www.nxp.com/products/MIFARE_DESFIRE_EV1_2K_8K`
- TalkToYourDESFireCard (реализация EV1/EV2/EV3): `https://github.com/MichaelsPlayground/TalkToYourDESFireCard`
- NFCjLib (ридер EV1): `https://github.com/AndroidCrypto/MifareDesfireEv3TutorialNFCjLib`
- NFC TagInfo by NXP: `https://play.google.com/store/apps/details?id=com.nxp.taginfolite`
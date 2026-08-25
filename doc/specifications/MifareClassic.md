# MIFARE Classic — работа с картой (спецификация)

Практическая справка по бесконтактным картам **NXP MIFARE Classic** (MF1S50 / MF1S70 / MF1S503x) для разработки NFC-функциональности на Android-терминале. Собрана по открытым источникам: исходники libnfc, proxmark3 (RfidResearchGroup), AOSP, Wikipedia MIFARE. NXP-даташит (`MF1S50YYX`) — под NDA, поэтому опкоды и протокол сверены по реализациям с открытым кодом.

> MIFARE Classic **не** рекомендуется для новых платёжных/транспортных систем: CRYPTO1 взломан (48-битный LFSR, атаки darkside/hardnested/offline). Совместимость с ASOP-экосистемой — опциональный мост для устаревших карт; новые карты — DESFire EV3.

## 1. Семейство и карта памяти

| Модель | Артикул NXP | Секторов | Блоков | Память | Блоков/сектор |
|--------|-------------|----------|--------|--------|---------------|
| Classic 1K | MF1S50 | 16 | 64 | 1024 Б | 4 |
| Classic 4K | MF1S70 | 40 | 256 | 4096 Б | 4 (секторы 0–31) / 16 (секторы 32–39) |
| Classic Mini | MF1S503x | 5 | 20 | 320 Б | 4 |

Каждый блок = 16 байт. **Сектор-трейлер** — последний блок сектора (блок 3 для 1K, блок 3/15 для 4K). Сектор 0 блок 0 — **блок производителя** (Manufacturer Block): UID (4 байта), BCC (1, контрольная сумма UID), остальное — заводские данные (код чипа, информация производителя); read-only после блокировки карты, не перезаписывается.

### Нумерация блоков

```
1K (16 секторов):
  Сектор 0: блоки  0  1  2 [3]
  Сектор 1: блоки  4  5  6 [7]
  ...
  Сектор 15: блоки 60 61 62 [63]
  [n] = сектор-трейлер

4K (40 секторов):
  Секторы 0–31: как 1K (4 блока, трейлер = блок 3)
  Секторы 32–39: 16 блоков, трейлер = блок 15
```

## 2. Сектор-трейлер (Sector Trailer) — 16 байт

| Смещение | Длина | Поле |
|----------|-------|------|
| 0–5 | 6 | **Key A** |
| 6 | 1 | **Access bits byte 1** |
| 7 | 1 | **Access bits byte 2** |
| 8 | 1 | **Access bits byte 3** |
| 9 | 1 | **GPB** (пользовательский байт) |
| 10–15 | 6 | **Key B** |

Структура в коде:

```c
// proxmark3: mifaredefault.h:59–64
typedef struct {
    uint8_t key_a[6];
    uint8_t access[3];      // bytes 6,7,8
    uint8_t gpb;            // byte 9
    uint8_t key_b[6];
} mf_trailer_t;
_Static_assert(sizeof(mf_trailer_t) == 16, "...");
```

```c
// libnfc: mifare.h:110–114
typedef struct {
    uint8_t  abtKeyA[6];
    uint8_t  abtAccessBits[4];  // [0..2] = access, [3] = GPB
    uint8_t  abtKeyB[6];
} mifare_classic_block_trailer;
```

## 3. Access bits — битовая раскладка

Три байта (6,7,8) хранят по 2 бита на каждый из 4 блоков сектора (b=0,1,2,3). Каждый access condition triplet `(C1_b, C2_b, C3_b)` определяет права доступа к блоку. Бит хранится в двух копиях — прямой и инверсной — для обнаружения ошибок записи. Если инверсия нарушена — ACR считается невалидным.

### Физическая раскладка байтов (каноническая, proxmark3 `mifare4.c:135–140`, `cmdhfmf.c:991–994`)

| Байт | Старший ниббл (7–4) | Младший ниббл (3–0) |
|------|---------------------|---------------------|
| 6 | `~C2` (инвертированный) | `~C1` (инвертированный) |
| 7 | `C1` (прямой) | `~C3` (инвертированный) |
| 8 | `C3` (прямой) | `C2` (прямой) |

Где каждый ниббл — 4 бита: бит `b` соответствует блоку `b`.

**Encode** — сборка 3 байт из трёх нибблов:

```
byte6 = (~C2_nibble << 4) | ~C1_nibble
byte7 = ( C1_nibble << 4) | ~C3_nibble
byte8 = ( C3_nibble << 4) |  C2_nibble
```

**Decode** — извлечение per-block condition из байтов:

```
C1_nibble = byte7 >> 4
C2_nibble = byte8 & 0x0F
C3_nibble = byte8 >> 4

cond(b) = (C1_nibble >> b & 1) << 2
        | (C2_nibble >> b & 1) << 1
        | (C3_nibble >> b & 1)
```

`cond(b)` — 3-битное значение `C1C2C3` (C1 = MSB), от 0 до 7.

**Валидация** (`mfValidateAccessConditions` в `mifare4.c`):

```
~C1_nibble == byte6 & 0x0F
~C2_nibble == byte6 >> 4
~C3_nibble == byte7 & 0x0F
```

### Верификация: транспортная конфигурация `FF 07 80 69`

Транспортная карта (обнулённая с завода): Key A = `FF FF FF FF FF FF`, Access = `FF 07 80 69`, Key B = `FF FF FF FF FF FF`.

- byte6 = `FF` = `0xF << 4 | 0xF` → `~C2=0xF` → C2_nibble = 0; `~C1=0xF` → C1_nibble = 0
- byte7 = `07` = `0x0 << 4 | 0x7` → C1_nibble = 0; `~C3=0x7` → C3_nibble = 8 = 0x8
- byte8 = `80` = `0x8 << 4 | 0x0` → C3_nibble = 0x8; C2_nibble = 0

Per-block:
| Блок | C1 | C2 | C3 | cond |
|------|----|----|----|------|
| 0 | 0 | 0 | 0 | **000** |
| 1 | 0 | 0 | 0 | **000** |
| 2 | 0 | 0 | 0 | **000** |
| 3 | 0 | 0 | 1 | **001** — трейлер |

> **Важно:** трейлер в транспортной конфигурации не `000`, а `001`. Полный ноль для трейлера (`cond=000`) дал бы `FF 0F 00`, а не `FF 07 80`. Частое заблуждение — считать, что транспорт = «все 000».

### Kotlin: encode access bits

```kotlin
data class AccessCondition(val c1: Boolean, val c2: Boolean, val c3: Boolean)
typealias SectorAccess = List<AccessCondition> // 4 элемента: блоки 0,1,2,3

fun encodeAccessBytes(ac: SectorAccess): Triple<UByte, UByte, UByte> {
    require(ac.size == 4)
    // порядок бит: b3 b2 b1 b0 (LSB = b3 → младший бит ниббла = блок 0)
    fun nibble(fn: (AccessCondition) -> Boolean): Int =
        (0..3).fold(0) { n, b -> if (fn(ac[b])) n or (1 shl b) else n }
    val c1n = nibble { it.c1 }
    val c2n = nibble { it.c2 }
    val c3n = nibble { it.c3 }
    val b6 = (c2n.inv() and 0x0F shl 4) or (c1n.inv() and 0x0F)
    val b7 = (c1n shl 4) or (c3n.inv() and 0x0F)
    val b8 = (c3n shl 4) or (c2n)
    return Triple(b6.toUByte(), b7.toUByte(), b8.toUByte())
}
```

### Kotlin: decode access bits

```kotlin
fun decodeAccessBytes(b6: UByte, b7: UByte, b8: UByte): SectorAccess {
    val c1n = (b7.toInt() shr 4) and 0x0F
    val c2n = b8.toInt() and 0x0F
    val c3n = (b8.toInt() shr 4) and 0x0F
    val valid = (c1n.inv() and 0x0F) == (b6.toInt() and 0x0F) &&
                (c2n.inv() and 0x0F) == (b6.toInt() shr 4) &&
                (c3n.inv() and 0x0F) == (b7.toInt() and 0x0F)
    return (0..3).map { b ->
        AccessCondition(
            c1 = (c1n shr b) and 1 != 0,
            c2 = (c2n shr b) and 1 != 0,
            c3 = (c3n shr b) and 1 != 0
        )
    }.also { require(valid) { "Invalid access bits: inversion mismatch" } }
}
```
## 4. Таблицы access conditions

### Data-блоки (блоки 0,1,2 в 4-блочных секторах; блоки 0..n-2 в 16-блочных секторах 4K)

| cond | Read | Write | Increment | Decrement / Transfer / Restore |
|------|------|-------|-----------|-------------------------------|
| `000` | A\|B | A\|B | A\|B | A\|B |
| `001` | A\|B | never | never | A\|B |
| `010` | A\|B | never | never | never |
| `011` | B | B | never | never |
| `100` | A\|B | B | never | never |
| `101` | B | never | never | never |
| `110` | A\|B | B | B | A\|B |
| `111` | never | never | never | never |

### Сектор-трейлер (последний блок сектора)

| cond | Key A read | Key A write | Access read | Access write | Key B read | Key B write |
|------|------------|-------------|-------------|--------------|------------|-------------|
| `000` | never | A | A | never | A | A |
| `001` | never | A | A | A | A | A |
| `010` | never | never | A | never | A | never |
| `011` | never | B | A\|B | B | never | B |
| `100` | never | B | A\|B | never | never | B |
| `101` | never | never | A\|B | B | never | never |
| `110` | never | never | A\|B | never | never | never |
| `111` | never | never | A\|B | never | never | never |

Источник: `proxmark3/client/luascripts/data_mf_accessdecode.lua:62–80` / `mifare4.c:58–78`.

> **Практические следствия:** cond `001` на трейлере (транспорт) = Key A нечитаем, но при знании Key A можно перезаписать access bits и Key B. Cond `000` на трейлере = Key A нечитаем, Key B читаем через Key A. `111` — полная блокировка (сектор «сожжён»).

## 5. Дефолтные ключи и GPB

### Дефолтные (заводские) ключи

| Ключ | Значение hex | Описание |
|------|-------------|----------|
| Key A / Key B (транспорт) | `FF FF FF FF FF FF` | Заводской, карта обнулена |
| `KEY_DEFAULT` (Android SDK) | `FF FF FF FF FF FF` | То же |
| `KEY_MIFARE_APPLICATION_DIRECTORY` | `A0 A1 A2 A3 A4 A5` | Для сектора 0 (MAD) |
| `KEY_NFC_FORUM` | `D3 F7 D3 F7 D3 F7` | NFC Forum Type 3 Tag |

### GPB (byte 9)

Пользовательский байт, НЕ участвует в access conditions. На транспортной карте = `0x69`. MAD (MIFARE Application Directory) использует его биты в секторе 0:

| Маска | Флаг MAD |
|-------|----------|
| `0x80` | `MAD_GPB_DA_MASK` — приложение с директорией A (16 AID) |
| `0x40` | `MAD_GPB_MA_MASK` — приложение с директорией B (32 AID) |
| `0x03` | `MAD_GPB_VER_MASK` — версия MAD (0=v1, 1=v2) |

Источник: `proxmark3/client/src/mifare/mad.h:97–100`.

## 6. Протокол и опкоды

MIFARE Classic общается **native-фреймингом** по ISO/IEC 14443-A (Layer 2/3, НЕ Layer 4). На Android это `NfcA` (не `IsoDep`). От DESFire отличается: нет wrapped-APDU, нет многофреймовости (кроме auth-шагов), кадры фиксированной длины.

### Команды PICC-уровня (после активации поля)

| Опкод | Команда | Описание | Формат запроса | Формат ответа |
|-------|---------|----------|----------------|---------------|
| `0x60` | **AUTH_A** | Аутентификация ключом A | `60 [block]` | 4 байта (tag_challenge) |
| `0x61` | **AUTH_B** | Аутентификация ключом B | `61 [block]` | 4 байта (tag_challenge) |
| `0x30` | **READ** | Чтение 16 байт блока | `30 [block]` | 18 байт (16 данных + 2 CRC) |
| `0xA0` | **WRITE** | Запись 16 байт в блок | `A0 [block]` (затем 16 байт данных) | `0x0A` (ACK) / `0x0B` (NAK) |
| `0xC0` | **DECREMENT** | Уменьшить value-блок на delta | `C0 [block]` (4 байта delta) | `0x0A` |
| `0xC1` | **INCREMENT** | Увеличить value-блок на delta | `C1 [block]` (4 байта delta) | `0x0A` |
| `0xC2` | **RESTORE** | Скопировать value-блок во внутренний буфер | `C2 [block]` | `0x0A` |
| `0xB0` | **TRANSFER** | Записать буфер в блок | `B0 [block]` | `0x0A` |
| `0x50` | **HALT** | Перевести карту в HALT | `50 00 [CRC_A]` | нет |

**Value-блоки** (для счётчиков): 4 байта value (LSB) + 4 байта ~value + 4 байта value (дубль) + 4 байта addr. INCREMENT/DECREMENT/TRANSFER — атомарные операции внутри чипа.

### CRYPTO1-рукопожатие (последовательность аутентификации)

CRYPTO1 — проприетарный потоковый шифр NXP на 48-битном LFSR. Аутентификация = обмен nonce + верификация.

```
PCD (читатель)                  PICC (карта)
  │                                │
  │ AUTH(0x60, block_n)            │
  │───────────────────────────────>│
  │                                │
  │   <── nt (4 байта nonce карты) │
  │                                │
  │   <── E(nt) первые 4 байта     │
  │        keystream CRYPTO1       │
  │                                │
  │ E(nt) + AR, E(AT)             │
  │ (6 + 4 байта)                 │
  │───────────────────────────────>│
  │                                │
  │ Команды RL/WR/INC/... после    │
  │ auth шифруются CRYPTO1;        │
  │ адрес блока и опкод — plaintext│
```

> CRYPTO1 взломан в 2007 (Nohl/Plötz), атаки darkside (2009), hardnested (2013), online-подбор за <1 секунду на GPU. **Любой сектор с известным Key A/B читается мгновенно.** Для транспортных карт с `FF FF FF FF FF FF` любой читатель получает полный дамп за секунды.

### Android: использование MifareClassic API

На Android MIFARE Classic доступен через `android.nfc.tech.MifareClassic` (требует `NfcA` в tech-листе). API абстрагирует аутентификацию и CRYPTO1.

```kotlin
import android.nfc.tech.MifareClassic

fun readSector(tag: Tag, sector: Int, key: ByteArray): List<ByteArray> {
    val mfc = MifareClassic.get(tag) ?: error("Not a Mifare Classic tag")
    mfc.connect()
    try {
        if (!mfc.authenticateSectorWithKeyA(sector, key)) {
            error("Auth failed for sector $sector")
        }
        val blocks = mfc.sectorToBlock(sector) until (mfc.sectorToBlock(sector) + mfc.getBlockCountInSector(sector))
        return blocks.map { mfc.readBlock(it) }
    } finally {
        mfc.close()
    }
}

fun writeSector(tag: Tag, sector: Int, key: ByteArray, blocks: List<ByteArray>) {
    val mfc = MifareClassic.get(tag) ?: error("Not a Mifare Classic tag")
    mfc.connect()
    try {
        if (!mfc.authenticateSectorWithKeyA(sector, key)) {
            error("Auth failed for sector $sector")
        }
        val base = mfc.sectorToBlock(sector)
        blocks.forEachIndexed { i, data -> mfc.writeBlock(base + i, data) }
    } finally {
        mfc.close()
    }
}
```

### NfcA (низкоуровневый) — для ручного CRYPTO1

Если нужно читать карту без Android-API (например, нестандартный key B) или выполнять value-операции:

```kotlin
import android.nfc.tech.NfcA

fun readBlockRaw(tag: Tag, block: Int): ByteArray {
    val nfca = NfcA.get(tag) ?: error("Not NfcA")
    nfca.connect()
    try {
        // auth должен быть выполнен до READ (см. либу libnfc или CRYPTO1-имплементацию)
        val cmd = byteArrayOf(0x30, block.toByte())
        return nfca.transceive(cmd) // 18 байт: 16 данных + 2 CRC
    } finally {
        nfca.close()
    }
}
```

> **Timeout на Android:** `NfcA` и `MifareClassic` могут таймаутить (особенно на чипах Broadcom/NXP PN548). Рекомендуется `setTimeout(500)` или выше. При ошибке — повторная активация tag + auth. AOSP-исходник `MifareClassic.java` — основной источник опкодов (Android 12+).

## 7. Клоны (FM11RF08S и аналоги) и риски

MIFARE Classic — один из самых клонируемых чипов в мире благодаря взломанному CRYPTO1. Основные клоны:

| Производитель | Чип | Совместимость | Особенности |
|---------------|-----|---------------|-------------|
| Fudan | FM11RF08S | Полная (1K, 4K) | Аппаратно идентичен NXP; некоторые партии не поддерживают value-операции |
| Genius | GMI | 1K, реже 4K | Частичная совместимость; ошибки в CRC, value ops |
| MFOC-совместимые | — | 1K | Сделаны из дампа; работают как карта, не как чип (эмуляция) |
| NXP (оригинал) | MF1S50/70 | — | Эталон; поддерживает все value ops |

### Риски

1. **Чтение без аутентификации:** CRYPTO1 — потоковый шифр. При известном одном ключе сектора (например транспортный `FF...FF`) восстанавливается keystream, и все остальные секторы с этим же ключом читаются. Darkside-атака вскрывает неизвестный Key A за 3–10 запросов без авторизации на слабых nonce.

2. **Копирование карт:** UID перезаписывается на некоторых клонах (UID writable magic cards). Полный дамп карты = клон, неотличимый от оригинала без запроса к бэкенду (online-валидация).

3. **Отсутствие аппаратной криптографии:** MIFARE Classic не умеет AES/ECC/3DES (только CRYPTO1). Невозможно безопасно хранить ключи на карте — любой сектор с известным access bits читается.

4. **Клоны с багами:** Некоторые Fudan FM11RF08S игнорируют access bits для трейлера (KEY A / KEY B всегда читаемы). Другие не выполняют `DECREMENT`/`TRANSFER` атомарно — value-счётчик может быть повреждён.

### Рекомендации для ASOP

- Не использовать MIFARE Classic как самостоятельное платёжное/транспортное средство.
- Если нужна обратная совместимость с устаревшими картами — только **read-only**: чтение UID + проверка по бекапе (online-верификация через gateway).
- Value-блоки (счётчики поездок) — **ненадёжны** на клонах. Использовать только на доверенных NXP-картах с защищённым трейлером (cond `011`/`100` для трейлера, Key B = секретный).
- Карту считать скомпрометированной, если трейлер имеет cond > `001` (могла быть перезаписана злоумышленником).

## 8. Объяснение человеческим языком

Этот раздел — для тех, кто впервые видит MIFARE Classic. Без таблиц и шестнадцатеричных страшилок, только суть.

### Аутентификация — как открыть сектор

У каждой секции в карте есть два ключа: **Key A** и **Key B** (каждый — 6 байт). Они хранятся в трейлере сектора. **Key A никогда не читается наружу** (для любой раскладки access bits «Key A read» = `never`), его можно только перезаписать. Key B читается при определённых правах, но только если уже знаешь Key A. Снаружи ключ не «вытащить».

Терминал не достаёт ключ из карты — у терминала **свой экземпляр** (в базе, в terminal_keys, на сервере), у карты — свой (в трейлере). Задача: доказать, что оба экземпляра совпадают, **не отправляя ключ по воздуху**. Это делает CRYPTO1 — потоковый шифр (гаммирующий) на 48-битном сдвиговом регистре (LFSR).

По эфиру летят только зашифрованные пакеты. Стрелки показывают направление.

---

**Шаг 0 — UID (антиколлизия, до auth).**
Карта → терминал: открытым текстом свой 4-байтный заводской номер (`UID`). Обе стороны его знают. UID пойдёт в CRYPTO1 как личная затравка, чтобы у двух одинаковых карт с одним ключом гамма была разной.

**Шаг 1 — терминал → карта.**
Летит только `60 <номер_блока>` (+ CRC). `0x60` = «хочу Key A», `0x61` = «хочу Key B». Никакого ключа или UID в пакете нет — это выбор замка, а не передача ключа.

**Шаг 2 — карта → терминал.**
Карта генерирует случайное число `nt` (32 бита, из своего встроенного ГСЧ). Она заряжает свой CRYPTO1: в регистр вводится **ключ сектора + UID**. Затем, бит за битом, она делает две вещи одновременно:
- выдаёт очередной бит **гаммы** (keystream) из регистра;
- втягивает очередной бит `nt` внутрь регистра (как винт ввинчивается).

Наружу уходит 4 байта: `nt ⊕ гамма` (гамма применена побитово к копии nt). Чистого nt в эфире не существует. Состояние регистра после шага 2 = (ключ, UID, nt).

**Шаг 3 — терминал расшифровывает вызов.**
У терминала свой экземпляр CRYPTO1. Он заряжает его своим ключом + UID (своей копией) и прокручивает, получая ту же гамму. Если ключ терминала совпадает с ключом карты — гамма та же, и терминал восстанавливает nt: `nt = (полученные 4 байта) ⊕ гамма`. Если ключ не совпал — гамма другая, nt не восстановится, весь протокол расходится. Это и есть доказательство «я знаю ключ».

**Шаг 4 — терминал → карта.**
Терминал генерирует свой вызов `nR` (по правилам ГСЧ карты — иначе карта сочтёт его подделкой) и отклик `AR` — математическую функцию от nt. Шлёт обратно 8 байт, завёрнутых продолжающейся гаммой: `nR ⊕ гамма, AR ⊕ гамма`.

**Шаг 5 — карта → терминал.**
Карта проверяет: форма `nR` правильная, `AR` совпал с ожидаемым для её nt. Если да — отвечает финальным `AT ⊕ гамма` (4 байта). Терминал проверяет AT.

**Шаг 6.**
Сектор открыт. Все последующие данные сектора шифруются той же гаммой. Убрал карту — состояние сброшено.

---

**Почему это ненадёжно:**
- Ключ всего 48 бит (6 байт).
- CRYPTO1 взломан: атаки darkside (2009), hardnested (2013), GPU-подбор за секунды.
- **Важный пункт:** у клона с тем же UID и тем же ключом гамма будет побайтово идентична — CRYPTO1 не имеет уникального чипового секрета. Клон аутентифицируется как оригинал. Отличить можно только вне карты (online-валидация на сервере, чёрные списки UID). Именно поэтому ASOP предпочитает DESFire EV3 (SUN/CMAC с уникальным чиповым ключом).

### Смена ключа — как поменять замок

Ключи хранятся прямо внутри сектора, в его 16-байтовом «замке-трейлере» — это последний блок секции. Трейлер устроен так:

- **6 байт — Key A**
- **3 байта — настройки доступа** (access bits; описано в разделах 3–4)
- **1 байт — служебный** (GPB, не трогать без нужды)
- **6 байт — Key B**

Заменить ключ = записать этот трейлер целиком (16 байт) через простую команду WRITE. Но:

- Перед этим вы должны **открыть сектор** старым ключом (аутентифицироваться).
- Можно поменять оба ключа сразу: записать свой Key A (6 байт) и свой Key B (6 байт), а в access bits прописать, кто что может делать.
- **Ловушка:** если access bits запрещают запись трейлера — сектор больше никогда не перезаписать. Такие секторы называют «сожжёнными». Поэтому когда меняете ключ в первый раз — ставьте мягкие права, а когда всё проверили — ужесточайте.

### Чтение и запись данных

Данные лежат в тех же блоках по 16 байт — простые бинарные массивы. Нет файлов, нет AID, нет типов — просто байты.

- **Чтение:** открыть сектор ключом → `READ(номер блока)` → получить 16 байт
- **Запись:** открыть сектор ключом → `WRITE(номер блока, 16 байт)` → карта пишет

Единственное, что может помешать — **access bits**. Тремя байтами в трейлере можно для каждого из четырёх блоков секции задать права: кто (ключ A или B) может читать, кто писать, кто увеличивать счётчик. Полная таблица — в разделе 4.

**Особый тип — value-блок** (для электронных кошельков/счётчиков поездок). Если блок размечен как value (access bits, cond `110` или `000`), у него особая структура:
```
[значение 4 байта] [~значение 4 байта] [значение 4 байта] [адрес 4 байта]
```
Карта понимает специальные команды: INCREMENT (прибавить поездку), DECREMENT (списать), TRANSFER (сохранить). Но на клонах эти операции глючат — для ASOP это риск.

### Жизненный цикл на примере

Допустим, вы — терминал, и у вас есть карта с заводской настройкой (все ключи `FF FF FF FF FF FF`):

1. Приложили карту → Android сообщил, что это MIFARE Classic.
2. Вызвали `authenticateSectorWithKeyA(0, KEY_DEFAULT)` — сектор 0 открыт.
3. Прочитали блок 0 (UID + BCC + заводские данные). SAK и ATQA уже известны из протокола активации карты — по ним Android определил tech-лист и тип карты.
4. Прочитали блок 3 — трейлер сектора 0, посмотрели access bits.
5. Если хотите записать свой ключ: пишете новый трейлер в блок 3 (Key A = ваш, Key B = ваш, access bits = какие хотите).
6. Дальше сектор работает под вашим ключом.

Всё. Дальше — дело архитектуры: какой ключ куда класть, что считать поездкой, как быть с взломанным CRYPTO1 (ответ — online-верификация через gateway, не полагаться на карту).

### SAK и ATQA — что это и откуда берётся

**SAK** (Select Acknowledge, 1 байт) и **ATQA** (Answer To Request type A, 2 байта) — не данные из блока 0, а протокольные байты. Карта отдаёт их читателю на этапе активации поля, до любой аутентификации.

**ATQA** — ответ на команду `REQA` (карта только что вошла в поле). Говорит читателю протокол фрейминга и размер UID:
- `0x0004` → 4-байтовый UID (Classic 1K)
- `0x0002` → 7-байтовый UID (Classic 4K)

**SAK** — ответ на команду `SEL` (читатель завершил антиколлизию, выбрал карту). Кодирует возможности карты:
- Бит 6 = 1 (0x40) → умеет ISO 14443-4 (IsoDep). У Classic = 0 (не умеет).
- Бит 3 = 1 (0x08) → использует проприетарный протокол (не ISO 14443-4). У Classic = 1.
- Classic 1K: **SAK = 0x08** (бит 3 только)
- Classic 4K: **SAK = 0x18** (биты 3 и 4)
- По SAK Android решает, какие tech-списки приписать тегу.

**Практика:** ты не читаешь SAK/ATQA из блока 0. После `Tag tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)` — `tag.getId()` уже даёт UID, а tech-лист определён по SAK. Блок 0 содержит только UID + BCC + заводской мусор.

## 8.1. ASOP сценарии использования (промпт 008/009/011)

Sector 1 (`0x04`) приложения АСОП (CRYPTO1-protected) используется в трёх сценариях:

1. **Активация карты водителя (промпт 008)**: VCM1 запись на сектор 1 при первом tap карты через activation flow.
2. **Открытие смены водителем (промпт 011)**: 16-байтный UUIDv7 `cardId` (MSB-first) в sector 1 block 1 (block 0: magic VCM1 + bitmask u16 LE + tripsLeft u16 LE + reserved) используется как `ASOP_SESSIONS.card_id` поля. При tap для shift-open:
   - `SessionFlowViewModel.onCardTappedForAuth()` читает VCM1 identity (`File 0`/`File 1` по DESFire NFC) или CRYPTO1 auth + MIFARE-Classic read (sector 1) для получения `cardId, userId, bitmask`.
   - bitmask содержит роль `DRIVER` ordinal → резолвится carrier через `asop_user_carriers` payload в локальной таблице `reference_rows`.
   - После успеха аутентификации: emit Kafka `asop.session.commands` с `sessionId = client-generated UUIDv7`.
3. **Tap пассажирской карты внутри открытого TRIP**: запись в `TripPaymentEntity (amount=0, transactionResultId='VALIDATION_ONLY')`. Row-формат не отличается от driver-card; entity переиспользует sector 1.

**Альтернативный путь** — бесконтактный DESFire EV1/EV2/EV3 (iCAR-класса):
- AID `0xA05A01`, ISO-файл 0 = cardIdentity JSON, файл 1 = RSA-PSS подпись.
- Тот же payload, но `IdentityMode = DESFIRE` кодирует как `File 0`/`File 1` чтение.

Подробная последовательность write-операций описана в `prompts/prompt_008.md` (clean-break от SAC1 → VCM1).

## 9. Источники

- libnfc `utils/mifare.h` — структуры трейлера: https://raw.githubusercontent.com/nfc-tools/libnfc/master/utils/mifare.h
- libnfc `utils/nfc-mfclassic.c` — транспорт `default_acl[]` = `{0xff,0x07,0x80,0x69}`: https://raw.githubusercontent.com/nfc-tools/libnfc/master/utils/nfc-mfclassic.c
- proxmark3 `client/src/mifare/mifare4.c` — decode/encode/validate ACR, таблицы cond: https://raw.githubusercontent.com/RfidResearchGroup/proxmark3/master/client/src/mifare/mifare4.c
- proxmark3 `client/luascripts/data_mf_accessdecode.lua` — таблицы access rights: https://raw.githubusercontent.com/RfidResearchGroup/proxmark3/master/client/luascripts/data_mf_accessdecode.lua
- proxmark3 `client/src/cmdhfmf.c` — per-block decode + транспорт fallback (строки 991–1008): https://raw.githubusercontent.com/RfidResearchGroup/proxmark3/master/client/src/cmdhfmf.c
- proxmark3 `client/src/mifare/mifaredefault.h` — `mf_trailer_t`, field offsets: https://raw.githubusercontent.com/RfidResearchGroup/proxmark3/master/client/src/mifare/mifaredefault.h
- proxmark3 `client/src/mifare/mad.h` — GPB флаги: https://raw.githubusercontent.com/RfidResearchGroup/proxmark3/master/client/src/mifare/mad.h
- Wikipedia MIFARE: https://en.wikipedia.org/wiki/MIFARE
- AOSP `MifareClassic.java`: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android12-release/core/java/android/nfc/tech/MifareClassic.java
- Nohl/Plötz CRYPTO1 analysis (2007): https://eprint.iacr.org/2008/572
- darkside attack (Garcia et al., 2009): https://www.cs.ru.nl/~rverdult/darkside_improved.pdf

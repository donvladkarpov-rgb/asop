# Prompt 005-01: Обработка неполноценных DESFire-клонов в flow активации карт

## Контекст

В ASOP-терминале реализован flow активации MIFARE DESFire-карт (промпт 005): root-login / авторизация картой-ключом → определение целевой карты → серверная регистрация (POST /sync/smart-cards/sign + /sync/cards/activate) → прошивка identity на карту (ChangeKey + CreateApplication + WriteData).

## Проблема

На рынке присутствуют клон-карты DESFire, которые **частично** эмулируют протокол: отвечают на PICC-уровневые команды (GetVersion 0x60, GetFreeMemory 0x6E, GetApplicationIDs 0x6A), но **не поддерживают** команды уровня приложений:

- `SelectApplication` (0x5A) — возврат `IOException` (Transceive failed)
- `AuthenticateISO` (0x1A) / `AuthenticateAES` (0xAA) — `IOException`
- `CreateApplication` (0xCA), `CreateStdDataFile` (0x6D), `WriteData` (0x8D) — очевидно не работают

Такая карта определяется как DESFire (версия EV1, HW major 51, storage 8K), но **непригодна** для активации — прошить identity невозможно.

## Имеющиеся данные

### CardReadScreen (read-only probe)
Штатное чтение карты через `DesfireCardReader`:

1. IsoDep GetVersion(0x60) → 28 байт: успешно (чтение версии, UID, batch, даты выпуска)
2. IsoDep GetVersion(0x60 0x00) → `Transceive failed` (фрейминг-вариация не поддерживается)
3. IsoDep GetFreeMemory(0x6E) → 3 байта: успешно (свободная память)
4. IsoDep GetApplicationIDs(0x6A) → `Transceive failed`
5. `DesfireAuthProbe`: SelectApplication(000000) → `Transceive failed`, все дальнейшие крипто-пробы падают
6. Вывод: карта DESFire EV1-клон, ни одного `0x5A` не проходит

### CardActivationScreen (write flow)

Текущий `processTargetCard`:

```
IsoDep.get(tag) → connect() → timeout=5000
→ tryAuthenticateMaster(iso, ZERO_KEY)
   → selectApplication(iso, byteArrayOf(0,0,0)) → IOException("Transceive failed")
   → все 3 попытки (AES zero, 3K3DES zero, ZERO_KEY) падают одинаково
→ fallback NfcA.get(tag)
   → selectApplication через NfcA → тоже Transceive failed
   → authenticateZeroKeysNfcA → тоже Transceive failed
```

### Симптомы на UI
- При прикладывании карты: быстрый фликер (busy → fail → return к Step.TargetCard)
- Сообщение: «Карта не идентифицирована. Возможно, она не зарегистрирована в системе или ключ устарел.»
- root-логин не помогает (проблема на уровне связи с картой, не авторизации)

## Ключевые файлы для изменений

- `frontend/android-terminal/app/src/main/java/ru/asop/terminal/nfc/DesfireCardReader.kt` — read-only зонд, PICC-команды + crypto probe
- `frontend/android-terminal/app/src/main/java/ru/asop/terminal/nfc/DesfireCardWriter.kt` — write-операции (ChangeKey, CreateApp, WriteData)
- `frontend/android-terminal/app/src/main/java/ru/asop/terminal/nfc/DesfireAuthProbe.kt` — крипто-пробы нулевыми ключами
- `frontend/android-terminal/app/src/main/java/ru/asop/terminal/ui/screen/CardActivationViewModel.kt` — `processTargetCard()`, `identifyCard()`, `tryAuthenticateMaster()`, fallback на NfcA
- `frontend/android-terminal/app/src/main/java/ru/asop/terminal/ui/screen/CardReadScreen.kt` — UI отображения результата чтения

## Что нужно сделать

### 1. Диагностика пригодности карты до активации

Перед тем как показывать «Карта новая. Заполните поля» или «Карта идентифицирована», нужно проверить **минимальную функциональность** карты:

- SelectApplication(мастер PICC, AID 000000) — ДОЛЖНА проходить
- AuthenticateISO (0x1A) с нулевым ключом ИЛИ AuthenticateAES (0xAA) с нулевым ключом — хотя бы одна должна проходить

Если SelectApplication не проходит — карта непригодна. UI должен показывать:
> «Карта не поддерживает SelectApplication. Возможно, это неполноценный клон DESFire. Используйте оригинальную карту NXP DESFire EV2/EV3.»

### 2. Fallback на NfcA (Layer 2, native фрейминг)

Часть клонов не поддерживает IsoDep (Layer 4), но может работать через NfcA с native-командами. У `DesfireCardReader.read()` уже есть fallback:

```kotlin
val nfcA = NfcA.get(tag)
if (nfcA != null) {
    nfcA.connect()
    nfcA.timeout = 3000
    val version = transceiveDesfire(nfcA::transceive, ...)
    // ...
}
```

Нужно добавить аналогичный fallback в `CardActivationViewModel.processTargetCard()`: если IsoDep не отвечает на `0x5A`, повторить через NfcA. Если NfcA тоже не отвечает — карта непригодна.

### 3. Разделение «карта не DESFire» и «карта DESFire, но нерабочая»

Сейчас сообщение одно — «Целевая карта не DESFire». Нужно два разных:

- **«Карта не DESFire»** — GetVersion не отвечает (карта другого типа)

- **«Карта не поддерживает SelectApplication — клон DESFire»** — GetVersion отвечает, но SelectApplication нет

### 4. Улучшение `tryAuthenticateMaster` для clone-карт

Текущая реализация в `DesfireCardWriter.tryAuthenticateMaster()` делает:
1. `selectApplication(iso, byteArrayOf(0,0,0))` — первая команда на свежем IsoDep
2. Если не прошла → сразу false
3. Потом пробует AES zero, 3K3DES zero, ZERO_KEY (каждый с selectApplication)

Проблема: если `selectApplication` падает, мы не знаем, упал ли он из-за не-DESFire карты или из-за сбоя связи. Нужно:

- Если все 3 попытки упали одинаково (IOException на 0x5A) → вероятно, клон без SelectApplication
- Если хотя бы одна попытка дала ответ (даже 0xAE AuthError) → карта DESFire, но ключ не подходит

### 5. Отладка: логирование первого байта ответа

Добавить логирование ответа (даже ошибочного) для `selectApplication`, чтобы различать:
- `IOException` (Transceive failed) — связь оборвалась
- `0xAE` (Authentication error) — команда известна, требует auth
- `0x1C` (Illegal command) — команда не поддерживается картой

## Не реализовывать

- Переход на чип-карты других производителей (STM, Infineon) — пока только NXP DESFire
- Обходные пути прошивки без SelectApplication (невозможно на DESFire)
- Поддержка Layer 2 для write-операций (CreateApplication требует Layer 4)

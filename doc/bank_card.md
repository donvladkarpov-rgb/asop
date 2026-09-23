# Работа с банковской картой (из легаси `doc/soft`)

Цель этого документа — зафиксировать, как в репозиториях `doc/soft/` реализовано взаимодействие
с **банковскими платёжными картами**: на стороне Android (терминал с Feitian F20, EMV-чтение карты)
и на стороне сервера (обработка транзакции, авторизация в эквайринге Multicard/SIRPOS,
до-авторизация, отмена, возврат).

Документ написан по коду легаси-проектов. Ничего не выдумано; где факт взят из закрытого бинарного
JAR (`com.transcard:multicard-client`), он помечен `[EXTERNAL]`. Имена файлов и классы даны точно,
чтобы по ним можно было вернуться в исходники.

---

## 1. Карта проектов `doc/soft/` (отбор по теме банковской карты)

| Проект | Что это | Отношение к банковской карте |
|---|---|---|
| `feitian-hardware-master` | Android-обёртка над SDK Feitian **FTSDK** для POS-терминала (F20/FTSafe) | **Сторона устройства.** EMV-ядро (контакт/бесконтак), чтение PAN/track2/expiry, TLV, PIN-pad. Плюс MIFARE/DESFire для транспортных карт |
| `asop-processing-Release-1.6` | Новейший версия сервиса-процессинга (Kotlin/Septima) | **Сторона сервера (ядро документа).** Банковский эквайринг Multicard/SIRPOS: authorize, reauth (до-авторизация), cancel, refund; RSA-шифрование данных карты; очереди/попытки; блокировка TID |
| `asop-processing-master` | Предшественник processing (без Multicard) | Контекст. Тот же пайплайн принятия решения (льгота → расчёт → отправка), но авторизация шла в «Solar», а не в банк |
| `asop-handler-master` | Приёмный сервис транзакций (intake) | Контекст. Создаёт типы карт (`BANK_CARD`), пишет `bill_bank_operations`, stop-list, фискальную очередь |
| `tariff-calculator-master` | Пакетный калькулятор тарифов | Не связан с банковскими картами (только расчёт стоимости поездки) |
| `asop-processing-receiver-master` | Пустой стаб (только README) | Не связан |

Общая схема контура (2 стадии, всё через Kafka):

```
 терминал ──► Kafka processing.queueRawMessages ──► asop-handler (приём, запись в БД)
        ──► Kafka processing.queueRawToProcessing ──► asop-processing (1.6)
                                                       ├─ решение: льгота/ядро → сумма
                                                       ├─ если надо платить банком:
                                                       │    Multicard (SIRPOS) authorize (+ reauth/cancel/refund)
                                                       └─ результат → processing.queue{Success,Privileges,Failure,…}
```

---

## 2. Android: `feitian-hardware-master`

### 2.1 Слои

```
Бизнес-логика терминала (android-terminal)
   └─ FeitianHardwareFactory.create(context) : IFeitianHardware     (фасад)
        ├─ FeitianHardware                  — реализация фасада (738 строк)
        │    └─ FEmvReader                  — EMV-ридер: searchCard / performTransaction / stop
        ├─ EmvTool                          — оркестрация EMV-ядра (searchCard, TLV, startEMV…)
        ├─ EmvEventHandler / EmptyEventHandler — колбэки ядра (OnEmvResponse)
        └─ FTSDK (jar):
             ├─ FTSDK_api_V1.0.1.30_20251010.jar     (com.ftpos.library.smartpos.*)
             └─ FTSDK_SysAPI_V1.0.243_20251219.jar   (com.ftpos.systemapi.*)
```

Файлы:
- `app/src/main/java/biz/altsoft/android/hardware/feitian_hardware/IFeitianHardware.kt` — интерфейсы и «карточные» дата-классы
- `.../FeitianHardware.kt` — реализация
- `.../emv/emv/EmvTool.kt`, `EmvEventHandler.kt`, `EmptyEventHandler.kt` — EMV
- `.../emv/bean/*.java` — XML-бобы параметров EMV
- `.../emv/constants/ICardType.java`, `IParamType.java`
- `.../emv/tlv/TLV.java`, `TLVElement.java`, `TLVList.java`
- `.../emv/utils/BytesUtil.java`, `XmlParser.java`
- `.../exception/*.kt` — типизированные исключения
- JAR: `app/libs/FTSDK_api_...jar`, `app/libs/FTSDK_SysAPI_...jar`

### 2.2 Публичный API устройства

`IFeitianHardware` (единственная точка входа для бизнес-логики):

```kotlin
interface IFeitianHardware : AutoCloseable {
  fun init(); fun destroy()
  val lights    : IFLights          // setOnly(vararg leds: EFLed)
  val mifare1K  : IFMifare1kClassic // транспортные карты Classic / DESFire
  val buzzer    : IFBuzzer
  val info      : IFHardwareInfo    // terminalNumber: String
  val printer   : IFPrinter
  val icReader  : IFICReader        // IC-ридер (контакт)
  val emvReader : IFEmvReader       // <-- банковские карты
  val system    : IFSystem
}
```

EMV-ридер:

```kotlin
interface IFEmvReader {
  fun searchCard(timeoutMs: Int, callback: IFEmvOnSearchCardCallback)   // «найти банковскую карту»
  fun performTransaction(params: FTransactionParameters, timeoutMs: Int, callback: IFEmvTransactionCallback)
  fun stop()
}

interface IFEmvTransactionCallback {
  fun onCardDetected()
  fun onSuccess(result: IFEmvTransactionResult)   // status + tagsMap
  fun onTimeout()
  fun onError(errorDescription: String)
}
interface IFEmvTransactionResult { val status: FEmvTransactionStatus; val tagsMap: Map<Int, ByteArray> }
```

Карточные параметры:

```kotlin
data class FEmvParameters(
  val mCurrencyCode: String?,     // «643» (RUB)
  val mTransType: Int,            // тип EMV-транзакции (0 = товары/услуги)
  val cardType: Int,              // ICardType
  val verifyPinSkip: Boolean,     // пропуск ввода PIN
)
data class FTransactionParameters(val amount: FAmount, val emvParameters: FEmvParameters)

enum class FEmvTransactionStatus {
  OnlineAuthorizationRequired, OfflineApproved, OfflineDeclined, UseContactInterface,
  UnablePerformTransaction, CardExpired, AuthorizationOnCardholderDeviceRequired,
  NonPaymentCard, SupportedAppsNotFound, TransactionParamsError, Declined, ServiceNotAllowed
}
```

### 2.3 EMV-поток (что реально делает `FeitianHardware`)

**`searchCard(timeoutMs, callback)`** — на самом деле это НЕ «предложить карту к ридеру», а
**полноценная EMV-транзакция с нулевой суммой** (реализация в `FEmvReader.searchCard`):

1. `cardType = TYPE_CARD_CONTACT_LESS` (жестко, 0x02).
2. `initializeTransaction(cardType)` → проверка ридера.
3. `Amount(0, 0)`, `TransRequest(0)` с:
   - `setmCurrencyCode("643")`, `setVerifyPinSkip(true)`,
   - `setMagTransQuickPass(false)` (без быстрого EMV-пути для магнитных),
   - `setMagTransServiceCodeProcess(true)` (проверяет service code в track2),
   - `setMaxTimeoutEMVThreadWait(30)`, `setReadRecordCallback(true)`, `setEnableAppSelectCallback(false)`,
   - `setAdditionalTlvData("1F300101")` (кастомный Feitian-тег).
4. `startEMVWithEmptyHandler(...)` — с обработчиком `EmptyEventHandler` (персонализация).
5. При `onSuccess` возвращает `code=0`, а `id = result.tagsMap[0x57]` — **track2 карты**.
   Т.е. «найденная карта» идентифицируется track2-меткой.
6. Ошибки: `onTimeout → errCode 89`, `onError → 1024`, исключение → `1023`.

**`performTransaction(params, timeoutMs, callback)`** — реальная EMV-транзакция с суммой:
- `initializeTransaction(params.emvParameters.cardType)`; `Amount(value, other)`;
- `TransRequest(params.emvParameters.mTransType)` + те же настройки, что выше;
- `startEMV(amount, cardType, transRequest, callback)` — обработчик `EmvEventHandler`.

**`stop()`** → `stopEMV()`.

### 2.4 `EmvTool` — обвязка EMV-ядра

```kotlin
setTLV(tag, value); getTlvList(tags)
searchCard(timeout, callback)                 // низкоуровневый emv.searchCard
stopEmvTransaction()                          // emv.stopEMV()
setIssuerOnlineResponseData(code, authCode, responseCode, issuerAuthData, issuerScript71, issuerScript72)
respondEvent(data)
initializeTransaction(cardType) → checkReader(cardType)
updateAllParameters()                         // загрузка всех EMV-параметров из XML
clearAllParameters()
updateParametersByFile(IParamType)            // acquirer / app EMV / EMVCL / CA / DRL / CRL / exception
checkReader(code)                             // контакт → icReader.checkICReader(); бесконтак → nfcReader.checkNFCCardreader()
startEMV(amount, cardType, transRequest, callback) / startEMVWithEmptyHandler(...)
```

### 2.5 `EmvEventHandler` — как из транзакции достаются данные карты

Список тегов, которые извлекаются из транзакции (`ICC_DATA_TAGS`):

```
9F10 9A 9F26 5F2A 84 9F27 9F02 82 9F36 9F34 9F1A 9F37 9C 5F34 95 9F03 5A 57 5F24 5F34 9F33 9F6B 9F24 5F25
```

В `onEndProcess(code, data)`:
- `code == 89` → `onTimeout()`.
- Для каждого тега: `getTlvList(tag)` → разобрать значение → `tagsMap[tag] = bytes`.
- **Если есть track2 (тег 0x57)**: разделитель `D` → PAN берётся из track2
  (`panFromTrack2`), срок действия восстанавливается как `YYMM + последний день месяца` (`expDate`).
- Теги 0x5A (PAN) и 0x5F24 (expiry): если результат пустой → подставляются значения из track2.
- **Результат статуса**: для контактной карты (`TYPE_CARD_CONTACT`) всегда `Declined`,
  для бесконтактной — `OfflineApproved`. Т.е. обёртка **всегда одобряет офлайн**
  (см. также `onOnlineProcess` ниже).

Поведение колбэков ядра (важно для проектирования):

| Колбэк | Поведение обёртки | Комментарий |
|---|---|---|
| `onAppSelect(reselect, list)` | ничего | выбор приложения выключен (`setEnableAppSelectCallback(false)`) |
| `onPinEntry(cvm)` | `respondEvent(null)` | PIN не вводится (флаг `verifyPinSkip`) |
| `onOnlineProcess(data)` | `setIssuerOnlineResponseData(0, null, "00", null, null, null)` + respondEvent | **имитация одобрения эмитентом — карта всегда проходит офлайн по этой обёртке**; для реального онлайн-эквайринга нужно заменить |
| `onEndProcess` | см. выше | сборка tagsMap |
| `onObtainData(code, data, dataInformation)` | для `1F3E` → `setTLV("1F3E","00000000")` (нет лимита накопленной суммы), `1F10` → `"01"` (всегда одобрено), `1F6A` → персонализация PAN `5A081122334455667788DF81100101` (PayPass DE), затем `respondEvent(null)` | данные ядру |
| `onSearchCard` / `onSearchCardAgain` | `searchCard(20, searchCardCallback)` | поиск для транзакции |
| `onProcessInteractionPoint(step)` | `respondEvent(null)` | шаги 1–9 EMV |
| `onUpdateTransAmount()` | `Amount(0,0)` | не используется |

`EmptyEventHandler` — вариант без извлечения данных (используется в `searchCard`),
заполняет те же служебные теги (1F3E/1F10/1F6A).

### 2.6 Банковские данные устройства (чем оперирует обёртка)

| Данные | Тег TLV / источник | Примечание |
|---|---|---|
| PAN (номер карты) | 0x5A (`5A`) | если пуст — вытаскивается из track2 |
| Track2 | 0x57 (`57`) | весь track2 (PAN+expiry+discretionary); используется как «id» найденной карты |
| Expiry (срок) | 0x5F24 (`5F24`) | восстанавливается `YYMM+конец месяца` из track2 |
| Application Cryptogram | 0x9F26 (`9F26`) | криптограмма авторизации |
| ATC | 0x9F36 (`9F36`) | счётчик транзакций приложения (защита от повторов) |
| AIP | 0x82 (`82`) | Application Interchange Profile |
| Результат TVR | 0x95 (`95`) | Terminal Verification Results |
| CVM | 0x9F34 (`9F34`) | Cardholder Verification Method |
| Терминальные данные | 0x9F33/0x9F1A/0x9C/0x9A | входят через kernel |

> Только для справки: текущая обёртка **не** передаёт ни один из этих тегов дальше на устройство
> в открытом виде; `tagsMap` возвращается в приложение целиком.

### 2.7 Типы карт

`ICardType`:
```java
int TYPE_CARD_CONTACT       = 0x01;
int TYPE_CARD_CONTACT_LESS  = 0x02;
int TYPE_CARD_MAGNETIC      = 0x08;
```
Проверка ридера `checkReader(code)` поддерживает совмещение битов (контакт+бесконтак).

### 2.8 EMV-параметры (файлы-бобы)

Загружаются из `assets` через `XmlParser` → `XmlDataBean`-наследники; каждый боб сериализуется в
TLV-последовательность и заливается в ядро (`EmvTool.updateParametersByFile`):

| IParamType | Боб | Управление ядра |
|---|---|---|
| `TYPE_EMV_ACQUIRER_PARAM` | `EMVAcquirerParamsBean` | `emv.setDefaultAppParameters` |
| `TYPE_APP_PARAM_EMV` | `EMVAppParamsBean` | `emv.manageEmvAppParameters(ADD)` |
| `TYPE_APP_PARAM_EMVCL` | `EMVCLAppParamsBean` | `emv.manageEmvclAppParameters(ADD)` |
| `TYPE_CA_PUBKEY` | `CAPublicKeyBean` (keyIndex, rsaExponent, rsaModulus, expirationDate, checksum) | `emv.manageCAPubKey(ADD)` |
| `TYPE_EMVCL_DRL` | `EMVCLDRLBean` | `emv.manageDRL(ADD)` |
| `TYPE_CRL` | `CRLBean` | `emv.setCRL(ADD)` |
| `TYPE_EXCEPTION_LIST` | `ExceptionListBean` | `emv.setExceptionList(ADD)` |

CA-ключи обязаны присутствовать — они нужны для офлайн-аутентификации карты (ODA). Без них EMV не пройдёт.

### 2.9 TLV и утилиты

- `emv/tlv/` — свой парсер BER-TLV (`TLVList`, `TLVElement`, `TLV`), лёгкий, без зависимостей.
- `BytesUtil` — hex ↔ bytes, конвертация.
- `XmlParser` — парсинг XML-файлов параметров EMV.

### 2.10 Исключения

| Класс | Смысл |
|---|---|
| `FCardAuthException` | ошибка аутентификации сектора карты (MIFARE-ключи) |
| `FCardAwaitTimeoutException` | карта не поднесена за таймаут |
| `FCardException` | общая карточная/NFC-ошибка |
| `FEmvException` | ошибка EMV-ядра |
| `FIcReaderException` | проблема IC-ридера |
| `FPrinterException` | принтер (нет бумаги/перегрев/зажевало) |

### 2.11 PIN и онлайн/офлайн (важно для PCI-скоупа)

- Ввод PIN реализуется **аппаратным pin-pad'ом** F20 (`initPinEntry`/`StartPinInput`, `PinSeting`-бобы
  в SysAPI) — PIN не выходит за пределы secure element.
- В обёртке `verifyPinSkip=true` по умолчанию; `onOnlineProcess` принудительно отвечает «одобрено
  офлайн». **Если проектируете реальный банковский эквайринг на терминале — online-процессинг
  (`setIssuerOnlineResponseData` + `respondEvent`) придётся реализовать по-настоящему.**
- SDK не содержит HTTP/сетевых вызовов и не хранит серверные ключи — вся сетевая
  авторизация и ключи живут на уровне приложения терминала.

---

## 3. Сервер: как выглядел контур в целом

### 3.1 `asop-handler-master` (приём)

Режимы (из README + `app.config.yaml`/`app.config-2.yaml`):
- **Промежуточный**: принять `queueRawMessages` → проверить → записать `bill_operations` +
  `tk_activity` → отправить в `queueRawToProcessing` (для processing). Ошибки → `queueRawFailureWriteDB`.
- **Финальный**: принять результат подтверждения из processing (`queueSuccessPayment`,
  `queuePrivilegesPayment`, `queueFailurePayment`, `queueProblemPayment`) → дозаписать полноценную
  транзакцию (bill_bank_operations, stop-list, фискальная очередь).

Цепочка хендлеров: `checkCard → checkSessionAndRound → checkTransaction → (update|create)Transaction`.

Что важно для банковской карты:
- **`CardCheckLogic`**: при неизвестной `hpan` карта **создаётся как `BANK_CARD(1000)`**
  (`ECardTypes.BANK_CARD`, про `CASH(1)`), с `cardNumber=maskedPan`, `tkActive=true`,
  а `accountId/cardKey/emitentId/usrContext=null` — т.е. **банковская карта не привязана к
  лицевому счёту/льготам АСОП**.
- **`createBillBankOp`**: пишет строку в `bill_bank_operations` для любой операции, кроме
  `BIG_AGE(9)` и `FAILURE(11)`. Поля: `id=transactionId`, `billOperationId=transactionId`,
  `retRefNumber`, `refNumber`, `tType`, `origReturnCode`, `device=serialNumbNameActOper`.
- **stop-list**: при `paymentStatus == TEMP_BLOCK(5)` добавляется запись `tk_stop_list`
  (`ckhReason = "${refNumber}, ${retRefNumber}"`).
- **фискальная очередь**: если `isPrivileged != true` и статус `TEMP_BLOCK(5)` или `DONE(1)` —
  льготные транзакции на фискализацию не идут.

### 3.2 `asop-processing-master` (старый decision-поток, без банка)

Маршруты: `transactionFill → transactionCheck → transactionCheckGateway → corePrivilegesAsk →
corePrivileges → corePrivilegesGateway → privilegesCalcAsk → privilegesCalc →
privilegesCalcGateway → solarAsk → solar → final…`.

Решения: 
- `TransactionCheckHandler/BenefitsLogic`: если `allowBenefits` и `routeMszCodes` не пуст → `needSolar=false,
  needCore=true` (данные из ядра), иначе `needSolar=true`.
- ветка «нужна авторизация» шла в **Solar** (а не в банк).

### 3.3 Solar-протокол (общий для master и 1.6)

- POST на `settings.solarParameters.solarAt` (`http://192.168.10.64:3000/mock`), протокол
  `solar-transport` v1.1.
- Запрос: `{header{messageId,messageDate,originator{system},protocol{name,version}},
  body{amount,currencyCode,expDate,hpan,iccData,pan,sequenceNumber,stan,terminalId,track2,
  transactionDate,attributes[]}}`.
- `attributes` (~37): `transactionId, altHpan, serialNumbNameActOper, roundId, serviceId, payTypeId,
  zoneIn, zoneOut, maskedPan, typesDiscounts, whiteList, rideNumber, discountedSum, offline,
  amountDiscounts, ignoredTransactionAge, allowBenefits, binAllowAges, terCodesZoneOut,
  allowDiscounts, binAllowDiscount, terCodesZoneIn, routeMszCodes, discountId, discountRuleId,
  appliedBin, workCardKey, sessionId, routeId, routeVariationId, sessionDateStart, roundDateStart,
  isPrivilegedRawProcessData, isDiscountedRawProcessData, rawReceivedDT …`.
- Ответ: `{body{response{type,code,message}}}`; типы `ESolarResponseTypes`:
  `SUCCESS("SUCCESS","SLR-0001")`, `FAILURE("FAILURE","SLR-0002")`, `TIMEOUT("TIMEOUT","SLR-0005")`.
- `SolarClient.sendTransaction` трактует успех только при HTTP 200–299 и `type=="SUCCESS"`.
- Эндпоинт приёма статусов от Solar: `POST /payment-status`.

В 1.6 роль «куда отдать на оплату» переключена с Solar на **Multicard** (ниже), но Solar-контракт
остался как источник карточных данных и обратного звонка.

---

## 4. Сервер: `asop-processing-Release-1.6` — банковский эквайринг

### 4.1 Роль и входы

- Processing-приложение АСОП (Kotlin 2.x / **Septima** framework, Jetty, Hazelcast, Kafka,
  PostgreSQL через Septima ORM).
- Входы:
  - Kafka: `processing.queueRawToProcessing` и промежуточные очереди (см.конфиг ниже);
  - HTTP: `POST /payment-status`, `GET /reauth-transactions` (timeout 30 мин),
    `GET /cancel-transactions`, `GET /refund-transactions`.
- Решения: льгота/privilege (ядро `rnkb-core-client`), расчёт суммы, и далее либо банк (Multicard),
  либо Solar, либо успех без оплаты.
- **Ключевые атрибуты окружения `[EXTERNAL]`-зависимости:**
  `com.transcard:multicard-client` — клиент к Multicard (в коде `MulticardClientAgent.getClient()`),
  бинарь вне репозитория.

### 4.2 Маршруты и хендлеры

`ERouteNames`: `transactionFill, transactionCheck, transactionCheckGateway, corePrivilegesAsk,
corePrivileges, corePrivilegesGateway, privilegesCalcAsk, privilegesCalc, privilegesCalcGateway,
acquiringGateway (закомментирован!), multicardAsk, multicard, solarAsk, solar, finalErrors,
finalSuccess`.

Таблица переходов (факт из `Route.kt`):

| Маршрут | OUT1 | OUT2 | ERR1 | ERR2 |
|---|---|---|---|---|
| transactionFill | transactionCheck | — | finalErrors | — |
| transactionCheck | transactionCheckGateway | — | finalErrors | — |
| transactionCheckGateway | corePrivilegesAsk | corePrivilegesGateway | finalErrors | — |
| corePrivileges | corePrivilegesGateway | — | finalErrors | corePrivileges (retry, maxRetries=5) |
| corePrivilegesGateway | privilegesCalcAsk | privilegesCalcGateway | finalErrors | — |
| privilegesCalc | privilegesCalcGateway | — | finalErrors | — |
| **privilegesCalcGateway** | **multicardAsk** | **finalSuccess** | finalErrors | — |
| multicardAsk | multicard | — | finalErrors | — |
| multicard | finalSuccess | — | finalErrors | multicard (self-retry) |
| solarAsk | solar | — | finalErrors | — |
| solar | *(none)* | — | finalErrors | solar (self-retry) |

Хендлеры: `TransactionFillingHandler`, `TransactionCheckHandler`, `NeedCoreHandler`,
`QueueAskHandler` (для всех `*Ask`), `CorePrivilegeHandler`, `NeedCalculateHandler`,
`TransactionCalcHandler`, `NeedSolarHandler`, `MulticardHandler`, `SolarCoreHandler`,
`FinalSuccessHandler`, `FinalErrorsHandler`.

Решение о банке: `privilegesCalcGateway → OUT1(multicardAsk)`, когда
`NeedSolarHandler.needSolar == true` (т.е. после расчёта осталась сумма к оплате картой).

**Мёртвый хендлер** `AcquiringGatewayHandler` — его маршрут `acquiringGateway` **закомментирован**
в `Route.kt` (строки 51–55). Раньше по нему шла проверка terminalId в `multicard_tids`:
TID в базе → OUT1 `multicardAsk`, TID не в базе → OUT2 `solarAsk`. Сейчас эта проверка не выполняется.

### 4.3 Поток авторизации банковской карты (`MulticardHandler`)

1. `multicardLogic.setMaxRetries(maxRetriesForTimeout)` / `setRetryDelay(retryDelayMs)` (из
   конфига `multicard`).
2. `tryToBlockTid(transaction)` — **до** запроса: INSERT в `blocked_tid_records`
   (ключ = `solar.terminalId`). Если TID уже там → `MulticardBlockedTidException`.
3. Если `transaction.multicard.errCode` ∈ `allowReDefferErrCodes` (`[74,811]`) → `operationNumber = SECOND`
   (повторный дефер).
4. `authTransaction(transaction, operationNumber)` — до `maxRetriesForTimeout` (4) попыток,
   только для `IMulticardRetryException`, задержка `retryDelayMs`.
5. Успех → `deleteBlockedTid(tid)`, кладём `transaction.multicard` =
   `getTransactionMulticardData(...)`:
   ```
   tid, enPan(hex), expDate(hex), extTransactionId, enIccData(hex), paymentSystem(code),
   errorCode, rrn(rpnLink), authCode, requestId, paymentSystemCode, rewindTransactionId
   ```
6. Если код ответа снова ∈ `allowReDefferErrCodes` → `MulticardReDefferException`
   → retry с `EMulticardErrorTypes.RETRY_DEFFERED`.
7. `process.isMulticarted=true`, `retryNumber=0`.
8. `errorCode in 0..10` → OUT1 `finalSuccess`, иначе → `paymentStatus=TEMP_BLOCK(5)` → `finalErrors`.

Транзакция в `finalSuccess` → `prefix paymentStatus = DONE(1)`, топик `queueSuccessPayment`
(обычная) или `queuePrivilegesPayment` (если `isPrivileged`).

### 4.4 TLV-запросы к Multicard (протокол)

Билдер — `MulticardProtocolAdapter`. Формат запросов как JSON сохраняется в
`bill_bank_operations_attempts.request_json` (`*TlvRequestToJson`).

#### AuthTLVRequest (`authorize`, первичная авторизация)

Поля (позиционно):
```
requestId             = id-prefix «9» в hex (например «123»→hex)
operationSum          = (operationSum × 100) HALF_UP → копейки Int
monetaryUnit          = EMonetaryUnit.RUB (код «643»)
encryptedData         = RSA(iccData || [0x57 len track2Bcd] || track2Bcd)   (см. 4.5)
terminalId            = solarData.terminalId
transactionId         = transactionData.transactionId.toString(16) — hex
authTimestamp         = terminalDT.toEpochMilli() / 1000
cardTTTI / cardTMI    = только MC: REAL_TIME_AUTHORIZED / UNKNOWN; иначе null
internalTransactionId = transactionData.transactionId.toString() — decimal
operationNumber       = FIRST (или SECOND на re-deffer)
```

#### ReauthTLVRequest (`reauthorize`, до-авторизация)

```
paymentSystem           = EPaySystem по коду debt.paymentSystem
requestId               = hex
operationSum            = (operationSum × 100) Int
monetaryUnit            = RUB(643)
terminalId              = debt.termPaymentKey
encryptedCardNumber     = hex ← debt.enPan
encryptedFPAN           = hex ← debt.enPan
cardExpiryDate          = hex ← debt.expDate
transactionId           = attemptId.toString(16)      // ВАЖНО: это id попытки (ASP-432)
authTimestamp           = debt.transactionDate.toInstant().epochSecond
internalTransactionId   = debt.extTransactionId
encryptedCardTags       = hex ← debt.enIccData
cardTTTI / cardTMI      = MC: DEBT_RECOVERY / UNKNOWN; иначе null
transportProcessingTicketId = debt.queueId.toString()
```

#### CancelTLVRequest (`cancel`)

```
requestId             = hex
terminalId            = debt.termPaymentKey
rewindTransactionId   = debt.rewindTransactionId
```

#### RefundTLVRequest (`refund`)

```
requestId             = hex
operationSum          = (debt.operationSum × 100) Int
terminalId            = debt.termPaymentKey
transactionId         = debt.queueId.toString(16)     // hex
rewindTransactionId   = debt.rewindTransactionId
transportProcessingTicketId = debt.queueId.toString()
```

### 4.5 RSA-шифрование данных карты

`RsaEncryptor`:
- Алгоритм: `Cipher.getInstance("RSA")` — **дефолтная трансформация JDK** (RSA/ECB/PKCS1Padding),
  **не OAEP** (в `RsaEncryptor.kt` строка `private const val ALGORITHM = "RSA"`).
- Публичный ключ — PEM-строка из **HashiCorp Vault**: `VaultAgent.client.getToken(settings.multicardRsaTokenName)`
  (токен `multicard_rsa`), путь `vaultUrl = http://192.168.10.36:8200/v1/secret/data/transcard`,
  env `ASOP_PROCESSING_VAULT_TOKEN`. Парсинг в `App.kt` → `getApplication().rsaPublicKey`.
- Парсинг: снять заголовки PEM, Base64 → `X509EncodedKeySpec` → `KeyFactory("RSA").generatePublic`.
- Шифруемый payload (`getEncryptedCardData(iccData, track2)`):
  ```
  iccDataBytes ‖ [0x57, track2Len] ‖ track2ToBcd(track2)   → encrypt()
  ```
  `track2ToBcd`: только hex-символы; нечётная длина дополняется `F`;
  пустой track2 → `[0x1F]`.

### 4.6 Коды ошибок Multicard (классификация в `MulticardLogic`)

Для `authorize`:
| Диапазон | Исключение | Тракт (MulticardHandler) |
|---|---|---|
| 0..1000 | успех (parse AuthTLVResponse) | finalSuccess / finalErrors при !0 |
| 1001..1005 | `MulticardProtocolException` | PROTOCOL → fatal |
| 1006..1008 | `MulticardSirposConnectionException` | SIRPOS_CONNECTION → retry (exceptionHandleWithRetry) |
| 1009..1011 | `MulticardRetrySirposConnectionException` | SIRPOS_CONNECTION → fatal (exceptionHandle) |

Исключения клиента → обёртки:
| Исключение клиента | Обёртка |
|---|---|
| `TransactionTimeoutException` / `ReadTimeoutException` / `WriteTimeoutException` | `MulticardRetryTimeoutException` |
| `ConnectionException` | `MulticardConnectionException` |
| `RequestProtocolException` | `MulticardProtocolException` |
| `ResponseProtocolException` | `MulticardCommonException` |
| `MaxConnectionsException` | пробрасывается как есть |
| TLVResponseException | логируется rawMsg, `MulticardCommonException` |

`getErrorDescription(code)` (общая, используется в попытках):
```
0..10     → "SUCCESS"
11..1000  → "FAILURE"   // ошибки processing-сервиса
1001..1005→ "ERROR"     // протокола
1006..1011→ "TIMEOUT"   // подключения
else      → "ERROR"
```

### 4.7 До-авторизация (ReAuth) — `GET /reauth-transactions?batch-size=&tid=`

- Читает очередь `reauth_attempts_multicard_queue` через PG-функцию
  `get_reauth_attempts_multicard_queue(:amount, :ttl, :configTimeProcess, :tid)`.
- Параметры (`multicardReAuthParameters`): `maxAgeTransactionDays=30`,
  `minAgeTransactionDays=1`, `maxRetries=4`, `retryDelayMs=1000`,
  `allowReAuthErrCodes=[76,95,82]`, `allowReDefferErrCodes=[74,811]`.
- На каждую запись: `isNeedReAuth` — по `bill_operations.operation_status`:
  - `1 (DONE)` или `8 (DEB_PAY)` → удалить из очереди, не до-авторизовывать (ASP-497);
  - `7 (REPAY)` → **не до-авторизовывать, НО НЕ удалять из очереди** (ASP-866);
  - иначе → до-авторизация.
- `checkTransaction`: если `tk_id` в in-memory `blockedCards` → `MulticardBlockedException`.
- `attemptId = generateIdWithPrefix()`; `ReauthTLVRequest.transactionId = attemptId.toString(16)`
  (**до Multicard уходит id попытки, не операции** — ASP-432).
- `createAttemptRecord`: INSERT `bill_bank_operations_attempts`
  (`id=attemptId, operation_id=queueId, message_id=requestId, attempt_date_time=now,
  request_json=JSON, attempt_type=1`).
- Повторы: до `maxRetries` (4), только `IMulticardRetryException`, пауза `retryDelayMs`.
- `updateAttemptResult`: `resp_code=errorCode`, `t_type=SUCCESS/FAILURE/ERROR/TIMEOUT`,
  `resp_operation_id=authCode`.
- `handleReauthorizationResult`:
  - `0..10` → снять с stop-list (`end_date=now where end_date is null`), `operation_status=1`,
    удалить из очереди;
  - код **не** ∈ `allowReAuthErrCodes` **и не** ∈ `1000..1096` (SIRPOS-зона) **и не** `-1` →
    добавить `tk_id` в `blockedCards`, удалить все записи карты из очереди;
  - остальное → запись останется в очереди (следующий цикл).
- Ответ: `[{transactionId, cardId, errCode?}]` (`errCode` опускается при null).

### 4.8 Отмена (Cancel) — `GET /cancel-transactions?batch-size=`

- Очередь `cancel_transactions_multicard_queue`, PG-функция
  `get_cancel_transactions_multicard_queue(:amount, :ttl, :configTimeProcess)`.
- Если `multicardCancelParameters.maxAgeTransactionDays` задан → предварительно удаляет старые.
- Per-запись: `CancelTLVRequest`, попытка (`attempt_type=2`), `client.cancel(...)`.
- `errorCode == 0` → `operation_status=2 (CANCELED)`, удалить из очереди.
- Ответ: `[{transactionId, errCode}]`, при ошибках — `errCode=-1`.

### 4.9 Возврат (Refund) — `GET /refund-transactions?batch-size=`

- Очередь `refund_transactions_multicard_queue` (несёт `operation_sum`), PG-функция
  `get_refund_transactions_multicard_queue(:amount, :ttl, :configTimeProcess)`.
- `RefundTLVRequest`, попытка (`attempt_type=3`), `client.refund(...)`.
- `errorCode == 0` → `operation_status=10 (REFUND)`, удалить из очереди.
- Ответ: `[{transactionId, errCode}]`.

### 4.10 Таблицы БД (Septima-модели, поля из `*.sql.json`)

| Таблица | Ключевые поля |
|---|---|
| `bill_operations` | `bill_operations_id`, `operation_sum`, `operation_status`, `discount_sum`, `discount_rule`, `used_benefit_code`, `used_benefit_guid`, `original_sum`, `service_id`, `pay_type`, `operation_date` |
| `bill_bank_operations` | `id=transactionId`, `bill_operation_id`, `ret_ref_number`, `ref_number`, `t_type`, `orig_return_code`, `device` |
| `bill_bank_operations_attempts` | `id`, `operation_id`, `message_id`, `request_json(text)`, `attempt_date_time`, `attempt_type`, `resp_code`, `resp_status`, `resp_message`, `resp_operation_id`, `t_type` |
| `reauth_attempts_multicard_queue` | `queue_id`, `date_create`, `taken_to_send`, `tk_id`, `term_payment_key`, `en_pan`, `exp_date`, `en_icc_data`, `payment_system`, `operation_sum`, `transaction_date`, `err_code`, `err_code_orig` |
| `cancel_transactions_multicard_queue` | `queue_id`, `date_create`, `taken_to_send`, `term_payment_key`, `rewind_transaction_id`, `transaction_date` |
| `refund_transactions_multicard_queue` | то же + `operation_sum` |
| `multicard_tids` | `tid` (признак «TID работает на эквайринг», кэш в Hazelcast) |
| `blocked_tid_records` | `blocked_tid_records_id=tid`, `date_create`, `transaction_data(JSON)` |
| `tk_stop_list` | `tk_id`, `stop_reason`, `start_date`, `end_date` (stop-list карт) |
| `tk_card` | `hpan`, `card_number(maskedPan)`, `card_type=1000 (BANK_CARD)`, `tk_active`, акаунт-поля null |

Записи в `bill_bank_operations_attempts` создаются для reauth/cancel/refund (ASP-428).
Удаление старья из reauth-очереди дополнительно помечает операции `operation_status=6 (BLOCK)`.

### 4.11 Статусы/enums

`EOperationStatus` (`bill_operations.operation_status`):
```
DONE=1 CANCELED=2 PROCESSING=3 PLANNED=4 TEMP_BLOCK=5 BLOCK=6 REPAY=7 DEB_PAY=8 BIG_AGE=9 REFUND=10 FAILURE=11
```

`EAttemptTypes` (`bill_bank_operations_attempts.attempt_type`):
```
REAUTH=1 «Доавторизация»  CANCEL=2 «Отмена»  REFUND=3 «Возврат»
```

`EMulticardErrorTypes` (поле `process.multicardErrType`, выбирает финальную очередь):
```
PROTOCOL=1 CONNECTION=2 SIRPOS_CONNECTION=3 TIMEOUT=4 CANCEL_CONNECTION=5 CANCEL_TIMEOUT=6
CANCEL_SIRPOS_CONNECTION=7 COMMON=8 BLOCKED_TID=9 RETRY_DEFFERED=10
```

`FinalErrorsHandler` — маппинг статусов в топики:
- CORE_PRIVILEGES → `queuePrivilegesFatalError`
- SOLAR / isSolared / isMulticarted → `queueFailurePayment`
- TRANSACTION_CHECK → `queueProblemPayment`
- MULTICARD → по `multicardErrType`: COMMON→`queueMulticardCommonError`, TIMEOUT→`queueMulticardTimeoutError`,
  CANCEL_*→`queueMulticardCancel*`, PROTOCOL→`queueMulticardProtocolError`, CONNECTION→`queueMulticardConnectionError`,
  SIRPOS_CONNECTION→`queueMulticardSirposConnectionError`, RETRY_DEFFERED→`queueMulticardRetryDefferedError`
- иначе → `queueProcessingFailure`

### 4.12 Конфигурация (обязательные параметры)

Из `app.config.yaml` (без учёта чувствительных значений):

```yaml
app:
  mainClass: com.transcard.asop_processing.AppKt
  entitiesPackage: com.transcard
  solarParameters: { solarAt, protocolName: solar-transport, protocolVersion: "1.1" }
  queues: [queueRawToProcessing, queuePrivileges, queuePrivilegesCalc, queueSolarFinSend, queueMulticardFinSend]
  multicardReAuthParameters:
    { maxAgeTransactionDays: 30, minAgeTransactionDays: 1, maxRetries: 4, retryDelayMs: 1000,
      allowReAuthErrCodes: [76,95,82], allowReDefferErrCodes: [74,811] }
  multicardRsaTokenName: multicard_rsa
  multicardCancelParameters:  { maxAgeTransactionDays: 30, minAgeTransactionDays: 1 }
  multicardRefundParameters:  { maxAgeTransactionDays: 30, minAgeTransactionDays: 1 }

stream-router:
  corePrivilegesAsk:  { executeInSeparateStream: true, queueName: queuePrivileges }
  privilegesCalcAsk:  { executeInSeparateStream: true, queueName: queuePrivilegesCalc }
  solarAsk:           { executeInSeparateStream: true, queueName: queueSolarFinSend }
  multicardAsk:       { executeInSeparateStream: true, queueName: queueMulticardFinSend }
  transactionCheck:   { transactionMaxDays: 4 }
  corePrivileges:     { maxRetries: 5, retryQueueName: queuePrivileges }
  solar:              { maxRetries: 4, retryQueueName: queueSolarFinSend }
  multicard:          { maxRetries: 4, retryQueueName: queueMulticardFinSend, retryDelayMs: 1000, maxRetriesForTimeout: 4 }
  finalSuccess:       { successPaymentQueueName, privilegesPaymentQueueName }
  finalErrors:        { 14 полей имён очередей (см. 4.11) }

multicard-client:
  ip: "82.194.226.163"    # SIRPOS
  port: 34082
  authorizeSettings / reauthorizeSettings / cancelSettings / refundSettings:
    { receiveTimeoutSettings: 60s, sendTimeoutSettings: 10s, transactionTimeoutSettings: 60s }
tcp-client: { maxOpenConnections: 10 }
hazelcast:
  cluster-name: asop-processing
  map: { "…MulticardByTid": { time-to-live-seconds: 3600 } }
id: { prefix: 9 }
```

### 4.13 Грабли и особенности (важно при проектировании своего АСОП)

1. **`ACQUIRING_GATEWAY` выключен.** Проверка принадлежности TID эквайрингу
   (`multicard_tids`) сейчас не используется — решение «банк или нет» принимается только
   решением по льготе/сумме (`privilegesCalcGateway.OUT1`). Если в вашем ASOP нужен явный
   отбор TID под банковский эквайринг — это будет отдельная функциональность.
2. **Данные карты передаются банку зашифрованными** (RSA): `iccData` + track2 (тег 0x57).
   Ключ — серверный RSA-публичный из Vault. Приватный ключ — у Multicard (не в этом контуре).
3. **ReAuth-трафик идёт с id попытки** (`bill_bank_operations_attempts.id`), не операции —
   это влияет на сопоставление ответов.
4. **Контроль блокировок**: `blocked_tid_records` (TID) и `tk_stop_list` (карты) — раздельные;
   stop-list снимается после успешной до-авторизации.
5. **Повторный дефер (re-deffer)**: ответы с кодами 74/811 → повторная отправка с
   `operationNumber=SECOND` (`allowReDefferErrCodes`, `RETRY_DEFFERED`).
6. **Timeout-очередь читается как отдельные GET-эндпоинты** с batch-size; у reauth — ещё и `tid`.
   Результат каждой записи возвращается списком `[{transactionId, cardId, errCode?}]`.
7. **Ни один master-проект не содержит банковского эквайринга** — только 1.6. Handler лишь
   помечает карту как `BANK_CARD` и пишет банковские операции/стоп-лист.
8. `SolarReceiverTool` очищает `transaction.solar=null` после маршрутизации; все ре-запросы к
   банку/солар строятся от сохранённого raw-сообщения (фикса regression ASP-217).
9. `bill_bank_operations_attempts.process` возвращает **новую** попытку на каждый вызов; ретраи
   не плодят операции, только попытки (id операции стабилен).
10. MSS 9: **виртуальный** `tk_stop_list`: при успехе reauth снимается `end_date=now` (soft-delete),
    а не DELETE — историю хранить надо.

---

## 5. Сводный quick-ref

| Понятие | Значение |
|---|---|
| Тип банковской карты | `ECardTypes.BANK_CARD = 1000`, без счёта АСОП |
| Статусы операций | 1 DONE, 2 CANCELED, 3 PROCESSING, 4 PLANNED, 5 TEMP_BLOCK, 6 BLOCK, 7 REPAY, 8 DEB_PAY, 9 BIG_AGE, 10 REFUND, 11 FAILURE |
| Типы попыток | 1 REAUTH «Доавторизация», 2 CANCEL «Отмена», 3 REFUND «Возврат» |
| Multicard-ошибки | 1 PROTOCOL, 2 CONNECTION, 3 SIRPOS_CONNECTION, 4 TIMEOUT, 5–7 CANCEL_*, 8 COMMON, 9 BLOCKED_TID, 10 RETRY_DEFFERED |
| Коды ответа Multicard | 0–10 success; 1001–1005 протокол; 1006–1008 connection; 1009–1011 retry; прочее 11–1000 «FAILURE», 1000–1096 «SIRPOS-зона» |
| Коды ре-auth, снимающие stop-list | `allowReAuthErrCodes=[76,95,82]`, re-deffer `allowReDefferErrCodes=[74,811]` |
| RSA | `Cipher.getInstance("RSA")` (JDK default = PKCS#1), ключ из Vault (`multicard_rsa`), payload `iccData‖[0x57,len]‖track2-BCD` |
| Solar | `solar-transport` v1.1; типы `SUCCESS/SLR-0001`, `FAILURE/SLR-0002`, `TIMEOUT/SLR-0005` |
| EMV теги устройства | PAN `5A`, track2 `57`, expiry `5F24`, AC `9F26`, ATC `9F36`, AIP `82`, TVR `95`, CVM `9F34` |
| Типы карт устройства | CONTACT 0x01, CONTACT_LESS 0x02, MAGNETIC 0x08 |
| Kafka (processing) | in: `processing.queueRawToProcessing`; промеж: `queuePrivileges/queuePrivilegesCalc/queueSolarFinSend/queueMulticardFinSend`; out: `queueSuccessPayment/queuePrivilegesPayment/queueFailurePayment/queueProblemPayment` + fatal-очереди |
| HTTP (processing) | `POST /payment-status`, `GET /reauth-transactions`, `GET /cancel-transactions`, `GET /refund-transactions` |

---

## 6. Что осталось за рамками `[EXTERNAL]` (не видно в коде)

- Полный TLV-контракт с Multicard/SIRPOS (структура `AuthTLVRequest`/`ReauthTLVRequest/...`
  и полей `EncryptedFPAN`, `RewindTransactionId`, `rpnLink` и т.п.) — находится в бинарном
  `com.transcard:multicard-client` (не в репозитории).
- Внутренняя логика FTSDK (`Emv`, `NfcReader`, PIN-pad) — в закрытых JAR:
  `app/libs/FTSDK_api_V1.0.1.30_20251010.jar`, `FTSDK_SysAPI_V1.0.243_20251219.jar`.
- Платежная система на PAN определяется только по BIN — `Utils.kt::detectPaymentSystem(pan): Long?`.
  Точные диапазоны (из кода):
  - `1` = Visa — `^4`;
  - `2` = MasterCard — `^(51|52|53|54|55|22|23|24|25|26|27)` (старое 51–55 + новое 2-серия 22–27);
  - `3` = МИР — `^(2200|2201|2202|2203|2204|3562|3565|6234|6291|6292|6711|6763|6764|6765|6768|6769|6773|9112|9417|5058|9762|5614|9051|9990|9364|8888|8600)` — **расширенный диапазон МИР** (ASP-446/ASP-448), включая co-badged BIN 35xx / 62xx / 67xx / 91xx / 94xx и др.;
  - `4` = UnionPay — `^62`.
- Полные SQL-определения очередей (`get_reauth_attempts_multicard_queue` и др.) — в PG, не в репозитории.
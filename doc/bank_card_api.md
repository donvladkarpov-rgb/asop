# API обмена с `android-payment` (банковский эквайринг на устройстве)

Документ из промпта 016 (Фаза 1). Определяет:
- локальный HTTP-API app-payment (`127.0.0.1:8790`) и HMAC-подпись запросов;
- pairing-секрет и ключ матчинга `HMAC(PAN)`;
- серверный контур: `POST /api/v1/payment/report` (app-payment → payment-service через gateway mTLS);
- pull чёрного списка `GET /api/v1/payments/blacklist?versionSince=…`;
- формат отчёта (совпадает с `PaymentReportRequest` в `payment-api`).

`android-payment` — ОТДЕЛЬНОЕ приложение (`ru.asop.payment`, свой signing key, PCI-изоляция). Терминал
`android-terminal` и дистрибьютор `android-distributor` НЕ общаются с банком напрямую: они ходят на
локальный сервер app-payment по `127.0.0.1`. app-payment, в свою очередь, общается с сервером АСОП по
mTLS (своя cert-sign идентичность §3.2.4 промпта 016).

---

## 1. Локальный HTTP-API (app-payment)

Сервер слушает **`http://127.0.0.1:8790`**. Cleartext разрешён только для localhost
(`network_security_config.xml`). Авторизация — **HMAC-SHA256** общим pairing-секретом (см. §2).

### 1.1 Подпись запроса

Каждый запрос несёт три заголовка:

| Заголовок | Обязателен | Назначение |
|-----------|-----------|------------|
| `X-Request-Id` | да | UUIDv7; идемпотентность (app-payment запоминает результат по requestId) |
| `X-Timestamp` | да | Unix-epoch (ms); отклоняется при `|now-ts| > 300 c` |
| `X-Signature` | да | `hex(HMAC_SHA256(secret, "<timestamp>"))` — подпись ТОЛЬКО по timestamp (как в `ApiKeyHmacFilter` gateway), тело не подписывается |

Подпись строится тем же `certSignHmac` helper'ом, что уже есть в `android-terminal`
(`CertSignHmacInterceptor`): `mac = HmacSHA256(secret, timestamp.toString())`, hex в нижнем регистре.

При неверной подписи/просрочке → `401`; при неизвестном `X-Request-Id` ключе пары → `403`.

### 1.2 Эндпоинты

#### `GET /capabilities`

Ответ:
```json
{
  "appVersion": "0.1.0",
  "emv": {"gac": true, "contact": false, "contactless": true, "online": true, "offlineFloorLimit": true},
  "devices": ["F20"],
  "maxAmount": 600000.00
}
```

#### `POST /pay`

Тело:
```json
{
  "amount": 100.00,
  "currency": "RUB",
  "paymentType": "TOPUP",        // TOPUP | FARE | DEBT_RECOVERY
  "capture": true,               // false = только авторизация (pre-auth), см. §5
  "sessionId": "…",              // опционально (для атрибуции чека)
  "transactionId": "…",          // опционально
  "message": "Пополнение транспортной карты"
}
```

Одобрено:
```json
{
  "requestId": "…",
  "status": "APPROVED",
  "paymentId": "…",             // UUIDv7, сгенерирован app-payment (ключ идемпотентности отчёта на сервере)
  "amount": 100.00,
  "acqReference": "MOCK-…",
  "rrn": "…",
  "authCode": "…",
  "card": {"maskedPan": "2200 00… 1234", "panLast4": "1234", "bin": "220000", "expiry": "12/28", "holdername": null},
  "approvedOffline": false
}
```

Отказ:
```json
{ "requestId": "…", "status": "DECLINED", "paymentId": "…", "errorCode": "05", "errorMessage": "Do not honor" }
```

Другие статусы: `TIMEOUT` (пользователь не приложил карту), `DEFERRED` (74/811 — отложенная
авторизация), `CANCELED` (пользователь отменил). `approvedOffline=true` — offline floor limit
(проезд/мелкий топ-ап без связи) → в АСОП создаётся долг, сумма встаёт в порог блэклиста.

Всегда возвращается `paymentId` (генерится app-payment) — это ключ идемпотентности
`PaymentReportRequest.paymentId` на сервере.

#### `GET /status/{requestId}`

Статус ранее начатого платежа (polling UI или возврат после kill):
```json
{
  "requestId": "…",
  "status": "APPROVED | DECLINED | TIMEOUT | DEFERRED | CANCELED | PENDING",
  "paymentId": "…",
  "acqReference": "…?",
  "errorCode": "…?",
  "errorMessage": "…?"
}
```
`404` — неизвестный requestId (сессия была очищена; клиент должен стартовать `/pay` заново).

#### `POST /void/{requestId}`

Авторизация одобрена, но не захвачена (`capture=false`) → запросить отмену/void.
```json
{ "requestId": "…", "status": "VOIDED | VOID_FAILED", "errorCode": "…?" }
```
`void` выполняется локально по EMV-эмуляции (или через `refund` на сервере, если успел capture).

### 1.3 Обратный результат в терминал

Режим A (handoff): терминал запускает app-payment через `startActivityForResult(ACTION_PAY)` с
`EXTRA_REQUEST` (JSON `{requestId, amount, currency, paymentType, capture, sessionId, transactionId, message}`).
Результат — в `EXTRA_RESULT` (JSON, те же поля, что `POST /pay`). Warm-путь по HTTP (§3.6 промпта 016)
использует те же эндпоинты `/pay`/`/status/{requestId}` — клиентский код в `PaymentHandoff` один для обоих
путей, отличается только транспорт.

---

## 2. Pairing-секрет и ключ матчинга HMAC(PAN)

Блэклист банковских карт матчится в АСОП по **`HMAC(PAN)`** (терминал/сервер PAN не видят; чёрный список
раздаётся на устройства как `asop_blacklists` через существующий дельта-пайплайн).

### 2.1 Выдача при pairing

При регистрации/привязке app-payment (после provisioning по cert-sign) клиент запрашивает pairing:

```
app-payment (mTLS) → POST /api/v1/payment/pairing   → { pairingSecret, panHmacKey, expiresAt }
```

- `pairingSecret` — 32 случайных байта (hex) — HMAC-ключ ЛОКАЛЬНОГО канала (§1.1);
- `panHmacKey` — 32-байтный ключ (base64) для `HMAC-SHA256(PAN)` каждого тапа. В app-payment
  ПЕРЕшифровывается локальным Keystore-AES-ключом (по образцу `TerminalKeyCryptor`), в БД не хранится
  в открытом виде.
- Время жизни `expiresAt` — 30 дней; до истечения app-payment делает refresh
  (`POST /api/v1/payment/pairing/refresh`) при появлении сети.
- Pairing логически привязан к сертификату приложения (mTLS identity): новый cert-sign инвалидирует
  старый pairing-секрет.

### 2.2 Матчинг HMAC(PAN) на сервере

`payment-service` получает от app-payment `cardToken` = `hmacHex = hex(HMAC_SHA256(panHmacKey, pan))`
(см. `PaymentAuthorizeRequest.cardToken`). Блэклист для банковских карт хранит тот же
`hmacHex` (в поле `ASOP_BLACKLISTS.CARD_BLOCK_TOKEN` / отдельной колонке типа карты — см. DDL
блока `ASOP_BLACKLISTS`; для VCM1-карт `CARD_ID`, для банковских — `CARD_TOKEN`). Проверка — простое
равенство строк, HMAC-ключ на сервере карт НЕ хранится (payмatch через app-payment).

Раздача на терминалы: поле `CARD_TOKEN` блэклиста входит в дельту `asop_blacklists` — терминал
проверяет `hmacHex` локально при тапе банковской карты ДО старта EMV (или в момент fare-decision).
`cardId` банковской карты (для FK/join) может отсутствовать — только `CARD_TOKEN`.

---

## 3. Серверный контур (app-payment → АСОП)

Все вызовы — через gateway, mTLS-цепочка `@Order(2)` `/api/v1/payment/**` (`SecurityConfig` gateway).
Внутренне gateway проксирует на `payment-service:8093`. К API также доступен путь `/api/v1/payments/**`
(JWT, web-admin) — методы контроллеров общие (dual-mapping).

### 3.1 Отчёт о платеже — `POST /api/v1/payment/report`

Тело — `PaymentReportRequest` (см. `payment-api`, поля соответствуют). app-payment шлёт отчёт для
КАЖДОГО завершённого платежа (APPROVED/DECLINED/TIMEOUT/DEFERRED/VOIDED/REFUNDED), `status` обязателен.

Идемпотентность: по `paymentId` — повтор отчёта не создаёт дубль и возвращает сохранённый результат
(используется механизм `ON CONFLICT (PAYMENT_ID) DO NOTHING` / `switchIfEmpty` по существующему).

### 3.2 Статус платежа — `POST /api/v1/payment/{id}/status` (опц.)

Замена локального `/status`: query сервера после восстановления связи для сверки с фактическим.
Ответ — `PaymentResponse`.

### 3.3 Pull чёрного списка — `GET /api/v1/payments/blacklist?versionSince=…`

Версионированный pull (по образцу мастер-`/delta`-эндпоинтов):
```
{ "items": [ {"cardToken":"…","blockType":"NEGATIVE_BALANCE","relatedDebtId":"…","deleted":false, "version":…} ], "nextVersion": … }
```
app-payment кэширует и отдаёт терминалу по локальному `GET /blacklist` (см. §4.3).

---

## 4. Потоки

### 4.1 Топ-ап у дистрибьютора (режим A на F20)

```
android-distributor: карта дистрибьютора (auth) → карта пассажира → сумма поездок (tariff-rates)
  → POST http://127.0.0.1:8790/pay (HMAC)          // если F20 и app-payment установлен
  → app-payment: NFC/EMV (FTSDK), online auth в ВТБ/мок
  → APPROVED → distributor пишет tripsLeft (VCM1, local)
  → app-payment: POST /api/v1/payment/report (offline-capable, очередь) + транзакция 0802
  → сервер: payment AUTHORIZED + tripsLeft + async-отчёт; чек (fiscal) по sessionId→TID
```
На обычном телефоне банк-опция скрыта (§Q4) — только наличные.

### 4.2 Проезд банковской картой (терминал, режим A, handoff)

```
Terminal: onTagDiscovered → CardClassifier.classify → BANK_EMV
  → beginBankHandoff(): disarm reader → PaymentHandoff.buildIntent → startActivityForResult
  → app-payment: FTSDK NfcReader → повторный тап → EMV → online/offline → результат
  → terminal: onBankPaymentResult → фиксация проезда, писк, re-arm reader
  → app-payment: /report (FARE) — терминал PAN не видит
```

### 4.3 Офлайн

- Топ-ап/проезд с offline floor limit: EMV-одобрение локально → `approvedOffline=true` →
  app-payment кладёт отчёт в очередь (Room `PendingEventEntity`) и шлёт при появлении сети.
  На сервере `FARE` offline создаёт **долг** (`ASOP_CARD_DEBTS`) → порог → блэклист по `HMAC(PAN)`.
- Polling чёрного списка app-payment'ом — периодический фоновый воркер (pull §3.3); терминал получает
  актуальный кэш через локальное `GET /blacklist`.

---

## 5. Статусы и коды

Локальные статусы `/pay`: `APPROVED, DECLINED, TIMEOUT, DEFERRED, CANCELED, PENDING, VOIDED, VOID_FAILED`.
Маппинг на АСОП (`ASOP_BANK_PAYMENTS.STATUS`): `APPROVED→AUTHORIZED(COMPLETED), DECLINED→DECLINED,
TIMEOUT→FAILED(+attempt TIMEOUT), DEFERRED→DEFERRED, CANCELED→REVERSED, VOIDED→REVERSED, REFUNDED→REFUNDED`.

Коды эквайера классов (легаси «MulticardLogic», §4.6 `bank_card.md`):
- `allowReAuthErrCodes = [76, 95, 82]` → `status=REAUTH_REQUIRED` (до-авторизация);
- `allowReDefferErrCodes = [74, 811]` → `status=DEFERRED`;
- `00` — успех; `05` — decline; `91` — таймаут эмитента.
# Промпт 013: устранение остаточных недочётов промпт 012

## Контекст

После инкремента промпт 012 (per-terminal event watermark) остались 3 пункта,
которые делают систему неполноценной:

1. **Pre-existing build regress в `CardActivationViewModel.kt`** — на момент
   написания промпта 012 upload компилировался только потому что incremental build
   кэш маскировал resolved/unresolved reference errors. После `clean build` (что
   обязательно по AGENTS.md) некоторые вызовы в `CardActivationViewModel.kt` не
   компилируются.

2. **Keep-alive для mTLS client cert chain** — сертификат терминала имеет
   фиксированный TTL (~год с момента `provision.sh`). После протухания все
   `/api/v1/sync/**` падают с TLS-ошибкой, данные не уходят, пользователь вынужден
   вручную выбирать «Сертификат» в drawer. Нужно автообновление **без
   перегенерации ключевой пары** (только re-sign CSR с тем же публичным ключом).

3. **End-to-end Kafka event inject tool** — невозможно э2э протестировать
   watermark processor без инструмента инжекции синтетических событий с
   произвольными seq. В sandbox нет `kcat`/`kafka-console-producer`/`kafka-python`,
   gateway не имеет debug-endpoint для inject.

## Задача 1: исправить CardActivationViewModel.kt compile regress

### Что сломано в HEAD

В `ui/screen/CardActivationViewModel.kt`:

| Line | Проблема |
|---|---|
| 220-221 | `viewModelScope.launch(Dispatchers.IO)` body содержит `runCatching { val resp = syncApi.rootLogin(...) }` — `body.userId` тип-инференция падает, потому что `RootLoginResponse` имеет поле `userId` как `UUID`, но в лямбде Kotlin не выводит. Также в `getCardByUid` — `resp.body()?.cardId` неоднозначен. |
| 995 | `syncApi.activateCardVcm1(activateReq)` — этот метод не существует в `SyncApi`. Был, видимо, удалён в промпт 005-02 при рефакторинге VCM1-only. |
| 1040 | `syncApi.getCardByUid(uid)` — этого метода нет. |

### План

1. **`SyncApi.kt`** — добавить `activateCardVcm1(...)` + `getCardByUid(...)` с правильными сигнатурами и `@Header("X-Event-Seq")` для consistency с остальными sync методами:

```kotlin
@POST("api/v1/sync/cards/activate")
suspend fun activateCard(
    @Body request: CardActivateRequest,
    @Header("X-Event-Seq") seq: Long = 0L
): Response<CardActivateResponse>

@POST("api/v1/sync/cards/activate-vcm1")         // ← restore
suspend fun activateCardVcm1(
    @Body request: CardActivateRequest,
    @Header("X-Event-Seq") seq: Long = 0L
): Response<CardActivateResponse>

@GET("api/v1/sync/cards/by-uid/{uid}")            // ← restore
suspend fun getCardByUid(
    @Path("uid") uid: String,
    @Header("X-Event-Seq") seq: Long = 0L
): Response<CardByUidResponse>
```

2. **`CardActivationViewModel.kt`** — исправить type inference issues:
   - Line 220: `result.fold(onSuccess = { userId: java.util.UUID -> ...})` — explicit type
   - Line 226: `result.fold(onFailure = { e: Throwable -> ...})` — explicit type
   - Line 1044: разрулить overload ambiguity через explicit type cast
   - Line 1047: `syncApi.activateCard(req, seq=0L)` — уже сделано в prompt 012

3. **`network/models/CardActivationModels.kt`** — сделать explicit type контракты:
   - `RootLoginResponse.userId: UUID` (was inferred как `String?` в mapping)
   - `CardByUidResponse.cardId: UUID` (was `String?`)
   - `CardActivateResponse.vcm1: Vcm1SubObject` — пусть nullable, чтобы legacy path

4. **Build gate** — после правок ОБЯЗАТЕЛЬНО (AGENTS.md):

```bash
./gradlew :frontend:android-terminal:app:clean :frontend:android-terminal:app:assembleDebug
```

APK устанавливаем только после clean build success.

### Backend-side changes?

Нет. `/api/v1/sync/auth/root` уже существует на gateway (`/sync/auth/root` → Keycloak password grant → возвращает `{userId}`). `/api/v1/sync/cards/activate` уже работает. `/api/v1/sync/cards/by-uid/{uid}` — может не быть на server. Нужно проверить `card-service`:

```bash
grep -rn "by-uid\|byUid" /home/vlad/IdeaProjects/asop/backend/card-service/src/main/
```

Если endpoint отсутствует — добавить в `card-service`:

```kotlin
@GetMapping("/api/v1/sync/cards/by-uid/{uid}")
fun getByUid(@PathVariable uid: String): Mono<ResponseEntity<*>>
```

## Задача 2: keep-alive для mTLS без перегенерации ключей

### Цель

Сертификат терминала имеет TTL ~ 1 год (по умолчанию в cryptservice). После
протухания все `/api/v1/sync/**` падают. Сейчас пользователь должен открыть
drawer → «Сертификат» → диалог подтверждения → нажать «Да» → `cert.sign()` →
co-service генерирует НОВЫЙ сертификат + entity registers новый cert, **НО старая ключевая пара остаётся**
(т.к. `MtlsManager.resetKeyAndCert()` уничтожает только keystore, ключи Keystore сохраняются).

Это и есть поведение по промпту. НО это **требует взаимодействия с пользователем**.
Нужно автомат: за 30 дней до expires → красный информер, за 7 дней →
автоматический re-sign.

### Архитектура

1. **Новый endpoint в `crypto-service`**:
   ```
   POST /api/v1/sync/terminals/{id}/cert-renew
   Body: { terminalId, csrBase64 (in PKCS#10 format) }
   ```
   - crypto-service использует СУЩЕСТВУЮЩИЙ публичный ключ терминала (запрос `terminal_signing_key` из БД)
   - **Не генерирует** новую ключевую пару (т.е. terminal-side: `MtlsManager.exportPublicKey()`)
   - Sign CSR с тем же public key, новый serial/cert-not-after=now+1y
   - Возвращает новый `{ certChain, certNotBefore, certNotAfter }`

2. **Android-side новый flow в `CertificateService.csrRenew()`**:
   - Не вызывает `MtlsManager.resetKey()`, только создаёт CSR с тем же `MtlsManager.publicKey`
   - POST `/api/v1/sync/terminals/{id}/cert-renew` → подождать 202 → poll `events/{gatewayEventId}`
   - При COMPLETED — сохранить новый `.p12` (keystore файл)

3. **Определение expiry**: 
   - **1-й источник**: `MtlsManager.keystoreNotAfter()` parse X.509 из текущего cert
   - **2-й источник**: опрашивать `GET /api/v1/terminals/{id}/cert` (новый endpoint в terminal-service) → вернуть `{isCurrent, expiresAt}`

4. **UI информер — красный снизу**:
   - В `MainScreen` bottomBar добавить новый informer `CertExpiryInformer`:
     - `expiresAt - now > 30 days` → invisible
     - `7 < days < 30` → жёлтый "Сертификат истекает через N дней"
     - `0 < days <= 7` → красный "Сертификат истекает через N дней" + кнопка «Продлить сейчас»
     - `days <= 0` → мигающий красный "Сертификат просрочен!"
   - Пользователь НЕ должен иметь стимула нажимать эту кнопку вручную — auto-trigger

5. **Автоматический trigger**:
   - `CertCheckWorker` — periodic 1 час. Если `days < 7` и не auto-renewed today:
     - Generate CSR с существующим ключом (MtlsManager)
     - POST cert-renew + событие в DB
     - Опубликовать UI notification

### План

Файлы:

```
backend/crypto-service/.../controller/CertRenewController.kt
backend/crypto-service/.../service/CertRenewService.kt
backend/terminal-service/.../controller/CertExpiryController.kt
backend/terminal-service/.../service/CertExpiryService.kt

frontend/android-terminal/.../cert/CertificateRenewer.kt
frontend/android-terminal/.../worker/CertCheckWorker.kt
frontend/android-terminal/.../ui/screen/MainScreen.kt — добавить CertExpiryInformer
```

### Задача 3: end-to-end Kafka event inject tool

### Цель

В sandbox нет `kcat`/`kafka-console-producer` для ручной инжекции синтетических
events. Gateway не имеет debug-endpoint для inject. Без такой возможности
watermark processor не может быть e2e протестирован.

### План

#### Подход A — debug-endpoint в gateway

1. `POST /api/v1/admin/portal-inject` (только для ролей `SUPER_ADMIN` через JWT)
   - Body: `{ topic, payload, headers: Map<String,String> }`
   - Gateway producer.send → Kafka producer publish с указанными headers
   - Возвращает 202 + eventId для poll

Файл: `gateway-service/.../controller/AdminInjectController.kt`

```kotlin
@RestController
class AdminInjectController(private val kafkaTemplate: ReactiveKafkaProducerTemplate<String, Any>) {
    @PostMapping("/api/v1/admin/portal-inject")
    fun inject(@Body req: InjectRequest, principal: Mono<Principal>): Mono<ResponseEntity<*>> {
        // only SUPER_ADMIN
        // kafkaTemplate.send(record with custom headers)
    }
}
```

2. Тестовый Web-admin UI: dashboard → "Inject test event" → форма с topic, payload, headers.

#### Подход B — kcat в Docker Compose

Добавить отдельный init container с `confluentinc/cp-kcat`:

```yaml
kcat:
  image: confluentinc/cp-kcat
  command: tail -f /dev/null
  entrypoint: /bin/sh
```

Тест через `docker compose exec kcat kcat -P -b kafka:9093 -t asop.session.commands ...`

**Подход B легче тестировать программно:**

```bash
docker compose exec kcat \
  kcat -P -b kafka:9093 -t asop.session.commands \
  -X 'headers=X-Terminal-Seq:5,X-Event-Id:f5c...' <<<'{"eventId":"...","eventType":"SessionOpened",...}'
```

#### Подход C (рекомендованный) — оба

- kcat для ad-hoc инжекции (быстро)
- debug-endpoint для CI/CD integration tests (SpringBoot)

### Files

```
infra/docker/docker-compose.yml — добавить kcat service
backend/gateway-service/.../controller/AdminInjectController.kt
backend/gateway-service/.../dto/InjectRequest.kt
backend/gateway-service/.../security/AdminOnly.kt (annotation)

# Тестовый скрипт:
/tmp/opencode/inject_session_seq5.sh  — example injection
```

### Validation matrix

| Тест | Expected |
|---|---|
| `inject session seq=5` with `last_seq=42` | `pending_seq` row created with `acks_published_at=now` |
| `inject session seq=4` after seq=5 | Cascade drain: seq=5 inserted into ASOP_SESSIONS, watermark→5, seq=4 still pending? |
| `inject session seq=43` | APPLIED — ASOP_SESSIONS INSERT, watermark=43 |
| `inject session seq=0` (legacy) | APPLIED directly, watermark unchanged |
| `inject session seq=43` (duplicate) | ALREADY_APPLIED idempotent no-op |

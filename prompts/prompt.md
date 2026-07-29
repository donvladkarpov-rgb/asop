# DeepSeek prompt: рефакторинг термин-флоу + перенос state в Redis

## Проект

ASOP — Kotlin+Spring Boot 3.3.5 (WebFlux, R2DBC) + PostgreSQL 14/PostGIS + Kafka 3.7.1 + Keycloak 25.0.4, все в Docker. Репозиторий ветка `develop`. Рабочая директория — `/home/vlad/IdeaProjects/asop`. Изучай `../AGENTS.md` и `../doc/context.md` перед началом.

Ключевые архитектурные договорённости (читай `../AGENTS.md`!):
- **Все ID = UUIDv7** через `UuidUtils.newId()` (модуль `:backend:shared:asop-common`, `ru.asop.common.util.UuidUtils`).
- **WebFlux, реактивщина везде**. `@Transactional` НЕ работает на R2DBC — надо `transactionalOperator.transactional(mono)`. `ReactiveCrudRepository.save()` с непустым `@Id` делает UPDATE, не INSERT — для нового UUID использовать `R2dbcEntityTemplate.insert()`.
- **Liquibase** — единый changelog `../infrastructure/db-migrations/migrations/v001-init.sql` (один файл, 67 таблиц, ~1587 строк). Любые изменения схемы — отдельный changeset в этом файле или новый `v002-*.sql`, подключённый через `db.changelog-master.yaml`. **Важно:** сейчас используется `docker compose down -v` при пересборке, поэтому можно смело дополнять существующий `v001-init.sql` (БД пересоздаётся). Но если делаем новый changeset — фиксируем checksum concerns, смотри AGENTS.md.
- **API-модули** (`backend/shared/api/{domain}-api`) содержат только controller-интерфейс + DTO. Реализация в `{domain}-service`.
- Германичные соглашения по package: `ru.asop.api.{domain}` для API, `ru.asop.{domain}` для сервиса.

## Текущее состояние термин-флоу (DEPRECATED — что надо менять)

### Provisioning (`../frontend/android-terminal`)
- `ui/screen/ProvisioningScreen.kt` — кнопка "Сгенерировать ключи и запросить сертификат" → `TerminalViewModel.autoProvision()` (`ui/screen/TerminalViewModel.kt:45`) вызывает `certificateService.provision("terminal-${System.currentTimeMillis()}")` — ЛИТЕРАЛЬНО строка от милллисекунд, НЕ ANDROID_ID!
- `service/CertificateService.kt:45-112` — `provision()` генерит EC P-256 keypair в AndroidKeyStore, POST на `api/v1/terminals/cert-sign` (plain HTTPS, без mTLS, chicken-and-egg), поллит `GET /api/v1/events/{eventId}` каждые 2 сек, до 5 мин таймаута. Сохраняет PEM цепочку в SharedPreferences `"asop_terminal_cert"`.
- **ANDROID_ID сейчас не используется нигде** (`grep ANDROID_ID Settings.Secure` returns 0 hits).

### Registration
- `ui/screen/RegistrationScreen.kt` — три текстовых поля: "Серийный номер" (обязательный), "Модель" (опц.), "Номер терминала" (опц.). Кнопка "Зарегистрировать" → `viewModel.registerTerminal(serial, model, number)`.
- `TerminalViewModel.kt:53-70` — вызывает `gatewayApi.registerTerminal(TerminalRegisterRequest(terminalSerial, terminalNumber, terminalModel))`.
- `network/models/TerminalModels.kt:6-12`:
  ```kotlin
  data class TerminalRegisterRequest(
      @Json("terminalSerial") val terminalSerial: String,
      @Json("terminalNumber") val terminalNumber: String? = null,
      @Json("terminalModel")  val terminalModel: String? = null,
      @Json("carrierId")      val carrierId: String? = null
  )
  data class TerminalResponse(
      @Json("id")             val id: String,
      @Json("terminalSerial") val terminalSerial: String,
      @Json("terminalNumber") val terminalNumber: String?,
      @Json("terminalModel")  val terminalModel: String?,
      @Json("carrierId")      val carrierId: String?,
      @Json("status")         val status: String,
      @Json("createdAt")      val createdAt: String,
      @Json("updatedAt")      val updatedAt: String
  )
  ```

### Серверная сторона регистрации
- `backend/shared/api/terminal-api/.../controller/TerminalApi.kt:21-25` — `POST /api/v1/terminals/register` синхронный, возвращает `Mono<ResponseEntity<TerminalResponse>>`.
- `backend/terminal-service/.../controller/TerminalController.kt:19-25` — вызывает `terminalService.register(request)`.
- `backend/terminal-service/.../service/TerminalService.kt:19-32`:
  ```kotlin
  fun register(request: TerminalRegisterRequest): Mono<TerminalResponse> {
      val now = Instant.now()
      val entity = TerminalEntity(
          terminalId = UuidUtils.newId(),
          terminalSerial = request.terminalSerial,
          terminalNumber = request.terminalNumber,
          terminalModel = request.terminalModel,
          carrierId = request.carrierId,
          status = "WAREHOUSE",
          createdAt = now,
          updatedAt = now
      )
      return terminalRepository.save(entity).map { it.toResponse() }
  }
  ```
  **Проблемы:**
  - ВСЕГДА создаёт новый UUID — не ищет по serial.
  - Использует `terminalRepository.save()` (AGENTS.md рекомендует `R2dbcEntityTemplate.insert()` для новых).
  - `TerminalRepository` (`backend/terminal-service/.../repository/TerminalRepository.kt:10-12`) уже имеет `findByTerminalSerial(terminalSerial: String): Mono<TerminalEntity>` — но `register` его НЕ использует. (Используется только cert-saga'ой, см. ниже.)

### Cert-saga (заодно — для контекста)
- `backend/terminal-service/.../service/CertCommandService.kt:40-117` — `@KafkaListener("asop.terminal.cert.issued")`. Внутри `ensureTerminal` (строки 98-117) ДЕЛАЕТ "find by serial, else insert new", но `register` endpoint это НЕ использует.
- `markAllAsNotCurrent` (строки 119-128) + `r2dbcTemplate.insert(newCert)` (после) обёрнуты в `transactionalOperator.transactional(...)`. Так делается atomic-ротация сертификатов.
- Таблица `ASOP_TERMINAL_CERTS` — `v001-init.sql:1306-1332`:
  ```sql
  CREATE TABLE ASOP_TERMINAL_CERTS (
      CERT_ID            UUID NOT NULL,  -- UUIDv7
      TERMINAL_ID        UUID NOT NULL,
      CERT_SERIAL        VARCHAR(50) NOT NULL,
      ISSUED_AT          TIMESTAMPTZ NOT NULL,
      EXPIRES_AT         TIMESTAMPTZ NOT NULL,
      REVOKED_AT         TIMESTAMPTZ,
      REVOCATION_REASON  VARCHAR(255),
      IS_CURRENT         BOOLEAN NOT NULL DEFAULT true,
      CERT_DATA          TEXT NOT NULL,
      CA_CHAIN           TEXT,
      CREATED_AT         TIMESTAMPTZ NOT NULL DEFAULT now(),
      CONSTRAINT pk_terminal_certs PRIMARY KEY (CERT_ID),
      CONSTRAINT fk_tc_terminal FOREIGN KEY (TERMINAL_ID)
          REFERENCES ASOP_TERMINALS (TERMINAL_ID) DEFERRABLE INITIALLY DEFERRED,
      CONSTRAINT uq_tc_cert_serial UNIQUE (CERT_SERIAL),
      CONSTRAINT chk_tc_dates CHECK (EXPIRES_AT > ISSUED_AT),
      CONSTRAINT chk_tc_not_both CHECK (NOT (IS_CURRENT = true AND REVOKED_AT IS NOT NULL))
  );
  CREATE UNIQUE INDEX uq_tc_current_per_terminal ON ASOP_TERMINAL_CERTS (TERMINAL_ID) WHERE IS_CURRENT = true;
  ```
  История сертификатов по терминалу хранится строками с `IS_CURRENT = false` + `REVOKED_AT`/`REVOCATION_REASON`.

### Известные баги схемы (нужно решить в рамках задачи!)
- В `v001-init.sql:858-884` (DDL `ASOP_TERMINALS`) **НЕТ колонок `CREATED_AT` и `UPDATED_AT`**, но `TerminalEntity` (`backend/terminal-service/.../model/TerminalEntity.kt`) их пытается вставить. Это значит `TerminalService.register` сейчас должен падать при инсерте на "column does not exist". Это либо pre-existing баг, либо R2DBC молча игнорирует unmapped колонки. **Проверь** реальное поведение и при необходимости добавь колонки через ALTER в `v001-init.sql` (или исправь entity — но entity колонки нужны, лучше миграция):
  ```sql
  ALTER TABLE ASOP_TERMINALS ADD COLUMN CREATED_AT TIMESTAMPTZ NOT NULL DEFAULT now();
  ALTER TABLE ASOP_TERMINALS ADD COLUMN UPDATED_AT TIMESTAMPTZ NOT NULL DEFAULT now();
  ```
- В DDL `TERMINAL_NUMBER VARCHAR(16) NOT NULL`, но в entity `terminalNumber: String? = null` — если на сервер передаётся null, инерт упадёт на NOT NULL. Решить (например, разрешить NULL — либо через миграцию `ALTER TABLE ASOP_TERMINALS ALTER COLUMN TERMINAL_NUMBER DROP NOT NULL;`, либо требовать inventary number в API).

## ИЗВЕСТНЫЕ БАГИ КОММИТА
Незакоммиченные в ветке `develop` файлы — `gradlew`, `../infrastructure/docker/certs/provision.sh` (mode → executable) и `docker-compose.yml` (увеличены `mem_limit` для всех сервисов). Не трогай их.

---

# ЗАДАЧА — ЧАСТЬ 1: ANDROID_ID как serial, передача UUID при регистрации

## Требования пользователя (as-is):

1. **Серийный номер терминала = `ANDROID_ID`** (см. `android.provider.Settings.Secure.ANDROID_ID`). Он универсальный и всегда есть. Смена ANDROID_ID означает фактически новый терминал.
2. Этап генерации ключевой пары (`Provisioning` экран + `CertificateService`) **оставить как есть** (но в `provision()` нужно передавать `terminalSerial = ANDROID_ID` вместо `"terminal-${System.currentTimeMillis()}"`).
3. При регистрации терминал шлёт на сервер:
   - `terminalSerial` = `ANDROID_ID` (взят автоматом, НЕ руками)
   - `terminalId` = UUID, прихранённый ранее (первичный ключ терминала в БД), если есть, либо `null` если нет
   - `terminalModel` = заполняемое поле (опц., пользовательский ввод). Откуда взять model из андроида автоматически — не понятно, пусть будет вводимым.
   - `terminalNumber` = inventory number, вводится пользователем ВРУЧНУЮ (это инвентарный номер, не путать с ANDROID_ID).

## Серверная логика (новая для `TerminalService.register` / endpoint `POST /api/v1/terminals/register`):

Сервер анализирует запрос:
- **Если присланный `terminalId` имеется в БД** → обновить все атрибуты существующего терминала (`terminalSerial`, `terminalNumber`, `terminalModel`, `updatedAt`), вернуть тот же `terminalId` и остальные поля (кроме сертификата). Статус операции = "выполнено успешно".
- **Если `terminalId == null` ИЛИ такого UUID нет в БД** → создать новый терминал:
  - Сгенерировать новый `terminalId = UuidUtils.newId()` (UUIDv7)
  - Сохранить в `ASOP_TERMINALS` (`status = "WAREHOUSE"`)
  - Найти в `ASOP_TERMINAL_CERTS` ранее сохранённый сертификат (по `terminalSerial`? — разберись, как связать. См. вопросы ниже). Сохранить новый сертификат, добавив строку в `ASOP_TERMINAL_CERTS` (история сертификатов по терминалу — накапливается через `IS_CURRENT = false` для старых + `IS_CURRENT = true` для нового). Если cert уже есть (например, присланный терминалом PEM) — сохраняем его текущим, старые помечаем `IS_CURRENT = false` атомарно (см. паттерн `markAllAsNotCurrent` + insert в `CertCommandService`). Использовать `transactionalOperator.transactional(...)` для atomicity.
  - Созданный `terminalId`豪华 вернуть клиенту
- **Если валидация не прошла** (например, дубликат serial с другим terminalId, либо нарушение FK) → вернуть DTO с ошибкой, текст которой Android покажет пользователю.

## Уточнение про сертификат (АМБИГУЙС — разберись при исполнении)

Из слов пользователя: "по новому терминалу, сохраняет все в базе, в том числе и новый сертификат". Но сертификат УЖЕ сохранён в `ASOP_TERMINAL_CERTS` на этапе provisioning (cert-saga:
`crypto-service` выпускает X.509 → `terminal-service` `CertCommandService.ensureTerminal` создаёт terminal в БД (по serial!) + сохраняет cert через `r2dbcTemplate.insert(newCert)`). То есть к моменту регистрации terminal строка уже существует, и cert уже привязан.

Два варианта трактовки, выбери обоснованный:
- **(A) Прозрачно линковать**: cert-sign уже создал terminal+cert по serial. На регистрации, если `terminalId == null`, берём существующий terminal через `findByTerminalSerial(ANDROID_ID)`, кладём в него обновлённые `terminalNumber`/`terminalModel`, и возвращаем этот существующий `terminalId` клиенту (его app и сохраняет у себя). Если по serial нет терминала — создаем новый (это странно, но на всякий случай, edge-case). Если `terminalId` прислан и найден — обновляем. Если прислан и не найден — это "терминал пропал из БД" — пересоздать.
- **(B) Как просил пользователь буквально**: клиент в регистрации тоже шлёт сертификат (PEM из SharedPreferences) и сервер "заново сохраняет". Этот вариант не.RECOMENDуется, потому что дубликат логики cert-saga, но если пользователь явно этого хочет — попроси уточнить. Работать по варианту (A), результат пояснить в PR.

При реализации:
- Транзакционность (`TransactionalOperator`) обязательна для цепочки "update terminal → markAllAsNotCurrent → insert cert".
- Использовать `R2dbcEntityTemplate.insert()` для новых TerminalEntity/TerminalCertEntity (не `save()`).

## Что менять в Android

### Файлы:
- `frontend/android-terminal/app/src/main/java/ru/asop/terminal/ui/screen/TerminalViewModel.kt:45` — `certificateService.provision("terminal-${System.currentTimeMillis()}")` → передавать ANDROID_ID.
- `frontend/android-terminal/app/src/main/java/ru/asop/terminal/service/CertificateService.kt:45` — `provision()` уже принимает `terminalSerial` параметром, ничего не менять (serrial приходит сверху).
- `../frontend/android-terminal/app/src/main/java/ru/asop/terminal/ui/screen/RegistrationScreen.kt` — переделать UI:
  - Поле "Серийный номер" → **read-only display** ANDROID_ID (показать hex-строку, не редактируется).
  - Поле "Модель" → вводимое, опц.
  - Поле "Инвентарный номер" → вводимое, **обязательное** (это `terminalNumber`).
  - Кнопка "Зарегистрировать" берёт ANDROID_ID из `TerminalViewModel` (или через helper-объект `DeviceIdProvider`).
- `frontend/android-terminal/.../network/models/TerminalModels.kt:6-12` — добавить поле `terminalId: String? = null` в `TerminalRegisterRequest`. Ответ `TerminalResponse` — добавить поле `operationStatus: String?` (`"SUCCESS"` либо null; либо явный result-type sealed class). Решить формат.
- `frontend/android-terminal/.../ui/screen/TerminalViewModel.kt:53-70` (`registerTerminal`) — после успешного response **сохранить `response.id`** в `SyncPreferences.setTerminalId(...)` (метод уже определён в `SyncPreferences.kt:58-62`, но сейчас НИГДЕ не вызывается — починить).
- `frontend/android-terminal/.../db/SyncPreferences.kt:35-67` — `terminalId` уже есть. При init приложения поднимать сохранённый `terminalId` в state, чтобы вывести в `RegistrationScreen` редактором подписи "Терминал уже привязан (UUID: ...)".
- При старте приложения (`MainActivity` или `TerminalNavHost`) — если `terminalId != null` и `certificateReady`, пропускать provisioning и registration, идти сразу в Main. Если `terminalId == null` но `certificateReady` — идти на Registration (показать ANDROID_ID как серийный номер, запросить ввод inventory number).

### ANDROID_ID helper:
Создать helper-объект `DeviceIdProvider`:
```kotlin
object DeviceIdProvider {
    fun getAndroidId(context: Context): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?: throw IllegalStateException("ANDROID_ID не доступен")
}
```
- ANDROID_ID уникален наDevice+user-signing-key combo, не меняется между запусками приложения, меняется при factory reset (и при смене signing key / на multi-user девайсах — на пользователя). Это и есть "смена ANDROID_ID = новый терминал".
- На эмуляторе Android Studio ANDROID_ID стабилен: `emulator-5554` даёт константный ANDROID_ID (например, `"4c6a2a8b5ab4d3e2"`). На реальных девайсах — уникальный 64-bit hex.
- **Важно:** `Settings.Secure.ANDROID_ID` не требует permission.

---

# ЗАДАЧА — ЧАСТЬ 2: Перенос состояния async-команд в Redis

## Текущее состояние (что мигрировать)

- `../backend/gateway-service/src/main/kotlin/ru/asop/gateway/service/EventService.kt` (72 строки) — in-memory `ConcurrentHashMap<UUID, EventStatus>`, TTL 30 мин через `Executors.newSingleThreadScheduledExecutor.scheduleAtFixedRate`.
- `../backend/gateway-service/src/main/kotlin/ru/asop/gateway/model/EventStatus.kt` (20 строк) — `enum EventState { PENDING, COMPLETED, FAILED }` + `data class EventStatus(eventId, commandTopic, state, resultData: String?, errorMessage: String?, createdAt, completedAt: Instant?)`.
- `../backend/gateway-service/src/main/kotlin/ru/asop/gateway/controller/EventController.kt` (42 строки) — `GET /api/v1/events/{eventId}` возвращает:
  - 404 если `eventService.getStatus(eventId).isEmpty`
  - 202 PENDING
  - 200 COMPLETED (тело = `EventStatus`, `resultData` = JSON статус-результата, e.g. cert PEM для cert-saga)
  - 422 FAILED (тело = `EventStatus` с `errorMessage`)
- Продюсеры (`CertCommandService`, `CarrierCommandService`, `Session/Transaction/Card/Debt/Fiscal/Audit/Gps` `CommandService`) — все вызывают `eventService.createPending(eventId, topic)` перед отправкой в Kafka.
- Консьюмеры (`CertEventConsumer.kt`, `CommandEventConsumer.kt`) — вызывают `eventService.complete(eventId, resultData)` / `eventService.fail(eventId, errorMessage)`.

## Требования:

### 2.1. Redis в `docker-compose.yml`
Добавить сервис `redis` аналогично postgres (см. `../infrastructure/docker/docker-compose.yml` строки 13-30 как шаблон):
```yaml
  redis:
    mem_limit: 256m
    image: redis:7-alpine
    ports:
      - "6379:6379"
    volumes:
      - redis_data:/data
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 5s
      timeout: 5s
      retries: 5
    networks:
      - asop-net
```
Добавить `redis_data:` в `volumes:` секцию (строки 613-617).

### 2.2. Зависимости gateway-service от redis
- В `../backend/gateway-service/build.gradle.kts` добавить Spring Data Redis Reactive: `implementation("org.springframework.boot:spring-boot-starter-data-redis-r2dbc")` (или `impl); через `spring-boot-starter-data-redis-runtime` но с реактивным `ReactiveRedisTemplate`). WebFlux stack, поэтому ** ТОЛЬКО РЕАКТИВНЫЙ API** — `ReactiveRedisTemplate<String, String>` (или `ReactiveRedisOperations`).
- В `../backend/gateway-service/src/main/resources/application.yml` (и `application.yml` для Docker) добавить:
  ```yaml
  spring:
    redis:
      host: ${REDIS_HOST:-redis}
      port: ${REDIS_PORT:-6379}
  ```
  В `docker-compose.yml` добавить к сервису gateway-service:
  ```yaml
  REDIS_HOST: redis
  REDIS_PORT: "6379"
  depends_on:
    redis:
      condition: service_healthy
  ```
  (Сейчас `gateway-service` зависит only from `kafka`, `keycloak`, `crypto-service` — дописать `redis: service_healthy`.)
- Поскольку cert saga требует чтобы terminal-service через Kafka тоже доставлял результат в gatewaevent (как сейчас). Только storage переезжает в Redis — команда-flу (Kafka headers `X-Event-Id`) НЕ меняется.

### 2.3. Реактивный `EventService` на Redis (new design)
Переписать `EventService.kt`:
- Вместо `ConcurrentHashMap` использовать `ReactiveStringRedisTemplate`.
- **TTL = 24 часа (1 сутки) — НЕ 30 минут.** Это убирает прежнюю "потерю" PENDING события через 30 мин, давая телефону, который был оффлайн, до 24 часов на синхронизацию.
- Ключ: `asop:event:{eventId}` (string, в Redis рекомендуется держать JSON-сериализованный `EventStatus` либо хэш для прямого доступа к `resultData`).
- Сериализация Jackson `EventStatus` → JSON либо корректнее: использовать Redis Hash (`hset` с field=state, resultData, errorMessage, createdAt, completedAt, commandTopic). Hash позволяет AST-атомарные апдейты полей. Но проще — String key с JSON. Допустимо любое, но должно поддерживать atomic `complete`/`fail` через `RedisCallback`/`opsForValue().set(...)` с проверкой. Либо Lua-скрипт. Если атомарность не критична (т.е.交锋 complete никогда не соревнуется — OK простое `get`+`set`).
- **MUST:** `createPending`, `complete`, `fail` — возвращают реактивные типы (`Mono<Void>`). Это значит все продюсеры (`CertCommandService.kt:42`, `CarrierCommandService.kt:61`, Session/Transaction/etc.) и консьюмеры (`CertEventConsumer.kt`, `CommandEventConsumer.kt`) нужно перевести на реактивные вызовы — `.then()` chains. Команда в Kafka идёт через `kafkaTemplate.send(...).thenReturn(...)` (уже reactive `Mono`!) и `doOnError` callbacks. Проверь, что `.then(eventService.createPending(...))` правильно склеивается с observable chain (например, сначала `createPending`, потом `kafkaTemplate.send().doOnError{eventService.fail(...).subscribe()}`, `subscribe()` для side-effects).
- `getStatus(eventId)` → `Mono<EventStatus>` (или `Mono<Optional<EventStatus>>`). Если key не существует → `Mono.empty()` (Контроллер уже возвращает `.notFound().build()` когда `isEmpty`).
- **Удалить старый `Executors.newSingleThreadScheduledExecutor` и `startCleanup()`** — TTL встроен в Redis через `EXPIRE` (24h). Запись ключа через `redisTemplate.opsForValue().set(key, value, Duration.ofHours(24))` (или `redisTemplate.expire(key, Duration.ofHours(24))` после `set`).

### 2.4. `EventController.kt` adaptations
- `EventController.getEventStatus` уже реактивный (`Mono<ResponseEntity<Any>>`). Поменять `eventService.getStatus(eventId)` на реактивную цепочку: `eventService.getStatus(eventId).map { ... }.defaultIfEmpty(ResponseEntity.notFound().build())` или `switchIfEmpty(Mono.just(...))`.
- HTTP responses остаются те же: 404 / 202 / 200 / 422 (как было).
- Тело `EventStatus` остаётся. Клиент (Android `getEventStatus` через `GatewayApi`/`CertSignApi`) НЕ меняется (он уже парсит `EventStatusResponse` по JSName полям, которые должны совпадать с Jackson-именами `eventId` / `commandTopic` / `state` / `resultData` / `errorMessage` / `createdAt` / `completedAt`).

### 2.5. Сериализация `EventStatus`
- Так как `Instant.now()` и `UUID` сериализуются Jackson через `JavaTimeModule`, уже зарегистрированный модуль в gateway, можно использовать Jackson's `ObjectMapper` для сериализации/десериализации в Redis String value.
- Для reactive Redis String template — `ReactiveRedisTemplate<String, String>` + Jackson `ObjectMapper.readValue`/`writeValueAsString` в `EventSerializer`. Или зарегать `Jackson2JsonRedisSerializer<EventStatus>` через `RedisSerializationContext` (тело reactive).
- `EventState` enum уже правильно Jackson-сериализуется по имени.

### 2.6. Бэк-компатибельность
- API контракта НЕ менять (DTO `EventStatus` остаётся). Тести ImageButtonов и андроид-клиент не трогать (только если чём-то добавилось поле).
- Если после переключения память в `EventService` чистится (in-memory data потеряется при рестарте gateway), это OK (мы RSTART нашего stateless). В Redis переезжаем — state переживает рестарт .

### 2.7. Проверка работы Redis в docker-compose
После добавления убедиться:
- `docker compose down -v` очищает redis_data.
- gateway-service стартует после `redis: service_healthy`.
- Если gateway падает с "Cannot connect to redis://redis:6379" — добавить `REDIS_HOST: redis` в environment gateway-service.
- В логах gateway должно быть `SUBSCRIBE -> redis`. (Health по умолчанию для Spring Data Redis — нет автогенерированного, но можно добавить `management.health.redis.enabled: true`.)

---

# ВЫПОЛНЕНИЕ

## Порядок работы:
1. Сначала schema — добавить колонки `CREATED_AT` / `UPDATED_AT` в `ASOP_TERMINALS` если их нет, и решить `TERMINAL_NUMBER NOT NULL` vs `nullable` (рекомендуется `DROP NOT NULL`, потому что при first-time cert-sign terminalNumber обычно null).
2. Потом серверная логика `TerminalService.register` (новая upsert логика + cert handling для edge case "нет такого терминала").
3. Обновить `TerminalRegisterRequest` DTO в `../backend/shared/api/terminal-api` (добавить `terminalId: UUID?`).
4. Обновить `TerminalResponse` DTO — добавить `operationStatus: String?` (или `operationResult` enum-like string `"SUCCESS"`/error message; лучше — явно: `data class TerminalRegisterResponse(val terminal: TerminalResponse, val status: OperationStatus, val errorMessage: String?)` — обсудить в PR).
5. Подключить Redis, добавить в docker-compose, конфиг в `gateway-service`.
6. Переписать `EventService` (reactive Redis, TTL 24h).
7. Адаптировать продьюсеры и консьюмеры.
8. Андроид — `DeviceIdProvider`, перепилить `RegistrationScreen`, `TerminalViewModel` (persist `terminalId`), `TerminalRegisterRequest` DTO.
9. Исправить nav программу (если есть terminalId — skip registration).
10. Проверить build:
    - `./gradlew build` — все 27 модулей (JVM flags в `../gradle.properties` уже настроены: `-Xmx4g`, `-Xmx2g`.
    - `docker compose -f infrastructure/docker/docker-compose.yml down -v`
    - `docker compose -f infrastructure/docker/docker-compose.yml up -d --build`
    - Логи: `docker compose -f infrastructure/docker/docker-compose.yml logs -f gateway-service terminal-service redis`

## Не забыть:
- Не коммитить автоматически — пользовательский переспрос < commit/push > обязателен по правилам.
- Не трогать незакоммиченные mem_limit-правки в `docker-compose.yml` (lines для них уже изменены — добавь только redis-сервис и mongo-зависимости поверх, не откатывая memlimit increases).
- Стиль кода — смотри соседние сервисы (`CarrierService` — plain R2DBC, `CertCommandService` — transactional cert flow). Использовать `R2dbcEntityTemplate.insert()`.
- Все ID = `UuidUtils.newId()` (UUIDv7).
- Kotlin 2.0.21, JDK 21. Spring Boot 3.3.5. Не использовать Spring 6.0 deprecated APIs.
- НЕ вводить блокирующие операции (`Mono.block()`) в WebFlux контроллерах — только в Kafka Listeners при синхронных void returns допускается (как сейчас в `CertCommandService.onCertIssued`).

## Уточни у пользователя (если в чём-то сомневаешься — спроси, не додумывай):
1. Если `terminalId` прислан клиентом и НЕ найден в БД — это "create new terminal with given UUID" или "fallback к `findByTerminalSerial(ANDROID_ID)`"? Предпочитаю второй вариант (т.к. cert-saga уже создала терминал по serial).
2. Оставить ли регистрация синхронной (`POST → 200 + body`) или переделать в saga? Сейчас синхронная (`Mono<ResponseEntity<TerminalResponse>>`), пользователь хочет "сервер вернул UUID обратно" — оставь синхронной, ничего в Kafka не пушить. Подтвердить в PR.
3. Если на регистрации с `terminalId = null` `findByTerminalSerial(ANDROID_ID)` уже находит терминал (cert-saga создала по serial) — это частый кейс: вернуть существующий `terminalId`, обновив атрибуты. **В этом случае доп. cert-insert НЕ нужен** — cert уже сохранён cert-sagoй. Подтвердить интерпретацию.

## После выполнения:
- В summary PR приложи список изменённых файлов с кратким описанием правок.
- Запусти `./gradlew build` (все 27 модулей), приложи результат в отчёт.
- Запусти `docker compose -f infrastructure/docker/docker-compose.yml down -v` затем `up -d --build`, проверь логи `gateway-service` (должно подцепиться Redis), `terminal-service` и `redis` (нет ошибок) — это подтверждает работоспособность Redis-миграции.
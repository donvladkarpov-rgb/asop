# Prompt 005-02: Серверный эндпоинт GET /api/v1/cards/by-uid/{uid}

## Контекст


Переделать объект cardIdentity:

Структура canonical JSON (identityJson, подписывается сервером — НЕ МЕНЯЕТСЯ):
{
"cardId": "<UUIDv7>",
"uid": "<7-байтный UID карты>",
"regionId": "",
"organizerId": "",
"carrierId": "",
"cardsDistributorId": "",
"auditServiceId": "",
"userId": "",
"roles": ["<роль>"]
}

Логика cardId:
- Для НОВОЙ карты (ключ нулевой, UID не найден на сервере):
  генерируется UUIDv7 на Android через UuidCreator.getTimeOrderedEpoch()
- Для СУЩЕСТВУЮЩЕЙ карты (UID найден в БД сервера):
  терминал делает GET /api/v1/cards/by-uid/{uid} до активации,
  получает cardId из ответа сервера, использует его

Формат хранения на карте (File 0): protobuf binary (вместо UTF-8 JSON).

Proto message CardIdentity (в frontend/android-terminal/app/src/main/proto/):
string card_id = 1;    // UUIDv7 записи в БД (совпадает с cardId из JSON)
string uid = 2;        // 7-байтный UID чипа
string region_id = 3;
string organizer_id = 4;
string carrier_id = 5;
string cards_distributor_id = 6;
string audit_service_id = 7;
string user_id = 8;
repeated string roles = 9;

Процесс записи на карту:
1. Собрать canonical JSON (identityJson) — для подписи сервером
2. POST /sync/smart-cards/sign → получить signatureBase64
   3a. Для новой карты: cardId = новый UUIDv7
   3b. Для existing карты: cardId = GET /api/v1/cards/by-uid/{uid} → serverCardId
4. Преобразовать JSON → proto CardIdentity (cardId из шага 3)
5. POST /sync/cards/activate c (cardIdentity, identityJson, signature, ...)
6. Записать на карту: File 0 = proto.Binary, File 1 = signatureBase64 (UTF-8)

Процесс чтения с карты (CardReadScreen):
1. Прочитать File 0 → parse proto → CardIdentity
2. Прочитать File 1 → signatureBase64
3. Отобразить identity (cardId, uid, regionId, ...)


В flow активации карт (промпт 005) терминал Android по новому protocol (промпт 005-02) должен
получать `cardId` существующей карты с сервера до отправки запроса на активацию.
Это нужно для случая, когда карта с данным UID уже зарегистрирована в БД — `cardId` должен
быть взят из БД, а не генерироваться на терминале.

## Архитектура

```
Android → GET /api/v1/sync/cards/by-uid/{uid} (mTLS)
       → gateway (ServiceRegistry: cards → card-service:8086)
       → card-service → CardMifareRepository.findByUid(uidBytes)
       → 200 { cardId, ... } или 404
```

Gateway уже имеет маппинг `cards` → card-service:8086 в `ServiceRegistry`.
Запрос идёт через mTLS (chain-1 gateway, `/api/v1/sync/**`).

## Что нужно реализовать

### 1. CardService / CardController

В `backend/card-service/` добавить эндпоинт:

```
GET /api/v1/cards/by-uid/{uid}
```

- `uid` — hex-строка 7-байтного UID (14 hex-символов), например `0433A232A02290`
- Декодировать hex → ByteArray, вызвать `cardMifareRepository.findByUid(uidBytes)`
- Если карта найдена → вернуть `{ "cardId": "UUIDv7" }`, HTTP 200
- Если не найдена → HTTP 404

### 2. Карта для UID байтов

`CardMifareEntity` уже имеет поле `uid: ByteArray` (колонка `UID` в `ASOP_CARD_MIFARES`).
`CardMifareRepository.findByUid(uid: ByteArray): Mono<CardMifareEntity>` уже существует.

Через seed-data.sql в БД есть записи с разными UID. Пример:
- seed-data.sql строка 258: `'\x043A2B1C00AA11BB'` — 7-байтный UID карты
- seed-data-delta-*.sql — новые карты с md5-based UID

### 3. Формат ответа

```json
{
  "cardId": "019fe...",
  "uid": "0433A232A02290"
}
```

Только если карта найдена. Если нет — пустое тело + 404.

### 4. Gateway

Эндпоинт `/api/v1/sync/cards/by-uid/{uid}` уже автоматически проксируется через
`ProxyController` на `card-service:8086`, так как `ServiceRegistry` маппит
`cards` → `https://card-service:8086/api/v1/cards`.

Дополнительной конфигурации не требуется.

### 5. Android (SyncApi)

В `frontend/android-terminal/.../network/SyncApi.kt` добавить:

```kotlin
@GET("api/v1/sync/cards/by-uid/{uid}")
suspend fun getCardByUid(@Path("uid") uid: String): Response<CardByUidResponse>
```

Модель ответа:

```kotlin
@JsonClass(generateAdapter = true)
data class CardByUidResponse(
    @Json(name = "cardId") val cardId: String,
    @Json(name = "uid") val uid: String
)
```

### 6. Android (CardActivationViewModel)

В `runActivation()` или в `buildCanonicalIdentity()` для existing-карт:
- Вызвать `syncApi.getCardByUid(uid)`
- Если 200: использовать `cardId` из ответа
- Если 404: сгенерировать новый UUIDv7 (новая карта)

```kotlin
// В activation flow, после identifyCard:
val serverCardId = if (existingCard) {
    syncApi.getCardByUid(uid).body()?.cardId
} else null
val cardId = serverCardId ?: UuidCreator.getTimeOrderedEpoch().toString()
```

## Файлы для изменений

- `backend/card-service/src/main/kotlin/ru/asop/card/controller/CardController.kt`
- `backend/card-service/src/main/kotlin/ru/asop/card/service/CardActivationService.kt`
- `frontend/android-terminal/.../network/SyncApi.kt`
- `frontend/android-terminal/.../network/models/CardActivationModels.kt` (добавить `CardByUidResponse`)
- `frontend/android-terminal/.../ui/screen/CardActivationViewModel.kt`

## Не реализовывать

- Авторизацию/проверку прав на чтение карты по UID (endpoint внутренний, mTLS-защищён через gateway)
- Массовый поиск (GET /api/v1/cards?uid=...) — только by-uid
- Кеширование ответа

# Задача: добавить keyset-пагинацию в дельта-синхронизацию orchestrator-service

## Контекст проекта

Проект **ASOP** — платформа оплаты проезда. Монорепо в `/home/vlad/IdeaProjects/asop`. Backend — Kotlin 2.0.21 + Spring Boot 3.3.5 (WebFlux, R2DBC), Kafka, Redis, MinIO, PostgreSQL.

Схема данных — в `infrastructure/db-migrations/migrations/v001-init.sql` (таблицы `ASOP_*`, к 41 справочнику добавлены колонки `CREATED_AT/UPDATED_AT/DELETED_AT` через DO-блок). Сборка: `./gradlew :backend:orchestrator-service:build` (проверить после правки). Тестов в репозитории нет.

## Что делает дельта-синхронизация

**Поток:** Android-терминал → `POST /api/v1/sync/references/delta` → gateway кладёт `DeltaSyncCommand` в Kafka `asop.delta.commands` → **orchestrator-service** (это тот сервис, который ты правишь) опрашивает мастер-сервисы по `GET /api/v1/{resource}/delta`, сериализует записи в Protobuf, режет на чанки по 50 КБ, пишет в Redis, помечает событие COMPLETED. Терминал потом скачивает чанки.

**Команда** (`backend/shared/asop-kafka-contracts/.../DeltaEvents.kt`):
```kotlin
data class DeltaSyncCommand(
    val eventId: UUID,
    val terminalId: UUID,
    val carrierId: UUID? = null,
    val regionId: UUID? = null,
    val lastUpdatedAt: Map<String, Instant> // tableName → lastUpdatedAt
)
```

**Мастер-сервисы** (admin 8091, carrier 8087, route 8092, user 8082, card 8086) имеют endpoint'ы вида:
```
GET /api/v1/{resource}/delta?updatedAtSince=...&includeDeleted=true&limit=10000&carrierId=...&regionId=...&userIdsIn=...
```
Отвечают **списком JSON-объектов**, отсортированным **по `updated_at` ASC**. Параметры:
- `updatedAtSince` (optional) — вернуть только записи с `updated_at > since` (строго больше);
- `includeDeleted` — если true, включить soft-deleted;
- `limit` — максимум строк в ответе (дефолт 10000);
- `carrierId`/`regionId` — фильтр (не все ресурсы поддерживают);
- `userIdsIn` — фильтр по пользователям (card/user таблицы).

**Ключевые детали возвращаемых JSON:**
- **admin-service** (регионы, организаторы, роли, benefits и т.д.) — возвращает сущности R2DBC, JSON-ключи **camelCase**: `updatedAt`, `benefitId` и т.п.
- **route-service** (fare-zones, routes, paths, vehicles и т.д.) — возвращает `Map<String, Any?>` напрямую из SQL, JSON-ключи **snake_case**: `updated_at`, `vehicle_id` и т.п.
- **user-service / card-service** — смешанно; в коде уже есть обработка `node.get("userId") ?: node.get("user_id")`, т.е. ключи могут быть в обоих регистрах.

## Файл, который править

`backend/orchestrator-service/src/main/kotlin/ru/asop/orchestrator/service/DeltaSyncService.kt` (весь файл приложен ниже). Три метода HTTP-запросов:
- `fetchDelta(ep, carrierId, regionId, updatedAtSince)` — для обычных таблиц;
- `fetchDeltaWithUserIds(ep, userIds, updatedAtSince)` — для USER_TABLES/CARD_TABLES (параметр `userIdsIn` вместо carrier/region);
- `fetchUserIds(command)` — первый запрос за списком user-ов, он использует `fetchDelta` с дефолтным `limit=10_000` (здесь лимит почти неважен, но цикл не нужен — users обычно немного; трогать можно только если не сломаешь).

`fetchTable(table, ep, command, userIds)` выбирает нужный метод по типу таблицы.

## Проблема

`limit=10_000` жёстко зашит в обоих fetch-методах (`DeltaSyncService.kt:87` и `:107`). Если в таблице >10000 строк (нужно для тестовых данных 100–200к записей на таблицу), мастер вернёт только первые 10000, остальные **молча теряются** — пагинации нет, `fetchTable` делает ровно один запрос на таблицу.

## Что нужно сделать

Реализовать **keyset-пагинацию** по `updated_at` в обоих методах (`fetchDelta` и `fetchDeltaWithUserIds`), чтобы выкачивались **все** строки таблицы за несколько последовательных запросов:

1. Сделать первый запрос с `updatedAtSince` из команды (как сейчас).
2. Если мастер вернул **ровно `limit`** строк (страница полная) — сделать ещё один запрос, передав в `updatedAtSince` значение `updated_at` **последней** (максимальной) строки предыдущего ответа.
3. Повторять, пока не придёт страница **меньше `limit`** (значит это последняя) или не закончатся данные.
4. Объединить все страницы в один `Flux<JsonNode>` (вернуть, например, `Flux.concat` страниц).

**Требования к реализации:**

- **Порядок сохранения**: мастера уже сортируют `updated_at ASC`, поэтому не ре-сортируй.
- **Определение `updated_at` последней строки**: парси ключ осторожно — он может быть `updatedAt` (camelCase, admin) или `updated_at` (snake_case, route). Значение — `Instant`. Хэлпер: `node.get("updatedAt") ?: node.get("updated_at")`. Если у строки нет `updated_at` — считать страницу последней (прервать цикл), чтобы не зациклиться.
- **Строгая граница**: повторный запрос должен использовать `>` к значению, поэтому передай ровно значение последней строки в `updatedAtSince`. Не прибавляй к нему ничего (никаких `+1ns`, чтобы не пропустить строки).
- **Цикл на Kotlin**: обычный `while`/рекурсия внутри `Flux`-цепи не сработает напрямую из-за реактивности. Используй идиому: метод `fetchPage(...)` возвращает `Flux<JsonNode>`, и `fetchDelta` строит `Flux.defer { fetchPage(...).concatWith(if (pageWasFull) fetchDeltaWithCursor(...) else Flux.empty()) }` — либо `Flux.defer` + `.repeat`/`.expand`, либо рекурсию через `flatMap`. Как удобнее, главное — без блокировок, строго реактивно (`Mono`/`Flux`), без `.block()`.
- **`limit`**: оставь 10000 в запросах (это и есть размер страницы). Не увеличивай.
- **Фильтры сохранить**: carrierId/regionId/userIdsIn/updatedAtSince должны прокидываться в каждую страницу (кроме `updatedAtSince`, который меняется на курсор).
- **Смягчение ошибок**: текущий `onErrorResume` на каждой странице логирует warn и возвращает `Flux.empty()`. Сохрани это поведение для каждой страницы, но если страница упала — прерывай цикл (не продолжай с пустым курсором).
- **`fetchUserIds`** (`:52`) — не обязателен к изменению, но убедись, что твоя правка не меняет его семантику (он возвращает все userIds; лимит 10000 на users в тестах вряд ли будет превышен, но если сделаешь пагинацию и там — тоже нормально).

## Пример ожидаемого поведения

Таблица `asop_benefits` (admin-service), 25 000 записей, `limit=10000`:
- запрос 1: `updatedAtSince=null` → 10000 строк, курсор = `updated_at` 10000-й;
- запрос 2: `updatedAtSince=<курсор>` → 10000 строк, новый курсор;
- запрос 3: `updatedAtSince=<курсор>` → 5000 строк (< limit) → стоп.
Итого 25000 строк, ни одна не потеряна и не задвоена.

## Проверка

1. `./gradlew :backend:orchestrator-service:build -x test` — должно собраться.
2. Опционально: поднять стек через `docker compose -f infrastructure/docker/docker-compose.yml up -d --build` и проверить логи orchestrator-service при дельта-запросе на таблице с >10000 строк (в консоли должно появиться несколько последовательных логов запросов / итоговое число строк).

## Важно: не трогать
- `ChunkingService` (чанкование в Redis) — не менять.
- `MasterRegistry` — не менять.
- `ProtoRowMapper` — не менять.
- Контракт `DeltaSyncCommand` — не менять.
- Не добавлять комментарии в код (в проекте их минимум, стиль — краткие KDoc на классе/методе).

---

Файл `DeltaSyncService.kt` (текущий):

```kotlin
package ru.asop.orchestrator.service

import com.fasterxml.jackson.databind.JsonNode
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import ru.asop.common.event.EventService
import ru.asop.kafka.events.delta.DeltaSyncCommand
import java.time.Instant
import java.util.UUID

@Service
class DeltaSyncService(
    private val masterRegistry: MasterRegistry,
    private val protoRowMapper: ProtoRowMapper,
    private val chunkingService: ChunkingService,
    private val eventService: EventService,
    private val masterWebClient: WebClient
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun process(command: DeltaSyncCommand): Mono<Void> {
        val eventId = command.eventId
        return Mono.defer {
            fetchUserIds(command).flatMap { userIds ->
                Flux.fromIterable(MasterRegistry.ALL.entries)
                    .concatMap { (table, ep) -> fetchTable(table, ep, command, userIds).map { table to it } }
                    .collectList()
                    .flatMap { tableRows ->
                        val entries = tableRows.flatMap { (table, rows) ->
                            rows.map { table to protoRowMapper.buildRowMessage(table, it) }
                        }
                        chunkingService.storeChunks(eventId, chunkingService.chunkBySize(entries))
                    }
                    .flatMap { meta ->
                        val resultData = "{\"totalChunks\":${meta.totalChunks},\"totalBytes\":${meta.totalBytes}}"
                        eventService.complete(eventId, resultData)
                    }
            }
        }.onErrorResume { err ->
            log.error("Delta sync failed for event {}", command.eventId, err)
            eventService.fail(command.eventId, err.message ?: "delta sync failed").then(Mono.error(err))
        }
    }

    private fun fetchUserIds(command: DeltaSyncCommand): Mono<Set<String>> {
        val ep = MasterRegistry.USER_TABLES["asop_users"]!!
        return fetchDelta(ep, command.carrierId, command.regionId, command.lastUpdatedAt["asop_users"])
            .map { node -> node.get("userId")?.asText() ?: node.get("user_id")?.asText() }
            .filter { it != null }
            .map { it!! }
            .collectList()
            .map { it.toSet() }
            .defaultIfEmpty(emptySet())
    }

    private fun fetchTable(
        table: String,
        ep: MasterEndpoint,
        command: DeltaSyncCommand,
        userIds: Set<String>
    ): Flux<JsonNode> {
        if (table == "asop_users") {
            return fetchDelta(ep, command.carrierId, command.regionId, command.lastUpdatedAt[table])
        }
        if (MasterRegistry.USER_TABLES.containsKey(table) || MasterRegistry.CARD_TABLES.containsKey(table)) {
            return fetchDeltaWithUserIds(ep, userIds, command.lastUpdatedAt[table])
        }
        return fetchDelta(ep, command.carrierId, command.regionId, command.lastUpdatedAt[table])
    }

    private fun fetchDelta(
        ep: MasterEndpoint,
        carrierId: UUID?,
        regionId: UUID?,
        updatedAtSince: Instant?
    ): Flux<JsonNode> {
        return masterWebClient.get().uri { u ->
            val builder = u.path("/api/v1/{resource}/delta")
                .queryParam("includeDeleted", true)
                .queryParam("limit", 10_000)
            carrierId?.let { builder.queryParam("carrierId", it.toString()) }
            regionId?.let { builder.queryParam("regionId", it.toString()) }
            updatedAtSince?.let { builder.queryParam("updatedAtSince", it.toString()) }
            builder.build(ep.resource)
        }.retrieve().bodyToFlux(JsonNode::class.java)
            .onErrorResume { err ->
                log.warn("Delta fetch failed for {}: {}", ep.resource, err.message)
                Flux.empty()
            }
    }

    private fun fetchDeltaWithUserIds(
        ep: MasterEndpoint,
        userIds: Set<String>,
        updatedAtSince: Instant?
    ): Flux<JsonNode> {
        return masterWebClient.get().uri { u ->
            val builder = u.path("/api/v1/{resource}/delta")
                .queryParam("includeDeleted", true)
                .queryParam("limit", 10_000)
            if (userIds.isNotEmpty()) builder.queryParam("userIdsIn", userIds.joinToString(","))
            updatedAtSince?.let { builder.queryParam("updatedAtSince", it.toString()) }
            builder.build(ep.resource)
        }.retrieve().bodyToFlux(JsonNode::class.java)
            .onErrorResume { err ->
                log.warn("Delta fetch failed for {} (userIdsIn): {}", ep.resource, err.message)
                Flux.empty()
            }
    }
}
```

## Дополнительные указания

- Сигнатуры `fetchDelta`/`fetchDeltaWithUserIds` используются ещё и в `fetchUserIds`, поэтому меняй их аккуратно (можно оставить как есть, добавив внутренний цикл).
- Подсказка по реактивной пагинации: `Flux.defer { first().concatWith(Flux.defer { rest() }) }`, где `rest()` рекурсивно проверяет полноту страницы по счётчику элементов — но аккуратно со `bodyToFlux`: ты получаешь `Flux<JsonNode>`, а полноту страницы надо знать до/после материализации. Практичный подход: материализовать страницу в `List` через `.collectList()`, затем проверить `size >= limit` и рекурсивно продолжить; вернуть `Flux.fromIterable(list)`.

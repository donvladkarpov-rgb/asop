# Задача: keyset-пагинация по VERSION в DeltaSyncService и FullSyncService (orchestrator-service)

## Контекст проекта

Проект **ASOP** — платформа оплаты проезда. Монорепо в `/home/vlad/IdeaProjects/asop`. Backend — Kotlin 2.0.21 + Spring Boot 3.3.5 (WebFlux, R2DBC), Kafka, Redis, MinIO, PostgreSQL.

**Важно: этот промпт выполняется строго ПОСЛЕ `prompt_003_02.md`.** К этому моменту:
- в БД у всех 42 дельта-таблицы есть колонка `VERSION BIGINT` из глобального sequence `asop_delta_version_seq` (уникальные возрастающие значения, триггер `trg_delta_version_*` на INSERT/UPDATE);
- мастера (admin 8091, carrier 8087, route 8092, user 8082, card 8086) принимают `versionSince: Long?` вместо `updatedAtSince` и сортируют `version ASC`;
- `DeltaSyncCommand` содержит `lastVersion: Long?` (единый глобальный watermark) вместо `lastUpdatedAt: Map<String, Instant>`;
- в JSON ответов мастеров у каждой строки присутствует ключ `version` (число).

Схема данных — в `infrastructure/db-migrations/asop_schema.sql` (источник DDL). Сборка: `./gradlew :backend:orchestrator-service:build` (проверить после правки). Тестов в репозитории нет.

## Что делает дельта-синхронизация

**Поток:** Android-терминал → `POST /api/v1/sync/references/delta` → gateway кладёт `DeltaSyncCommand` в Kafka `asop.delta.commands` → **orchestrator-service** (этот сервис) опрашивает мастер-сервисы по `GET /api/v1/{resource}/delta`, сериализует записи в Protobuf, режет на чанки по 50 КБ в Redis, помечает событие COMPLETED. Терминал скачивает чанки и накатывает в Room.

**Команда** (`backend/shared/asop-kafka-contracts/.../DeltaEvents.kt`, уже изменена 003_02):
```kotlin
data class DeltaSyncCommand(
    val eventId: UUID,
    val terminalId: UUID,
    val carrierId: UUID? = null,
    val regionId: UUID? = null,
    val lastVersion: Long? = null   // единый watermark по sequence
)
```

**Мастер-сервисы** имеют endpoint'ы вида:
```
GET /api/v1/{resource}/delta?versionSince=...&includeDeleted=true&limit=10000&carrierId=...&regionId=...&userIdsIn=...
```
Отвечают **списком JSON-объектов**, отсортированным **по `version` ASC**. Параметры:
- `versionSince` (optional, Long) — вернуть только записи с `version > versionSince` (строго больше);
- `includeDeleted` — если true, включить soft-deleted;
- `limit` — максимум строк в ответе (дефолт 10000);
- `carrierId`/`regionId` — фильтр (не все ресурсы поддерживают);
- `userIdsIn` — фильтр по пользователям (card/user таблицы).

**Ключевые детали возвращаемых JSON:**
- admin-service — сущности R2DBC, JSON-ключи **camelCase** (`updatedAt`, `benefitId` и т.п.); курсор — `version`.
- route-service — `Map<String, Any?>` напрямую из SQL, JSON-ключи **snake_case**; курсор — `version`.
- user-service / card-service — смешанно; в коде уже есть обработка `node.get("userId") ?: node.get("user_id")`.
- **Курсор для пагинации во всех случаях**: `node.get("version")?.asLong()` (ключ `version` единый, без регистровых различий).

## Файлы, которые править

1. `backend/orchestrator-service/src/main/kotlin/ru/asop/orchestrator/service/DeltaSyncService.kt` — пагинация дельты.
2. `backend/orchestrator-service/src/main/kotlin/ru/asop/orchestrator/service/FullSyncService.kt` — пагинация полной выгрузки.

## Проблема

`limit=10_000` жёстко зашит и в `DeltaSyncService` (`:87`, `:107`), и в `FullSyncService` (`:56`, `:73`, `:82`). Пагинации нет — `fetchTable`/`fetchAll` делают ровно один запрос на таблицу. Для тестовых данных 100–200к записей на таблицу (см. `seed-data-delta-2.sql`) мастер вернёт только первые 10000 строк, остальные **молча потеряются**. Это критично и для дельты, и для полной выгрузки («выкачка всего сразу» в prompt_003.md).

Курсор `version` из sequence уникален и строго возрастает даже внутри одного bulk-INSERT (в отличие от `updated_at`, который был бы одинаковым в одной транзакции), поэтому keyset-пагинация по `version` работает без потерь и зацикливаний.

## Что нужно сделать

Реализовать **keyset-пагинацию по `version`** в обоих сервисах, чтобы выкачивались **все** строки таблицы за несколько последовательных запросов:

### 1. `DeltaSyncService.kt`

Методы `fetchDelta(ep, carrierId, regionId, versionSince)` и `fetchDeltaWithUserIds(ep, userIds, versionSince)` (после 003_02 они принимают `Long?` и шлют `versionSince`):

1. Сделать первый запрос с `versionSince` из команды (`command.lastVersion`).
2. Если мастер вернул **ровно `limit`** строк (страница полная) — сделать ещё один запрос, передав в `versionSince` значение `version` **последней** (максимальной) строки предыдущего ответа.
3. Повторять, пока не придёт страница **меньше `limit`** (значит это последняя) или не закончатся данные.
4. Объединить все страницы в один `Flux<JsonNode>` (вернуть, например, `Flux.concat` страниц).

**Требования к реализации:**
- **Порядок сохранения**: мастера уже сортируют `version ASC`, поэтому не ре-сортируй.
- **Определение курсора**: `node.get("version")?.asLong()`. Если у строки нет `version` — считать страницу последней (прервать цикл), чтобы не зациклиться.
- **Строгая граница**: повторный запрос должен использовать `version > курсор`, поэтому передай ровно `version` последней строки в `versionSince`. Ничего не прибавляй.
- **Реактивность**: без `.block()`, строго `Mono`/`Flux`. Идиома: `Flux.defer { fetchPage(...).concatWith(if (pageWasFull) fetchPageWithCursor(...) else Flux.empty()) }`, либо материализовать страницу в `List` через `.collectList()`, проверить `size >= limit` и рекурсивно продолжить; вернуть `Flux.fromIterable(все строки)`.
- **`limit`**: оставь 10000 в запросах (это и есть размер страницы). Не увеличивай.
- **Фильтры сохранить**: carrierId/regionId/userIdsIn/versionSince должны прокидываться в каждую страницу (кроме `versionSince`, который меняется на курсор).
- **Смягчение ошибок**: текущий `onErrorResume` на каждой странице логирует warn и возвращает `Flux.empty()`. Сохрани для каждой страницы, но если страница упала — прерывай цикл (не продолжай с пустым курсором).
- **`fetchUserIds`** (`:52`) — использует `fetchDelta`, пагинация достанется ему автоматически (для пользователей лимит вряд ли превысится, но единообразие желательно).

### 2. `FullSyncService.kt`

`fetchAll(command)` делает **один запрос на таблицу** в трёх местах:
- `:53-60` — `asop_users` (через `/admin-users/delta`);
- `:70-77` — обычные таблицы (по `carrierId`/`regionId`);
- `:79-85` — USER/CARD таблицы (по `userIdsIn`).

Во всех трёх `limit=10_000` без цикла. Добавить keyset-пагинацию по `version` по той же логике, что и для дельты:
1. Вынести запрос страницы в отдельную функцию (например `fetchPage(uriBuilder, rowsMapper)`: строит URI с `includeDeleted=true`, `limit=10000`, `versionSince=<курсор>`, `carrierId/regionId` или `userIdsIn`, возвращает `Flux<JsonNode>` или `Mono<List<JsonNode>>`).
2. Зациклить: первый запрос без курсора (все строки полной выгрузки), при полной странице (`size >= limit`) повторить с `versionSince = version` последней строки.
3. Собрать все страницы в `List<Message>` через `buildRowMessage` (как сейчас), объединить.
4. `onErrorResume` на каждой странице: warn + `Flux.empty()` (как сейчас), при ошибке прерывать цикл.
5. Остальную логику (сборка ZIP, MinIO, pre-signed URL, `eventService.complete/fail`) — **не менять**.

**Требования:**
- Реактивность, без `.block()`.
- Сортировка уже `version ASC` на мастерах — не ре-сортить.
- Курсор: `node.get("version")?.asLong()`; отсутствие `version` у строки → страница последняя.
- **Не менять**: `ChunkingService`, `MasterRegistry`, `ProtoRowMapper`, контракты `DeltaSyncCommand`/`FullSyncCommand`, механизм ZIP/MinIO.

## Пример ожидаемого поведения

Таблица `asop_benefits` (admin-service), 25 000 записей, `limit=10000`:
- запрос 1: `versionSince=null` → 10000 строк, курсор = `version` 10000-й;
- запрос 2: `versionSince=<курсор>` → 10000 строк, новый курсор;
- запрос 3: `versionSince=<курсор>` → 5000 строк (< limit) → стоп.
Итого 25000 строк, ни одна не потеряна и не задвоена.

## Проверка

1. `./gradlew :backend:orchestrator-service:build -x test` — должно собраться.
2. Опционально: поднять стек через `docker compose -f infrastructure/docker/docker-compose.yml up -d --build`, залить `seed-data-delta-2.sql` (100–200к строк), запустить дельта-синк и полную выгрузку — в логах orchestrator-service несколько последовательных страниц на таблицу, итог без потерь (все строки доехали до терминала).

## Важно: не трогать
- `ChunkingService` — не менять.
- `MasterRegistry` — не менять.
- `ProtoRowMapper` — не менять.
- Контракты `DeltaSyncCommand`/`FullSyncCommand` — не менять.
- Механизм ZIP/MinIO в `FullSyncService` — не менять (только добавить цикл пагинации в `fetchAll`).
- Не добавлять комментарии в код (стиль проекта — краткие KDoc).

---

Файл `DeltaSyncService.kt` (ожидаемое состояние после 003_02; курсор уже переведён на `version`):

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
        return fetchDelta(ep, command.carrierId, command.regionId, command.lastVersion)
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
            return fetchDelta(ep, command.carrierId, command.regionId, command.lastVersion)
        }
        if (MasterRegistry.USER_TABLES.containsKey(table) || MasterRegistry.CARD_TABLES.containsKey(table)) {
            return fetchDeltaWithUserIds(ep, userIds, command.lastVersion)
        }
        return fetchDelta(ep, command.carrierId, command.regionId, command.lastVersion)
    }

    private fun fetchDelta(
        ep: MasterEndpoint,
        carrierId: UUID?,
        regionId: UUID?,
        versionSince: Long?
    ): Flux<JsonNode> {
        return masterWebClient.get().uri { u ->
            val builder = u.path("/api/v1/{resource}/delta")
                .queryParam("includeDeleted", true)
                .queryParam("limit", 10_000)
            carrierId?.let { builder.queryParam("carrierId", it.toString()) }
            regionId?.let { builder.queryParam("regionId", it.toString()) }
            versionSince?.let { builder.queryParam("versionSince", it.toString()) }
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
        versionSince: Long?
    ): Flux<JsonNode> {
        return masterWebClient.get().uri { u ->
            val builder = u.path("/api/v1/{resource}/delta")
                .queryParam("includeDeleted", true)
                .queryParam("limit", 10_000)
            if (userIds.isNotEmpty()) builder.queryParam("userIdsIn", userIds.joinToString(","))
            versionSince?.let { builder.queryParam("versionSince", it.toString()) }
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

- Сигнатуры `fetchDelta`/`fetchDeltaWithUserIds` используются ещё и в `fetchUserIds`, поэтому меняй их аккуратно (можно оставить сигнатуры, добавив внутренний цикл).
- Практичный подход для реактивной пагинации: материализовать страницу в `List` через `.collectList()`, проверить `size >= limit` и рекурсивно продолжить с новым курсором; в конце вернуть `Flux.fromIterable(все строки)`. Можно через `Flux.defer { ... .concatWith(...) }` или `expand` — как удобнее, главное без блокировок.
- В `FullSyncService.kt` переиспользуй ту же идиому для трёх видов запросов (users / обычные / user-card). Общий хелпер-функция `fetchPage` с параметром `versionSince` уберёт дублирование.

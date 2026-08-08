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

/**
 * Дельта-синхронизация: опрашивает мастер-сервисы (/delta), сериализует
 * записи в Protobuf, чанкует по 50 КБ, пишет в Redis, COMPLETED event.
 */
@Service
class DeltaSyncService(
    private val masterRegistry: MasterRegistry,
    private val protoRowMapper: ProtoRowMapper,
    private val chunkingService: ChunkingService,
    private val eventService: EventService,
    private val threeDesKeyService: ThreeDesKeyService,
    private val masterWebClient: WebClient
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun process(command: DeltaSyncCommand): Mono<Void> {
        val eventId = command.eventId
        return Mono.defer {
            fetchUserIds(command).flatMap { userIds ->
                threeDesKeyService.readBaseConfig().flatMap { baseConfig ->
                    val retentionYears = threeDesKeyService.retentionYears(baseConfig)
                    Flux.fromIterable(MasterRegistry.ALL.entries)
                        .concatMap { (table, ep) ->
                            fetchTable(table, ep, command, userIds)
                                .concatMap { row ->
                                    toRowMessage(table, row, retentionYears)
                                }
                                .map { table to it }
                        }
                        .collectList()
                        .flatMap { tableRows ->
                            chunkingService.storeChunks(eventId, chunkingService.chunkBySize(tableRows))
                        }
                        .flatMap { meta ->
                            val resultData = "{\"totalChunks\":${meta.totalChunks},\"totalBytes\":${meta.totalBytes}}"
                            eventService.complete(eventId, resultData)
                        }
                }
            }
        }.onErrorResume { err ->
            log.error("Delta sync failed for event {}", command.eventId, err)
            eventService.fail(command.eventId, err.message ?: "delta sync failed").then(Mono.error(err))
        }
    }

    private fun toRowMessage(table: String, row: JsonNode, retentionYears: Int): Mono<com.google.protobuf.Message> {
        if (table == ThreeDesKeyService.TABLE) {
            return threeDesKeyService.buildKeyRow(row, retentionYears)
        }
        return Mono.just(protoRowMapper.buildRowMessage(table, row))
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
        return fetchAllPages(versionSince) { cursor ->
            masterWebClient.get().uri { u ->
                val builder = u.scheme("https")
                    .host(ep.serviceHost)
                    .port(ep.port)
                    .path("/api/v1/{resource}/delta")
                    .queryParam("includeDeleted", true)
                    .queryParam("limit", LIMIT)
                carrierId?.let { builder.queryParam("carrierId", it.toString()) }
                regionId?.let { builder.queryParam("regionId", it.toString()) }
                cursor?.let { builder.queryParam("versionSince", it.toString()) }
                builder.build(ep.resource)
            }.retrieve().bodyToFlux(JsonNode::class.java)
                .collectList()
                .onErrorResume { err ->
                    log.warn("Delta fetch failed for {}: {}", ep.resource, err.message)
                    Mono.just(emptyList())
                }
        }
    }

    /**
     * Мастер-сервисы (Reactor Netty) отклоняют URL длиннее ~4 КБ
     * (max-initial-line-length). user ID — 36 символов, поэтому весь набор
     * в одном query-параметре выходит за лимит (414 URI Too Long). Разбиваем
     * userIds на батчи по [USER_IDS_BATCH] и опрашиваем каждый отдельно.
     */
    private fun fetchDeltaWithUserIds(
        ep: MasterEndpoint,
        userIds: Set<String>,
        versionSince: Long?
    ): Flux<JsonNode> {
        if (userIds.isEmpty()) return Flux.empty()
        return Flux.fromIterable(userIds.toList().chunked(USER_IDS_BATCH))
            .concatMap { batch ->
                fetchAllPages(versionSince) { cursor ->
                    masterWebClient.get().uri { u ->
                        val builder = u.scheme("https")
                            .host(ep.serviceHost)
                            .port(ep.port)
                            .path("/api/v1/{resource}/delta")
                            .queryParam("includeDeleted", true)
                            .queryParam("limit", LIMIT)
                        builder.queryParam("userIdsIn", batch.joinToString(","))
                        cursor?.let { builder.queryParam("versionSince", it.toString()) }
                        builder.build(ep.resource)
                    }.retrieve().bodyToFlux(JsonNode::class.java)
                        .collectList()
                        .onErrorResume { err ->
                            log.warn("Delta fetch failed for {} (userIdsIn batch of {}): {}", ep.resource, batch.size, err.message)
                            Mono.just(emptyList())
                        }
                }
            }
    }

    /**
     * Keyset-пагинация по VERSION. Страница размера [LIMIT] считается полной —
     * берётся курсор `version` последней строки и запрашивается следующая страница
     * (version > курсор). Пустая/усечённая страница (в т.ч. ошибка) — конец цикла.
     */
    private fun fetchAllPages(
        initialVersionSince: Long?,
        pageFetcher: (Long?) -> Mono<List<JsonNode>>
    ): Flux<JsonNode> {
        return pageFetcher(initialVersionSince)
            .expand { page ->
                if (page.size >= LIMIT) {
                    val cursor = page.last().get("version")?.asLong()
                    if (cursor != null) pageFetcher(cursor) else Mono.empty()
                } else {
                    Mono.empty()
                }
            }
            .flatMapIterable { it }
    }

    private companion object {
        const val LIMIT = 10_000
        // 80 UUID (~2.9 КБ URL) — надёжно ниже 4 КБ лимита Reactor Netty
        const val USER_IDS_BATCH = 80
    }
}

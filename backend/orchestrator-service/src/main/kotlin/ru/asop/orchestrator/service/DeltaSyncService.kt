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

package ru.asop.gateway.controller

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.data.redis.core.ReactiveRedisTemplate
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import ru.asop.api.gateway.dto.request.DeltaSyncRequest
import ru.asop.api.gateway.dto.request.FullSyncRequest
import ru.asop.api.gateway.dto.response.AcceptedResponse
import ru.asop.api.gateway.dto.response.DeltaMetaResponse
import ru.asop.common.event.EventService
import ru.asop.gateway.service.DeltaCommandService
import ru.asop.gateway.service.TerminalResolver
import java.time.Instant
import java.util.UUID

/**
 * Delta/full-синхронизация справочников для терминалов.
 * Все под mTLS (chain Order 1, /api/v1/sync).
 */
@RestController
class DeltaReferenceController(
    private val deltaCommandService: DeltaCommandService,
    private val terminalResolver: TerminalResolver,
    private val eventService: EventService,
    private val chunkRedis: ReactiveRedisTemplate<String, ByteArray>,
    private val objectMapper: ObjectMapper,
    @Qualifier("minioWebClient") private val minioWebClient: WebClient
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @PostMapping("/api/v1/sync/references/delta")
    fun requestDelta(
        @Valid @RequestBody request: DeltaSyncRequest
    ): Mono<ResponseEntity<AcceptedResponse>> {
        log.info("Delta sync requested: terminalId={}, tables={}", request.terminalId, request.lastUpdatedAt.size)
        return terminalResolver.resolve(request.terminalId)
            .flatMap { context ->
                deltaCommandService.publishDelta(request, context)
            }
            .map { eventId ->
                ResponseEntity.accepted()
                    .header("X-Event-Id", eventId.toString())
                    .body(AcceptedResponse(
                        eventId = eventId,
                        topic = "asop.delta.commands",
                        acceptedAt = Instant.now(),
                        locationHint = "/api/v1/sync/references/{eventId}/meta"
                    ))
            }
    }

    @PostMapping("/api/v1/sync/references/full")
    fun requestFull(
        @Valid @RequestBody request: FullSyncRequest
    ): Mono<ResponseEntity<AcceptedResponse>> {
        log.info("Full sync requested: terminalId={}", request.terminalId)
        return terminalResolver.resolve(request.terminalId)
            .flatMap { context ->
                deltaCommandService.publishFull(request, context)
            }
            .map { eventId ->
                ResponseEntity.accepted()
                    .header("X-Event-Id", eventId.toString())
                    .body(AcceptedResponse(
                        eventId = eventId,
                        topic = "asop.delta.full.commands",
                        acceptedAt = Instant.now(),
                        locationHint = "/api/v1/sync/references/{eventId}/meta"
                    ))
            }
    }

    @GetMapping("/api/v1/sync/references/{eventId}/meta")
    fun getMeta(
        @PathVariable eventId: UUID
    ): Mono<ResponseEntity<DeltaMetaResponse>> {
        val metaKey = "asop:event:$eventId:meta"
        return chunkRedis.opsForValue().get(metaKey)
            .map { bytes ->
                val node = objectMapper.readTree(bytes)
                DeltaMetaResponse(
                    totalChunks = node.path("totalChunks").takeIf { it.isNumber }?.asInt(),
                    totalBytes = node.path("totalBytes").takeIf { it.isNumber }?.asLong(),
                    createdAt = node.path("createdAt").takeIf { it.isTextual }?.asText()
                )
            }
            .switchIfEmpty(
                eventService.getStatus(eventId)
                    .flatMap { maybe ->
                        if (maybe.isEmpty) Mono.empty()
                        else {
                            val s3Url = maybe.get().resultData
                                ?.let { runCatching { objectMapper.readTree(it) }.getOrNull() }
                                ?.path("s3Url")?.takeIf { it.isTextual }?.asText()
                            if (s3Url != null) {
                                Mono.just(DeltaMetaResponse(s3Url = s3Url))
                            } else {
                                Mono.just(DeltaMetaResponse())
                            }
                        }
                    }
            )
            .map { ResponseEntity.ok(it) }
            .defaultIfEmpty(ResponseEntity.status(HttpStatus.NOT_FOUND).build())
    }

    @GetMapping(value = ["/api/v1/sync/references/{eventId}/chunks/{n}"])
    fun getChunk(
        @PathVariable eventId: UUID,
        @PathVariable n: Int
    ): Mono<ResponseEntity<ByteArray>> {
        val chunkKey = "asop:event:$eventId:chunk:$n"
        return chunkRedis.opsForValue().get(chunkKey)
            .map { bytes ->
                ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("application/x-protobuf"))
                    .body(bytes)
            }
            .defaultIfEmpty(ResponseEntity.status(HttpStatus.NOT_FOUND).build())
    }

    @GetMapping("/api/v1/sync/references/{eventId}/download")
    fun downloadFullDump(
        @PathVariable eventId: UUID
    ): Mono<ResponseEntity<Flux<DataBuffer>>> {
        return eventService.getStatus(eventId)
            .flatMap { maybe ->
                if (maybe.isEmpty) {
                    Mono.just(ResponseEntity.status(HttpStatus.NOT_FOUND).build<Flux<DataBuffer>>())
                } else {
                    val s3Url = maybe.get().resultData
                        ?.let { runCatching { objectMapper.readTree(it) }.getOrNull() }
                        ?.path("s3Url")?.takeIf { it.isTextual }?.asText()
                    if (s3Url == null) {
                        Mono.just(ResponseEntity.status(HttpStatus.NOT_FOUND).build())
                    } else {
                        val body = minioWebClient.get()
                            .uri(s3Url)
                            .retrieve()
                            .bodyToFlux(DataBuffer::class.java)
                        Mono.just(
                            ResponseEntity.ok()
                                .contentType(MediaType.parseMediaType("application/zip"))
                                .header("Content-Disposition", "attachment; filename=\"full_$eventId.zip\"")
                                .body(body)
                        )
                    }
                }
            }
            .onErrorResume { err ->
                log.warn("Full dump download failed for {}: {}", eventId, err.message)
                Mono.just(ResponseEntity.status(HttpStatus.BAD_GATEWAY).build())
            }
    }
}

package ru.asop.orchestrator.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.google.protobuf.Message
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.ReactiveRedisTemplate
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import ru.asop.proto.v1.DeltaChunk
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Чанкование записей в Protobuf DeltaChunk по 50 КБ (serializedSize)
 * и запись чанков + meta в Redis (`asop:event:{eventId}:chunk:{n}`).
 */
@Service
class ChunkingService(
    private val chunkRedis: ReactiveRedisTemplate<String, ByteArray>,
    private val objectMapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        const val MAX_CHUNK_BYTES = 50_000
        private val TTL = Duration.ofHours(24)
    }

    fun chunkBySize(entries: List<Pair<String, Message>>, maxBytes: Int = MAX_CHUNK_BYTES): List<DeltaChunk> {
        val chunks = mutableListOf<DeltaChunk>()
        val buffer = mutableListOf<Pair<String, Message>>()
        for (entry in entries) {
            buffer.add(entry)
            val draft = toChunk(buffer, 0, 0, "")
            if (draft.serializedSize > maxBytes) {
                buffer.removeAt(buffer.size - 1)
                if (buffer.isNotEmpty()) {
                    chunks.add(toChunk(buffer, 0, 0, ""))
                    buffer.clear()
                }
                buffer.add(entry)
            }
        }
        if (buffer.isNotEmpty()) {
            chunks.add(toChunk(buffer, 0, 0, ""))
        }
        // проставляем метаданные индексов
        return chunks.mapIndexed { i, c ->
            c.toBuilder()
                .setChunkIndex(i)
                .setTotalChunks(chunks.size)
                .build()
        }
    }

    private fun toChunk(entries: List<Pair<String, Message>>, index: Int, total: Int, eventId: String): DeltaChunk {
        val builder = DeltaChunk.newBuilder()
        val chunkDescriptor = DeltaChunk.getDescriptor()
        for ((table, msg) in entries) {
            val field = chunkDescriptor.findFieldByName(table) ?: continue
            builder.addRepeatedField(field, msg)
        }
        builder.chunkIndex = index
        builder.totalChunks = total
        builder.eventId = eventId
        return builder.build()
    }

    fun storeChunks(eventId: UUID, chunks: List<DeltaChunk>): Mono<ChunkMeta> {
        val totalBytes = chunks.sumOf { it.serializedSize }
        return Mono.defer {
            var mono: Mono<Boolean> = Mono.just(true)
            chunks.forEachIndexed { i, chunk ->
                val key = "asop:event:$eventId:chunk:$i"
                mono = mono.then(chunkRedis.opsForValue().set(key, chunk.toByteArray(), TTL))
            }
            mono.then(writeMeta(eventId, chunks.size, totalBytes)).thenReturn(ChunkMeta(chunks.size, totalBytes))
        }
    }

    data class ChunkMeta(val totalChunks: Int, val totalBytes: Int)

    private fun writeMeta(eventId: UUID, totalChunks: Int, totalBytes: Int): Mono<Boolean> {
        val meta = mapOf(
            "totalChunks" to totalChunks,
            "totalBytes" to totalBytes,
            "createdAt" to Instant.now().toString()
        )
        val key = "asop:event:$eventId:meta"
        return try {
            val json = objectMapper.writeValueAsString(meta)
            chunkRedis.opsForValue().set(key, json.toByteArray(Charsets.UTF_8), TTL)
        } catch (e: Exception) {
            log.error("Failed to serialize meta for {}", eventId, e)
            Mono.just(false)
        }
    }
}

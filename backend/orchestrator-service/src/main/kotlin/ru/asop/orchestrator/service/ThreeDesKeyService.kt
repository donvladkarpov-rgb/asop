package ru.asop.orchestrator.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.google.protobuf.ByteString
import com.google.protobuf.Message
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import ru.asop.proto.v1.Asop3desKeysRow
import ru.asop.orchestrator.config.OrchestratorProperties
import java.time.Instant
import java.util.Base64

/**
 * Спец-обработка глобального пула 3DES-ключей (asop_3des_keys):
 *  - blob (зашифрован публичным ключом сервера) → crypto-service `decrypt` → plaintext 24 байта;
 *  - серверный фильтр «N лет» (CREATED_AT >= now - N, N из base-конфига);
 *  - построение proto-строки Asop3desKeysRow (key_material как bytes plaintext).
 */
@Service
class ThreeDesKeyService(
    private val masterWebClient: WebClient,
    private val props: OrchestratorProperties,
    private val objectMapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        const val TABLE = "asop_3des_keys"
        // Ключи base-конфига (scope=NULL) из ASOP_CONFIG_PARAMS
        const val CFG_RETENTION_YEARS = "threeDesKeys.retentionYears"
        const val CFG_ROTATION_CRON = "threeDesKeys.rotationCron"
        const val CFG_ROTATION_ENABLED = "threeDesKeys.rotationEnabled"
    }

    fun readBaseConfig(): Mono<Map<String, Any?>> {
        return masterWebClient.get()
            .uri("https://admin-service:8091/api/v1/config-params/base")
            .retrieve()
            .bodyToMono(JsonNode::class.java)
            .onErrorResume { err ->
                log.warn("3des-keys: base config fetch failed, using defaults: {}", err.message)
                Mono.empty()
            }
            .defaultIfEmpty(objectMapper.createObjectNode())
            .map { node ->
                @Suppress("UNCHECKED_CAST")
                objectMapper.convertValue(node, Map::class.java) as Map<String, Any?>
            }
    }

    fun retentionYears(base: Map<String, Any?>): Int {
        val fromConfig = (base[CFG_RETENTION_YEARS] as? Number)?.toInt()
            ?: (base[CFG_RETENTION_YEARS] as? String)?.toIntOrNull()
        log.debug("3des retentionYears resolved: {} (base)", fromConfig ?: props.threeDesKeys.retentionYears)
        return fromConfig ?: props.threeDesKeys.retentionYears
    }

    fun rotationCron(base: Map<String, Any?>): String {
        return (base[CFG_ROTATION_CRON] as? String)
            ?: props.threeDesKeys.rotationCron
    }

    fun rotationEnabled(base: Map<String, Any?>): Boolean {
        return (base[CFG_ROTATION_ENABLED] as? Boolean)
            ?: ((base[CFG_ROTATION_ENABLED] as? String)?.toBooleanStrictOrNull() ?: props.threeDesKeys.rotationEnabled)
    }

    /** Дефолтный cron из application.yml — fallback при недоступности base-конфига. */
    fun defaultCron(): String = props.threeDesKeys.rotationCron

    /**
     * Преобразует JSON-строку /delta в Asop3desKeysRow:
     *  - отбрасывает записи старше N лет (серверный фильтр);
     *  - расшифровывает KEY_MATERIAL через crypto-service.
     * Возвращает пустой Mono если запись отфильтрована.
     */
    fun buildKeyRow(node: JsonNode, retentionYears: Int): Mono<Message> {
        val createdAt = node.get("createdAt")?.asText()
            ?: node.get("created_at")?.asText()
        if (createdAt != null) {
            val cutoff = Instant.now().minusSeconds(retentionYears * 365L * 86400L)
            val created = runCatching { Instant.parse(createdAt) }.getOrNull()
            if (created != null && created.isBefore(cutoff)) {
                return Mono.empty()
            }
        }
        val cipherBase64 = node.get("keyMaterial")?.asText()
            ?: node.get("key_material")?.asText()
        if (cipherBase64.isNullOrBlank()) {
            log.warn("3des key row without keyMaterial, skipping")
            return Mono.empty()
        }
        return decrypt(cipherBase64)
            .map { plainBase64 ->
                val rowBuilder = Asop3desKeysRow.newBuilder()
                    .setKeyId(node.get("keyId")?.asText() ?: node.get("key_id")?.asText() ?: "")
                    .setKeyMaterial(ByteString.copyFrom(Base64.getDecoder().decode(plainBase64)))
                    .setCreatedAt(parseEpoch(node, "createdAt", "created_at") ?: 0L)
                    .setDeletedAt(parseEpoch(node, "deletedAt", "deleted_at") ?: 0L)
                    .setVersion(node.get("version")?.asLong() ?: 0L)
                rowBuilder.build()
            }
    }

    private fun parseEpoch(node: JsonNode, keyA: String, keyB: String): Long? {
        val raw = node.get(keyA)?.asText() ?: node.get(keyB)?.asText() ?: return null
        if (raw == "null" || raw.isBlank()) return null
        return runCatching {
            raw.toLongOrNull()?.let { it } ?: Instant.parse(raw).toEpochMilli()
        }.getOrNull()
    }

    private fun decrypt(cipherBase64: String): Mono<String> {
        return masterWebClient.post()
            .uri("https://crypto-service:8081/api/v1/keys/decrypt")
            .bodyValue(mapOf("cipherBase64" to cipherBase64))
            .retrieve()
            .bodyToMono(JsonNode::class.java)
            .map { it.get("keyMaterialBase64").asText() }
            .onErrorResume { err ->
                log.error("3des decrypt failed: {}", err.message)
                Mono.error(err)
            }
    }
}
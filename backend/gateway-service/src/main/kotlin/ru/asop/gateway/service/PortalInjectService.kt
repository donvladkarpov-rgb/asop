package ru.asop.gateway.service

import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.kafka.clients.producer.ProducerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.reactive.ReactiveKafkaProducerTemplate
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import ru.asop.api.gateway.dto.request.PortalInjectRequest
import ru.asop.common.util.UuidUtils
import java.util.UUID

/**
 * Промпт 013 Task 3: end-to-end Kafka inject tool.
 *
 * POST /api/v1/admin/portal-inject (JWT, easter/admin/... роль) →
 *   1. JSON-сериализует payload на лету через ObjectMapper → bytes
 *   2. publish в KafkaTemplate как сырой bytes (consumers дешифруют как JsonNode+десериализуют)
 *   3. inject headers: X-Event-Id (gating), X-Terminal-Seq (промпт 012), X-Injector-Id (audit),
 *      X-Injected-At (timestamp).
 *
 * Журналирует всё — ключ + payload bytes + partition/offset.
 *
 * ВНИМАНИЕ: payload намеренно как Map<String, String> чтобы серриализация всегда
 * проходила через ObjectMapper; в target-топиках consumers используют
 * JsonDeserializer + конкретный класс, поэтому сложные вложенные структуры
 * могут потерять типы. Для минимально-валидного inject — flatMap.
 */
@Service
class PortalInjectService(
    private val kafkaTemplate: ReactiveKafkaProducerTemplate<String, Any>,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun publish(req: PortalInjectRequest): Mono<PortalInjectResult> {
        val eventId = UuidUtils.newId()
        val injectedAtMs = System.currentTimeMillis()

        return Mono.fromCallable {
            val payloadJson = objectMapper.writeValueAsString(req.payload)
            val bytes = payloadJson.toByteArray(Charsets.UTF_8)

            val record = ProducerRecord(
                req.topic,
                req.key,
                bytes as Any
            )

            // Inject headers для audit trail (кто и когда вколол)
            req.headers.forEach { (k, v) -> record.headers().add(k, v.encodeToByteArray()) }
            record.headers().add("X-Event-Id", eventId.toString().encodeToByteArray())
            record.headers().add("X-Injector-Id", "portal-admin".encodeToByteArray())
            record.headers().add("X-Injected-At", injectedAtMs.toString().encodeToByteArray())

            log.info(
                "PortalInject: topic={} key={} eventId={} payloadBytes={} headers={}",
                req.topic, req.key, eventId, bytes.size, req.headers
            )
            record
        }.flatMap { record ->
            kafkaTemplate.send(record).map { sendResult ->
                PortalInjectResult(
                    eventId = eventId,
                    topic = req.topic,
                    partition = sendResult.recordMetadata().partition(),
                    offset = sendResult.recordMetadata().offset()
                )
            }
        }
    }

    data class PortalInjectResult(
        val eventId: UUID,
        val topic: String,
        val partition: Int,
        val offset: Long
    )
}

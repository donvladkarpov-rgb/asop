package ru.asop.api.gateway.dto.request

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern

/**
 * Промпт 013 Task 3: end-to-end Kafka inject tool
 * для admin operator (portAL CLI → HTTPS POST → Kafka publish).
 *
 * Body — domain-validated: topic должен быть из allowlist (ASOP_KAFKA_TOPICS),
 * payload — JSON-сериализуемая DTO.
 *
 * headers — список X-*-заголовков прокидываемых в Kafka records как record.headers().
 * Самые важные: X-Event-Id (gating по EventService), X-Keycloak-Id,
 * X-Carrier-Id, X-Region-Id, X-Timezone.
 */
data class PortalInjectRequest(
    @field:NotBlank
    @field:Pattern(
        regexp = "^asop\\.[a-z0-9]+\\.(commands|events|issued|full\\.commands)\$",
        message = "topic must match asop.{domain}.{phase} pattern"
    )
    val topic: String,

    /** partition key (optional). Defaults to "" (round-robin). */
    val key: String? = null,

    /** Extra Kafka headers (X-Event-Id, X-Terminal-Seq, X-Carrier-Id, X-Region-Id, ...). */
    val headers: Map<String, String> = emptyMap(),

    /** Raw event payload as map. Serialized via Jackson to JSON. */
    val payload: Map<String, String> = emptyMap(),
)

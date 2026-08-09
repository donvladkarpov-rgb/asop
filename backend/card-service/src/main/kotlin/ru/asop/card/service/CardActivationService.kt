package ru.asop.card.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.http.HttpStatus
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Mono
import ru.asop.api.card.dto.request.CardActivateRequest
import ru.asop.api.card.dto.response.CardActivateResponse
import ru.asop.card.model.CardEntity
import ru.asop.card.model.CardMifareEntity
import ru.asop.card.repository.CardMifareRepository
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PSSParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.Base64
import java.util.UUID

@Service
class CardActivationService(
    private val cardMifareRepository: CardMifareRepository,
    private val template: R2dbcEntityTemplate,
    private val databaseClient: DatabaseClient,
    private val transactionalOperator: TransactionalOperator,
    private val cryptoWebClient: WebClient,
    private val objectMapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private var cachedPublicKeyBase64: String? = null

    fun activate(request: CardActivateRequest): Mono<CardActivateResponse> {
        return Mono.fromCallable {
            val identity = objectMapper.readTree(request.identityJson)
            authorize(request, identity)
            val pk = pubKey()
            if (!verifySignature(pk, request.identityJson, request.identitySignature)) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid cardIdentity signature")
            }
            identity
        }.flatMap { identity -> upsert(request, identity) }
    }

    private fun authorize(request: CardActivateRequest, identity: JsonNode) {
        val roles = identity.path("roles")
        if (roles.isArray && roles.size() > 0) {
            val target = roles.get(0).asText()
            if (target == "SUPER_ADMIN") {
                if (!request.authorizedByRoot) {
                    throw ResponseStatusException(HttpStatus.FORBIDDEN, "Root card can only be registered with root login/password")
                }
                return
            }
            if (request.authorizedByRoot) {
                return
            }
            val allowed = AUTHORIZATION_MATRIX[target] ?: emptySet()
            if (request.operatorRoles.none { it in allowed }) {
                throw ResponseStatusException(HttpStatus.FORBIDDEN, "No authorizing card role for $target")
            }
        } else {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "cardIdentity.roles must be non-empty")
        }
    }

    private fun pubKey(): String {
        cachedPublicKeyBase64?.let { return it }
        val node = cryptoWebClient.get()
            .uri("https://crypto-service:8081/api/v1/keys/public")
            .retrieve()
            .bodyToMono(JsonNode::class.java)
            .block()
        val key = node?.path("publicKeyBase64")?.asText()
            ?: throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Crypto service unavailable")
        cachedPublicKeyBase64 = key
        return key
    }

    private fun verifySignature(publicKeyBase64: String, dataJson: String, signatureBase64: String): Boolean {
        return runCatching {
            val bytes = dataJson.toByteArray(StandardCharsets.UTF_8)
            val publicKey = KeyFactory.getInstance("RSA").generatePublic(
                X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyBase64))
            )
            val signature = Signature.getInstance("RSASSA-PSS")
            val spec = PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1)
            signature.setParameter(spec)
            signature.initVerify(publicKey)
            signature.update(bytes)
            signature.verify(Base64.getDecoder().decode(signatureBase64))
        }.getOrDefault(false)
    }

    private fun upsert(request: CardActivateRequest, identity: JsonNode): Mono<CardActivateResponse> {
        val cardId = UUID.fromString(identity.path("cardId").asText())
        val uidHex = identity.path("uid").asText()
        val uidBytes = hexToBytes(uidHex)
        val role = if (identity.path("roles").isArray && identity.path("roles").size() > 0) {
            identity.path("roles").get(0).asText()
        } else {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "cardIdentity.roles must be non-empty")
        }
        val now = Instant.now()

        return cardMifareRepository.findById(cardId)
            .flatMap {
                updateExisting(it, request, identity, role, now)
            }
            .switchIfEmpty(
                cardMifareRepository.findByUid(uidBytes)
                    .flatMap<CardActivateResponse> { existing ->
                        if (existing.cardId != cardId) {
                            Mono.error(ResponseStatusException(HttpStatus.CONFLICT, "Card with this UID is already registered"))
                        } else {
                            updateExisting(existing, request, identity, role, now)
                        }
                    }
                    .switchIfEmpty(Mono.defer { insertNew(cardId, uidBytes, request, identity, role, now) })
            )
    }

    private fun insertNew(
        cardId: UUID,
        uidBytes: ByteArray,
        request: CardActivateRequest,
        identity: JsonNode,
        role: String,
        now: Instant
    ): Mono<CardActivateResponse> {
        val card = buildCard(request, identity, role, now)
        val mifare = CardMifareEntity(
            cardId = cardId,
            uid = uidBytes,
            cardRole = role,
            identityJson = request.identityJson,
            identitySignature = request.identitySignature,
            keyVersion = 1,
            validFrom = now,
            createdAt = now,
            updatedAt = now
        )
        return transactionalOperator.transactional(
            template.insert(card).then(template.insert(mifare))
        ).map { CardActivateResponse(cardId, role, now) }
    }

    private fun updateExisting(
        existing: CardMifareEntity,
        request: CardActivateRequest,
        identity: JsonNode,
        role: String,
        now: Instant
    ): Mono<CardActivateResponse> {
        val cardId = existing.cardId
        val updatedMifare = existing.copy(
            cardRole = role,
            identityJson = request.identityJson,
            identitySignature = request.identitySignature,
            updatedAt = now
        )
        return transactionalOperator.transactional(
            template.update(updatedMifare)
                .then(updateCardFields(cardId, request, identity, now))
        ).map { CardActivateResponse(cardId, role, now) }
    }

    private fun updateCardFields(
        cardId: UUID,
        request: CardActivateRequest,
        identity: JsonNode,
        now: Instant
    ): Mono<Int> {
        val sql = "UPDATE ASOP_CARDS SET " +
            "USER_ID = :userId, " +
            "REGION_ID = :regionId, " +
            "ORGANIZER_ID = :organizerId, " +
            "CARRIER_ID = :carrierId, " +
            "CARDS_DISTRIBUTOR_ID = :cardsDistributorId, " +
            "AUDIT_SERVICE_ID = :auditServiceId, " +
            "REGISTERED_AT = :registeredAt, " +
            "REGISTERED_BY_USER_ID = :registeredByUserId, " +
            "UPDATED_AT = :updatedAt " +
            "WHERE CARD_ID = :cardId"
        val userId = uuidOrNull(identity, "userId")
        val regionId = uuidOrNull(identity, "regionId")
        val organizerId = uuidOrNull(identity, "organizerId")
        val carrierId = uuidOrNull(identity, "carrierId")
        val cardsDistributorId = uuidOrNull(identity, "cardsDistributorId")
        val auditServiceId = uuidOrNull(identity, "auditServiceId")
        val spec = databaseClient.sql(sql)
        if (userId != null) spec.bind("userId", userId) else spec.bindNull("userId", UUID::class.java)
        if (regionId != null) spec.bind("regionId", regionId) else spec.bindNull("regionId", UUID::class.java)
        if (organizerId != null) spec.bind("organizerId", organizerId) else spec.bindNull("organizerId", UUID::class.java)
        if (carrierId != null) spec.bind("carrierId", carrierId) else spec.bindNull("carrierId", UUID::class.java)
        if (cardsDistributorId != null) spec.bind("cardsDistributorId", cardsDistributorId) else spec.bindNull("cardsDistributorId", UUID::class.java)
        if (auditServiceId != null) spec.bind("auditServiceId", auditServiceId) else spec.bindNull("auditServiceId", UUID::class.java)
        spec.bind("registeredAt", now)
        val rootUserId = request.rootUserId
        if (rootUserId != null) spec.bind("registeredByUserId", rootUserId) else spec.bindNull("registeredByUserId", UUID::class.java)
        return spec
            .bind("updatedAt", now)
            .bind("cardId", cardId)
            .fetch().rowsUpdated()
            .map { it.toInt() }
    }

    private fun buildCard(request: CardActivateRequest, identity: JsonNode, role: String, now: Instant): CardEntity {
        return CardEntity(
            cardId = UUID.fromString(identity.path("cardId").asText()),
            cardTypeId = MIFARE_DESFIRE_TYPE,
            userId = uuidOrNull(identity, "userId"),
            regionId = uuidOrNull(identity, "regionId"),
            organizerId = uuidOrNull(identity, "organizerId"),
            carrierId = uuidOrNull(identity, "carrierId"),
            cardsDistributorId = uuidOrNull(identity, "cardsDistributorId"),
            auditServiceId = uuidOrNull(identity, "auditServiceId"),
            registeredAt = now,
            registeredByUserId = request.rootUserId,
            createdAt = now,
            updatedAt = now
        )
    }

    private fun uuidOrNull(node: JsonNode, field: String): UUID? {
        val value = node.path(field)
        return if (value.isTextual && value.asText().isNotBlank()) UUID.fromString(value.asText()) else null
    }

    private fun hexToBytes(hex: String): ByteArray {
        if (hex.length % 2 != 0) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "UID must be even-length hex")
        return ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    companion object {
        private val MIFARE_DESFIRE_TYPE = UUID.fromString("00000000-0000-0000-0000-000000000403")

        private val ALL_AUTHORIZING_ROLES: Set<String> = setOf(
            "SUPER_ADMIN", "REGION_ADMIN", "ORGANIZER_ADMIN", "CARRIER_ADMIN", "DISTRIBUTOR_ADMIN",
            "KRS_ADMIN", "CARRIER_DISPATCHER", "DISTRIBUTOR_DISPATCHER", "KRS_DISPATCHER",
            "DRIVER", "KRS_FOREMAN", "KRS_CONTROLLER"
        )

        val AUTHORIZATION_MATRIX: Map<String, Set<String>> = mapOf(
            "REGION_ADMIN" to setOf("SUPER_ADMIN", "REGION_ADMIN"),
            "ORGANIZER_ADMIN" to setOf("SUPER_ADMIN", "REGION_ADMIN", "ORGANIZER_ADMIN"),
            "CARRIER_ADMIN" to setOf("SUPER_ADMIN", "REGION_ADMIN", "ORGANIZER_ADMIN", "CARRIER_ADMIN"),
            "DISTRIBUTOR_ADMIN" to setOf("SUPER_ADMIN", "REGION_ADMIN", "ORGANIZER_ADMIN", "DISTRIBUTOR_ADMIN"),
            "KRS_ADMIN" to setOf("SUPER_ADMIN", "REGION_ADMIN", "ORGANIZER_ADMIN", "KRS_ADMIN"),
            "CARRIER_DISPATCHER" to setOf("SUPER_ADMIN", "REGION_ADMIN", "ORGANIZER_ADMIN", "CARRIER_ADMIN", "CARRIER_DISPATCHER"),
            "DISTRIBUTOR_DISPATCHER" to setOf("SUPER_ADMIN", "REGION_ADMIN", "ORGANIZER_ADMIN", "DISTRIBUTOR_ADMIN", "DISTRIBUTOR_DISPATCHER"),
            "KRS_DISPATCHER" to setOf("SUPER_ADMIN", "REGION_ADMIN", "ORGANIZER_ADMIN", "KRS_ADMIN", "KRS_DISPATCHER"),
            "DRIVER" to setOf("SUPER_ADMIN", "REGION_ADMIN", "ORGANIZER_ADMIN", "CARRIER_ADMIN", "CARRIER_DISPATCHER", "DRIVER"),
            "KRS_FOREMAN" to setOf("SUPER_ADMIN", "REGION_ADMIN", "ORGANIZER_ADMIN", "KRS_ADMIN", "KRS_DISPATCHER", "KRS_FOREMAN"),
            "KRS_CONTROLLER" to setOf("SUPER_ADMIN", "REGION_ADMIN", "ORGANIZER_ADMIN", "KRS_ADMIN", "KRS_DISPATCHER", "KRS_FOREMAN", "KRS_CONTROLLER"),
            "PASSENGER" to ALL_AUTHORIZING_ROLES,
            "PASSENGER_ANONYMOUS" to ALL_AUTHORIZING_ROLES
        )
    }
}
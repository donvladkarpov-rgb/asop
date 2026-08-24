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
import ru.asop.api.card.dto.request.CardActivateRequestClassic
import ru.asop.api.card.dto.response.CardActivateResponse
import ru.asop.api.card.dto.response.ClassicCardActivateResponse
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
        // Промпт 008: MIFARE Classic VCM1-flow — без RSA-PSS подписи, server-master cardId по UID.
        return if (request.vcm1 != null) {
            activateClassic(request)
        } else {
            // Legacy DESFire-flow с signature verification
            activateWithSignature(request)
        }
    }

    /**
     * VCM1-flow (промпт 008): MIFARE Classic карты, unsigned identity, server-master cardId.
     *
     * Алгоритм:
     *  1. Валидируем bitmask (0..0x3FFF) и entityType соответствие.
     *  2. Поиск в ASOP_CARD_MIFARES по UID:
     *      - если найден:
     *          - Берём существующий cardId (serverCardId).
     *          - Если serverCardId == clientCardId → cardIdOverridden = false (просто обновляем поля).
     *          - Если serverCardId != clientCardId → cardIdOverridden = true (терминал обязан перезаписать block 1).
     *      - если не найден → создаём новую запись с clientCardId (карта новая).
     *  3. Записываем как ASOP_CARDS, ASOP_CARD_MIFARES с IDENTITY_JSON=VCM1 (format='VCM1'),
     *     IDENTITY_SIGNATURE=NULL (или сохраняем существующее), CARD_TECH='CLASSIC'.
     *  4. Никаких RSA-PSS подписей не требуется.
     */
    private fun activateClassic(request: CardActivateRequest): Mono<CardActivateResponse> {
        val vcm1 = request.vcm1
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "vcm1 payload missing for Classic")
        val classicTech = vcm1.technology.uppercase()
        if (classicTech != "CLASSIC") {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "vcm1 flow requires technology=CLASSIC")
        }
        val bitmask = vcm1.bitmask
        if (bitmask !in 0..0x3FFF) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST,
                "bitmask out of range: 0x${bitmask.toString(16)} (must be 0..0x3FFF)")
        }
        val primaryRole = highestBitRole(bitmask)
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "bitmask has no set bit")

        // Промпт 009: multi-role restriction — все биты должны соответствовать ОДНОМУ entityType.
        // Например, DRIVER(bit 9) + CARRIER_DISPATCHER(bit 6) оба → CARRIER. OK.
        // DRIVER + KRS_FOREMAN → разные entityType (CARRIER ≠ AUDIT_SERVICE) — ЗАПРЕТ.
        if (Integer.bitCount(bitmask) > 1) {
            val types = allRolesForBitmask(bitmask)
                .map { primaryEntityType(it) }
                .toSet()
            if (types.size > 1) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "multi-role cards require same entityType across all roles, got $types")
            }
        }

        // Промпт 009 (clean-break): все роли кроме PASSENGER_ANONYMOUS требуют entityType="userId".
        val entityType = vcm1.entityType.lowercase()
        val expectedField = (if (primaryRole == "PASSENGER_ANONYMOUS") "none" else "userId").lowercase()
        if (expectedField != entityType) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST,
                "entityType=$entityType inconsistent with primary role=$primaryRole (expected $expectedField — промпт 009)"
            )
        }
        if (entityType != "none" && vcm1.entityId == null) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST,
                "entityId (userId) required for $entityType (primary role=$primaryRole)")
        }
        if (entityType == "none" && vcm1.entityId != null) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST,
                "entityId must be null for PASSENGER_ANONYMOUS")
        }
        // Промпт 009 defense-in-depth: проверить, что userId существует в ASOP_USERS.
        // Без этого можно записать в БД "осиротевший" userId, который рушит JOIN.
        // Реактивно (block() на reactor-потоке запрещён — IllegalStateException).
        val userIdCheck: Mono<Void> = if (entityType == "userid" && vcm1.entityId != null) {
            val nonNullEntityId = vcm1.entityId!!
            databaseClient.sql("SELECT 1 FROM ASOP_USERS WHERE user_id = :userId LIMIT 1")
                .bind("userId", nonNullEntityId)
                .fetch()
                .rowsUpdated()
                .map { it > 0 }
                .defaultIfEmpty(false)
                .flatMap { exists ->
                    if (exists) Mono.empty()
                    else Mono.error(ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "userId $nonNullEntityId not found in ASOP_USERS"
                    ))
                }
        } else {
            Mono.empty()
        }

        // authorize() flow для VCM1: проверяем рядом матрицы авторизации.
        authorizeClassic(request, primaryRole)

        val clientCardId = vcm1.cardId
        val uidBytes = hexToBytes(vcm1.uid)
        val now = Instant.now()

        return userIdCheck.then(cardMifareRepository.findByUid(uidBytes))
            .flatMap<CardActivateResponse> { existing ->
                val serverCardId = existing.cardId
                val overridden = serverCardId != clientCardId
                updateClassicCard(existing, vcm1, primaryRole, entityType, now)
                    .map { vcm1Resp ->
                        // Если серверный cardId отличается от переданного терминалом,
                        // терминал должен перезаписать block 1 на карте после успешной активации.
                        CardActivateResponse(
                            cardId = serverCardId,
                            cardRole = primaryRole,
                            registeredAt = now,
                            vcm1 = vcm1Resp.copy(
                                cardId = serverCardId,
                                cardIdOverridden = overridden
                            )
                        )
                    }
            }
            .switchIfEmpty(Mono.defer {
                insertClassicCard(clientCardId, uidBytes, vcm1, primaryRole, entityType, now)
                    .map { vcm1Resp ->
                        CardActivateResponse(
                            cardId = clientCardId,
                            cardRole = primaryRole,
                            registeredAt = now,
                            vcm1 = ClassicCardActivateResponse(
                                cardId = clientCardId,
                                cardIdOverridden = false,
                                bitmask = bitmask,
                                entityType = entityType,
                                entityId = vcm1.entityId,
                                registeredAt = now
                            )
                        )
                    }
            })
            // Единая концепция ролей: карта — лишь носитель части ролей. Роли с активированной
            // карты дописываются в профиль пользователя (ASOP_USER_ROLES), чтобы «Роли
            // пользователей» и прочие списки показывали их без дублирования источников.
            .delayUntil { syncUserRolesFromCard(vcm1.entityId, bitmask) }
    }

    /**
     * Роли карты → user_roles (аддитивно). Добавляет отсутствующие активные связки
     * user↔role; ON CONFLICT оживляет soft-deleted (PK user_id+role_id составной —
     * повторная активация не падает). Роли НЕ снимаются: у человека может быть
     * несколько ролей из разных источников (web-admin, другие карты). PASSENGER_ANONYMOUS
     * не синкается (entityType=none, без userId).
     */
    private fun syncUserRolesFromCard(userId: UUID?, bitmask: Int): Mono<Void> {
        if (userId == null) return Mono.empty()
        val roles = allRolesForBitmask(bitmask).filter { it != "PASSENGER_ANONYMOUS" }
        if (roles.isEmpty()) return Mono.empty()
        return reactor.core.publisher.Flux.fromIterable(roles)
            .concatMap { roleName ->
                databaseClient.sql(
                    """
                    INSERT INTO ASOP_USER_ROLES (user_id, role_id)
                    SELECT :userId, r.role_id FROM ASOP_ROLES r
                    WHERE r.role_name = :roleName
                    ON CONFLICT (user_id, role_id) DO UPDATE SET deleted_at = NULL
                    """.trimIndent()
                )
                    .bind("userId", userId)
                    .bind("roleName", roleName)
                    .fetch().rowsUpdated()
            }
            .then()
    }

    /**
     * Промпт 009: маппинг роли → entityType. После clean-break все роли кроме
     * `PASSENGER_ANONYMOUS` ссылаются на userId как entity (carrier/region/etc.
     * выводятся через JOIN на ASOP_USER_* по этому userId, а не с карты).
     */
    private fun primaryEntityType(role: String): String = when (role) {
        "PASSENGER_ANONYMOUS" -> "none"
        else -> "userId"
    }

    private fun updateClassicCard(
        existing: CardMifareEntity,
        vcm1: CardActivateRequestClassic,
        primaryRole: String,
        entityType: String,
        now: Instant
    ): Mono<ClassicCardActivateResponse> {
        val entityId = vcm1.entityId
        val identityJsonObject = buildVcm1Json(vcm1.bitmask, entityType, entityId, now)
        val identityJsonString = objectMapper.writeValueAsString(identityJsonObject)
        return transactionalOperator.transactional(
            updateClassicMifare(
                cardId = existing.cardId,
                cardRole = primaryRole,
                identityJson = identityJsonString,
                now = now
            )
                .then(updateCardEntityForVcm1(existing.cardId, primaryRole, entityType, entityId, now))
        ).map {
            ClassicCardActivateResponse(
                cardId = existing.cardId,
                cardIdOverridden = false,
                bitmask = vcm1.bitmask,
                entityType = entityType,
                entityId = entityId,
                registeredAt = now
            )
        }
    }

    /**
     * Update path для VCM1 re-flash: тот же root cause — memory_map JSONB ↔ Spring R2DBC String
     * mismatch. Не пишем memory_map вообще (NULL остаётся NULL), остальные поля — явные casts.
     */
    private fun updateClassicMifare(
        cardId: UUID,
        cardRole: String,
        identityJson: String,
        now: Instant
    ): Mono<Long> {
        val sql = "UPDATE ASOP_CARD_MIFARES SET " +
            "CARD_ROLE = :cardRole, " +
            "CARD_TECH = 'CLASSIC', " +
            "IDENTITY_JSON = :identityJson, " +
            "IDENTITY_SIGNATURE = NULL, " +
            "UPDATED_AT = :now " +
            "WHERE CARD_ID = :cardId"
        return databaseClient.sql(sql)
            .bind("cardRole", cardRole)
            .bind("identityJson", identityJson)
            .bind("now", now)
            .bind("cardId", cardId)
            .fetch()
            .rowsUpdated()
    }

    private fun insertClassicCard(
        cardId: UUID,
        uidBytes: ByteArray,
        vcm1: CardActivateRequestClassic,
        primaryRole: String,
        entityType: String,
        now: Instant
    ): Mono<ClassicCardActivateResponse> {
        val entityId = vcm1.entityId
        val card = buildClassicCard(cardId, primaryRole, entityType, entityId, now)
        val identityJsonObject = buildVcm1Json(vcm1.bitmask, entityType, entityId, now)
        val identityJsonString = objectMapper.writeValueAsString(identityJsonObject)
        return transactionalOperator.transactional(
            template.insert(card).then(insertClassicMifare(cardId, uidBytes, primaryRole, identityJsonString, now))
        ).map {
            ClassicCardActivateResponse(
                cardId = cardId,
                cardIdOverridden = false,
                bitmask = vcm1.bitmask,
                entityType = entityType,
                entityId = entityId,
                registeredAt = now
            )
        }
    }

    /**
     * Raw SQL insert (вместо `template.insert(mifare)`) чтобы избежать ошибки
     * "column memory_map is of type jsonb but expression is of type character varying":
     * Spring Data R2DBC + Kotlin String? для JSONB-колонки биндит параметр как VARCHAR,
     * а не как JSONB. Явный `::jsonb` CAST решает проблему — memory_map здесь = NULL всегда.
     */
    private fun insertClassicMifare(
        cardId: UUID,
        uidBytes: ByteArray,
        primaryRole: String,
        identityJson: String,
        now: Instant
    ): Mono<Long> {
        val sql = "INSERT INTO ASOP_CARD_MIFARES " +
            "(CARD_ID, UID, ATQA, SAK, PROTOCOL_VERSION, MEMORY_MAP, CARD_ROLE, CARD_TECH, " +
            " IDENTITY_JSON, IDENTITY_SIGNATURE, CERTIFICATE_SERIAL, PUBLIC_KEY_HASH, KEY_VERSION, " +
            " VALID_FROM, VALID_UNTIL, REVOKED_AT, REVOCATION_REASON, LAST_AUTH_AT, LAST_AUTH_TERMINAL, " +
            " CREATED_AT, UPDATED_AT, DELETED_AT, VERSION) " +
            "VALUES " +
            "(:cardId, :uid, NULL, NULL, NULL, " +
            " CAST(:memoryMap AS JSONB), " +
            " :cardRole, 'CLASSIC', " +
            " :identityJson, NULL, NULL, NULL, 1, " +
            " NULL, NULL, NULL, NULL, NULL, NULL, " +
            " :now, :now, NULL, NULL)"
        return databaseClient.sql(sql)
            .bind("cardId", cardId)
            .bind("uid", uidBytes)
            .bindNull("memoryMap", String::class.java)
            .bind("cardRole", primaryRole)
            .bind("identityJson", identityJson)
            .bind("now", now)
            .fetch()
            .rowsUpdated()
    }

    private fun updateCardEntityForVcm1(
        cardId: UUID,
        primaryRole: String,
        entityType: String,
        entityId: UUID?,
        now: Instant
    ): Mono<Int> {
        // Промпт 009 (clean-break): все *_ID колонки кроме USER_ID = NULL.
        // Routing по ASOP_USER_REGIONS / ASOP_USER_CARRIERS / ASOP_USER_ORGANIZERS / etc.
        // выполняется через JOIN на USER_ID (см. AC5, AC7).
        // Колонки оставлены в схеме для backward-compat с DESFire (legacy) картами.
        val userId: UUID? = if (entityType == "userid") entityId else null
        val sql = "UPDATE ASOP_CARDS SET " +
            "USER_ID = :userId, " +
            "REGION_ID = NULL, " +
            "ORGANIZER_ID = NULL, " +
            "CARRIER_ID = NULL, " +
            "CARDS_DISTRIBUTOR_ID = NULL, " +
            "AUDIT_SERVICE_ID = NULL, " +
            "UPDATED_AT = :updatedAt " +
            "WHERE CARD_ID = :cardId"
        // Промпт 009 fix: bind() возвращает новый immutable spec — он ДОЛЖЕН
        // идти через цепочку, иначе binding теряется. Иначе получаем 500:
        // "No parameter specified for [userId] in query".
        val specWithUser = if (userId != null) databaseClient.sql(sql).bind("userId", userId)
            else databaseClient.sql(sql).bindNull("userId", UUID::class.java)
        return specWithUser
            .bind("updatedAt", now)
            .bind("cardId", cardId)
            .fetch().rowsUpdated()
            .map { it.toInt() }
    }

    private fun buildClassicCard(
        cardId: UUID,
        primaryRole: String,
        entityType: String,
        entityId: UUID?,
        now: Instant
    ): CardEntity {
        // Промпт 009: только USER_ID = entityId; остальные *_ID = NULL.
        // Для VCM1 (post-clean-break) entityType всегда "userId" или "none".
        return CardEntity(
            cardId = cardId,
            cardTypeId = MIFARE_DESFIRE_TYPE,
            userId = if (entityType == "userid") entityId else null,
            regionId = null,
            organizerId = null,
            carrierId = null,
            cardsDistributorId = null,
            auditServiceId = null,
            registeredAt = now,
            registeredByUserId = null,
            createdAt = now,
            updatedAt = now
        )
    }

    /**
     * Промпт 009 defense-in-depth: проверить, что userId существует в ASOP_USERS.
     * PROTECT от записи orphan userId в БД (который рушит JOIN ASOP_USERS_*).
     */
    /**
     * Строит VCM1 IDENTITY_JSON-объект для хранения в БД.
     */
    private fun buildVcm1Json(bitmask: Int, entityType: String, entityId: UUID?, now: Instant): JsonNode {
        val obj = objectMapper.createObjectNode()
        obj.put("format", "VCM1")
        obj.put("formatVersion", 1)
        obj.put("bitmask", bitmask)
        if (entityType != "none") {
            val entity = objectMapper.createObjectNode()
            entity.put("type", entityType)
            entity.put("id", entityId.toString())
            obj.set<JsonNode>("entity", entity)
        }
        obj.put("activatedAt", now.toString())
        return obj
    }

    private fun authorizeClassic(request: CardActivateRequest, primaryRole: String) {
        if (primaryRole == "SUPER_ADMIN") {
            if (!request.authorizedByRoot) {
                throw ResponseStatusException(HttpStatus.FORBIDDEN,
                    "SUPER_ADMIN card requires authorizedByRoot=true")
            }
            return
        }
        if (request.authorizedByRoot) return
        val allowed = AUTHORIZATION_MATRIX[primaryRole] ?: emptySet()
        if (request.operatorRoles.none { it in allowed }) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN,
                "No authorizing card role for $primaryRole")
        }
    }

    /** Lowest ordinal set-bit → AsopCardType role name. */
    private fun highestBitRole(bitmask: Int): String? {
        for (i in 0..13) {
            if (((bitmask shr i) and 1) == 1) {
                return ROLE_BY_ORDINAL.getOrNull(i)
            }
        }
        return null
    }

    /**
     * Промпт 008: список всех ролей, чьи биты установлены в bitmask.
     * Используется в multi-role restriction (промпт 009): если >1 роли и их
     * primaryEntityType различается — отклонить активацию.
     */
    private fun allRolesForBitmask(bitmask: Int): List<String> {
        val out = mutableListOf<String>()
        for (i in 0..13) {
            if (((bitmask shr i) and 1) == 1) {
                ROLE_BY_ORDINAL.getOrNull(i)?.let { out += it }
            }
        }
        return out
    }

    /** Map role name → entity field name (legacy — оставлено для DESFire-flow). */
    private fun entityFieldForRole(role: String): String? = when (role) {
        "SUPER_ADMIN", "PASSENGER" -> "userId"
        "REGION_ADMIN" -> "regionId"
        "ORGANIZER_ADMIN" -> "organizerId"
        "CARRIER_ADMIN", "CARRIER_DISPATCHER", "DRIVER" -> "carrierId"
        "DISTRIBUTOR_ADMIN", "DISTRIBUTOR_DISPATCHER" -> "cardsDistributorId"
        "KRS_ADMIN", "KRS_DISPATCHER", "KRS_FOREMAN", "KRS_CONTROLLER" -> "auditServiceId"
        "PASSENGER_ANONYMOUS" -> "none"
        else -> null
    }

    /** Legacy DESFire-flow с RSA-PSS signature verification. */
    private fun activateWithSignature(request: CardActivateRequest): Mono<CardActivateResponse> {
        val identityJson = request.identityJson
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "identityJson required for DESFire flow")
        val signature = request.identitySignature
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "identitySignature required for DESFire flow")
        return Mono.fromCallable {
            val identity = objectMapper.readTree(identityJson)
            authorize(request, identity)
            identity
        }.flatMap { identity ->
            pubKey().map { pk ->
                if (!verifySignature(pk, identityJson, signature)) {
                    throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid cardIdentity signature")
                }
                identity
            }
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

    private fun pubKey(): Mono<String> {
        cachedPublicKeyBase64?.let { return Mono.just(it) }
        return cryptoWebClient.get()
            .uri("https://crypto-service:8081/api/v1/keys/public")
            .retrieve()
            .bodyToMono(JsonNode::class.java)
            .map { node ->
                val key = node.path("publicKeyBase64").asText()
                if (key.isBlank()) {
                    throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Crypto service unavailable")
                }
                cachedPublicKeyBase64 = key
                key
            }
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
                            // Same UID, different cardId — update the existing record (idempotent)
                            updateExisting(existing, request, identity, role, now)
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
            cardTech = resolveCardTechnology(request.technology),
            identityJson = request.identityJson,
            identitySignature = request.identitySignature,
            keyVersion = 1,
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
            cardTech = resolveCardTechnology(request.technology),
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

        /**
         * Ordinal → AsopCardType.role string. Должно совпадать с frontend/Android AsopCardType.kt
         * (card_type ordering). Используется в VCM1-flow для восстановления primary role
         * из bitmask encoded в карте (промпт 008).
         */
        private val ROLE_BY_ORDINAL = listOf(
            "SUPER_ADMIN",          // bit 0
            "PASSENGER",            // bit 1
            "REGION_ADMIN",         // bit 2
            "ORGANIZER_ADMIN",      // bit 3
            "CARRIER_ADMIN",        // bit 4
            "DISTRIBUTOR_ADMIN",    // bit 5
            "KRS_ADMIN",            // bit 6
            "CARRIER_DISPATCHER",   // bit 7
            "DISTRIBUTOR_DISPATCHER", // bit 8
            "KRS_DISPATCHER",       // bit 9
            "DRIVER",               // bit 10
            "KRS_FOREMAN",          // bit 11
            "KRS_CONTROLLER",        // bit 12
            "PASSENGER_ANONYMOUS"   // bit 13
        )

        /** Маппинг строки → CARD_TECH (DB CHECK). DEFAULT — DESFIRE. */
        private fun resolveCardTechnology(tech: String?): String =
            if (tech.equals("CLASSIC", ignoreCase = true)) "CLASSIC" else "DESFIRE"

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
package ru.asop.card.controller

import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.data.relational.core.query.Criteria
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import ru.asop.api.card.controller.CardApi
import ru.asop.api.card.dto.request.CardActivateRequest
import ru.asop.api.card.dto.request.CardRegisterRequest
import ru.asop.api.card.dto.request.CardBlockRequest
import ru.asop.api.card.dto.response.CardActivateResponse
import ru.asop.api.card.dto.response.CardResponse
import ru.asop.card.config.DeltaSupport
import ru.asop.card.model.CardEntity
import ru.asop.card.model.CardMifareEntity
import ru.asop.card.repository.CardMifareRepository
import ru.asop.card.service.CardActivationService
import ru.asop.card.service.CardService
import java.security.Principal
import java.util.UUID

@RestController
class CardController(
    private val cardService: CardService,
    private val cardActivationService: CardActivationService,
    private val template: R2dbcEntityTemplate,
    private val cardMifareRepository: CardMifareRepository
) : CardApi {

    override fun registerCard(
        request: CardRegisterRequest,
        principal: Mono<Principal>
    ): Mono<ResponseEntity<CardResponse>> {
        return cardService.register(request)
            .map { ResponseEntity.ok(it) }
    }

    override fun activateCard(
        request: CardActivateRequest,
        principal: Mono<Principal>
    ): Mono<ResponseEntity<CardActivateResponse>> {
        return cardActivationService.activate(request)
            .map { ResponseEntity.ok(it) }
    }

    override fun activateCardVcm1(
        request: CardActivateRequest,
        principal: Mono<Principal>
    ): Mono<ResponseEntity<CardActivateResponse>> {
        // VCM1-flow использует тот же CardActivateRequest DTO с заполненным vcm1-полем.
        // CardActivationService.activate() проверяет request.vcm1 != null и переключается на VCM1.
        // Здесь — alias для отдельного URL (clearer для API consumers).
        return cardActivationService.activate(request)
            .map { ResponseEntity.ok(it) }
    }

    override fun blockCard(
        id: UUID,
        request: CardBlockRequest,
        principal: Mono<Principal>
    ): Mono<ResponseEntity<CardResponse>> {
        return Mono.empty()
    }

    override fun getCard(id: UUID): Mono<ResponseEntity<CardResponse>> {
        return cardService.getById(id)
            .map { ResponseEntity.ok(it) }
    }

    @GetMapping("/by-uid/{uid}")
    fun getCardByUid(@PathVariable uid: String): Mono<ResponseEntity<CardByUidResponse>> {
        val uidBytes = try {
            val hex = uid.filter { it !in setOf(' ', '-') }
            if (hex.length % 2 != 0) return Mono.just(ResponseEntity.badRequest().build())
            hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        } catch (e: Exception) {
            return Mono.just(ResponseEntity.badRequest().build())
        }
        return cardMifareRepository.findByUid(uidBytes)
            .map { mifare -> ResponseEntity.ok(CardByUidResponse(mifare.cardId.toString(), uid)) }
            .switchIfEmpty(Mono.just(ResponseEntity.notFound().build()))
    }

    @GetMapping("/delta")
    fun listDelta(
        @RequestParam(required = false) versionSince: Long?,
        @RequestParam(required = false) includeDeleted: Boolean,
        @RequestParam(required = false) userIdsIn: String?,
        @RequestParam(required = false, defaultValue = "10000") limit: Int
    ): Flux<CardEntity> {
        var extra: Criteria? = null
        if (!userIdsIn.isNullOrBlank()) {
            val ids = userIdsIn.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { UUID.fromString(it) }
            if (ids.isNotEmpty()) extra = Criteria.where("user_id").`in`(ids)
        }
        return template.select(CardEntity::class.java)
            .matching(DeltaSupport.query(versionSince, includeDeleted, limit, extra))
            .all()
    }
}

data class CardByUidResponse(
    val cardId: String,
    val uid: String
)

package ru.asop.card.controller

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux
import ru.asop.card.model.CardBankEntity
import ru.asop.card.repository.CardBankRepository

@RestController
class CardBankController(
    private val repository: CardBankRepository
) {

    @GetMapping("/api/v1/card-banks/delta")
    fun listDelta(
        @RequestParam(required = false) userIdsIn: String?,
        @RequestParam(required = false) versionSince: Long?,
        @RequestParam(required = false, defaultValue = "false") includeDeleted: Boolean,
        @RequestParam(required = false, defaultValue = "10000") limit: Int
    ): Flux<CardBankEntity> = repository.findDelta(userIdsIn, versionSince, includeDeleted, limit)
}

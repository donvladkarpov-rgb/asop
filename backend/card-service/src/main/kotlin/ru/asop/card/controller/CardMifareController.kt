package ru.asop.card.controller

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux
import ru.asop.card.model.CardMifareEntity
import ru.asop.card.repository.CardMifareRepository

@RestController
class CardMifareController(
    private val repository: CardMifareRepository
) {

    @GetMapping("/api/v1/card-mifares/delta")
    fun listDelta(
        @RequestParam(required = false) userIdsIn: String?,
        @RequestParam(required = false) versionSince: Long?,
        @RequestParam(required = false, defaultValue = "false") includeDeleted: Boolean,
        @RequestParam(required = false, defaultValue = "10000") limit: Int
    ): Flux<CardMifareEntity> = repository.findDelta(userIdsIn, versionSince, includeDeleted, limit)
}

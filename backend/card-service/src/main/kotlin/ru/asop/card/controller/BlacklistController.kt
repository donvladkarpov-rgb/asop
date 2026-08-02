package ru.asop.card.controller

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux
import ru.asop.card.model.BlacklistEntity
import ru.asop.card.repository.BlacklistRepository

@RestController
class BlacklistController(
    private val repository: BlacklistRepository
) {

    @GetMapping("/api/v1/blacklists/delta")
    fun listDelta(
        @RequestParam(required = false) userIdsIn: String?,
        @RequestParam(required = false) versionSince: Long?,
        @RequestParam(required = false, defaultValue = "false") includeDeleted: Boolean,
        @RequestParam(required = false, defaultValue = "10000") limit: Int
    ): Flux<BlacklistEntity> = repository.findDelta(userIdsIn, versionSince, includeDeleted, limit)
}

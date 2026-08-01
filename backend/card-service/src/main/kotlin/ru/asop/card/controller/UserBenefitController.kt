package ru.asop.card.controller

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux
import ru.asop.card.model.UserBenefitEntity
import ru.asop.card.repository.UserBenefitRepository
import java.time.Instant

@RestController
class UserBenefitController(
    private val repository: UserBenefitRepository
) {

    @GetMapping("/api/v1/user-benefits/delta")
    fun listDelta(
        @RequestParam(required = false) userIdsIn: String?,
        @RequestParam(required = false) updatedAtSince: Instant?,
        @RequestParam(required = false, defaultValue = "false") includeDeleted: Boolean,
        @RequestParam(required = false, defaultValue = "10000") limit: Int
    ): Flux<UserBenefitEntity> = repository.findDelta(userIdsIn, updatedAtSince, includeDeleted, limit)
}

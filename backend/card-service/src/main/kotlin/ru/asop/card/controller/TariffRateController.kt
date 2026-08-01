package ru.asop.card.controller

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux
import ru.asop.card.model.TariffRateEntity
import ru.asop.card.repository.TariffRateRepository
import java.time.Instant

@RestController
class TariffRateController(
    private val repository: TariffRateRepository
) {

    @GetMapping("/api/v1/tariff-rates/delta")
    fun listDelta(
        @RequestParam(required = false) userIdsIn: String?,
        @RequestParam(required = false) updatedAtSince: Instant?,
        @RequestParam(required = false, defaultValue = "false") includeDeleted: Boolean,
        @RequestParam(required = false, defaultValue = "10000") limit: Int
    ): Flux<TariffRateEntity> = repository.findDelta(updatedAtSince, includeDeleted, limit)
}

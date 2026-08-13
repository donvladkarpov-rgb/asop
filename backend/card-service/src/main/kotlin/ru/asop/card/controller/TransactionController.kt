package ru.asop.card.controller

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux
import ru.asop.card.repository.TransactionRepository
import java.util.UUID

@RestController
@RequestMapping("/api/v1/transactions")
class TransactionController(
    private val transactionRepository: TransactionRepository
) {
    @GetMapping
    fun listBySession(@RequestParam("sessionId") sessionId: UUID): Flux<TransactionWithCard> =
        transactionRepository.findBySessionIdWithCard(sessionId)
}

package ru.asop.card.controller

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import ru.asop.card.dto.BlacklistBlockRequest
import ru.asop.card.model.BlacklistEntity
import ru.asop.card.repository.BlacklistRepository
import ru.asop.card.service.BlacklistService
import java.util.UUID

@RestController
class BlacklistController(
    private val repository: BlacklistRepository,
    private val service: BlacklistService
) {

    @GetMapping("/api/v1/blacklists")
    fun list(
        @RequestParam(required = false) blockType: String?,
        @RequestParam(required = false, defaultValue = "false") includeDeleted: Boolean,
        @RequestParam(required = false, defaultValue = "1000") limit: Int
    ): Flux<BlacklistEntity> = service.list(blockType, includeDeleted, limit)

    @PostMapping("/api/v1/blacklists")
    fun block(@RequestBody request: BlacklistBlockRequest): Mono<ResponseEntity<BlacklistEntity>> =
        service.block(request).map { ResponseEntity.status(HttpStatus.CREATED).body(it) }

    @DeleteMapping("/api/v1/blacklists/{cardId}")
    fun unblock(@PathVariable cardId: UUID): Mono<ResponseEntity<Void>> =
        service.unblock(cardId).map { ResponseEntity.noContent().build<Void>() }

    @GetMapping("/api/v1/blacklists/delta")
    fun listDelta(
        @RequestParam(required = false) userIdsIn: String?,
        @RequestParam(required = false) versionSince: Long?,
        @RequestParam(required = false, defaultValue = "false") includeDeleted: Boolean,
        @RequestParam(required = false, defaultValue = "10000") limit: Int
    ): Flux<BlacklistEntity> = repository.findDelta(userIdsIn, versionSince, includeDeleted, limit)
}
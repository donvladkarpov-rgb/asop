package ru.asop.api.reference.controller

import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import reactor.core.publisher.Mono
import ru.asop.api.reference.dto.request.CardTypeCreateRequest
import ru.asop.api.reference.dto.request.CardTypeUpdateRequest
import ru.asop.api.reference.dto.response.CardTypeResponse
import java.util.UUID

@RequestMapping("/api/v1/card-types")
interface CardTypeApi {

    @GetMapping
    fun listCardTypes(): Mono<ResponseEntity<List<CardTypeResponse>>>

    @GetMapping("/{id}")
    fun getCardType(@PathVariable id: UUID): Mono<ResponseEntity<CardTypeResponse>>

    @PostMapping
    fun createCardType(@Valid @RequestBody request: CardTypeCreateRequest): Mono<ResponseEntity<CardTypeResponse>>

    @PutMapping("/{id}")
    fun updateCardType(@PathVariable id: UUID, @Valid @RequestBody request: CardTypeUpdateRequest): Mono<ResponseEntity<CardTypeResponse>>

    @DeleteMapping("/{id}")
    fun deleteCardType(@PathVariable id: UUID): Mono<ResponseEntity<Void>>
}

package ru.asop.api.reference.controller

import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import reactor.core.publisher.Mono
import ru.asop.api.reference.dto.request.EventTypeCreateRequest
import ru.asop.api.reference.dto.response.EventTypeResponse

@RequestMapping("/api/v1/event-types")
interface EventTypeApi {

    @GetMapping
    fun listEventTypes(): Mono<ResponseEntity<List<EventTypeResponse>>>

    @GetMapping("/{id}")
    fun getEventType(@PathVariable id: String): Mono<ResponseEntity<EventTypeResponse>>

    @PostMapping
    fun createEventType(@Valid @RequestBody request: EventTypeCreateRequest): Mono<ResponseEntity<EventTypeResponse>>
}

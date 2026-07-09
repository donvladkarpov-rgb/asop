package ru.asop.gateway.controller

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono
import ru.asop.gateway.model.EventState
import ru.asop.gateway.service.EventService
import java.util.UUID

@RestController
class EventController(
    private val eventService: EventService
) {

    @GetMapping("/api/v1/events/{eventId}")
    fun getEventStatus(
        @PathVariable eventId: UUID
    ): Mono<ResponseEntity<Any>> {
        val status = eventService.getStatus(eventId)

        if (status.isEmpty) {
            return Mono.just(ResponseEntity.notFound().build())
        }

        val s = status.get()

        return when (s.state) {
            EventState.PENDING -> Mono.just(
                ResponseEntity.status(HttpStatus.ACCEPTED).body(s)
            )
            EventState.COMPLETED -> Mono.just(
                ResponseEntity.ok(s)
            )
            EventState.FAILED -> Mono.just(
                ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(s)
            )
        }
    }
}

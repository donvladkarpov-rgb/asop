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
import ru.asop.api.reference.dto.request.SessionTypeCreateRequest
import ru.asop.api.reference.dto.request.SessionTypeUpdateRequest
import ru.asop.api.reference.dto.response.SessionTypeResponse
import java.util.UUID

@RequestMapping("/api/v1/session-types")
interface SessionTypeApi {

    @GetMapping
    fun listSessionTypes(): Mono<ResponseEntity<List<SessionTypeResponse>>>

    @GetMapping("/{id}")
    fun getSessionType(@PathVariable id: UUID): Mono<ResponseEntity<SessionTypeResponse>>

    @PostMapping
    fun createSessionType(@Valid @RequestBody request: SessionTypeCreateRequest): Mono<ResponseEntity<SessionTypeResponse>>

    @PutMapping("/{id}")
    fun updateSessionType(@PathVariable id: UUID, @Valid @RequestBody request: SessionTypeUpdateRequest): Mono<ResponseEntity<SessionTypeResponse>>

    @DeleteMapping("/{id}")
    fun deleteSessionType(@PathVariable id: UUID): Mono<ResponseEntity<Void>>
}

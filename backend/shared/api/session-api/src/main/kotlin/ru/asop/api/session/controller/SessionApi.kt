package ru.asop.api.session.controller

import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import ru.asop.api.session.dto.request.SessionOpenRequest
import ru.asop.api.session.dto.request.SessionCloseRequest
import ru.asop.api.session.dto.response.SessionResponse
import java.security.Principal
import java.util.UUID

@RequestMapping("/api/v1/sessions")
interface SessionApi {

    @PostMapping("/open")
    fun openSession(
        @Valid @RequestBody request: SessionOpenRequest,
        principal: Mono<Principal>
    ): Mono<ResponseEntity<SessionResponse>>

    @PutMapping("/{id}/close")
    fun closeSession(
        @PathVariable id: UUID,
        @Valid @RequestBody request: SessionCloseRequest,
        principal: Mono<Principal>
    ): Mono<ResponseEntity<SessionResponse>>

    @GetMapping("/{id}")
    fun getSession(
        @PathVariable id: UUID
    ): Mono<ResponseEntity<SessionResponse>>

    // Промпт 011: список смен для web-admin (страница «Смены»).
    @GetMapping
    fun listSessions(
        @RequestParam(required = false) terminalId: UUID?
    ): Flux<SessionResponse>
}

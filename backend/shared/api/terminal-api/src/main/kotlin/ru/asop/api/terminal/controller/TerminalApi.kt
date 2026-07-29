package ru.asop.api.terminal.controller

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
import ru.asop.api.terminal.dto.request.TerminalCarrierAssignRequest
import ru.asop.api.terminal.dto.request.TerminalRegisterRequest
import ru.asop.api.terminal.dto.request.TerminalStatusChangeRequest
import ru.asop.api.terminal.dto.response.TerminalRegisterResponse
import ru.asop.api.terminal.dto.response.TerminalResponse
import java.security.Principal
import java.util.UUID

@RequestMapping("/api/v1/terminals")
interface TerminalApi {

    @GetMapping
    fun listTerminals(
        @RequestParam(required = false) carrierId: UUID?,
        @RequestParam(required = false) regionId: UUID?
    ): Flux<TerminalResponse>

    @PostMapping("/register")
    fun registerTerminal(
        @Valid @RequestBody request: TerminalRegisterRequest,
        principal: Mono<Principal>
    ): Mono<ResponseEntity<TerminalRegisterResponse>>

    @GetMapping("/{id}")
    fun getTerminal(
        @PathVariable id: UUID
    ): Mono<ResponseEntity<TerminalResponse>>

    @PutMapping("/{id}/status")
    fun changeTerminalStatus(
        @PathVariable id: UUID,
        @Valid @RequestBody request: TerminalStatusChangeRequest,
        principal: Mono<Principal>
    ): Mono<ResponseEntity<TerminalResponse>>

    @PutMapping("/{id}/carrier")
    fun assignCarrier(
        @PathVariable id: UUID,
        @Valid @RequestBody request: TerminalCarrierAssignRequest,
        principal: Mono<Principal>
    ): Mono<ResponseEntity<TerminalResponse>>
}

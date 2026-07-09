package ru.asop.terminal.controller

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono
import ru.asop.api.terminal.controller.TerminalApi
import ru.asop.api.terminal.dto.request.TerminalRegisterRequest
import ru.asop.api.terminal.dto.request.TerminalStatusChangeRequest
import ru.asop.api.terminal.dto.response.TerminalResponse
import ru.asop.terminal.service.TerminalService
import java.security.Principal
import java.util.UUID

@RestController
class TerminalController(
    private val terminalService: TerminalService
) : TerminalApi {

    override fun registerTerminal(
        request: TerminalRegisterRequest,
        principal: Mono<Principal>
    ): Mono<ResponseEntity<TerminalResponse>> {
        return terminalService.register(request)
            .map { ResponseEntity.ok(it) }
    }

    override fun getTerminal(id: UUID): Mono<ResponseEntity<TerminalResponse>> {
        return terminalService.getById(id)
            .map { ResponseEntity.ok(it) }
    }

    override fun changeTerminalStatus(
        id: UUID,
        request: TerminalStatusChangeRequest,
        principal: Mono<Principal>
    ): Mono<ResponseEntity<TerminalResponse>> {
        return terminalService.changeStatus(id, request)
            .map { ResponseEntity.ok(it) }
    }
}

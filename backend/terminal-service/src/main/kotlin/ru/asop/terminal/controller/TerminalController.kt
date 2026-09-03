package ru.asop.terminal.controller

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import ru.asop.api.terminal.controller.TerminalApi
import ru.asop.api.terminal.dto.request.TerminalCarrierAssignRequest
import ru.asop.api.terminal.dto.request.TerminalRegisterRequest
import ru.asop.api.terminal.dto.request.TerminalStatusChangeRequest
import ru.asop.api.terminal.dto.response.TerminalRegisterResponse
import ru.asop.api.terminal.dto.response.TerminalResponse
import ru.asop.terminal.service.TerminalService
import ru.asop.terminal.service.WatermarkQueryService
import java.security.Principal
import java.util.UUID

@RestController
class TerminalController(
    private val terminalService: TerminalService,
    private val watermarkQueryService: WatermarkQueryService
) : TerminalApi {

    override fun listTerminals(carrierId: UUID?, regionId: UUID?): Flux<TerminalResponse> =
        terminalService.list(carrierId, regionId)

    override fun registerTerminal(
        request: TerminalRegisterRequest,
        principal: Mono<Principal>
    ): Mono<ResponseEntity<TerminalRegisterResponse>> {
        return terminalService.register(request)
            .map { ResponseEntity.ok(it) }
    }

    override fun getTerminal(id: UUID): Mono<ResponseEntity<TerminalResponse>> {
        return terminalService.getById(id)
            .map { ResponseEntity.ok(it) }
            .defaultIfEmpty(ResponseEntity.notFound().build())
    }

    override fun changeTerminalStatus(
        id: UUID,
        request: TerminalStatusChangeRequest,
        principal: Mono<Principal>
    ): Mono<ResponseEntity<TerminalResponse>> {
        return terminalService.changeStatus(id, request)
            .map { ResponseEntity.ok(it) }
    }

    override fun assignCarrier(
        id: UUID,
        request: TerminalCarrierAssignRequest,
        principal: Mono<Principal>
    ): Mono<ResponseEntity<TerminalResponse>> {
        return terminalService.assignCarrier(id, request.carrierId)
            .map { ResponseEntity.ok(it) }
            .defaultIfEmpty(ResponseEntity.notFound().build())
    }

    /**
     * Промпт 012: GET /api/v1/terminals/{id}/event-watermark — публичный для терминала
     * (mTLS-цепочка его пропускает). Возвращает last_seq + pending count.
     */
    @GetMapping("/api/v1/terminals/{id}/event-watermark")
    fun getEventWatermark(@PathVariable id: UUID): Mono<ResponseEntity<ru.asop.terminal.model.TerminalEventWatermark>> {
        return watermarkQueryService.getWatermark(id)
            .map { ResponseEntity.ok(it) }
            .defaultIfEmpty(ResponseEntity.notFound().build())
    }
}

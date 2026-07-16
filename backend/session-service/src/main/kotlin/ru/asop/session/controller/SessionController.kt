package ru.asop.session.controller

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono
import ru.asop.api.session.controller.SessionApi
import ru.asop.api.session.dto.request.SessionOpenRequest
import ru.asop.api.session.dto.request.SessionCloseRequest
import ru.asop.api.session.dto.response.SessionResponse
import ru.asop.session.service.SessionService
import java.security.Principal
import java.util.UUID

@RestController
class SessionController(
    private val sessionService: SessionService
) : SessionApi {

    override fun openSession(
        request: SessionOpenRequest,
        principal: Mono<Principal>
    ): Mono<ResponseEntity<SessionResponse>> {
        return sessionService.open(request)
            .map { ResponseEntity.ok(it) }
    }

    override fun closeSession(
        id: UUID,
        request: SessionCloseRequest,
        principal: Mono<Principal>
    ): Mono<ResponseEntity<SessionResponse>> {
        return sessionService.close(id, request)
            .map { ResponseEntity.ok(it) }
    }

    override fun getSession(id: UUID): Mono<ResponseEntity<SessionResponse>> {
        return sessionService.getById(id)
            .map { ResponseEntity.ok(it) }
    }
}

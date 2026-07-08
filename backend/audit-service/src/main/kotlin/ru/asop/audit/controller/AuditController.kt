package ru.asop.audit.controller

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono
import ru.asop.api.audit.controller.AuditApi
import ru.asop.api.audit.dto.request.AuditTaskCreateRequest
import ru.asop.api.audit.dto.response.AuditTaskResponse
import ru.asop.audit.service.AuditService
import java.security.Principal
import java.util.UUID

@RestController
class AuditController(
    private val auditService: AuditService
) : AuditApi {

    override fun createTask(
        request: AuditTaskCreateRequest,
        principal: Mono<Principal>
    ): Mono<ResponseEntity<AuditTaskResponse>> {
        return auditService.createTask(request)
            .map { ResponseEntity.ok(it) }
    }

    override fun getTask(id: UUID): Mono<ResponseEntity<AuditTaskResponse>> {
        return auditService.getTaskById(id)
            .map { ResponseEntity.ok(it) }
    }
}

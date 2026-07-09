package ru.asop.api.audit.controller

import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import reactor.core.publisher.Mono
import ru.asop.api.audit.dto.request.AuditTaskCreateRequest
import ru.asop.api.audit.dto.response.AuditTaskResponse
import java.security.Principal
import java.util.UUID

@RequestMapping("/api/v1/audit")
interface AuditApi {

    @PostMapping("/tasks")
    fun createTask(
        @Valid @RequestBody request: AuditTaskCreateRequest,
        principal: Mono<Principal>
    ): Mono<ResponseEntity<AuditTaskResponse>>

    @GetMapping("/tasks/{id}")
    fun getTask(
        @PathVariable id: UUID
    ): Mono<ResponseEntity<AuditTaskResponse>>
}

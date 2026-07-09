package ru.asop.audit.service

import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import ru.asop.api.audit.dto.request.AuditTaskCreateRequest
import ru.asop.api.audit.dto.response.AuditTaskResponse
import ru.asop.audit.model.AuditTaskEntity
import ru.asop.audit.repository.AuditTaskRepository
import ru.asop.common.util.UuidUtils
import java.time.Instant
import java.util.UUID

@Service
class AuditService(
    private val auditTaskRepository: AuditTaskRepository
) {

    fun createTask(request: AuditTaskCreateRequest): Mono<AuditTaskResponse> {
        val now = Instant.now()
        val entity = AuditTaskEntity(
            taskId = UuidUtils.newId(),
            taskNumber = request.taskNumber,
            status = "DRAFT",
            description = request.description,
            createdAt = now,
            updatedAt = now
        )
        return auditTaskRepository.save(entity).map { it.toResponse() }
    }

    fun getTaskById(id: UUID): Mono<AuditTaskResponse> {
        return auditTaskRepository.findById(id).map { it.toResponse() }
    }
}

private fun AuditTaskEntity.toResponse() = AuditTaskResponse(
    id = taskId,
    taskNumber = taskNumber,
    status = status,
    description = description,
    createdAt = createdAt,
    updatedAt = updatedAt
)

package ru.asop.audit.repository

import org.springframework.data.repository.reactive.ReactiveCrudRepository
import org.springframework.stereotype.Repository
import ru.asop.audit.model.AuditTaskEntity
import java.util.UUID

@Repository
interface AuditTaskRepository : ReactiveCrudRepository<AuditTaskEntity, UUID>

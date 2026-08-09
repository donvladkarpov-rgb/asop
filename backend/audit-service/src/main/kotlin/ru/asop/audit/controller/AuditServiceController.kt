package ru.asop.audit.controller

import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.data.relational.core.query.Criteria
import org.springframework.data.relational.core.query.Update
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import ru.asop.audit.config.DeltaSupport
import ru.asop.audit.model.AuditServiceEntity
import ru.asop.common.util.UuidUtils
import java.time.Instant
import java.util.UUID

/**
 * CRUD справочника КРС (ASOP_AUDIT_SERVICES) + дельта-синхронизация.
 */
@RestController
class AuditServiceController(
    private val template: R2dbcEntityTemplate
) {

    @GetMapping("/api/v1/audit-services")
    fun list(): Flux<AuditServiceEntity> =
        template.select(AuditServiceEntity::class.java)
            .matching(org.springframework.data.relational.core.query.Query.query(Criteria.where("deleted_at").isNull()))
            .all()

    @GetMapping("/api/v1/audit-services/{id}")
    fun getById(@PathVariable id: UUID): Mono<ResponseEntity<AuditServiceEntity>> =
        template.select(AuditServiceEntity::class.java)
            .matching(org.springframework.data.relational.core.query.Query.query(Criteria.where("audit_service_id").`is`(id)))
            .one()
            .map { ResponseEntity.ok(it) }
            .defaultIfEmpty(ResponseEntity.notFound().build())

    @PostMapping("/api/v1/audit-services")
    fun create(@RequestBody entity: AuditServiceEntity): Mono<AuditServiceEntity> {
        val now = Instant.now()
        val e = entity.copy(
            auditServiceId = if (entity.auditServiceId != null) entity.auditServiceId else UuidUtils.newId(),
            createdAt = now,
            updatedAt = now
        )
        return template.insert(e)
    }

    @PutMapping("/api/v1/audit-services/{id}")
    fun update(@PathVariable id: UUID, @RequestBody entity: AuditServiceEntity): Mono<ResponseEntity<AuditServiceEntity>> {
        return template.select(AuditServiceEntity::class.java)
            .matching(org.springframework.data.relational.core.query.Query.query(Criteria.where("audit_service_id").`is`(id)))
            .one()
            .flatMap { existing ->
                val updated = existing.copy(
                    serviceCode = entity.serviceCode,
                    serviceName = entity.serviceName,
                    issuerType = entity.issuerType,
                    organizerId = entity.organizerId,
                    carrierId = entity.carrierId,
                    isActive = entity.isActive,
                    updatedAt = Instant.now()
                )
                template.update(updated).map { ResponseEntity.ok(it) }
            }
            .defaultIfEmpty(ResponseEntity.notFound().build())
    }

    @DeleteMapping("/api/v1/audit-services/{id}")
    fun delete(@PathVariable id: UUID): Mono<ResponseEntity<Void>> =
        template.delete(AuditServiceEntity::class.java)
            .matching(org.springframework.data.relational.core.query.Query.query(Criteria.where("audit_service_id").`is`(id)))
            .all()
            .thenReturn(ResponseEntity.noContent().build<Void>())

    @GetMapping("/api/v1/audit-services/delta")
    fun listDelta(
        @RequestParam(required = false) versionSince: Long?,
        @RequestParam(required = false, defaultValue = "false") includeDeleted: Boolean,
        @RequestParam(required = false, defaultValue = "10000") limit: Int
    ): Flux<AuditServiceEntity> {
        return template.select(AuditServiceEntity::class.java)
            .matching(DeltaSupport.query(versionSince, includeDeleted, limit))
            .all()
    }
}
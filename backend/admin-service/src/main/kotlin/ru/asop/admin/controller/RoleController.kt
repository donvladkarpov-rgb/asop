package ru.asop.admin.controller

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import reactor.core.publisher.Mono
import ru.asop.admin.model.RoleEntity
import ru.asop.admin.repository.RoleRepository
import ru.asop.api.reference.controller.RoleApi
import ru.asop.api.reference.dto.request.RoleCreateRequest
import ru.asop.api.reference.dto.request.RoleUpdateRequest
import ru.asop.api.reference.dto.response.RoleResponse
import ru.asop.common.util.UuidUtils
import java.util.UUID

@RestController
class RoleController(
    private val repository: RoleRepository,
    private val template: R2dbcEntityTemplate
) : RoleApi {

    override fun listRoles(): Mono<ResponseEntity<List<RoleResponse>>> {
        return repository.findAll()
            .map { it.toResponse() }
            .collectList()
            .map { ResponseEntity.ok(it) }
    }

    override fun getRole(id: UUID): Mono<ResponseEntity<RoleResponse>> {
        return repository.findById(id)
            .map { ResponseEntity.ok(it.toResponse()) }
            .defaultIfEmpty(ResponseEntity.notFound().build())
    }

    override fun createRole(request: RoleCreateRequest): Mono<ResponseEntity<RoleResponse>> {
        val entity = RoleEntity(
            roleId = UuidUtils.newId(),
            roleName = request.roleName
        )
        return template.insert(entity)
            .map { ResponseEntity.status(HttpStatus.CREATED).body(it.toResponse()) }
    }

    override fun updateRole(id: UUID, request: RoleUpdateRequest): Mono<ResponseEntity<RoleResponse>> {
        return repository.findById(id)
            .flatMap { existing ->
                val updated = existing.copy(
                    roleName = request.roleName ?: existing.roleName
                )
                repository.save(updated).map { ResponseEntity.ok(it.toResponse()) }
            }
            .defaultIfEmpty(ResponseEntity.notFound().build())
    }

    override fun deleteRole(id: UUID): Mono<ResponseEntity<Void>> {
        return repository.deleteById(id).thenReturn(ResponseEntity.noContent().build())
    }

    private fun RoleEntity.toResponse() = RoleResponse(
        id = roleId,
        roleName = roleName
    )
}

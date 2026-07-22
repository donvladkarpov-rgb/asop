package ru.asop.user.controller

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import ru.asop.api.user.controller.UserRoleApi
import ru.asop.api.user.dto.request.UserRoleCreateRequest
import ru.asop.api.user.dto.response.UserRoleResponse
import ru.asop.user.service.UserRoleService

@RestController
class UserRoleController(
    private val service: UserRoleService
) : UserRoleApi {

    override fun list(userId: String?, roleId: String?): Flux<UserRoleResponse> =
        service.list(userId, roleId).map { row -> UserRoleResponse(
            userId = row["user_id"]?.toString() ?: "",
            roleId = row["role_id"]?.toString() ?: "",
            roleName = row["role_name"]?.toString(),
            firstName = row["first_name"]?.toString(),
            lastNameInitial = row["last_name_initial"]?.toString()
        )}

    override fun create(request: UserRoleCreateRequest): Mono<ResponseEntity<UserRoleResponse>> =
        service.create(request.userId, request.roleId).map {
            ResponseEntity.status(201).body(UserRoleResponse(
                userId = it["user_id"]?.toString() ?: "",
                roleId = it["role_id"]?.toString() ?: "",
                roleName = it["role_name"]?.toString(),
                firstName = it["first_name"]?.toString(),
                lastNameInitial = it["last_name_initial"]?.toString()
            ))
        }

    override fun delete(userId: String, roleId: String): Mono<ResponseEntity<Void>> =
        service.delete(userId, roleId).map { rows ->
            if (rows > 0) ResponseEntity.noContent().build() else ResponseEntity.notFound().build()
        }
}

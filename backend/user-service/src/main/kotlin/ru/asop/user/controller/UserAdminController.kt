package ru.asop.user.controller

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import ru.asop.api.user.controller.UserAdminApi
import ru.asop.api.user.dto.request.UserCreateRequest
import ru.asop.api.user.dto.response.UserResponse
import ru.asop.user.service.UserAdminService

@RestController
class UserAdminController(
    private val service: UserAdminService
) : UserAdminApi {

    override fun list(): Flux<UserResponse> =
        service.list().flatMapMany { Flux.fromIterable(it) }.map { rowToResponse(it) }

    override fun get(id: String): Mono<ResponseEntity<UserResponse>> =
        service.getById(id).flatMap { row ->
            if (row.isEmpty()) Mono.just(ResponseEntity.notFound().build())
            else Mono.just(ResponseEntity.ok(rowToResponse(row)))
        }

    override fun create(request: UserCreateRequest): Mono<ResponseEntity<UserResponse>> =
        service.create(request).map { ResponseEntity.status(201).body(it) }

    override fun update(id: String, request: UserCreateRequest): Mono<ResponseEntity<UserResponse>> =
        service.update(id, request).flatMap { resp ->
            if (resp.id.isNotEmpty() && resp.firstName.isNotEmpty()) Mono.just(ResponseEntity.ok(resp))
            else Mono.just(ResponseEntity.notFound().build())
        }

    override fun delete(id: String): Mono<ResponseEntity<Void>> =
        service.delete(id).map { rows ->
            if (rows > 0) ResponseEntity.noContent().build()
            else ResponseEntity.notFound().build()
        }

    private fun rowToResponse(row: Map<String, Any?>): UserResponse = UserResponse(
        id = row["user_id"]?.toString() ?: "",
        firstName = row["first_name"]?.toString() ?: "",
        lastNameInitial = row["last_name_initial"]?.toString() ?: "",
        patronymicInitial = row["patronymic_initial"]?.toString(),
        phone = row["phone"]?.toString(),
        keycloakId = row["keycloak_id"]?.toString()
    )
}

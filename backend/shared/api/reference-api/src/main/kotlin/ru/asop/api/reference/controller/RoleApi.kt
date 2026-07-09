package ru.asop.api.reference.controller

import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import reactor.core.publisher.Mono
import ru.asop.api.reference.dto.request.RoleCreateRequest
import ru.asop.api.reference.dto.request.RoleUpdateRequest
import ru.asop.api.reference.dto.response.RoleResponse
import java.util.UUID

@RequestMapping("/api/v1/roles")
interface RoleApi {

    @GetMapping
    fun listRoles(): Mono<ResponseEntity<List<RoleResponse>>>

    @GetMapping("/{id}")
    fun getRole(@PathVariable id: UUID): Mono<ResponseEntity<RoleResponse>>

    @PostMapping
    fun createRole(@Valid @RequestBody request: RoleCreateRequest): Mono<ResponseEntity<RoleResponse>>

    @PutMapping("/{id}")
    fun updateRole(@PathVariable id: UUID, @Valid @RequestBody request: RoleUpdateRequest): Mono<ResponseEntity<RoleResponse>>

    @DeleteMapping("/{id}")
    fun deleteRole(@PathVariable id: UUID): Mono<ResponseEntity<Void>>
}

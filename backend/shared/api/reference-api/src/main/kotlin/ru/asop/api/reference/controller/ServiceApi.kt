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
import ru.asop.api.reference.dto.request.ServiceCreateRequest
import ru.asop.api.reference.dto.request.ServiceUpdateRequest
import ru.asop.api.reference.dto.response.ServiceResponse
import java.util.UUID

@RequestMapping("/api/v1/services")
interface ServiceApi {

    @GetMapping
    fun listServices(): Mono<ResponseEntity<List<ServiceResponse>>>

    @GetMapping("/{id}")
    fun getService(@PathVariable id: UUID): Mono<ResponseEntity<ServiceResponse>>

    @PostMapping
    fun createService(@Valid @RequestBody request: ServiceCreateRequest): Mono<ResponseEntity<ServiceResponse>>

    @PutMapping("/{id}")
    fun updateService(@PathVariable id: UUID, @Valid @RequestBody request: ServiceUpdateRequest): Mono<ResponseEntity<ServiceResponse>>

    @DeleteMapping("/{id}")
    fun deleteService(@PathVariable id: UUID): Mono<ResponseEntity<Void>>
}

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
import org.springframework.web.bind.annotation.RequestParam
import reactor.core.publisher.Mono
import ru.asop.api.reference.dto.request.BenefitCreateRequest
import ru.asop.api.reference.dto.request.BenefitUpdateRequest
import ru.asop.api.reference.dto.response.BenefitResponse
import java.util.UUID

@RequestMapping("/api/v1/benefits")
interface BenefitApi {

    @GetMapping
    fun listBenefits(@RequestParam(required = false) regionId: UUID? = null): Mono<ResponseEntity<List<BenefitResponse>>>

    @GetMapping("/{id}")
    fun getBenefit(@PathVariable id: UUID): Mono<ResponseEntity<BenefitResponse>>

    @PostMapping
    fun createBenefit(@Valid @RequestBody request: BenefitCreateRequest): Mono<ResponseEntity<BenefitResponse>>

    @PutMapping("/{id}")
    fun updateBenefit(@PathVariable id: UUID, @Valid @RequestBody request: BenefitUpdateRequest): Mono<ResponseEntity<BenefitResponse>>

    @DeleteMapping("/{id}")
    fun deleteBenefit(@PathVariable id: UUID): Mono<ResponseEntity<Void>>
}

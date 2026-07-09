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
import ru.asop.api.reference.dto.request.BenefitStepCreateRequest
import ru.asop.api.reference.dto.request.BenefitStepUpdateRequest
import ru.asop.api.reference.dto.response.BenefitStepResponse
import java.util.UUID

@RequestMapping("/api/v1/benefit-steps")
interface BenefitStepApi {

    @GetMapping
    fun listBenefitSteps(): Mono<ResponseEntity<List<BenefitStepResponse>>>

    @GetMapping("/{id}")
    fun getBenefitStep(@PathVariable id: UUID): Mono<ResponseEntity<BenefitStepResponse>>

    @PostMapping
    fun createBenefitStep(@Valid @RequestBody request: BenefitStepCreateRequest): Mono<ResponseEntity<BenefitStepResponse>>

    @PutMapping("/{id}")
    fun updateBenefitStep(@PathVariable id: UUID, @Valid @RequestBody request: BenefitStepUpdateRequest): Mono<ResponseEntity<BenefitStepResponse>>

    @DeleteMapping("/{id}")
    fun deleteBenefitStep(@PathVariable id: UUID): Mono<ResponseEntity<Void>>

    @GetMapping("/by-benefit")
    fun listByBenefitId(@RequestParam benefitId: UUID): Mono<ResponseEntity<List<BenefitStepResponse>>>
}

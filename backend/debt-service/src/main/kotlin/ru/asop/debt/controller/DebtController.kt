package ru.asop.debt.controller

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono
import ru.asop.api.debt.controller.DebtApi
import ru.asop.api.debt.dto.request.DebtCreateRequest
import ru.asop.api.debt.dto.response.DebtResponse
import ru.asop.debt.service.DebtService
import java.security.Principal
import java.util.UUID

@RestController
class DebtController(
    private val debtService: DebtService
) : DebtApi {

    override fun createDebt(
        request: DebtCreateRequest,
        principal: Mono<Principal>
    ): Mono<ResponseEntity<DebtResponse>> {
        return debtService.create(request)
            .map { ResponseEntity.ok(it) }
    }

    override fun getDebt(id: UUID): Mono<ResponseEntity<DebtResponse>> {
        return debtService.getById(id)
            .map { ResponseEntity.ok(it) }
    }
}

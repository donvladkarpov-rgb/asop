package ru.asop.user.controller

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import ru.asop.api.user.controller.UserCarrierApi
import ru.asop.api.user.dto.request.UserCarrierCreateRequest
import ru.asop.api.user.dto.response.UserCarrierResponse
import ru.asop.user.service.UserCarrierService

@RestController
class UserCarrierController(
    private val service: UserCarrierService
) : UserCarrierApi {

    override fun list(userId: String?, carrierId: String?): Flux<UserCarrierResponse> =
        service.list(userId, carrierId).map { row -> UserCarrierResponse(
            userId = row["user_id"]?.toString() ?: "",
            carrierId = row["carrier_id"]?.toString() ?: "",
            carrierName = row["carrier_name"]?.toString(),
            firstName = row["first_name"]?.toString(),
            lastNameInitial = row["last_name_initial"]?.toString()
        )}

    override fun create(request: UserCarrierCreateRequest): Mono<ResponseEntity<UserCarrierResponse>> =
        service.create(request.userId, request.carrierId).map {
            ResponseEntity.status(201).body(UserCarrierResponse(
                userId = it["user_id"]?.toString() ?: "",
                carrierId = it["carrier_id"]?.toString() ?: "",
                carrierName = it["carrier_name"]?.toString(),
                firstName = it["first_name"]?.toString(),
                lastNameInitial = it["last_name_initial"]?.toString()
            ))
        }

    override fun delete(userId: String, carrierId: String): Mono<ResponseEntity<Void>> =
        service.delete(userId, carrierId).map { rows ->
            if (rows > 0) ResponseEntity.noContent().build() else ResponseEntity.notFound().build()
        }
}

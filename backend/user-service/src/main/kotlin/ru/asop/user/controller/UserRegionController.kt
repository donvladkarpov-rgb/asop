package ru.asop.user.controller

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import ru.asop.api.user.controller.UserRegionApi
import ru.asop.api.user.dto.request.UserRegionCreateRequest
import ru.asop.api.user.dto.response.UserRegionResponse
import ru.asop.user.service.UserRegionService

@RestController
class UserRegionController(
    private val service: UserRegionService
) : UserRegionApi {

    override fun list(userId: String?, regionId: String?): Flux<UserRegionResponse> =
        service.list(userId, regionId).map { row -> UserRegionResponse(
            userId = row["user_id"]?.toString() ?: "",
            regionId = row["region_id"]?.toString() ?: "",
            regionName = row["region_name"]?.toString(),
            firstName = row["first_name"]?.toString(),
            lastNameInitial = row["last_name_initial"]?.toString()
        )}

    override fun create(request: UserRegionCreateRequest): Mono<ResponseEntity<UserRegionResponse>> =
        service.create(request.userId, request.regionId).map {
            ResponseEntity.status(201).body(UserRegionResponse(
                userId = it["user_id"]?.toString() ?: "",
                regionId = it["region_id"]?.toString() ?: "",
                regionName = it["region_name"]?.toString(),
                firstName = it["first_name"]?.toString(),
                lastNameInitial = it["last_name_initial"]?.toString()
            ))
        }

    override fun delete(userId: String, regionId: String): Mono<ResponseEntity<Void>> =
        service.delete(userId, regionId).map { rows ->
            if (rows > 0) ResponseEntity.noContent().build() else ResponseEntity.notFound().build()
        }
}

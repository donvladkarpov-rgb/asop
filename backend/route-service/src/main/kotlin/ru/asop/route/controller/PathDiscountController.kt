package ru.asop.route.controller

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import ru.asop.api.route.controller.PathDiscountApi
import ru.asop.api.route.dto.request.PathDiscountCreateRequest
import ru.asop.api.route.dto.response.PathDiscountResponse
import ru.asop.route.repository.GenericRouteRepository
import ru.asop.route.repository.RouteTableRegistry
import ru.asop.route.service.PathDiscountService
import java.util.UUID

@RestController
class PathDiscountController(
    private val service: PathDiscountService,
    private val repository: GenericRouteRepository
) : PathDiscountApi {

    @GetMapping("/delta")
    fun listDelta(
        @RequestParam(required = false) versionSince: Long?,
        @RequestParam(required = false) includeDeleted: Boolean,
        @RequestParam(required = false) regionId: UUID?,
        @RequestParam(required = false) carrierId: UUID?,
        @RequestParam(required = false, defaultValue = "10000") limit: Int
    ): Flux<Map<String, Any?>> {
        val info = RouteTableRegistry.resolve("path-discounts") ?: return Flux.empty()
        return repository.findDelta(info, versionSince, includeDeleted == true, regionId, carrierId, limit)
    }


    override fun list(): Flux<PathDiscountResponse> =
        service.list().flatMapMany { Flux.fromIterable(it) }.map { row ->
            PathDiscountResponse(
                id = row["path_discount_id"]?.toString() ?: "",
                pathId = row["path_id"]?.toString() ?: "",
                carrierId = row["carrier_id"]?.toString(),
                vehicleId = row["vehicle_id"]?.toString(),
                tariffTypeId = row["tariff_type_id"]?.toString(),
                discountName = row["discount_name"]?.toString() ?: "",
                discountType = row["discount_type"]?.toString() ?: "",
                discountValue = (row["discount_value"] as? Number)?.toDouble() ?: 0.0,
                validFrom = row["valid_from"]?.toString() ?: "",
                validUntil = row["valid_until"]?.toString(),
                isActive = row["is_active"] as? Boolean
            )
        }

    override fun get(id: String): Mono<ResponseEntity<PathDiscountResponse>> =
        service.getById(id).flatMap { row ->
            if (row.isEmpty()) Mono.just(ResponseEntity.notFound().build())
            else Mono.just(ResponseEntity.ok(rowToResponse(row)))
        }

    override fun create(request: PathDiscountCreateRequest): Mono<ResponseEntity<PathDiscountResponse>> {
        val data = mapOf(
            "id" to null,
            "pathId" to request.pathId,
            "carrierId" to request.carrierId,
            "vehicleId" to request.vehicleId,
            "tariffTypeId" to request.tariffTypeId,
            "discountName" to request.discountName,
            "discountType" to request.discountType,
            "discountValue" to request.discountValue.toString(),
            "validFrom" to request.validFrom,
            "validUntil" to request.validUntil,
            "isActive" to request.isActive?.toString()
        )
        return service.create(data).map { ResponseEntity.status(201).body(rowToResponse(it)) }
    }

    override fun update(id: String, request: PathDiscountCreateRequest): Mono<ResponseEntity<PathDiscountResponse>> {
        val data = mapOf(
            "pathId" to request.pathId,
            "carrierId" to request.carrierId,
            "vehicleId" to request.vehicleId,
            "tariffTypeId" to request.tariffTypeId,
            "discountName" to request.discountName,
            "discountType" to request.discountType,
            "discountValue" to request.discountValue.toString(),
            "validFrom" to request.validFrom,
            "validUntil" to request.validUntil,
            "isActive" to request.isActive?.toString()
        )
        return service.update(id, data).flatMap { row ->
            if (row.isEmpty()) Mono.just(ResponseEntity.notFound().build())
            else Mono.just(ResponseEntity.ok(rowToResponse(row)))
        }
    }

    override fun delete(id: String): Mono<ResponseEntity<Void>> =
        service.delete(id).map { rows ->
            if (rows > 0) ResponseEntity.noContent().build()
            else ResponseEntity.notFound().build()
        }

    private fun rowToResponse(row: Map<String, Any?>): PathDiscountResponse = PathDiscountResponse(
        id = row["path_discount_id"]?.toString() ?: "",
        pathId = row["path_id"]?.toString() ?: "",
        carrierId = row["carrier_id"]?.toString(),
        vehicleId = row["vehicle_id"]?.toString(),
        tariffTypeId = row["tariff_type_id"]?.toString(),
        discountName = row["discount_name"]?.toString() ?: "",
        discountType = row["discount_type"]?.toString() ?: "",
        discountValue = (row["discount_value"] as? Number)?.toDouble() ?: 0.0,
        validFrom = row["valid_from"]?.toString() ?: "",
        validUntil = row["valid_until"]?.toString(),
        isActive = row["is_active"] as? Boolean
    )
}

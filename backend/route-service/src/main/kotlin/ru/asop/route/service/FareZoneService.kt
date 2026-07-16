package ru.asop.route.service

import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import ru.asop.route.entity.FareZoneEntity
import ru.asop.route.repository.GenericRouteRepository
import ru.asop.route.repository.RouteTableRegistry
import java.util.UUID

@Service
class FareZoneService(private val repo: GenericRouteRepository) {

    private val tableInfo = RouteTableRegistry.resolve("fare-zones")
        ?: error("fare-zones not found in registry")

    fun list(): Mono<List<Map<String, Any?>>> =
        repo.list(tableInfo).collectList()

    fun getById(id: String): Mono<Map<String, Any?>> =
        repo.getById(tableInfo, UUID.fromString(id))

    fun create(data: Map<String, String?>): Mono<Map<String, Any?>> {
        val entity = FareZoneEntity.fromRequest(data)
        return repo.create(tableInfo, entity.zoneId, entity.toDbMap().filterKeys { it.lowercase() != tableInfo.pkColumn.lowercase() })
    }

    fun update(id: String, data: Map<String, String?>): Mono<Map<String, Any?>> {
        val entity = FareZoneEntity.fromRequest(data + ("id" to id))
        return repo.update(tableInfo, UUID.fromString(id), entity.toDbMap().filterKeys { it.lowercase() != tableInfo.pkColumn.lowercase() })
    }

    fun delete(id: String): Mono<Long> =
        repo.delete(tableInfo, UUID.fromString(id))
}

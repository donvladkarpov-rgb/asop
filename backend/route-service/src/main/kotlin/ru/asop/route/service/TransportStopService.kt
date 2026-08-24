package ru.asop.route.service

import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import ru.asop.route.entity.TransportStopEntity
import ru.asop.route.repository.GenericRouteRepository
import ru.asop.route.repository.RouteTableRegistry
import java.util.UUID

@Service
class TransportStopService(private val repo: GenericRouteRepository) {

    private val tableInfo = RouteTableRegistry.resolve("transport-stops")
        ?: error("transport-stops not found in registry")

    fun list(regionId: UUID? = null, carrierId: UUID? = null): Mono<List<Map<String, Any?>>> =
        repo.listFiltered(tableInfo, regionId, carrierId).collectList()

    fun getById(id: String): Mono<Map<String, Any?>> =
        repo.getById(tableInfo, parseId(id))

    fun create(data: Map<String, String?>): Mono<Map<String, Any?>> {
        val entity = TransportStopEntity.fromRequest(data)
        return repo.create(tableInfo, entity.stopId, entity.toDbMap().filterKeys { it.lowercase() != tableInfo.pkColumn.lowercase() })
    }

    fun update(id: String, data: Map<String, String?>): Mono<Map<String, Any?>> {
        val uuid = parseId(id)
        val entity = TransportStopEntity.fromRequest(data + ("id" to id))
        return repo.update(tableInfo, uuid, entity.toDbMap().filterKeys { it.lowercase() != tableInfo.pkColumn.lowercase() })
    }

    fun delete(id: String): Mono<Long> =
        repo.delete(tableInfo, parseId(id))

    private fun parseId(id: String): UUID =
        try { UUID.fromString(id) }
        catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid UUID: '$id'")
        }
}

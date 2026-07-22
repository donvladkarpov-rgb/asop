package ru.asop.route.service

import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import ru.asop.route.repository.GenericRouteRepository
import ru.asop.route.repository.RouteTableRegistry
import java.util.UUID

@Service
class VehicleTypeService(private val repo: GenericRouteRepository) {

    private val tableInfo = RouteTableRegistry.resolve("vehicle-types")
        ?: error("vehicle-types not found in registry")

    fun list(): Mono<List<Map<String, Any?>>> =
        repo.list(tableInfo).collectList()

    fun getById(id: String): Mono<Map<String, Any?>> =
        repo.getById(tableInfo, parseId(id))

    fun create(data: Map<String, String?>): Mono<Map<String, Any?>> {
        val id = UUID.randomUUID()
        val dbMap = linkedMapOf(
            "vehicle_type_id" to id,
            "type_name" to (data["typeName"] ?: error("typeName is required"))
        )
        return repo.create(tableInfo, id, dbMap.filterKeys { it.lowercase() != tableInfo.pkColumn.lowercase() })
    }

    fun update(id: String, data: Map<String, String?>): Mono<Map<String, Any?>> {
        val uuid = parseId(id)
        val dbMap = linkedMapOf(
            "type_name" to (data["typeName"] ?: error("typeName is required"))
        )
        return repo.update(tableInfo, uuid, dbMap)
    }

    fun delete(id: String): Mono<Long> =
        repo.delete(tableInfo, parseId(id))

    private fun parseId(id: String): UUID =
        try { UUID.fromString(id) }
        catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid UUID: '$id'")
        }
}

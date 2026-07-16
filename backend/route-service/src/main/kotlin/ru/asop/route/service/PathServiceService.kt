package ru.asop.route.service

import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import ru.asop.route.entity.PathServiceEntity
import ru.asop.route.repository.GenericRouteRepository
import ru.asop.route.repository.RouteTableRegistry
import java.util.UUID

@Service
class PathServiceService(private val repo: GenericRouteRepository) {

    private val tableInfo = RouteTableRegistry.resolve("path-services")
        ?: error("path-services not found in registry")

    fun list(): Mono<List<Map<String, Any?>>> =
        repo.list(tableInfo).collectList()

    fun getById(id: String): Mono<Map<String, Any?>> =
        repo.getById(tableInfo, parseId(id))

    fun create(data: Map<String, String?>): Mono<Map<String, Any?>> {
        val entity = PathServiceEntity.fromRequest(data)
        return repo.create(tableInfo, entity.pathServiceId, entity.toDbMap().filterKeys { it.lowercase() != tableInfo.pkColumn.lowercase() })
    }

    fun update(id: String, data: Map<String, String?>): Mono<Map<String, Any?>> {
        val uuid = parseId(id)
        val entity = PathServiceEntity.fromRequest(data + ("id" to id))
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

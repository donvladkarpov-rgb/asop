package ru.asop.route.service

import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.util.UUID

@Service
class ContractRouteService(private val db: DatabaseClient) {

    fun list(contractId: String?, routeId: String?): Flux<Map<String, Any?>> {
        val conditions = mutableListOf<String>()
        val params = mutableMapOf<String, Any>()

        if (contractId != null) {
            conditions.add("cr.contract_id = :contractId")
            params["contractId"] = UUID.fromString(contractId)
        }
        if (routeId != null) {
            conditions.add("cr.route_id = :routeId")
            params["routeId"] = UUID.fromString(routeId)
        }

        val whereClause = if (conditions.isNotEmpty()) "WHERE ${conditions.joinToString(" AND ")}" else ""

        val sql = """
            SELECT cr.contract_id, cr.route_id, r.route_number, c.contract_number
            FROM ASOP_CONTRACT_ROUTES cr
            LEFT JOIN ASOP_ROUTES r ON r.route_id = cr.route_id
            LEFT JOIN ASOP_CONTRACTS c ON c.contract_id = cr.contract_id
            $whereClause
            ORDER BY cr.contract_id, cr.route_id
        """.trimIndent()

        val spec = db.sql(sql)
        var current = spec
        for ((k, v) in params) {
            when (v) {
                is UUID -> current = current.bind(k, v)
                else -> current = current.bind(k, v)
            }
        }
        return current.fetch().all()
    }

    fun create(contractId: String, routeId: String): Mono<Map<String, Any?>> {
        val cId = UUID.fromString(contractId)
        val rId = UUID.fromString(routeId)
        val sql = "INSERT INTO ASOP_CONTRACT_ROUTES (contract_id, route_id) VALUES (:contractId, :routeId)"
        return db.sql(sql)
            .bind("contractId", cId)
            .bind("routeId", rId)
            .fetch()
            .rowsUpdated()
            .then(list(contractId, routeId).next())
    }

    fun delete(contractId: String, routeId: String): Mono<Long> {
        val sql = "DELETE FROM ASOP_CONTRACT_ROUTES WHERE contract_id = :contractId AND route_id = :routeId"
        return db.sql(sql)
            .bind("contractId", UUID.fromString(contractId))
            .bind("routeId", UUID.fromString(routeId))
            .fetch()
            .rowsUpdated()
    }
}

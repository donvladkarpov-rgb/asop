package ru.asop.gateway.config

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class ServiceRegistry {

    private val log = LoggerFactory.getLogger(javaClass)

    private val isDocker = System.getenv("ASOP_ENV") == "docker"

    private val services: Map<String, String> = mapOf(
        "users" to svc("user-service", 8082),
        "terminals" to svc("terminal-service", 8084),
        "sessions" to svc("session-service", 8085),
        "cards" to svc("card-service", 8086),
        "card-mifares" to svc("card-service", 8086),
        "card-banks" to svc("card-service", 8086),
        "card-tariffs" to svc("card-service", 8086),
        "blacklists" to svc("card-service", 8086),
        "user-benefits" to svc("card-service", 8086),
        "tariff-rates" to svc("card-service", 8086),
        "transactions" to svc("card-service", 8086),
        "carriers" to svc("carrier-service", 8087),
        "cards-distributors" to svc("carrier-service", 8087),
        "contracts" to svc("carrier-service", 8087),
        "tids" to svc("carrier-service", 8087),
        "debts" to svc("debt-service", 8088),
        "audit" to svc("audit-service", 8089),
        "audit-services" to svc("audit-service", 8089),
        "fiscal" to svc("fiscal-service", 8090),
        "crypto" to svc("crypto-service", 8081),
        "smart-cards" to svc("crypto-service", 8081),
        "keys" to svc("crypto-service", 8081),
        "regions" to svc("admin-service", 8091),
        "territories" to svc("admin-service", 8091),
        "organizers" to svc("admin-service", 8091),
        "organizer-territories" to svc("admin-service", 8091),
        "roles" to svc("admin-service", 8091),
        "card-types" to svc("admin-service", 8091),
        "tariff-types" to svc("admin-service", 8091),
        "session-types" to svc("admin-service", 8091),
        "event-types" to svc("admin-service", 8091),
        "transaction-types" to svc("admin-service", 8091),
        "transaction-results" to svc("admin-service", 8091),
        "services" to svc("admin-service", 8091),
        "benefits" to svc("admin-service", 8091),
        "benefit-steps" to svc("admin-service", 8091),
        "asop-keys" to svc("admin-service", 8091),
        "config-params" to svc("admin-service", 8091),
        // Routes & Paths (route-service)
        "fare-zones" to svc("route-service", 8092),
        "transport-stops" to svc("route-service", 8092),
        "routes" to svc("route-service", 8092),
        "paths" to svc("route-service", 8092),
        "path-transport-stops" to svc("route-service", 8092),
        "schedule" to svc("route-service", 8092),
        "path-services" to svc("route-service", 8092),
        "path-discounts" to svc("route-service", 8092),
        "path-benefits" to svc("route-service", 8092),
        "vehicles" to svc("route-service", 8092),
        "vehicle-types" to svc("route-service", 8092),
        "vehicle-models" to svc("route-service", 8092),
        "contract-routes" to svc("route-service", 8092),
        "tracking" to svc("session-service", 8085),
        "stops" to svc("route-service", 8092),
        "admin-users" to svc("user-service", 8082),
        "user-roles" to svc("user-service", 8082),
        "user-carriers" to svc("user-service", 8082),
        "user-regions" to svc("user-service", 8082),
        "user-distributors" to svc("user-service", 8082),
        "user-krs" to svc("user-service", 8082),
    )

    private fun svc(host: String, port: Int): String {
        val h = if (isDocker) host else "localhost"
        return "https://$h:$port"
    }

    fun getBaseUrl(resource: String): String? {
        val url = services[resource]
        if (url == null) log.debug("Unknown service resource: {}", resource)
        return url
    }

    init {
        log.info("ServiceRegistry initialized (mode: {})", if (isDocker) "docker" else "local")
        services.forEach { (k, v) -> log.debug("  {} -> {}", k, v) }
    }
}

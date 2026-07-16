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
        "carriers" to svc("carrier-service", 8087),
        "debts" to svc("debt-service", 8088),
        "audit" to svc("audit-service", 8089),
        "fiscal" to svc("fiscal-service", 8090),
        "crypto" to svc("crypto-service", 8081),
        "regions" to svc("admin-service", 8091),
        "territories" to svc("admin-service", 8091),
        "organizers" to svc("admin-service", 8091),
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

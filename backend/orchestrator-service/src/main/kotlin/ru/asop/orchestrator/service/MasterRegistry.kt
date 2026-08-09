package ru.asop.orchestrator.service

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

data class MasterEndpoint(
    val serviceHost: String,
    val port: Int,
    val resource: String
) {
    val baseUrl: String get() = "https://$serviceHost:$port"
}

/**
 * Маппинг таблица справочника → мастер-сервис + delta resource.
 * Используется оркестратором для `GET {baseUrl}/api/v1/{resource}/delta?...`.
 * Держим в синхроне с gateway ServiceRegistry.
 */
@Component
class MasterRegistry {

    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private fun admin(resource: String) = MasterEndpoint("admin-service", 8091, resource)
        private fun carrier(resource: String) = MasterEndpoint("carrier-service", 8087, resource)
        private fun route(resource: String) = MasterEndpoint("route-service", 8092, resource)
        private fun user(resource: String) = MasterEndpoint("user-service", 8082, resource)
        private fun card(resource: String) = MasterEndpoint("card-service", 8086, resource)
        private fun audit(resource: String) = MasterEndpoint("audit-service", 8089, resource)

        // Таблицы, фильтруемые по (carrierId, regionId) напрямую на мастере
        val FILTERED_TABLES: Map<String, MasterEndpoint> = mapOf(
            "asop_organizer_territories" to admin("organizer-territories"),
            "asop_services" to admin("services"),
            "asop_benefits" to admin("benefits"),
            "asop_benefit_steps" to admin("benefit-steps"),
            "asop_carriers" to carrier("carriers"),
            "asop_contracts" to carrier("contracts"),
            "asop_cards_distributors" to carrier("cards-distributors"),
            "asop_tids" to carrier("tids"),
            "asop_contract_routes" to route("contract-routes"),
            "asop_vehicles" to route("vehicles"),
            "asop_fare_zones" to route("fare-zones"),
            "asop_transport_stops" to route("transport-stops"),
            "asop_routes" to route("routes"),
            "asop_paths" to route("paths"),
            "asop_path_transport_stops" to route("path-transport-stops"),
            "asop_schedule" to route("schedule"),
            "asop_path_services" to route("path-services"),
            "asop_path_discounts" to route("path-discounts"),
            "asop_path_benefits" to route("path-benefits"),
            "asop_user_roles" to user("user-roles"),
            "asop_user_carriers" to user("user-carriers"),
            "asop_user_regions" to user("user-regions")
        )

        // Глобальные справочники (без carrier/region фильтра)
        val GLOBAL_TABLES: Map<String, MasterEndpoint> = mapOf(
            "asop_regions" to admin("regions"),
            "asop_territories" to admin("territories"),
            "asop_organizers" to admin("organizers"),
            "asop_roles" to admin("roles"),
            "asop_card_types" to admin("card-types"),
            "asop_tariff_types" to admin("tariff-types"),
            "asop_session_types" to admin("session-types"),
            "asop_event_types" to admin("event-types"),
            "asop_transaction_types" to admin("transaction-types"),
            "asop_transaction_results" to admin("transaction-results"),
            "asop_vehicle_types" to route("vehicle-types"),
            "asop_vehicle_models" to route("vehicle-models"),
            // tariff-rates в card-service НЕ фильтруется по userIdsIn (SQL без фильтра)
            "asop_tariff_rates" to card("tariff-rates"),
            // Глобальный пул ротируемых 3DES-ключей карт (админка генерирует через crypto-service)
            "asop_3des_keys" to admin("three-des-keys"),
            // Справочник КРС (audit-service)
            "asop_audit_services" to audit("audit-services")
        )

        // Пользовательские таблицы (user-service): фильтр по carrierId/regionId каскадом
        val USER_TABLES: Map<String, MasterEndpoint> = mapOf(
            "asop_users" to user("admin-users")
        )

        // Карточные таблицы (card-service): фильтр по userIdsIn (каскад от USER_TABLES)
        val CARD_TABLES: Map<String, MasterEndpoint> = mapOf(
            "asop_cards" to card("cards"),
            "asop_card_mifares" to card("card-mifares"),
            "asop_card_banks" to card("card-banks"),
            "asop_card_tariffs" to card("card-tariffs"),
            "asop_blacklists" to card("blacklists"),
            "asop_user_benefits" to card("user-benefits")
        )

        val ALL: Map<String, MasterEndpoint> = FILTERED_TABLES + GLOBAL_TABLES + USER_TABLES + CARD_TABLES
    }

    init {
        log.info("MasterRegistry initialized: {} tables", ALL.size)
    }
}

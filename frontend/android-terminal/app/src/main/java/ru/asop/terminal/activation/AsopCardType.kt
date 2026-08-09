package ru.asop.terminal.activation

/**
 * 14 типов карт АСОП (промпт 005, п.1.1/1.4). Маппинг на CARD_ROLE/ROLE_NAME.
 * Порядок = иерархия (от root-админа к анонимному пассажиру).
 */
enum class AsopCardType(
    val label: String,
    val role: String,
    val needsRegion: Boolean = false,
    val needsOrganizer: Boolean = false,
    val needsCarrier: Boolean = false,
    val needsDistributor: Boolean = false,
    val needsAuditService: Boolean = false,
    val needsUser: Boolean = false
) {
    SUPER_ADMIN("Root админ", "SUPER_ADMIN", needsUser = true),
    REGION_ADMIN("Админ региона", "REGION_ADMIN", needsRegion = true, needsUser = true),
    ORGANIZER_ADMIN("Админ организатора", "ORGANIZER_ADMIN", needsRegion = true, needsOrganizer = true, needsUser = true),
    CARRIER_ADMIN("Админ перевозчика", "CARRIER_ADMIN", needsRegion = true, needsOrganizer = true, needsCarrier = true, needsUser = true),
    DISTRIBUTOR_ADMIN("Админ дистрибьютора", "DISTRIBUTOR_ADMIN", needsRegion = true, needsOrganizer = true, needsDistributor = true, needsUser = true),
    KRS_ADMIN("Админ КРС", "KRS_ADMIN", needsRegion = true, needsOrganizer = true, needsAuditService = true, needsUser = true),
    CARRIER_DISPATCHER("Диспетчер перевозчика", "CARRIER_DISPATCHER", needsRegion = true, needsOrganizer = true, needsCarrier = true, needsUser = true),
    DISTRIBUTOR_DISPATCHER("Диспетчер дистрибьютора", "DISTRIBUTOR_DISPATCHER", needsRegion = true, needsOrganizer = true, needsDistributor = true, needsUser = true),
    KRS_DISPATCHER("Диспетчер КРС", "KRS_DISPATCHER", needsRegion = true, needsOrganizer = true, needsAuditService = true, needsUser = true),
    DRIVER("Водитель", "DRIVER", needsRegion = true, needsOrganizer = true, needsCarrier = true, needsUser = true),
    KRS_FOREMAN("Бригадир КРС", "KRS_FOREMAN", needsRegion = true, needsOrganizer = true, needsAuditService = true, needsUser = true),
    KRS_CONTROLLER("Сотрудник КРС", "KRS_CONTROLLER", needsRegion = true, needsOrganizer = true, needsAuditService = true, needsUser = true),
    PASSENGER("Пассажир персональный", "PASSENGER", needsUser = true),
    PASSENGER_ANONYMOUS("Пассажир анонимный", "PASSENGER_ANONYMOUS");

    val requiresRoot: Boolean get() = this == SUPER_ADMIN

    companion object {
        val STAFF: List<AsopCardType> = entries.filter { it != PASSENGER && it != PASSENGER_ANONYMOUS }
        val PASSENGERS: List<AsopCardType> = listOf(PASSENGER, PASSENGER_ANONYMOUS)

        fun fromRole(role: String): AsopCardType? = entries.firstOrNull { it.role == role }
    }
}

/**
 * Матрица авторизации регистрации карт (промпт 005, п.2).
 * key = целевая роль, value = множество ролей, которые могут авторизовать
 * (включая self + цепочку). Root админ — только через login/password.
 */
object CardActivationMatrix {

    private val ALL_AUTHORIZING_ROLES = setOf(
        "SUPER_ADMIN", "REGION_ADMIN", "ORGANIZER_ADMIN", "CARRIER_ADMIN", "DISTRIBUTOR_ADMIN",
        "KRS_ADMIN", "CARRIER_DISPATCHER", "DISTRIBUTOR_DISPATCHER", "KRS_DISPATCHER",
        "DRIVER", "KRS_FOREMAN", "KRS_CONTROLLER"
    )

    // Локальная копия AUTHORIZATION_MATRIX из CardActivationService (backend).
    // Для SUPER_ADMIN — только root login (не карта) — обрабатывается отдельно.
    val AUTHORIZATION_MATRIX: Map<String, Set<String>> = mapOf(
        "REGION_ADMIN" to setOf("SUPER_ADMIN", "REGION_ADMIN"),
        "ORGANIZER_ADMIN" to setOf("SUPER_ADMIN", "REGION_ADMIN", "ORGANIZER_ADMIN"),
        "CARRIER_ADMIN" to setOf("SUPER_ADMIN", "REGION_ADMIN", "ORGANIZER_ADMIN", "CARRIER_ADMIN"),
        "DISTRIBUTOR_ADMIN" to setOf("SUPER_ADMIN", "REGION_ADMIN", "ORGANIZER_ADMIN", "DISTRIBUTOR_ADMIN"),
        "KRS_ADMIN" to setOf("SUPER_ADMIN", "REGION_ADMIN", "ORGANIZER_ADMIN", "KRS_ADMIN"),
        "CARRIER_DISPATCHER" to setOf("SUPER_ADMIN", "REGION_ADMIN", "ORGANIZER_ADMIN", "CARRIER_ADMIN", "CARRIER_DISPATCHER"),
        "DISTRIBUTOR_DISPATCHER" to setOf("SUPER_ADMIN", "REGION_ADMIN", "ORGANIZER_ADMIN", "DISTRIBUTOR_ADMIN", "DISTRIBUTOR_DISPATCHER"),
        "KRS_DISPATCHER" to setOf("SUPER_ADMIN", "REGION_ADMIN", "ORGANIZER_ADMIN", "KRS_ADMIN", "KRS_DISPATCHER"),
        "DRIVER" to setOf("SUPER_ADMIN", "REGION_ADMIN", "ORGANIZER_ADMIN", "CARRIER_ADMIN", "CARRIER_DISPATCHER", "DRIVER"),
        "KRS_FOREMAN" to setOf("SUPER_ADMIN", "REGION_ADMIN", "ORGANIZER_ADMIN", "KRS_ADMIN", "KRS_DISPATCHER", "KRS_FOREMAN"),
        "KRS_CONTROLLER" to setOf("SUPER_ADMIN", "REGION_ADMIN", "ORGANIZER_ADMIN", "KRS_ADMIN", "KRS_DISPATCHER", "KRS_FOREMAN", "KRS_CONTROLLER"),
        "PASSENGER" to ALL_AUTHORIZING_ROLES,
        "PASSENGER_ANONYMOUS" to ALL_AUTHORIZING_ROLES
    )

    /**
     * Локальный role-чек (UX): может ли комплект операторских ролей активировать
     * целевую роль. Сервер проверяет по-своему (defense-in-depth).
     */
    fun canAuthorize(operatorRoles: List<String>, targetRole: String): Boolean {
        if (targetRole == "SUPER_ADMIN") return false // только root login
        val allowed = AUTHORIZATION_MATRIX[targetRole] ?: return false
        return operatorRoles.any { it in allowed }
    }
}
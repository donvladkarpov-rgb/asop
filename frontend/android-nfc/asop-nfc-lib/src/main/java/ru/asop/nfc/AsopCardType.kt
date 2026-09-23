package ru.asop.nfc

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
    val needsUser: Boolean = false,
    val regionDropDownOptional: Boolean = false,
) {
    SUPER_ADMIN("Главный администратор", "SUPER_ADMIN", needsUser = true),

    PASSENGER("Пассажир персональный", "PASSENGER", needsUser = true, regionDropDownOptional = true),

    REGION_ADMIN("Администратор региона", "REGION_ADMIN", needsRegion = true, needsUser = true),
    ORGANIZER_ADMIN("Администратор организатора", "ORGANIZER_ADMIN", needsRegion = true, needsOrganizer = true, needsUser = true),
    CARRIER_ADMIN("Администратор перевозчика", "CARRIER_ADMIN", needsRegion = true, needsOrganizer = true, needsCarrier = true, needsUser = true),
    DISTRIBUTOR_ADMIN("Администратор дистрибьютора", "DISTRIBUTOR_ADMIN", needsRegion = true, needsOrganizer = true, needsDistributor = true, needsUser = true),
    KRS_ADMIN("Администратор КРС", "KRS_ADMIN", needsRegion = true, needsOrganizer = true, needsAuditService = true, needsUser = true),
    CARRIER_DISPATCHER("Диспетчер перевозчика", "CARRIER_DISPATCHER", needsRegion = true, needsOrganizer = true, needsCarrier = true, needsUser = true),
    DISTRIBUTOR_DISPATCHER("Диспетчер дистрибьютора", "DISTRIBUTOR_DISPATCHER", needsRegion = true, needsOrganizer = true, needsDistributor = true, needsUser = true),
    KRS_DISPATCHER("Диспетчер КРС", "KRS_DISPATCHER", needsRegion = true, needsOrganizer = true, needsAuditService = true, needsUser = true),
    DRIVER("Водитель", "DRIVER", needsRegion = true, needsOrganizer = true, needsCarrier = true, needsUser = true),
    KRS_FOREMAN("Бригадир КРС", "KRS_FOREMAN", needsRegion = true, needsOrganizer = true, needsAuditService = true, needsUser = true),
    KRS_CONTROLLER("Сотрудник КРС", "KRS_CONTROLLER", needsRegion = true, needsOrganizer = true, needsAuditService = true, needsUser = true),
    PASSENGER_ANONYMOUS("Пассажир анонимный", "PASSENGER_ANONYMOUS");

    val requiresRoot: Boolean get() = this == SUPER_ADMIN

    val requiresUserSearch: Boolean get() = this != PASSENGER_ANONYMOUS

    companion object {
        val STAFF: List<AsopCardType> = entries.filter { it != PASSENGER && it != PASSENGER_ANONYMOUS }
        val PASSENGERS: List<AsopCardType> = listOf(PASSENGER, PASSENGER_ANONYMOUS)

        fun fromRole(role: String): AsopCardType? = entries.firstOrNull { it.role == role }

        /** VCM1: highest-set bit (lowest ordinal) → primary role. bitmask=0 → null. */
        fun highestSetBitRole(bitmask: Int): AsopCardType? {
            for (i in 0..13) {
                if (((bitmask shr i) and 1) == 1) return entries.getOrNull(i)
            }
            return null
        }

        /** VCM1: все роли из bitmask (для UI — показать badges). */
        fun allRolesForBitmask(bitmask: Int): List<AsopCardType> =
            (0..13).filter { (bitmask shr it) and 1 == 1 }.mapNotNull { entries.getOrNull(it) }

        /** VCM1: bitmask из выбранных ролей (1 << ordinal). */
        fun bitmaskForEntityRoles(roles: Collection<AsopCardType>): Int {
            var bits = 0
            for (role in roles) bits = bits or (1 shl role.ordinal)
            return bits
        }
    }
}

/**
 * Матрица авторизации регистрации карт (промпт 005). Локальная копия
 * AUTHORIZATION_MATRIX из CardActivationService (backend). Для SUPER_ADMIN — только
 * root login (не карта).
 */
object CardActivationMatrix {

    private val ALL_AUTHORIZING_ROLES = setOf(
        "SUPER_ADMIN", "REGION_ADMIN", "ORGANIZER_ADMIN", "CARRIER_ADMIN", "DISTRIBUTOR_ADMIN",
        "KRS_ADMIN", "CARRIER_DISPATCHER", "DISTRIBUTOR_DISPATCHER", "KRS_DISPATCHER",
        "DRIVER", "KRS_FOREMAN", "KRS_CONTROLLER"
    )

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

    fun canAuthorize(operatorRoles: List<String>, targetRole: String): Boolean {
        if (targetRole == "SUPER_ADMIN") return false
        val allowed = AUTHORIZATION_MATRIX[targetRole] ?: return false
        return operatorRoles.any { it in allowed }
    }
}
package ru.asop.common.exception

enum class ErrorCode(
    val code: String,
    val httpStatus: Int,
    val defaultMessage: String
) {
    // Общие
    INTERNAL_ERROR("ERR_001", 500, "Internal server error"),
    VALIDATION_ERROR("ERR_002", 400, "Validation error"),
    NOT_FOUND("ERR_003", 404, "Resource not found"),
    UNAUTHORIZED("ERR_004", 401, "Unauthorized"),
    FORBIDDEN("ERR_005", 403, "Access denied"),
    CONFLICT("ERR_006", 409, "Resource conflict"),

    // Carrier
    CARRIER_NOT_FOUND("CARRIER_001", 404, "Carrier not found"),
    CARRIER_INN_ALREADY_EXISTS("CARRIER_002", 409, "Carrier with this INN already exists"),
    CARRIER_INVALID_INN("CARRIER_003", 400, "Invalid INN format"),

    // Terminal
    TERMINAL_NOT_FOUND("TERMINAL_001", 404, "Terminal not found"),
    TERMINAL_SERIAL_ALREADY_EXISTS("TERMINAL_002", 409, "Terminal with this serial number already exists"),

    // User
    USER_NOT_FOUND("USER_001", 404, "User not found"),
    USER_KEYCLOAK_ID_ALREADY_EXISTS("USER_002", 409, "User with this Keycloak ID already exists"),

    // Card
    CARD_NOT_FOUND("CARD_001", 404, "Card not found"),
    CARD_UID_ALREADY_EXISTS("CARD_002", 409, "Card with this UID already exists"),
    CARD_BLOCKED("CARD_003", 403, "Card is blocked"),

    // Kafka
    KAFKA_PRODUCE_FAILED("KAFKA_001", 500, "Failed to produce Kafka event");

    companion object {
        fun fromCode(code: String): ErrorCode? = entries.find { it.code == code }
    }
}
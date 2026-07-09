package ru.asop.common.exception

open class BusinessException(
    val errorCode: ErrorCode,
    override val message: String = errorCode.defaultMessage,
    val details: Map<String, Any?> = emptyMap(),
    cause: Throwable? = null
) : RuntimeException(message, cause) {

    companion object {
        fun notFound(entity: String, id: Any) = BusinessException(
            errorCode = ErrorCode.NOT_FOUND,
            message = "$entity with id=$id not found",
            details = mapOf("entity" to entity, "id" to id)
        )

        fun conflict(entity: String, field: String, value: Any) = BusinessException(
            errorCode = ErrorCode.CONFLICT,
            message = "$entity with $field=$value already exists",
            details = mapOf("entity" to entity, "field" to field, "value" to value)
        )

        fun validation(field: String, reason: String) = BusinessException(
            errorCode = ErrorCode.VALIDATION_ERROR,
            message = "Validation failed for field '$field': $reason",
            details = mapOf("field" to field, "reason" to reason)
        )
    }
}
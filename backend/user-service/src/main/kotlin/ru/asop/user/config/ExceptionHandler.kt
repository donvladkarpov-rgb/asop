package ru.asop.user.config

import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ResponseStatusException

/**
 * Маппинг ошибок user-service в HTTP-коды с человекочитаемыми сообщениями:
 *  • DataIntegrityViolation (uq_users_phone и др. констрейнты) → 409;
 *  • IllegalArgumentException (валидация) → 400;
 *  • ResponseStatusException — как задан.
 */
@RestControllerAdvice
class ExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(DataIntegrityViolationException::class)
    fun onIntegrityViolation(e: DataIntegrityViolationException): ResponseEntity<Map<String, String>> {
        val msg = e.mostSpecificCause?.message ?: e.message ?: "integrity violation"
        val friendly = when {
            msg.contains("uq_users_phone") ->
                "Пользователь с таким телефоном уже существует"
            msg.contains("pk_user_roles") || msg.contains("duplicate key value\" on \"asop_user_roles") ->
                "Роль уже назначена этому пользователю"
            msg.contains("pk_user_carriers") ->
                "Пользователь уже привязан к этому перевозчику"
            msg.contains("pk_user_regions") ->
                "Пользователь уже привязан к этому региону"
            msg.contains("pk_user_cards_distributors") ->
                "Пользователь уже привязан к этому дистрибьютору"
            msg.contains("pk_user_krs") ->
                "Пользователь уже привязан к этой КРС"
            else -> "Нарушение целостности данных: ${msg.take(200)}"
        }
        log.warn("DataIntegrityViolation: {}", msg.take(300))
        return ResponseEntity.status(HttpStatus.CONFLICT).body(mapOf("error" to friendly))
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun onIllegalArgument(e: IllegalArgumentException): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(mapOf("error" to (e.message ?: "Некорректный запрос")))

    @ExceptionHandler(ResponseStatusException::class)
    fun onResponseStatus(e: ResponseStatusException): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(e.statusCode).body(mapOf("error" to (e.reason ?: e.message)))
}

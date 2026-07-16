package ru.asop.gateway.config

import org.slf4j.LoggerFactory
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler
import org.springframework.core.annotation.Order
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import ru.asop.common.exception.BusinessException
import ru.asop.common.exception.ErrorCode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant

@Component
@Order(-2) // Должен быть раньше DefaultErrorWebExceptionHandler
class WebFluxExceptionHandler : ErrorWebExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)
    private val objectMapper: ObjectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())

    data class ErrorResponse(
        val timestamp: Instant = Instant.now(),
        val errorCode: String,
        val message: String,
        val details: Map<String, Any?> = emptyMap(),
        val path: String? = null
    )

    override fun handle(exchange: ServerWebExchange, ex: Throwable): Mono<Void> {
        val path = exchange.request.path.value()

        val (status, errorResponse) = when (ex) {
            is BusinessException -> {
                log.warn("Business error at {}: {} - {}", path, ex.errorCode.code, ex.message)
                HttpStatus.valueOf(ex.errorCode.httpStatus) to ErrorResponse(
                    errorCode = ex.errorCode.code,
                    message = ex.message,
                    details = ex.details,
                    path = path
                )
            }
            is org.springframework.security.access.AccessDeniedException -> {
                log.warn("Access denied at {}: {}", path, ex.message)
                HttpStatus.FORBIDDEN to ErrorResponse(
                    errorCode = ErrorCode.FORBIDDEN.code,
                    message = ErrorCode.FORBIDDEN.defaultMessage,
                    path = path
                )
            }
            is org.springframework.security.authentication.AuthenticationCredentialsNotFoundException,
            is org.springframework.security.core.AuthenticationException -> {
                log.warn("Authentication failed at {}: {}", path, ex.message)
                HttpStatus.UNAUTHORIZED to ErrorResponse(
                    errorCode = ErrorCode.UNAUTHORIZED.code,
                    message = ErrorCode.UNAUTHORIZED.defaultMessage,
                    path = path
                )
            }
            is org.springframework.web.server.ResponseStatusException -> {
                log.warn("ResponseStatusException at {}: {}", path, ex.message)
                HttpStatus.valueOf(ex.statusCode.value()) to ErrorResponse(
                    errorCode = "ERR_${ex.statusCode.value()}",
                    message = ex.reason ?: "Request error",
                    path = path
                )
            }
            else -> {
                log.error("Unexpected error at $path", ex)
                HttpStatus.INTERNAL_SERVER_ERROR to ErrorResponse(
                    errorCode = ErrorCode.INTERNAL_ERROR.code,
                    message = ErrorCode.INTERNAL_ERROR.defaultMessage,
                    path = path
                )
            }
        }

        exchange.response.statusCode = status
        exchange.response.headers.contentType = MediaType.APPLICATION_JSON

        val bytes = objectMapper.writeValueAsBytes(errorResponse)
        val buffer: DataBuffer = exchange.response.bufferFactory().wrap(bytes)
        return exchange.response.writeWith(Mono.just(buffer))
    }
}
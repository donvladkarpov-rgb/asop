package ru.asop.payment.acquirer

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Конфигурация банковского эквайринга (env `ASOP_ACQUIRER_PROVIDER`).
 *
 * `provider = mock` — [MockAcquirerAdapter] (dev/test, без сети банка).
 * `provider = vtb`  — [VtbSirposAdapter] (интерфейс ВТБ, TBD).
 */
@ConfigurationProperties(prefix = "asop.acquirer")
data class AcquirerProperties(
    val provider: String = "mock",
    val mock: MockProperties = MockProperties()
) {
    data class MockProperties(
        val latencyMs: Long = 0,
        val approveCode: String = "00",
        val declineCode: String = "05",
        val deferCode: String = "74",
        val reauthCode: String = "76",
        val timeoutNextRetrySeconds: Long = 60
    )
}

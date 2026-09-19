package ru.asop.payment.acquirer

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID
import kotlin.math.absoluteValue

/**
 * Мок эквайера (prompt_016 §3.1.9).
 *
 * Не делает сетевых вызовов, не шифрует данные карты, работает по `cardToken`/amount.
 * Сценарий — детерминированный (по копейкам суммы) либо задаётся через [MockScenarioStore].
 */
@Component
class MockAcquirerAdapter(
    private val scenarioStore: MockScenarioStore,
    private val properties: AcquirerProperties
) : AcquirerGateway {

    private val log = LoggerFactory.getLogger(javaClass)

    override val provider: String = "MOCK"

    override fun authorize(request: AcquirerAuthorizeRequest): Mono<AcquirerAuthResult> {
        val scenario = scenarioStore.consumeOverride()
            ?: AcquirerScenario.byAmountMinor(minorUnits(request.amount))
        return runWithLatency(scenario, request)
    }

    override fun refund(paymentId: UUID, amount: BigDecimal): Mono<AcquirerAuthResult> {
        val latency = properties.mock.latencyMs
        val result = AcquirerAuthResult(
            approved = true,
            status = "REFUNDED",
            acquirerReference = reference(paymentId),
            rrn = rrn(paymentId),
            authCode = authCode(paymentId),
            errorCode = properties.mock.approveCode,
            errorMessage = null,
            durationMs = latency
        )
        return Mono.just(result).delayElement(java.time.Duration.ofMillis(latency))
    }

    override fun reauthorize(paymentId: UUID, amount: BigDecimal): Mono<AcquirerAuthResult> {
        val latency = properties.mock.latencyMs
        val result = AcquirerAuthResult(
            approved = true,
            status = "AUTHORIZED",
            acquirerReference = reference(paymentId),
            rrn = rrn(paymentId),
            authCode = authCode(paymentId),
            errorCode = properties.mock.approveCode,
            durationMs = latency
        )
        return Mono.just(result).delayElement(java.time.Duration.ofMillis(latency))
    }

    override fun cancel(paymentId: UUID, amount: BigDecimal): Mono<AcquirerAuthResult> {
        val latency = properties.mock.latencyMs
        val result = AcquirerAuthResult(
            approved = true,
            status = "REVERSED",
            acquirerReference = reference(paymentId),
            rrn = rrn(paymentId),
            authCode = authCode(paymentId),
            errorCode = properties.mock.approveCode,
            durationMs = latency
        )
        return Mono.just(result).delayElement(java.time.Duration.ofMillis(latency))
    }

    override fun status(paymentId: UUID, acquirerReference: String?): Mono<AcquirerAuthResult> {
        val latency = properties.mock.latencyMs
        val result = AcquirerAuthResult(
            approved = true,
            status = "AUTHORIZED",
            acquirerReference = acquirerReference ?: reference(paymentId),
            rrn = rrn(paymentId),
            authCode = authCode(paymentId),
            errorCode = properties.mock.approveCode,
            durationMs = latency
        )
        return Mono.just(result).delayElement(java.time.Duration.ofMillis(latency))
    }

    private fun runWithLatency(scenario: AcquirerScenario, request: AcquirerAuthorizeRequest): Mono<AcquirerAuthResult> {
        val latency = properties.mock.latencyMs
        val result = buildResult(scenario, request.paymentId, latency)
        log.info(
            "MockAcquirer: paymentId={}, amount={}, scenario={}, status={}, ref={}",
            request.paymentId, request.amount, scenario, result.status, result.acquirerReference
        )
        val mono = Mono.just(result)
        return if (latency > 0) mono.delayElement(java.time.Duration.ofMillis(latency)) else mono
    }

    private fun buildResult(scenario: AcquirerScenario, paymentId: UUID, latency: Long): AcquirerAuthResult =
        when (scenario) {
            AcquirerScenario.APPROVE -> AcquirerAuthResult(
                approved = true,
                status = "AUTHORIZED",
                acquirerReference = reference(paymentId),
                rrn = rrn(paymentId),
                authCode = authCode(paymentId),
                errorCode = properties.mock.approveCode,
                durationMs = latency
            )

            AcquirerScenario.DECLINE -> AcquirerAuthResult(
                approved = false,
                status = "DECLINED",
                errorCode = properties.mock.declineCode,
                errorMessage = "Mock: declined by issuer",
                durationMs = latency
            )

            AcquirerScenario.TIMEOUT -> AcquirerAuthResult(
                approved = false,
                status = "TIMEOUT",
                errorCode = "TIMEOUT",
                errorMessage = "Mock: acquirer timeout",
                durationMs = latency
            )

            AcquirerScenario.DEFER -> AcquirerAuthResult(
                approved = false,
                status = "DEFERRED",
                errorCode = properties.mock.deferCode,
                errorMessage = "Mock: deferred authorization",
                deferred = true,
                durationMs = latency
            )

            AcquirerScenario.REAUTH_REQUIRED -> AcquirerAuthResult(
                approved = false,
                status = "REAUTH_REQUIRED",
                errorCode = properties.mock.reauthCode,
                errorMessage = "Mock: re-authorization required",
                durationMs = latency
            )

            AcquirerScenario.DUPLICATE -> AcquirerAuthResult(
                approved = false,
                status = "DUPLICATE",
                errorCode = "94",
                errorMessage = "Mock: duplicate transmission",
                durationMs = latency
            )
        }

    private fun minorUnits(amount: BigDecimal): Int =
        amount.setScale(2, RoundingMode.HALF_UP)
            .movePointRight(2)
            .toBigInteger()
            .mod(java.math.BigInteger.valueOf(100))
            .toInt()

    private fun reference(paymentId: UUID): String = "MOCK-" + paymentId.toString().take(18)

    private fun rrn(paymentId: UUID): String =
        (paymentId.mostSignificantBits.absoluteValue % 1_000_000_000_000L).toString().padStart(12, '0')

    private fun authCode(paymentId: UUID): String =
        (paymentId.leastSignificantBits.absoluteValue % 1_000_000L).toString().padStart(6, '0')
}

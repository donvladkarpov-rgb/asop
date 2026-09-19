package ru.asop.payment.acquirer

import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicReference

/**
 * Runtime-override сценария мока (для e2e-тестов):
 * `POST /api/v1/payments/mock/scenario` задаёт «следующий ответ», который
 * потребляется один раз и сбрасывается.
 */
@Component
class MockScenarioStore {

    private val next = AtomicReference<AcquirerScenario?>(null)
    private val current = AtomicReference<AcquirerScenario>(AcquirerScenario.APPROVE)

    fun setNext(scenario: AcquirerScenario) {
        next.set(scenario)
    }

    /** Потребляет override (одноразово) или возвращает null. */
    fun consumeOverride(): AcquirerScenario? {
        val value = next.getAndSet(null)
        if (value != null) current.set(value)
        return value
    }

    fun current(): AcquirerScenario = current.get()

    fun clear() {
        next.set(null)
        current.set(AcquirerScenario.APPROVE)
    }
}

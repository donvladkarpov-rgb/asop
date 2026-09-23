package ru.asop.payment.controller

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono
import ru.asop.payment.acquirer.AcquirerScenario
import ru.asop.payment.acquirer.MockScenarioStore

/**
 * Управление моком эквайринга для e2e-тестов (prompt_016 §3.1.9).
 * Доступен только при `asop.acquirer.provider=mock`.
 */
@RestController
@RequestMapping("/api/v1/payments/mock")
@ConditionalOnProperty(name = ["asop.acquirer.provider"], havingValue = "mock", matchIfMissing = true)
class MockAcquirerController(
    private val scenarioStore: MockScenarioStore
) {

    @GetMapping("/scenario")
    fun current(): Mono<MockScenarioResponse> =
        Mono.just(MockScenarioResponse(current = scenarioStore.current().name))

    /** Задать одноразовый сценарий следующей авторизации. */
    @PostMapping("/scenario")
    fun setNext(@RequestBody request: MockScenarioRequest): Mono<MockScenarioResponse> {
        scenarioStore.setNext(AcquirerScenario.valueOf(request.scenario.uppercase()))
        return Mono.just(
            MockScenarioResponse(current = scenarioStore.current().name, next = request.scenario.uppercase())
        )
    }

    @PostMapping("/reset")
    fun reset(): Mono<MockScenarioResponse> {
        scenarioStore.clear()
        return Mono.just(MockScenarioResponse(current = scenarioStore.current().name))
    }
}

data class MockScenarioRequest(val scenario: String)

data class MockScenarioResponse(val current: String, val next: String? = null)

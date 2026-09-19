package ru.asop.payment.config

import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import ru.asop.payment.acquirer.AcquirerGateway
import ru.asop.payment.acquirer.AcquirerProperties
import ru.asop.payment.acquirer.MockAcquirerAdapter
import ru.asop.payment.acquirer.VtbSirposAdapter

/**
 * Выбор реализации [AcquirerGateway] по `asop.acquirer.provider` (`mock` | `vtb`).
 */
@Configuration
class AcquirerConfig {

    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    @Primary
    fun acquirerGateway(
        properties: AcquirerProperties,
        mock: MockAcquirerAdapter,
        vtb: VtbSirposAdapter
    ): AcquirerGateway {
        val selected = when (properties.provider.lowercase()) {
            "vtb" -> vtb
            "mock" -> mock
            else -> {
                log.warn("Unknown acquirer provider '{}', falling back to MOCK", properties.provider)
                mock
            }
        }
        log.info("AcquirerGateway selected: {} (provider config='{}')", selected.provider, properties.provider)
        return selected
    }
}

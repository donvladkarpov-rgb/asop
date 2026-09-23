package ru.asop.payment.acquirer

import org.springframework.stereotype.Component
import reactor.core.publisher.Mono
import java.math.BigDecimal
import java.util.UUID

/**
 * Адаптер эквайера ВТБ (SIRPOS).
 *
 * TODO(prompt_016 §3.1.4): интерфейс TBD (транспорт/протокол/библиотека/ключи).
 * Легаси: raw TCP к SIRPOS, закрытая `com.transcard:multicard-client:1.0.3` (недоступна),
 * коды `allowReAuthErrCodes=[76,95,82]`, `allowReDefferErrCodes=[74,811]`, `tk_stop_list`.
 *
 * До получения интерфейса используется [MockAcquirerAdapter] (`ASOP_ACQUIRER_PROVIDER=mock`).
 */
@Component
class VtbSirposAdapter : AcquirerGateway {

    override val provider: String = "VTB"

    override fun authorize(request: AcquirerAuthorizeRequest): Mono<AcquirerAuthResult> =
        Mono.error(IllegalStateException("VTB/SIRPOS acquirer interface is not configured (TBD, prompt_016 §3.1.4)"))

    override fun reauthorize(paymentId: UUID, amount: BigDecimal): Mono<AcquirerAuthResult> =
        Mono.error(IllegalStateException("VTB/SIRPOS acquirer interface is not configured (TBD, prompt_016 §3.1.4)"))

    override fun cancel(paymentId: UUID, amount: BigDecimal): Mono<AcquirerAuthResult> =
        Mono.error(IllegalStateException("VTB/SIRPOS acquirer interface is not configured (TBD, prompt_016 §3.1.4)"))

    override fun refund(paymentId: UUID, amount: BigDecimal): Mono<AcquirerAuthResult> =
        Mono.error(IllegalStateException("VTB/SIRPOS acquirer interface is not configured (TBD, prompt_016 §3.1.4)"))

    override fun status(paymentId: UUID, acquirerReference: String?): Mono<AcquirerAuthResult> =
        Mono.error(IllegalStateException("VTB/SIRPOS acquirer interface is not configured (TBD, prompt_016 §3.1.4)"))
}

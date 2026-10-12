package ru.asop.fiscal.scheduler

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import ru.asop.fiscal.service.FiscalReceiptService

@Component
class FiscalRetryScheduler(
    private val service: FiscalReceiptService,
    @Value("\${fiscal.retry-enabled:true}") private val enabled: Boolean
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${fiscal.retry-interval-ms:30000}")
    fun retry() {
        if (!enabled) return
        service.retryDue()
            .doOnNext { if (it > 0) log.info("Fiscal retry processed {}", it) }
            .subscribe({ }, { e -> log.error("Fiscal retry failed: {}", e.message, e) })
    }
}

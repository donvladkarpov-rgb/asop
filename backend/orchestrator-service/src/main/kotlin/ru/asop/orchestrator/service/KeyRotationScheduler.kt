package ru.asop.orchestrator.service

import org.slf4j.LoggerFactory
import org.springframework.scheduling.TriggerContext
import org.springframework.scheduling.annotation.SchedulingConfigurer
import org.springframework.scheduling.config.ScheduledTaskRegistrar
import org.springframework.scheduling.support.CronTrigger
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import java.time.Duration

/**
 * Ротация 3DES-ключей: периодически вызывает crypto-service `generate`
 * и вставляет новую запись через admin-service `POST /api/v1/three-des-keys`.
 * Параметры (cron, enabled) перечитываются из base-строки config-params при каждом
 * срабатывании (в т.ч. для расчёта следующего запуска); enabled=false останавливает.
 *
 * Намеренно не использует @Scheduled: cron динамический (из БД), и паттерн
 * SchedulingConfigurer + CronTrigger даёт перечитывание cron на каждый тик.
 */
@Component
class KeyRotationScheduler(
    private val threeDesKeyService: ThreeDesKeyService,
    private val masterWebClient: WebClient
) : SchedulingConfigurer {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun configureTasks(taskRegistrar: ScheduledTaskRegistrar) {
        taskRegistrar.addTriggerTask(
            { rotate() },
            { ctx: TriggerContext ->
                // Timeout 10 сек чтобы не блокировать однопоточный TaskScheduler
                // при недоступности admin-service. При ошибке/таймауте — fallback
                // на дефолтный cron из application.yml (не null — иначе шедулер умрёт).
                val cron: String = try {
                    threeDesKeyService.readBaseConfig()
                        .map { base -> threeDesKeyService.rotationCron(base) }
                        .block(Duration.ofSeconds(10))
                } catch (e: Exception) {
                    log.warn("3des rotation: base config fetch failed, using default cron: {}", e.message)
                    null
                } ?: threeDesKeyService.defaultCron()
                CronTrigger(cron).nextExecution(ctx)
            }
        )
    }

    private fun rotate() {
        threeDesKeyService.readBaseConfig()
            .flatMap { base ->
                if (!threeDesKeyService.rotationEnabled(base)) {
                    log.debug("3des key rotation disabled by config, skipping")
                    Mono.empty()
                } else {
                    generate()
                }
            }
            .subscribe(
                { log.info("3DES key rotation: new key generated") },
                { err -> log.error("3DES key rotation failed", err) }
            )
    }

    private fun generate(): Mono<Void> {
        return masterWebClient.post()
            .uri("https://admin-service:8091/api/v1/three-des-keys")
            .retrieve()
            .bodyToMono(com.fasterxml.jackson.databind.JsonNode::class.java)
            .then()
            .onErrorResume { err ->
                log.error("3DES key rotation generate failed: {}", err.message)
                Mono.error(err)
            }
    }
}
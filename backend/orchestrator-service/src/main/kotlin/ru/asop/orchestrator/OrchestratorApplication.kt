package ru.asop.orchestrator

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication(scanBasePackages = ["ru.asop"])
@EnableScheduling
@ConfigurationPropertiesScan
class OrchestratorApplication

fun main(args: Array<String>) {
    runApplication<OrchestratorApplication>(*args)
}

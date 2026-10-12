package ru.asop.fiscal

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@EnableScheduling
@SpringBootApplication(scanBasePackages = ["ru.asop"])
@ConfigurationPropertiesScan
class FiscalServiceApplication

fun main(args: Array<String>) {
    runApplication<FiscalServiceApplication>(*args)
}

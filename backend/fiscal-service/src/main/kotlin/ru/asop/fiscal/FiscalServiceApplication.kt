package ru.asop.fiscal

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication(scanBasePackages = ["ru.asop"])
@ConfigurationPropertiesScan
class FiscalServiceApplication

fun main(args: Array<String>) {
    runApplication<FiscalServiceApplication>(*args)
}

package ru.asop.debt

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication(scanBasePackages = ["ru.asop"])
@ConfigurationPropertiesScan
class DebtServiceApplication

fun main(args: Array<String>) {
    runApplication<DebtServiceApplication>(*args)
}

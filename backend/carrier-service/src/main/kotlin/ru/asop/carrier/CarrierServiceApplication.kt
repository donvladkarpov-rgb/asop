package ru.asop.carrier

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication(scanBasePackages = ["ru.asop"])
@ConfigurationPropertiesScan
class CarrierServiceApplication

fun main(args: Array<String>) {
    runApplication<CarrierServiceApplication>(*args)
}
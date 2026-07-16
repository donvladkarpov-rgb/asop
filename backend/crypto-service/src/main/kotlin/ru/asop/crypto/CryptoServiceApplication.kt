package ru.asop.crypto

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication(scanBasePackages = ["ru.asop.crypto"])
@ConfigurationPropertiesScan
class CryptoServiceApplication

fun main(args: Array<String>) {
    runApplication<CryptoServiceApplication>(*args)
}
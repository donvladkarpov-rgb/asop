package ru.asop.session

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication(scanBasePackages = ["ru.asop"])
@ConfigurationPropertiesScan
class SessionServiceApplication

fun main(args: Array<String>) {
    runApplication<SessionServiceApplication>(*args)
}
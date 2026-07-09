package ru.asop.terminal

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication(scanBasePackages = ["ru.asop"])
@ConfigurationPropertiesScan
class TerminalServiceApplication

fun main(args: Array<String>) {
    runApplication<TerminalServiceApplication>(*args)
}
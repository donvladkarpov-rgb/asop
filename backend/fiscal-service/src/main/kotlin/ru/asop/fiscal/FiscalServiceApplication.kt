package ru.asop.fiscal

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class FiscalServiceApplication

fun main(args: Array<String>) {
    runApplication<FiscalServiceApplication>(*args)
}
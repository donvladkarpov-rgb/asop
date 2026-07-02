package ru.asop.debt

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class DebtServiceApplication

fun main(args: Array<String>) {
    runApplication<DebtServiceApplication>(*args)
}
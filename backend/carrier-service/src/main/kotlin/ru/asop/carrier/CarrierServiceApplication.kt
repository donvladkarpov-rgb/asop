package ru.asop.carrier

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class CarrierServiceApplication

fun main(args: Array<String>) {
    runApplication<CarrierServiceApplication>(*args)
}
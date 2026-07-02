package ru.asop.session

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class SessionServiceApplication

fun main(args: Array<String>) {
    runApplication<SessionServiceApplication>(*args)
}
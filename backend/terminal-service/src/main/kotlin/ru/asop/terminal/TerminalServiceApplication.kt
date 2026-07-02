package ru.asop.terminal

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class TerminalServiceApplication

fun main(args: Array<String>) {
    runApplication<TerminalServiceApplication>(*args)
}
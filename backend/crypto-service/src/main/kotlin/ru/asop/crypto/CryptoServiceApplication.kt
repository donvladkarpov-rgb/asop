package ru.asop.crypto

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication(scanBasePackages = ["ru.asop.crypto"])
class CryptoServiceApplication

fun main(args: Array<String>) {
    runApplication<CryptoServiceApplication>(*args)
}
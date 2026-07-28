package ru.asop.gateway

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration
import org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication(
    scanBasePackages = ["ru.asop"],
    exclude = [
        RedisAutoConfiguration::class,
        RedisReactiveAutoConfiguration::class
    ]
)
@ConfigurationPropertiesScan
class GatewayApplication

fun main(args: Array<String>) {
    runApplication<GatewayApplication>(*args)
}
package ru.asop.route

import io.swagger.v3.oas.annotations.OpenAPIDefinition
import io.swagger.v3.oas.annotations.info.Info
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan("ru.asop.route", "ru.asop.common")
@OpenAPIDefinition(
    info = Info(
        title = "ASOP Route & Path Service API",
        version = "0.1.0",
        description = "CRUD API for fare zones, transport stops, routes, paths, schedule, " +
            "path services/discounts/benefits, and vehicles."
    )
)
class RouteServiceApplication

fun main(args: Array<String>) {
    runApplication<RouteServiceApplication>(*args)
}

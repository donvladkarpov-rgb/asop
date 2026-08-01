package ru.asop.gateway.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.function.client.WebClient

@Configuration
class MinioWebClientConfig {
    @Bean
    fun minioWebClient(): WebClient = WebClient.builder().build()
}

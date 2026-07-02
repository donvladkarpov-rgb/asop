package ru.asop.gateway.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.security.web.authentication.preauth.x509.X509PrincipalExtractor
import org.springframework.security.web.server.SecurityWebFilterChain
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers
import java.security.cert.X509Certificate

@Configuration
@EnableWebFluxSecurity
class SecurityConfig {

    // Chain 1: Терминалы (mTLS).
    // Обрабатывает ТОЛЬКО пути /api/v1/terminals/* и /api/v1/sync/*
    // Аутентификация — по клиентскому X.509 сертификату.
    @Bean
    @Order(1)
    fun terminalSecurityFilterChain(http: ServerHttpSecurity): SecurityWebFilterChain {
        return http
            .securityMatcher(ServerWebExchangeMatchers.pathMatchers(
                "/api/v1/terminals/**",
                "/api/v1/sync/**"
            ))
            .csrf { it.disable() }
            .authorizeExchange { exchanges ->
                exchanges.anyExchange().authenticated()
            }
            .x509 { x509 ->
                x509.principalExtractor(TerminalPrincipalExtractor())
            }
            .build()
    }

    // Chain 2: Веб-клиенты (JWT через Keycloak).
    // Обрабатывает ВСЕ ОСТАЛЬНЫЕ пути.
    @Bean
    @Order(2)
    fun webSecurityFilterChain(http: ServerHttpSecurity): SecurityWebFilterChain {
        return http
            .csrf { it.disable() }
            .formLogin { it.disable() }
            .httpBasic { it.disable() }
            .authorizeExchange { exchanges ->
                exchanges
                    .pathMatchers("/actuator/health", "/actuator/info").permitAll()
                    .pathMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                    .anyExchange().authenticated()
            }
            .oauth2ResourceServer { oauth2 ->
                oauth2.jwt { }
            }
            .build()
    }
}

// Извлекает terminalSerial из CN клиентского сертификата.
// DN формат: "CN=TERM-001, OU=carrier-123, O=ASOP"
// Результат: "TERM-001"
class TerminalPrincipalExtractor : X509PrincipalExtractor {
    override fun extractPrincipal(x509Certificate: X509Certificate): Any {
        val subject = x509Certificate.subjectX500Principal.name
        val cn = subject.split(",")
            .map { it.trim() }
            .firstOrNull { it.startsWith("CN=") }
            ?.substringAfter("CN=")

        if (cn == null) {
            throw UsernameNotFoundException("CN not found in certificate subject: $subject")
        }
        return cn
    }
}
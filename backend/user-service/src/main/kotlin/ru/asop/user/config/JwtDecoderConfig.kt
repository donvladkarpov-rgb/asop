package ru.asop.user.config

import com.nimbusds.jose.proc.JWKSecurityContext
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.proc.BadJWTException
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import java.util.Date

@Configuration
class JwtDecoderConfig {

    @Value("\${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}")
    private lateinit var jwkSetUri: String

    @Bean
    fun reactiveJwtDecoder(): ReactiveJwtDecoder {
        val claimsVerifier = object : DefaultJWTClaimsVerifier<JWKSecurityContext>() {
            override fun verify(claimsSet: JWTClaimsSet, context: JWKSecurityContext?) {
                val now = Date()
                val exp = claimsSet.expirationTime
                if (exp != null && exp.before(now)) {
                    throw BadJWTException("JWT token has expired")
                }
                val nbf = claimsSet.notBeforeTime
                if (nbf != null && nbf.after(now)) {
                    throw BadJWTException("JWT token is not yet valid (nbf)")
                }
            }
        }

        return NimbusReactiveJwtDecoder.withJwkSetUri(jwkSetUri)
            .jwtProcessorCustomizer { p ->
                p.jwtClaimsSetVerifier = claimsVerifier
            }
            .build()
    }
}

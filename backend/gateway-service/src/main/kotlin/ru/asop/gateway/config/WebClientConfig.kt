package ru.asop.gateway.config

import io.netty.handler.ssl.SslContextBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import java.io.FileInputStream
import java.security.KeyStore
import javax.net.ssl.TrustManagerFactory

@Configuration
class WebClientConfig {

    @Bean
    fun proxyWebClient(): WebClient = sslWebClient()

    @Bean
    fun keycloakWebClient(): WebClient = sslWebClient()

    private fun sslWebClient(): WebClient {
        val trustStorePath = System.getenv("TRUSTSTORE_PATH") ?: "classpath:truststore.p12"
        val trustStorePassword = System.getenv("TRUSTSTORE_PASSWORD") ?: "changeit"

        val trustStore = KeyStore.getInstance("PKCS12")
        if (trustStorePath.startsWith("classpath:")) {
            val resource = javaClass.getResourceAsStream(trustStorePath.substringAfter("classpath:"))
                ?: throw IllegalStateException("Truststore not found on classpath: ${trustStorePath.substringAfter("classpath:")}")
            resource.use { trustStore.load(it, trustStorePassword.toCharArray()) }
        } else {
            FileInputStream(trustStorePath.removePrefix("file:")).use { trustStore.load(it, trustStorePassword.toCharArray()) }
        }

        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(trustStore)

        val sslContext = SslContextBuilder.forClient()
            .trustManager(tmf)
            .build()

        val httpClient = HttpClient.create().secure { spec -> spec.sslContext(sslContext) }

        return WebClient.builder()
            .clientConnector(ReactorClientHttpConnector(httpClient))
            .codecs { it.defaultCodecs().maxInMemorySize(16 * 1024 * 1024) }
            .build()
    }
}

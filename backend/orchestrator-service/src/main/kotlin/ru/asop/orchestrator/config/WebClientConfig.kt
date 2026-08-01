package ru.asop.orchestrator.config

import io.netty.handler.ssl.SslContextBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import reactor.netty.tcp.SslProvider
import java.io.FileInputStream
import java.security.KeyStore
import javax.net.ssl.TrustManagerFactory

@Configuration
class WebClientConfig {

    @Bean
    fun masterWebClient(): WebClient {
        val trustStorePath = System.getenv("TRUSTSTORE_PATH") ?: "/tmp/certs/truststore.p12"
        val trustStorePassword = System.getenv("TRUSTSTORE_PASSWORD") ?: "changeit"

        val trustStore = KeyStore.getInstance("PKCS12")
        FileInputStream(trustStorePath.removePrefix("file:")).use { trustStore.load(it, trustStorePassword.toCharArray()) }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(trustStore)

        val sslContextBuilder = SslContextBuilder.forClient().trustManager(tmf)
        val httpClient = HttpClient.create().secure { spec ->
            spec.sslContext(sslContextBuilder)
                .defaultConfiguration(SslProvider.DefaultConfigurationType.NONE)
                .build()
        }

        return WebClient.builder()
            .clientConnector(ReactorClientHttpConnector(httpClient))
            .codecs { it.defaultCodecs().maxInMemorySize(64 * 1024 * 1024) }
            .build()
    }
}

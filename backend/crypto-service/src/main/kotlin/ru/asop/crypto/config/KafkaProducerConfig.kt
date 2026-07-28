package ru.asop.crypto.config

import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringSerializer
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.core.reactive.ReactiveKafkaProducerTemplate
import org.springframework.kafka.support.serializer.JsonSerializer
import reactor.kafka.sender.SenderOptions

@Configuration
class KafkaProducerConfig {

    @Value("\${spring.kafka.bootstrap-servers}")
    private lateinit var bootstrapServers: String

    @Value("\${spring.kafka.ssl.trust-store-location:}")
    private lateinit var trustStoreLocation: String

    @Value("\${spring.kafka.ssl.trust-store-password:}")
    private lateinit var trustStorePassword: String

    @Value("\${spring.kafka.ssl.key-store-location:}")
    private lateinit var keyStoreLocation: String

    @Value("\${spring.kafka.ssl.key-store-password:}")
    private lateinit var keyStorePassword: String

    @Bean
    fun reactiveKafkaProducerTemplate(): ReactiveKafkaProducerTemplate<String, Any> {
        val props = HashMap<String, Any>().apply {
            put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
            put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java)
            put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer::class.java)
            put(ProducerConfig.ACKS_CONFIG, "all")
            put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true)
            put(ProducerConfig.RETRIES_CONFIG, 3)
            put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5)
            put(ProducerConfig.CLIENT_ID_CONFIG, "crypto-service-producer")
            put(JsonSerializer.ADD_TYPE_INFO_HEADERS, false)
            put("security.protocol", "SSL")
            put("ssl.truststore.location", trustStoreLocation.removePrefix("file:"))
            put("ssl.truststore.password", trustStorePassword)
            put("ssl.endpoint.identification.algorithm", "")
            if (keyStoreLocation.isNotBlank()) {
                put("ssl.keystore.location", keyStoreLocation.removePrefix("file:"))
                put("ssl.keystore.password", keyStorePassword)
            }
        }
        val senderOptions = SenderOptions.create<String, Any>(props)
        return ReactiveKafkaProducerTemplate(senderOptions)
    }
}
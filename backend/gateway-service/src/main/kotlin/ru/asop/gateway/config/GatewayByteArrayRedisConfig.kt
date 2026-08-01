package ru.asop.gateway.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory
import org.springframework.data.redis.core.ReactiveRedisTemplate
import org.springframework.data.redis.serializer.RedisSerializationContext
import org.springframework.data.redis.serializer.RedisSerializer
import org.springframework.data.redis.serializer.StringRedisSerializer

/**
 * Бинарный Redis-шаблон для чтения chunk-ов (`asop:event:{eventId}:chunk:{n}`)
 * и meta (`asop:event:{eventId}:meta`), записанных оркестратором (Protobuf bytes).
 */
@Configuration
class GatewayByteArrayRedisConfig {

    @Bean
    fun chunkRedisTemplate(connectionFactory: ReactiveRedisConnectionFactory): ReactiveRedisTemplate<String, ByteArray> {
        val byteArraySerializer: RedisSerializer<ByteArray> = RedisSerializer.byteArray()
        val context = RedisSerializationContext
            .newSerializationContext<String, ByteArray>(StringRedisSerializer())
            .value(byteArraySerializer)
            .build()
        return ReactiveRedisTemplate(connectionFactory, context)
    }
}

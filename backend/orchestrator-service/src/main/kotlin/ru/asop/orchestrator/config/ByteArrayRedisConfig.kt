package ru.asop.orchestrator.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory
import org.springframework.data.redis.core.ReactiveRedisTemplate
import org.springframework.data.redis.serializer.RedisSerializationContext
import org.springframework.data.redis.serializer.RedisSerializer
import org.springframework.data.redis.serializer.StringRedisSerializer

/**
 * Бинарный Redis-шаблон для chunk-ов (`asop:event:{eventId}:chunk:{n}`) и meta.
 * Ключи — строки, значения — raw bytes (Protobuf). Симметрично gateway-читалке.
 */
@Configuration
class ByteArrayRedisConfig {

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

package ru.asop.orchestrator.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
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

    // Spring Boot 3.x не читает spring.redis.* — задаём фабрику из env явно,
    // как в gateway (ReactiveRedisConfig). Иначе host по умолчанию localhost.
    @Bean
    @org.springframework.context.annotation.Primary
    fun reactiveRedisConnectionFactory(): ReactiveRedisConnectionFactory {
        val redisHost = System.getenv("REDIS_HOST") ?: "localhost"
        val redisPort = (System.getenv("REDIS_PORT") ?: "6379").toInt()
        return LettuceConnectionFactory(RedisStandaloneConfiguration(redisHost, redisPort))
    }

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

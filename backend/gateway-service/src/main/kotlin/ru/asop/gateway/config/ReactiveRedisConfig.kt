package ru.asop.gateway.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.ReactiveStringRedisTemplate

@Configuration(proxyBeanMethods = false)
class ReactiveRedisConfig {

    private val redisHost: String by lazy { System.getenv("REDIS_HOST") ?: "localhost" }
    private val redisPort: Int by lazy { (System.getenv("REDIS_PORT") ?: "6379").toInt() }

    @Bean
    @org.springframework.context.annotation.Primary
    fun reactiveRedisConnectionFactory(): ReactiveRedisConnectionFactory {
        val config = RedisStandaloneConfiguration(redisHost, redisPort)
        return LettuceConnectionFactory(config)
    }

    @Bean
    fun reactiveStringRedisTemplate(factory: ReactiveRedisConnectionFactory): ReactiveStringRedisTemplate {
        return ReactiveStringRedisTemplate(factory)
    }
}

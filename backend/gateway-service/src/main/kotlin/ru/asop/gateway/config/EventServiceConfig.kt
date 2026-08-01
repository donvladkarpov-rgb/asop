package ru.asop.gateway.config

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import ru.asop.common.event.EventService

/**
 * EventService живёт в asop-common без @Service (иначе его сканировали бы
 * все сервисы и падали без Redis). Определяем bean локально — gateway имеет
 * spring-boot-starter-data-redis-reactive и auto-конфигурированный
 * ReactiveStringRedisTemplate.
 */
@Configuration
class EventServiceConfig {

    @Bean
    fun eventService(
        redis: ReactiveStringRedisTemplate,
        objectMapper: ObjectMapper
    ): EventService = EventService(redis, objectMapper)
}

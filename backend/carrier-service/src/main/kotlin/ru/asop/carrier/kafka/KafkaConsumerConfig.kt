package ru.asop.carrier.kafka

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "asop.kafka.topics")
data class KafkaTopicProperties(
    var carrierCommands: String = "asop.carrier.commands"
)

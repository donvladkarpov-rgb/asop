package ru.asop.orchestrator.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "asop")
data class OrchestratorProperties(
    val kafka: Kafka = Kafka(),
    val s3: S3 = S3(),
    val purge: Purge = Purge()
) {
    data class Kafka(
        val topics: Topics = Topics()
    ) {
        data class Topics(
            val deltaCommands: String = "asop.delta.commands",
            val deltaFullCommands: String = "asop.delta.full.commands"
        )
    }

    data class S3(
        val endpoint: String = "http://minio:9000",
        val accessKey: String = "asop",
        val secretKey: String = "asop-secret",
        val bucket: String = "asop-sync"
    )

    data class Purge(
        val enabled: Boolean = true,
        val intervalMs: Long = 3_600_000,
        val retentionMonths: Int = 6
    )
}

package ru.asop.orchestrator.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import java.net.URI

@Configuration
class S3Config {

    @Bean
    fun s3Client(props: OrchestratorProperties): S3Client {
        return S3Client.builder()
            .endpointOverride(URI.create(props.s3.endpoint))
            .region(Region.of("us-east-1"))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(props.s3.accessKey, props.s3.secretKey)))
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
            .build()
    }
}

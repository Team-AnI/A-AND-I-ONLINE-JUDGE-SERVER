package com.aandiclub.online.judge.config

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.services.sns.SnsClient
import software.amazon.awssdk.services.sqs.SqsClient

@ConfigurationProperties(prefix = "judge.problem-events")
data class ProblemEventProperties(
    val enabled: Boolean = false,
    val queueUrl: String = "",
    val waitTimeSeconds: Int = 20,
    val maxMessages: Int = 10,
    val publishEnabled: Boolean = false,
    val topicArn: String = "",
)

@ConfigurationProperties(prefix = "judge.user-events")
data class UserEventProperties(
    val enabled: Boolean = false,
    val queueUrl: String = "",
    val waitTimeSeconds: Int = 20,
    val maxMessages: Int = 10,
)

@ConfigurationProperties(prefix = "judge.submission-events")
data class SubmissionEventProperties(
    val publishEnabled: Boolean = false,
    val topicArn: String = "",
)

@Configuration
class ProblemEventConfig {

    @Bean
    @ConditionalOnProperty(
        prefix = "judge.problem-events",
        name = ["enabled"],
        havingValue = "true",
        matchIfMissing = false
    )
    fun problemEventSqsClient(): SqsClient = SqsClient.create()

    @Bean
    @ConditionalOnProperty(
        prefix = "judge.user-events",
        name = ["enabled"],
        havingValue = "true",
        matchIfMissing = false
    )
    fun userEventSqsClient(): SqsClient = SqsClient.create()

    @Bean("problemSnsClient")
    @ConditionalOnProperty(prefix = "judge.problem-events", name = ["publish-enabled"], havingValue = "true")
    fun problemSnsClient(): SnsClient = SnsClient.create()

    @Bean("submissionSnsClient")
    @ConditionalOnProperty(prefix = "judge.submission-events", name = ["publish-enabled"], havingValue = "true")
    fun submissionSnsClient(): SnsClient = SnsClient.create()
}

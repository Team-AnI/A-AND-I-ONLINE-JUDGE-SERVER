package com.aandiclub.online.judge.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "judge.submission")
data class SubmissionProperties(
    val dedupTtlMinutes: Long = 5,
)

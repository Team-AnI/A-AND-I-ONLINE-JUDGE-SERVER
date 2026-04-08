package com.aandiclub.online.judge.domain

import java.time.Instant

data class ProblemMetadata(
    val courseId: String? = null,
    val startAt: Instant? = null,
    val endAt: Instant? = null,
)

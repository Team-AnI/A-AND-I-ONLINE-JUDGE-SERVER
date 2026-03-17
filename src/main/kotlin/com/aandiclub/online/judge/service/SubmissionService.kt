package com.aandiclub.online.judge.service

import com.aandiclub.online.judge.api.dto.AdminSubmissionRecord
import com.aandiclub.online.judge.api.dto.MyProblemSubmissionRecord
import com.aandiclub.online.judge.api.dto.SubmissionAccepted
import com.aandiclub.online.judge.api.dto.SubmissionRequest
import com.aandiclub.online.judge.api.dto.SubmissionResult
import com.aandiclub.online.judge.domain.Submission
import com.aandiclub.online.judge.domain.SubmissionStatus
import com.aandiclub.online.judge.logging.SubmissionMdc
import com.aandiclub.online.judge.repository.SubmissionRepository
import com.aandiclub.online.judge.worker.JudgeWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.listener.ChannelTopic
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer
import org.springframework.http.HttpStatus
import org.springframework.http.codec.ServerSentEvent
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.ObjectMapper
import java.security.MessageDigest
import java.time.Duration

@Service
class SubmissionService(
    private val submissionRepository: SubmissionRepository,
    private val redisTemplate: ReactiveStringRedisTemplate,
    private val listenerContainer: ReactiveRedisMessageListenerContainer,
    private val judgeWorker: JudgeWorker,
    private val judgeWorkerScope: CoroutineScope,
    private val judgeWorkerSemaphore: Semaphore,
    private val objectMapper: ObjectMapper,
    private val submissionProperties: com.aandiclub.online.judge.config.SubmissionProperties,
) {
    private val log = LoggerFactory.getLogger(SubmissionService::class.java)

    private fun computeCodeHash(code: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(code.toByteArray())
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    suspend fun createSubmission(
        request: SubmissionRequest,
        submitterId: String,
    ): SubmissionAccepted {
        // Check for duplicate submission within 5 minutes
        val codeHash = computeCodeHash(request.code)
        val dedupKey = "submission:dedup:$submitterId:${request.problemId}:${request.language}:$codeHash"

        val cachedSubmissionId = redisTemplate.opsForValue()
            .get(dedupKey)
            .awaitSingleOrNull()

        if (cachedSubmissionId != null) {
            log.info("Duplicate submission detected, returning cached submissionId: {}", cachedSubmissionId)
            return SubmissionAccepted(
                submissionId = cachedSubmissionId,
                streamUrl = "/v1/submissions/$cachedSubmissionId/stream",
            )
        }

        // Create new submission
        val submission = Submission(
            submitterId = submitterId,
            submitterPublicCode = request.publicCode,
            problemId = request.problemId,
            language = request.language,
            code = request.code,
        )
        val saved = submissionRepository.save(submission).awaitSingle()
        SubmissionMdc.withSubmissionId(saved.id) {
            log.info("Submission created: language={}", saved.language)
        }

        // Cache the submission ID to prevent duplicates
        redisTemplate.opsForValue()
            .set(dedupKey, saved.id, Duration.ofMinutes(submissionProperties.dedupTtlMinutes))
            .awaitSingle()

        judgeWorkerScope.launch(Dispatchers.IO + SubmissionMdc.context(saved.id)) {
            judgeWorkerSemaphore.withPermit {
                runCatching { judgeWorker.execute(saved) }
                    .onFailure { ex ->
                        log.error("Judge worker failed", ex)
                        val errorPayload = objectMapper.writeValueAsString(
                            mapOf(
                                "event" to "error",
                                "submissionId" to saved.id,
                                "message" to (ex.message ?: "internal worker error"),
                            )
                        )
                        redisTemplate.convertAndSend("submission:${saved.id}", errorPayload).awaitSingle()
                    }
            }
        }
        return SubmissionAccepted(
            submissionId = saved.id,
            streamUrl = "/v1/submissions/${saved.id}/stream",
        )
    }

    suspend fun streamResults(
        submissionId: String,
        submitterId: String,
        isAdmin: Boolean,
    ): Flow<ServerSentEvent<String>> {
        requireAuthorizedSubmission(submissionId, submitterId, isAdmin)
        return listenerContainer.receive(ChannelTopic.of("submission:$submissionId"))
            .asFlow()
            .transformWhile { message ->
                val payload = message.message
                val event = when {
                    payload.contains("\"event\":\"done\"") -> "done"
                    payload.contains("\"event\":\"error\"") -> "error"
                    else -> "test_case_result"
                }
                emit(
                    ServerSentEvent.builder<String>()
                        .event(event)
                        .data(payload)
                        .build()
                )
                event != "done" && event != "error"
            }
    }

    suspend fun getResult(
        submissionId: String,
        submitterId: String,
        isAdmin: Boolean,
    ): SubmissionResult? {
        val submission = requireAuthorizedSubmission(submissionId, submitterId, isAdmin)
        if (submission.status == SubmissionStatus.PENDING || submission.status == SubmissionStatus.RUNNING) {
            return null
        }
        return SubmissionResult(
            submissionId = submission.id,
            status = submission.status,
            testCases = submission.testCaseResults,
        )
    }

    suspend fun getProblemSubmissions(
        problemId: String,
        submitterId: String,
    ): List<MyProblemSubmissionRecord> =
        submissionRepository.findAllBySubmitterIdAndProblemIdOrderByCreatedAtDesc(submitterId, problemId)
            .collectList()
            .awaitSingle()
            .map { submission ->
                MyProblemSubmissionRecord(
                    submissionId = submission.id,
                    problemId = submission.problemId,
                    language = submission.language,
                    status = submission.status,
                    testCases = submission.testCaseResults,
                    createdAt = submission.createdAt,
                    completedAt = submission.completedAt,
                )
            }

    suspend fun getAllSubmissions(): List<AdminSubmissionRecord> =
        submissionRepository.findAllByOrderByCreatedAtDesc()
            .collectList()
            .awaitSingle()
            .map { submission ->
                AdminSubmissionRecord(
                    submissionId = submission.id,
                    submitterId = submission.submitterId,
                    submitterPublicCode = submission.submitterPublicCode,
                    problemId = submission.problemId,
                    language = submission.language,
                    code = submission.code,
                    status = submission.status,
                    testCases = submission.testCaseResults,
                    createdAt = submission.createdAt,
                    completedAt = submission.completedAt,
                )
            }

    private suspend fun requireAuthorizedSubmission(
        submissionId: String,
        submitterId: String,
        isAdmin: Boolean,
    ): Submission {
        val submission = submissionRepository.findById(submissionId).awaitSingleOrNull()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Submission not found")
        if (!isAdmin && submission.submitterId != submitterId) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Submission does not belong to the requester")
        }
        return submission
    }
}

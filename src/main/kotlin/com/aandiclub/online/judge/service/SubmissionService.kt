package com.aandiclub.online.judge.service

import com.aandiclub.online.judge.api.dto.AdminSubmissionRecord
import com.aandiclub.online.judge.api.dto.MyProblemSubmissionRecord
import com.aandiclub.online.judge.api.dto.SubmissionAccepted
import com.aandiclub.online.judge.api.dto.SubmissionRequest
import com.aandiclub.online.judge.api.dto.SubmissionResult
import com.aandiclub.online.judge.api.v2.support.V2ErrorCode
import com.aandiclub.online.judge.domain.Submission
import com.aandiclub.online.judge.domain.SubmissionStatus
import com.aandiclub.online.judge.domain.TestCaseResult
import com.aandiclub.online.judge.logging.SubmissionMdc
import com.aandiclub.online.judge.logging.api.JudgeEventLogger
import com.aandiclub.online.judge.logging.api.JudgeEventType
import com.aandiclub.online.judge.repository.SubmissionRepository
import com.aandiclub.online.judge.sandbox.SandboxExecutionException
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
import tools.jackson.databind.node.ObjectNode
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId

@Service
class SubmissionService(
    private val submissionRepository: SubmissionRepository,
    private val userRepository: com.aandiclub.online.judge.repository.UserRepository,
    private val redisTemplate: ReactiveStringRedisTemplate,
    private val listenerContainer: ReactiveRedisMessageListenerContainer,
    private val judgeWorker: JudgeWorker,
    private val judgePerformanceMonitorService: JudgePerformanceMonitorService,
    private val judgeWorkerScope: CoroutineScope,
    private val judgeWorkerSemaphore: Semaphore,
    private val objectMapper: ObjectMapper,
    private val submissionProperties: com.aandiclub.online.judge.config.SubmissionProperties,
    private val judgeEventLogger: JudgeEventLogger? = null,
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
        traceId: String? = null,
    ): SubmissionAccepted {
        // Resolve User by publicCode
        val user = userRepository.findByPublicCode(request.publicCode).awaitSingleOrNull()
            ?: throw ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "User not found with publicCode: ${request.publicCode}"
            )

        // Check for duplicate submission within 5 minutes
        val codeHash = computeCodeHash(request.code)
        val dedupKey = "submission:dedup:${user.userId}:${request.problemId}:${request.language}:$codeHash"

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
            submitterId = user.userId,
            submitterPublicCode = request.publicCode,
            problemId = request.problemId,
            language = request.language,
            code = request.code,
        )
        val saved = submissionRepository.save(submission).awaitSingle()
        judgePerformanceMonitorService.onSubmissionAccepted(saved)
        judgeEventLogger?.event(
            eventType = JudgeEventType.JUDGE_REQUESTED,
            traceId = traceId,
            resourceId = saved.id,
            metadata = mapOf(
                "submissionId" to saved.id,
                "problemId" to saved.problemId,
                "language" to saved.language.name,
            ),
        )
        SubmissionMdc.withSubmissionId(saved.id) {
            log.info("Submission created: language={}", saved.language)
        }

        // Cache the submission ID to prevent duplicates
        redisTemplate.opsForValue()
            .set(dedupKey, saved.id, Duration.ofMinutes(submissionProperties.dedupTtlMinutes))
            .awaitSingle()

        judgeWorkerScope.launch(Dispatchers.IO + SubmissionMdc.context(saved.id)) {
            judgeWorkerSemaphore.withPermit {
                runCatching { judgeWorker.execute(saved, traceId = traceId) }
                    .onFailure { ex ->
                        log.error("Judge worker failed", ex)
                        if (ex !is SandboxExecutionException) {
                            judgeEventLogger?.eventError(
                                eventType = JudgeEventType.JUDGE_COMPLETED,
                                errorCode = V2ErrorCode.GRADING_FAILED,
                                throwable = ex,
                                traceId = traceId,
                                resourceId = saved.id,
                                metadata = mapOf(
                                    "submissionId" to saved.id,
                                    "problemId" to saved.problemId,
                                    "language" to saved.language.name,
                                ),
                            )
                        }
                        judgePerformanceMonitorService.onSubmissionFailed(saved.id)
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
                val data = if (event == "test_case_result") {
                    maskHiddenCasePayload(payload)
                } else {
                    payload
                }
                emit(
                    ServerSentEvent.builder<String>()
                        .event(event)
                        .data(data)
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
            testCases = maskHiddenCaseResults(submission.testCaseResults),
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
                    testCases = maskHiddenCaseResults(submission.testCaseResults),
                    createdAt = submission.createdAt.toKstOffsetDateTime(),
                    completedAt = submission.completedAt?.toKstOffsetDateTime(),
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
                    testCases = maskHiddenCaseResults(submission.testCaseResults),
                    createdAt = submission.createdAt.toKstOffsetDateTime(),
                    completedAt = submission.completedAt?.toKstOffsetDateTime(),
                )
            }

    private fun Instant.toKstOffsetDateTime(): OffsetDateTime =
        atZone(KST_ZONE_ID).toOffsetDateTime()

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

    private fun maskHiddenCasePayload(payload: String): String {
        val node = runCatching { objectMapper.readTree(payload) }.getOrNull()
            ?: return payload
        if (!node.isObject) return payload

        val maskedNode = (node as ObjectNode).deepCopy()
        maskedNode.put("output", HIDDEN_CASE_OUTPUT_MASK)
        return objectMapper.writeValueAsString(maskedNode)
    }

    private fun maskHiddenCaseResults(results: List<TestCaseResult>): List<TestCaseResult> =
        results.map { it.copy(output = HIDDEN_CASE_OUTPUT_MASK) }

    companion object {
        private val KST_ZONE_ID: ZoneId = ZoneId.of("Asia/Seoul")
        private const val HIDDEN_CASE_OUTPUT_MASK = "비공개"
    }
}

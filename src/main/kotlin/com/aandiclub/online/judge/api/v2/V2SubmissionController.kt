package com.aandiclub.online.judge.api.v2

import com.aandiclub.online.judge.api.requestAccess
import com.aandiclub.online.judge.api.v2.dto.V2SubmissionAcceptedData
import com.aandiclub.online.judge.api.v2.dto.V2SubmissionRequest
import com.aandiclub.online.judge.api.v2.dto.V2SubmissionResultData
import com.aandiclub.online.judge.api.v2.dto.V2SubmissionStreamEventData
import com.aandiclub.online.judge.api.v2.dto.toV1
import com.aandiclub.online.judge.api.v2.dto.toV2
import com.aandiclub.online.judge.api.v2.support.V2ApiResponse
import com.aandiclub.online.judge.api.v2.support.V2ApiResponses
import com.aandiclub.online.judge.api.v2.support.V2ErrorCode
import com.aandiclub.online.judge.api.v2.support.v2RequestContext
import com.aandiclub.online.judge.logging.SubmissionMdc
import com.aandiclub.online.judge.service.SubmissionService
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.codec.ServerSentEvent
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

@RestController
@RequestMapping("/v2/submissions")
@Tag(name = "Submissions V2", description = "A&I v2 submission APIs.")
class V2SubmissionController(
    private val submissionService: SubmissionService,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(V2SubmissionController::class.java)

    @PostMapping
    suspend fun submit(
        @Valid @RequestBody request: V2SubmissionRequest,
        exchange: ServerWebExchange,
    ): ResponseEntity<V2ApiResponse<V2SubmissionAcceptedData>> {
        val access = exchange.requestAccess()
        val context = exchange.v2RequestContext()
        val accepted = submissionService.createSubmission(request.toV1(), access.submitterId)
        val data = accepted.toV2(streamUrl = "/v2/submissions/${accepted.submissionId}/stream")
        SubmissionMdc.withSubmissionId(accepted.submissionId) {
            log.info(
                "V2 submission request accepted: submitterId={}, problemId={}, language={}, deviceOS={}, clientTimestamp={}",
                access.submitterId,
                request.problemId,
                request.language,
                context.deviceOS,
                context.timestamp,
            )
        }
        return ResponseEntity.accepted().body(V2ApiResponses.success(data))
    }

    @GetMapping("/{submissionId}/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    suspend fun streamResults(
        @PathVariable submissionId: String,
        exchange: ServerWebExchange,
    ): Flow<ServerSentEvent<String>> {
        val access = exchange.requestAccess()
        return submissionService.streamResults(submissionId, access.submitterId, access.isAdmin)
            .map { event ->
                val eventName = event.event() ?: "message"
                val payload = event.data().toJsonNode()
                val wrapped = objectMapper.writeValueAsString(
                    V2ApiResponses.success(
                        V2SubmissionStreamEventData(
                            event = eventName,
                            payload = payload,
                        )
                    )
                )
                ServerSentEvent.builder<String>()
                    .event(eventName)
                    .data(wrapped)
                    .build()
            }
    }

    @GetMapping("/{submissionId}")
    suspend fun getResult(
        @PathVariable submissionId: String,
        exchange: ServerWebExchange,
    ): ResponseEntity<V2ApiResponse<V2SubmissionResultData>> {
        val access = exchange.requestAccess()
        val result = submissionService.getResult(submissionId, access.submitterId, access.isAdmin)
            ?: return ResponseEntity.status(409).body(
                V2ApiResponses.error(
                    errorCode = V2ErrorCode.JUDGE_RESULT_NOT_READY,
                    message = "Submission result is not ready",
                    value = submissionId,
                    alert = "채점이 아직 완료되지 않았습니다.",
                )
            )
        return ResponseEntity.ok(V2ApiResponses.success(result.toV2()))
    }

    private fun String?.toJsonNode(): JsonNode =
        runCatching { objectMapper.readTree(this ?: "null") }
            .getOrElse { objectMapper.valueToTree(this) }
}

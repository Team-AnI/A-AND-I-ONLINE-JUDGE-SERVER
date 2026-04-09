package com.aandiclub.online.judge.api.v2

import com.aandiclub.online.judge.api.requestAccess
import com.aandiclub.online.judge.api.v2.dto.V2MyProblemSubmissionRecord
import com.aandiclub.online.judge.api.v2.dto.toV2
import com.aandiclub.online.judge.api.v2.support.V2ApiResponse
import com.aandiclub.online.judge.api.v2.support.V2ApiResponses
import com.aandiclub.online.judge.service.SubmissionService
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange

@RestController
@RequestMapping("/v2/problems")
@Tag(name = "Problem Submissions V2", description = "A&I v2 problem submission APIs.")
class V2ProblemSubmissionController(
    private val submissionService: SubmissionService,
) {
    @GetMapping("/{problemId}/submissions/me")
    suspend fun getMyProblemSubmissions(
        @PathVariable problemId: String,
        exchange: ServerWebExchange,
    ): ResponseEntity<V2ApiResponse<List<V2MyProblemSubmissionRecord>>> {
        val access = exchange.requestAccess()
        val records = submissionService.getProblemSubmissions(problemId, access.submitterId).map { it.toV2() }
        return ResponseEntity.ok(V2ApiResponses.success(records))
    }
}

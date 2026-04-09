package com.aandiclub.online.judge.api.v2

import com.aandiclub.online.judge.api.v2.dto.V2AdminSubmissionRecord
import com.aandiclub.online.judge.api.v2.dto.toV2
import com.aandiclub.online.judge.api.v2.support.V2ApiResponse
import com.aandiclub.online.judge.api.v2.support.V2ApiResponses
import com.aandiclub.online.judge.service.SubmissionService
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v2/admin/submissions")
@Tag(name = "Admin Submissions V2", description = "A&I v2 administrative submission APIs.")
class V2AdminSubmissionController(
    private val submissionService: SubmissionService,
) {
    @GetMapping
    suspend fun getAllSubmissions(): ResponseEntity<V2ApiResponse<List<V2AdminSubmissionRecord>>> =
        ResponseEntity.ok(V2ApiResponses.success(submissionService.getAllSubmissions().map { it.toV2() }))
}

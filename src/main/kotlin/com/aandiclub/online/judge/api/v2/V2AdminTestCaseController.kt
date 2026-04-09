package com.aandiclub.online.judge.api.v2

import com.aandiclub.online.judge.api.v2.dto.V2ProblemTestCaseRecord
import com.aandiclub.online.judge.api.v2.dto.toV2
import com.aandiclub.online.judge.api.v2.support.V2ApiResponse
import com.aandiclub.online.judge.api.v2.support.V2ApiResponses
import com.aandiclub.online.judge.service.ProblemService
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v2/admin/testcases")
@Tag(name = "Admin Test Cases V2", description = "A&I v2 administrative test case APIs.")
class V2AdminTestCaseController(
    private val problemService: ProblemService,
) {
    @GetMapping
    suspend fun getAllTestCases(): ResponseEntity<V2ApiResponse<List<V2ProblemTestCaseRecord>>> =
        ResponseEntity.ok(V2ApiResponses.success(problemService.getAllProblemsWithTestCases().map { it.toV2() }))
}

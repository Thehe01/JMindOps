package com.kama.jmindops.controller;

import com.kama.jmindops.model.common.ApiResponse;
import com.kama.jmindops.model.request.RagBatchEvaluationRequest;
import com.kama.jmindops.model.request.RagEvaluationRequest;
import com.kama.jmindops.model.response.RagBatchEvaluationResponse;
import com.kama.jmindops.model.response.RagEvaluationResponse;
import com.kama.jmindops.service.RagService;
import com.kama.jmindops.service.RagSource;
import com.kama.jmindops.security.ResourceAccessService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/rag")
public class RagEvaluationController {
    private final RagService ragService;
    private final ResourceAccessService resourceAccessService;
    public RagEvaluationController(RagService ragService, ResourceAccessService resourceAccessService) {
        this.ragService = ragService;
        this.resourceAccessService = resourceAccessService;
    }


    @PostMapping("/evaluate")
    public ApiResponse<RagEvaluationResponse> evaluate(@Valid @RequestBody RagEvaluationRequest request) {
        resourceAccessService.requireOwnedKnowledgeBase(request.getKbId());
        List<RagSource> sources = ragService.hybridSearchWithSources(request.getKbId(), request.getQuery());
        boolean hit = request.getExpectedDocumentId() != null && sources.stream()
                .anyMatch(source -> request.getExpectedDocumentId().equals(source.documentId()));
        return ApiResponse.success(RagEvaluationResponse.builder().hit(hit).sources(sources).build());
    }

    @PostMapping("/evaluate/batch")
    public ApiResponse<RagBatchEvaluationResponse> evaluateBatch(@Valid @RequestBody RagBatchEvaluationRequest request) {
        resourceAccessService.requireOwnedKnowledgeBase(request.getKbId());
        RagBatchEvaluationResponse response = ragService.evaluateBatch(request.getKbId(), request.getTestCases());
        return ApiResponse.success(response);
    }
}

package com.kama.jmindops.service;

import com.kama.jmindops.model.request.CreateKnowledgeBaseRequest;
import com.kama.jmindops.model.request.UpdateKnowledgeBaseRequest;
import com.kama.jmindops.model.response.CreateKnowledgeBaseResponse;
import com.kama.jmindops.model.response.GetKnowledgeBasesResponse;

public interface KnowledgeBaseFacadeService {
    GetKnowledgeBasesResponse getKnowledgeBases();

    CreateKnowledgeBaseResponse createKnowledgeBase(CreateKnowledgeBaseRequest request);

    void deleteKnowledgeBase(String knowledgeBaseId);

    void updateKnowledgeBase(String knowledgeBaseId, UpdateKnowledgeBaseRequest request);
}


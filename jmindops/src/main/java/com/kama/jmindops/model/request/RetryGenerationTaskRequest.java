package com.kama.jmindops.model.request;

import jakarta.validation.constraints.Size;

public class RetryGenerationTaskRequest {
    @Size(max = 64, message = "requestId 长度不能超过 64")
    private String requestId;

    public RetryGenerationTaskRequest() {
    }

    public RetryGenerationTaskRequest(String requestId) {
        this.requestId = requestId;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }
}


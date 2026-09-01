package com.kama.jmindops.model.response;

import com.kama.jmindops.service.RagSource;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class RagEvaluationResponse {
    private boolean hit;
    private List<RagSource> sources;

    public RagEvaluationResponse() {}
    public RagEvaluationResponse(boolean hit, List<RagSource> sources) {
        this.hit = hit;
        this.sources = sources;
    }
    public boolean getHit() { return this.hit; }
    public void setHit(boolean hit) { this.hit = hit; }
    public List<RagSource> getSources() { return this.sources; }
    public void setSources(List<RagSource> sources) { this.sources = sources; }
    public static RagEvaluationResponseBuilder builder() { return new RagEvaluationResponseBuilder(); }
    public static class RagEvaluationResponseBuilder {
        private boolean hit;
        private List<RagSource> sources;
        public RagEvaluationResponseBuilder() {}
        public RagEvaluationResponseBuilder hit(boolean hit) { this.hit = hit; return this; }
        public RagEvaluationResponseBuilder sources(List<RagSource> sources) { this.sources = sources; return this; }
        public RagEvaluationResponse build() { return new RagEvaluationResponse(hit, sources); }
    }
}

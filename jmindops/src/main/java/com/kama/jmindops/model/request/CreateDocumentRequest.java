package com.kama.jmindops.model.request;

import com.kama.jmindops.validation.ValidationPatterns;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateDocumentRequest {
    @NotBlank(message = "知识库 ID 不能为空")
    @Pattern(regexp = ValidationPatterns.UUID, message = "知识库 ID 格式不正确")
    private String kbId;
    @NotBlank(message = "文件名不能为空")
    @Size(max = 255, message = "文件名不能超过 255 个字符")
    private String filename;
    @NotBlank(message = "文件类型不能为空")
    @Pattern(regexp = "(?i)^(md|markdown|txt)$", message = "仅支持 Markdown 和 TXT 文本文件")
    private String filetype;
    @PositiveOrZero(message = "文件大小不能为负数")
    @Max(value = 10 * 1024 * 1024, message = "文件大小不能超过 10MB")
    private Long size;

    public CreateDocumentRequest() {}
    public CreateDocumentRequest(String kbId, String filename, String filetype, Long size) {
        this.kbId = kbId;
        this.filename = filename;
        this.filetype = filetype;
        this.size = size;
    }
    public String getKbId() { return this.kbId; }
    public void setKbId(String kbId) { this.kbId = kbId; }
    public String getFilename() { return this.filename; }
    public void setFilename(String filename) { this.filename = filename; }
    public String getFiletype() { return this.filetype; }
    public void setFiletype(String filetype) { this.filetype = filetype; }
    public Long getSize() { return this.size; }
    public void setSize(Long size) { this.size = size; }
    public static CreateDocumentRequestBuilder builder() { return new CreateDocumentRequestBuilder(); }
    public static class CreateDocumentRequestBuilder {
        private String kbId;
        private String filename;
        private String filetype;
        private Long size;
        public CreateDocumentRequestBuilder() {}
        public CreateDocumentRequestBuilder kbId(String kbId) { this.kbId = kbId; return this; }
        public CreateDocumentRequestBuilder filename(String filename) { this.filename = filename; return this; }
        public CreateDocumentRequestBuilder filetype(String filetype) { this.filetype = filetype; return this; }
        public CreateDocumentRequestBuilder size(Long size) { this.size = size; return this; }
        public CreateDocumentRequest build() { return new CreateDocumentRequest(kbId, filename, filetype, size); }
    }
}


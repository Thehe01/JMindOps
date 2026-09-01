package com.kama.jmindops.model.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateDocumentRequest {
    @Size(min = 1, max = 255, message = "文件名长度必须在 1 到 255 个字符之间")
    private String filename;
    @Pattern(regexp = "(?i)^(md|markdown|txt)$", message = "仅支持 Markdown 和 TXT 文本文件")
    private String filetype;
    @PositiveOrZero(message = "文件大小不能为负数")
    @Max(value = 10 * 1024 * 1024, message = "文件大小不能超过 10MB")
    private Long size;

    public UpdateDocumentRequest() {}
    public UpdateDocumentRequest(String filename, String filetype, Long size) {
        this.filename = filename;
        this.filetype = filetype;
        this.size = size;
    }
    public String getFilename() { return this.filename; }
    public void setFilename(String filename) { this.filename = filename; }
    public String getFiletype() { return this.filetype; }
    public void setFiletype(String filetype) { this.filetype = filetype; }
    public Long getSize() { return this.size; }
    public void setSize(Long size) { this.size = size; }
    public static UpdateDocumentRequestBuilder builder() { return new UpdateDocumentRequestBuilder(); }
    public static class UpdateDocumentRequestBuilder {
        private String filename;
        private String filetype;
        private Long size;
        public UpdateDocumentRequestBuilder() {}
        public UpdateDocumentRequestBuilder filename(String filename) { this.filename = filename; return this; }
        public UpdateDocumentRequestBuilder filetype(String filetype) { this.filetype = filetype; return this; }
        public UpdateDocumentRequestBuilder size(Long size) { this.size = size; return this; }
        public UpdateDocumentRequest build() { return new UpdateDocumentRequest(filename, filetype, size); }
    }
}


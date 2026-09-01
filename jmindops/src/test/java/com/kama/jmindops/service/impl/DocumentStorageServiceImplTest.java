package com.kama.jmindops.service.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentStorageServiceImplTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void storesFilesUnderUuidScopedDirectory() throws Exception {
        DocumentStorageServiceImpl service = service();
        String kbId = UUID.randomUUID().toString();
        String documentId = UUID.randomUUID().toString();
        MockMultipartFile file = new MockMultipartFile(
                "file", "notes.md", "text/markdown", "# Notes".getBytes());

        String relativePath = service.saveFile(kbId, documentId, file);

        assertThat(relativePath).startsWith(kbId + "/" + documentId + "/");
        assertThat(service.fileExists(relativePath)).isTrue();
        assertThat(service.getFilePath(relativePath)).startsWith(temporaryDirectory.toRealPath());
    }

    @Test
    void rejectsPathTraversalWhenResolvingStoredFile() {
        DocumentStorageServiceImpl service = service();
        assertThatThrownBy(() -> service.getFilePath("../outside.txt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("超出");
    }

    @Test
    void rejectsNonUuidDirectorySegments() {
        DocumentStorageServiceImpl service = service();
        MockMultipartFile file = new MockMultipartFile(
                "file", "notes.txt", "text/plain", "notes".getBytes());

        assertThatThrownBy(() -> service.saveFile("../other", UUID.randomUUID().toString(), file))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private DocumentStorageServiceImpl service() {
        DocumentStorageServiceImpl service = new DocumentStorageServiceImpl();
        ReflectionTestUtils.setField(service, "baseStoragePath", temporaryDirectory.toString());
        return service;
    }
}

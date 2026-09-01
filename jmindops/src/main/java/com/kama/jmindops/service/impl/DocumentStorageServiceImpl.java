package com.kama.jmindops.service.impl;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.kama.jmindops.service.DocumentStorageService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

@Service

public class DocumentStorageServiceImpl implements DocumentStorageService {
    private static final Logger log = LoggerFactory.getLogger(DocumentStorageServiceImpl.class);


    @Value("${document.storage.base-path:./data/documents}")
    private String baseStoragePath;

    @Override
    public String saveFile(String kbId, String documentId, MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("上传的文件为空");
        }

        // 路径段只能来自服务端 UUID，避免未来调用方把相对路径带入存储目录。
        UUID.fromString(kbId);
        UUID.fromString(documentId);

        // 构建文件存储路径: basePath/kbId/documentId/filename
        Path baseDir = normalizedBaseDirectory();
        Path documentDir = baseDir.resolve(kbId).resolve(documentId).normalize();
        requireContained(baseDir, documentDir);
        
        // 确保目录存在
        Files.createDirectories(documentDir);
        Path realBaseDir = baseDir.toRealPath();
        Path realDocumentDir = documentDir.toRealPath();
        requireContained(realBaseDir, realDocumentDir);
        
        // 生成唯一文件名（使用 UUID + 原始文件名）
        String originalFilename = file.getOriginalFilename();
        String extension = "";
        if (originalFilename != null && originalFilename.contains(".")) {
            extension = originalFilename.substring(originalFilename.lastIndexOf("."));
        }
        String uniqueFilename = UUID.randomUUID().toString() + extension;
        
        // 先写同目录临时文件，再原子替换，避免异常时留下半个文件。
        Path targetPath = realDocumentDir.resolve(uniqueFilename).normalize();
        requireContained(realBaseDir, targetPath);
        Path temporaryPath = realDocumentDir.resolve(uniqueFilename + ".part").normalize();
        requireContained(realBaseDir, temporaryPath);
        try {
            try (var inputStream = file.getInputStream()) {
                Files.copy(inputStream, temporaryPath, StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                Files.move(temporaryPath, targetPath,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporaryPath, targetPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporaryPath);
        }
        
        // 返回相对路径（相对于 baseStoragePath）
        String relativePath = Paths.get(kbId, documentId, uniqueFilename).toString().replace("\\", "/");
        log.info("文件保存成功: kbId={}, documentId={}, size={}",
                kbId, documentId, file.getSize());
        
        return relativePath;
    }

    @Override
    public void deleteFile(String filePath) throws IOException {
        Path fullPath = getFilePath(filePath);
        if (Files.exists(fullPath)) {
            Files.delete(fullPath);
            log.info("文件删除成功: {}", filePath);
            
            // 尝试删除空的父目录
            Path parentDir = fullPath.getParent();
            if (parentDir != null && Files.exists(parentDir)) {
                try {
                    Files.delete(parentDir);
                    log.info("目录删除成功: {}", parentDir);
                } catch (IOException e) {
                    // 目录不为空或其他原因无法删除，忽略
                    log.debug("目录删除失败（可能不为空）: {}", parentDir);
                }
            }
        } else {
            log.warn("文件不存在，跳过删除: {}", filePath);
        }
    }

    @Override
    public Path getFilePath(String filePath) {
        Path baseDir = normalizedBaseDirectory();
        Path resolved = baseDir.resolve(filePath).normalize();
        requireContained(baseDir, resolved);
        if (Files.exists(resolved)) {
            try {
                Path realBaseDir = baseDir.toRealPath();
                Path realResolved = resolved.toRealPath();
                requireContained(realBaseDir, realResolved);
                return realResolved;
            } catch (IOException e) {
                throw new IllegalArgumentException("无法解析文档存储路径", e);
            }
        }
        return resolved;
    }

    @Override
    public boolean fileExists(String filePath) {
        Path fullPath = getFilePath(filePath);
        return Files.exists(fullPath) && Files.isRegularFile(fullPath);
    }

    private Path normalizedBaseDirectory() {
        return Paths.get(baseStoragePath).toAbsolutePath().normalize();
    }

    private void requireContained(Path baseDir, Path candidate) {
        if (!candidate.startsWith(baseDir)) {
            throw new IllegalArgumentException("文档路径超出允许的存储目录");
        }
    }
}

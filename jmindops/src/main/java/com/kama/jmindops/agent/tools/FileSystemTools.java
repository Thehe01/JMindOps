package com.kama.jmindops.agent.tools;


import com.kama.jmindops.governance.RequiresToolApproval;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
@ConditionalOnProperty(name = "app.tools.filesystem.enabled", havingValue = "true")
public class FileSystemTools implements Tool {
    private static final Logger log = LoggerFactory.getLogger(FileSystemTools.class);
    private static final int MAX_PATH_LENGTH = 512;
    private static final int MAX_READ_BYTES = 1_000_000;
    private static final int MAX_WRITE_CHARS = 100_000;
    private static final int MAX_LIST_ENTRIES = 200;

    private final Path baseDirectory;

    public FileSystemTools(@Value("${app.tools.filesystem.base-path:./data/mcp-workspace}") String baseDirectory) {
        this.baseDirectory = Paths.get(baseDirectory).toAbsolutePath().normalize();
    }

    @Override
    public String getName() {
        return "fileSystemTool";
    }

    @Override
    public String getDescription() {
        return "提供文件系统操作的工具，包括读取文件、写入文件、列出目录等功能";
    }

    @Override
    public ToolType getType() {
        return ToolType.OPTIONAL;
    }

    /**
     * 读取文件内容
     *
     * @param filePath 文件路径（相对于工作目录）
     * @return 文件内容
     */
    @org.springframework.ai.tool.annotation.Tool(
            name = "readFile",
            description = "读取指定文件的内容。参数：filePath - 文件路径（相对于工作目录）"
    )
    public String readFile(String filePath) {
        try {
            Path path = validateAndResolvePath(filePath);

            if (!Files.exists(path)) {
                return "错误：文件不存在 - " + filePath;
            }

            if (!Files.isRegularFile(path)) {
                return "错误：路径不是文件 - " + filePath;
            }
            if (Files.size(path) > MAX_READ_BYTES) {
                return "错误：文件超过允许读取的大小上限";
            }

            String content = Files.readString(path);
            log.info("成功读取文件: {}", filePath);
            return "文件内容:\n" + content;

        } catch (SecurityException e) {
            log.error("安全错误：{}", e.getMessage());
            return "错误：访问被拒绝 - " + e.getMessage();
        } catch (IOException e) {
            log.error("读取文件失败: {}", filePath, e);
            return "错误：读取文件失败 - " + e.getMessage();
        } catch (Exception e) {
            log.error("未知错误: {}", e.getMessage(), e);
            return "错误：操作失败 - " + e.getMessage();
        }
    }

    /**
     * 写入文件内容
     *
     * @param filePath 文件路径（相对于工作目录）
     * @param content  要写入的内容
     * @return 操作结果
     */
    @org.springframework.ai.tool.annotation.Tool(
            name = "writeFile",
            description = "将内容写入指定文件。如果文件不存在则创建，如果文件存在则覆盖。参数：filePath - 文件路径（相对于工作目录），content - 要写入的内容"
    )
    @RequiresToolApproval
    public String writeFile(String filePath, String content) {
        try {
            validateWriteContent(content);
            Path path = validateAndResolvePath(filePath);

            // 确保父目录存在
            Path parent = path.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
                log.info("创建目录: {}", parent);
            }

            Files.writeString(path, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            log.info("成功写入文件: {}", filePath);
            return "成功写入文件: " + filePath;

        } catch (SecurityException e) {
            log.error("安全错误：{}", e.getMessage());
            return "错误：访问被拒绝 - " + e.getMessage();
        } catch (IOException e) {
            log.error("写入文件失败: {}", filePath, e);
            return "错误：写入文件失败 - " + e.getMessage();
        } catch (Exception e) {
            log.error("未知错误: {}", e.getMessage(), e);
            return "错误：操作失败 - " + e.getMessage();
        }
    }

    /**
     * 追加内容到文件
     *
     * @param filePath 文件路径（相对于工作目录）
     * @param content  要追加的内容
     * @return 操作结果
     */
    @org.springframework.ai.tool.annotation.Tool(
            name = "appendToFile",
            description = "将内容追加到指定文件的末尾。如果文件不存在则创建。参数：filePath - 文件路径（相对于工作目录），content - 要追加的内容"
    )
    @RequiresToolApproval
    public String appendToFile(String filePath, String content) {
        try {
            validateWriteContent(content);
            Path path = validateAndResolvePath(filePath);

            // 确保父目录存在
            Path parent = path.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
                log.info("创建目录: {}", parent);
            }

            Files.writeString(path, content, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            log.info("成功追加内容到文件: {}", filePath);
            return "成功追加内容到文件: " + filePath;

        } catch (SecurityException e) {
            log.error("安全错误：{}", e.getMessage());
            return "错误：访问被拒绝 - " + e.getMessage();
        } catch (IOException e) {
            log.error("追加内容到文件失败: {}", filePath, e);
            return "错误：追加内容失败 - " + e.getMessage();
        } catch (Exception e) {
            log.error("未知错误: {}", e.getMessage(), e);
            return "错误：操作失败 - " + e.getMessage();
        }
    }

    /**
     * 列出目录中的文件和子目录
     *
     * @param directoryPath 目录路径（相对于工作目录），如果为空则列出当前目录
     * @return 目录内容列表
     */
    @org.springframework.ai.tool.annotation.Tool(
            name = "listFiles",
            description = "列出指定目录中的文件和子目录。参数：directoryPath - 目录路径（相对于工作目录），如果为空则列出当前目录"
    )
    public String listFiles(String directoryPath) {
        try {
            Path path;
            if (directoryPath == null || directoryPath.trim().isEmpty()) {
                path = validateAndResolvePath(".");
            } else {
                path = validateAndResolvePath(directoryPath);
            }

            if (!Files.exists(path)) {
                return "错误：目录不存在 - " + directoryPath;
            }

            if (!Files.isDirectory(path)) {
                return "错误：路径不是目录 - " + directoryPath;
            }

            List<String> items;
            try (Stream<Path> paths = Files.list(path)) {
                items = paths
                        .limit(MAX_LIST_ENTRIES)
                        .map(p -> {
                            String name = p.getFileName().toString();
                            if (Files.isDirectory(p)) {
                                return "[DIR] " + name;
                            }
                            try {
                                long size = Files.size(p);
                                return "[FILE] " + name + " (" + formatFileSize(size) + ")";
                            } catch (IOException e) {
                                return "[FILE] " + name;
                            }
                        })
                        .sorted()
                        .collect(Collectors.toList());
            }

            if (items.isEmpty()) {
                return "目录为空: " + directoryPath;
            }

            log.info("成功列出目录内容: {}", directoryPath);
            return "目录内容 (" + directoryPath + "):\n" + String.join("\n", items);

        } catch (SecurityException e) {
            log.error("安全错误：{}", e.getMessage());
            return "错误：访问被拒绝 - " + e.getMessage();
        } catch (IOException e) {
            log.error("列出目录失败: {}", directoryPath, e);
            return "错误：列出目录失败 - " + e.getMessage();
        } catch (Exception e) {
            log.error("未知错误: {}", e.getMessage(), e);
            return "错误：操作失败 - " + e.getMessage();
        }
    }

    /**
     * 删除文件或目录
     *
     * @param path 文件或目录路径（相对于工作目录）
     * @return 操作结果
     */
    @org.springframework.ai.tool.annotation.Tool(
            name = "deleteFile",
            description = "删除指定的文件或目录。参数：path - 文件或目录路径（相对于工作目录）"
    )
    @RequiresToolApproval
    public String deleteFile(String path) {
        try {
            Path filePath = validateAndResolvePath(path);
            if (filePath.equals(baseDirectory)) {
                throw new SecurityException("禁止删除整个工具工作区");
            }

            if (!Files.exists(filePath)) {
                return "错误：文件或目录不存在 - " + path;
            }

            if (Files.isDirectory(filePath)) {
                // 递归删除目录
                try (Stream<Path> paths = Files.walk(filePath)) {
                    paths.sorted((a, b) -> b.compareTo(a)) // 先删除文件，再删除目录
                            .forEach(p -> {
                                try {
                                    Files.delete(p);
                                } catch (IOException e) {
                                    log.warn("删除失败: {}", p, e);
                                }
                            });
                }
                log.info("成功删除目录: {}", path);
                return "成功删除目录: " + path;
            } else {
                Files.delete(filePath);
                log.info("成功删除文件: {}", path);
                return "成功删除文件: " + path;
            }

        } catch (SecurityException e) {
            log.error("安全错误：{}", e.getMessage());
            return "错误：访问被拒绝 - " + e.getMessage();
        } catch (IOException e) {
            log.error("删除文件失败: {}", path, e);
            return "错误：删除失败 - " + e.getMessage();
        } catch (Exception e) {
            log.error("未知错误: {}", e.getMessage(), e);
            return "错误：操作失败 - " + e.getMessage();
        }
    }

    /**
     * 创建目录
     *
     * @param directoryPath 目录路径（相对于工作目录）
     * @return 操作结果
     */
    @org.springframework.ai.tool.annotation.Tool(
            name = "createDirectory",
            description = "创建指定目录，如果父目录不存在则一并创建。参数：directoryPath - 目录路径（相对于工作目录）"
    )
    @RequiresToolApproval
    public String createDirectory(String directoryPath) {
        try {
            Path path = validateAndResolvePath(directoryPath);

            if (Files.exists(path)) {
                if (Files.isDirectory(path)) {
                    return "目录已存在: " + directoryPath;
                } else {
                    return "错误：路径已存在但不是目录 - " + directoryPath;
                }
            }

            Files.createDirectories(path);
            log.info("成功创建目录: {}", directoryPath);
            return "成功创建目录: " + directoryPath;

        } catch (SecurityException e) {
            log.error("安全错误：{}", e.getMessage());
            return "错误：访问被拒绝 - " + e.getMessage();
        } catch (IOException e) {
            log.error("创建目录失败: {}", directoryPath, e);
            return "错误：创建目录失败 - " + e.getMessage();
        } catch (Exception e) {
            log.error("未知错误: {}", e.getMessage(), e);
            return "错误：操作失败 - " + e.getMessage();
        }
    }

    /**
     * 验证路径并解析为绝对路径，防止路径遍历攻击
     *
     * @param filePath 文件路径
     * @return 解析后的绝对路径
     * @throws SecurityException 如果路径不安全
     */
    private Path validateAndResolvePath(String filePath) throws SecurityException {
        if (filePath == null || filePath.trim().isEmpty()) {
            throw new IllegalArgumentException("文件路径不能为空");
        }
        if (filePath.length() > MAX_PATH_LENGTH) {
            throw new SecurityException("文件路径过长");
        }

        Path requestedPath = Paths.get(filePath);
        if (requestedPath.isAbsolute()) {
            throw new SecurityException("只允许使用相对路径");
        }
        for (Path segment : requestedPath) {
            if (segment.toString().startsWith(".") && !".".equals(segment.toString())) {
                throw new SecurityException("禁止访问隐藏文件或目录");
            }
        }

        Path resolvedPath = baseDirectory.resolve(requestedPath).toAbsolutePath().normalize();
        if (!resolvedPath.startsWith(baseDirectory)) {
            throw new SecurityException("路径遍历攻击被阻止: " + filePath);
        }

        try {
            if (!Files.isDirectory(baseDirectory, LinkOption.NOFOLLOW_LINKS)) {
                throw new SecurityException("工具工作区不存在或不是目录");
            }
            Path realBase = baseDirectory.toRealPath();
            Path nearestExisting = resolvedPath;
            while (nearestExisting != null
                    && !Files.exists(nearestExisting, LinkOption.NOFOLLOW_LINKS)) {
                nearestExisting = nearestExisting.getParent();
            }
            if (nearestExisting == null || !nearestExisting.toRealPath().startsWith(realBase)) {
                throw new SecurityException("符号链接越界访问被阻止: " + filePath);
            }
            if (Files.exists(resolvedPath, LinkOption.NOFOLLOW_LINKS)
                    && !resolvedPath.toRealPath().startsWith(realBase)) {
                throw new SecurityException("符号链接越界访问被阻止: " + filePath);
            }
        } catch (IOException exception) {
            throw new SecurityException("无法验证工具工作区路径", exception);
        }

        return resolvedPath;
    }

    private void validateWriteContent(String content) {
        if (content == null) {
            throw new IllegalArgumentException("写入内容不能为空");
        }
        if (content.length() > MAX_WRITE_CHARS) {
            throw new IllegalArgumentException("写入内容超过允许的大小上限");
        }
    }

    /**
     * 格式化文件大小
     *
     * @param size 文件大小（字节）
     * @return 格式化后的文件大小字符串
     */
    private String formatFileSize(long size) {
        if (size < 1024) {
            return size + " B";
        } else if (size < 1024 * 1024) {
            return String.format("%.2f KB", size / 1024.0);
        } else if (size < 1024 * 1024 * 1024) {
            return String.format("%.2f MB", size / (1024.0 * 1024.0));
        } else {
            return String.format("%.2f GB", size / (1024.0 * 1024.0 * 1024.0));
        }
    }
}

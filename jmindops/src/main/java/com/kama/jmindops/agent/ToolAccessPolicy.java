package com.kama.jmindops.agent;

import java.util.Locale;
import java.util.Set;

/**
 * 在把工具描述发送给模型之前执行的确定性安全策略。
 * 工具内部校验仍然保留，形成“不可见 + 不可执行”的两层防护。
 */
public final class ToolAccessPolicy {
    private static final Set<String> ALL_EXTERNAL_TOOL_BEANS = Set.of(
            "dataBaseTool", "fileSystemTool", "emailTool");
    private static final Set<String> ALL_EXTERNAL_CALLBACKS = Set.of(
            "databaseQuery", "readFile", "listFiles", "writeFile", "appendToFile",
            "deleteFile", "createDirectory", "sendEmail");
    private static final Set<String> DESTRUCTIVE_DATABASE_SIGNALS = Set.of(
            "drop ", "drop\n", "truncate ", "alter ", "delete from", "insert into",
            "update ", "grant ", "revoke ", "删除表", "删表", "清空表", "修改表结构");
    private static final Set<String> DATABASE_TARGETS = Set.of(
            "数据库", "数据表", "table", "sql", "app_user");
    private static final Set<String> SECRET_SIGNALS = Set.of(
            ".env", "api_key", "apikey", "access_token", "secret", "密钥", "密码", "令牌", "凭证");
    private static final Set<String> SECRET_ACCESS_ACTIONS = Set.of(
            "读取", "打开", "查看", "发送", "发到", "发给", "上传", "导出", "泄露", "read", "send", "upload", "export");
    private static final Set<String> KNOWLEDGE_SOURCE_SIGNALS = Set.of(
            "知识库", "上传的文档", "文档中", "资料中");
    private static final Set<String> WORKSPACE_SIGNALS = Set.of(
            "工作区", "本地文件", "配置文件");
    private static final Set<String> READ_ONLY_CHECK_SIGNALS = Set.of(
            "只读", "读取", "检查", "核对", "对比", "是否一致");
    private static final Set<String> READ_ONLY_RAG_BLOCKED_CALLBACKS = Set.of(
            "databaseQuery", "writeFile", "appendToFile", "deleteFile", "createDirectory", "sendEmail");

    private ToolAccessPolicy() {
    }

    public static Decision evaluate(RoutingDecision route, String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return Decision.allowAll();
        }
        String normalized = userMessage.trim().toLowerCase(Locale.ROOT);
        if (route == RoutingDecision.RAG
                && containsAny(normalized, KNOWLEDGE_SOURCE_SIGNALS)
                && containsAny(normalized, WORKSPACE_SIGNALS)
                && containsAny(normalized, READ_ONLY_CHECK_SIGNALS)) {
            return new Decision(
                    Set.of("dataBaseTool", "emailTool"),
                    READ_ONLY_RAG_BLOCKED_CALLBACKS,
                    "本轮需要联合知识库与工作区核查，仅开放知识检索、文件读取和目录列举；禁止任何写入、删除、数据库或邮件操作。",
                    true,
                    false
            );
        }
        if (route != RoutingDecision.MCP) {
            return Decision.allowAll();
        }
        if (containsAny(normalized, SECRET_SIGNALS) && containsAny(normalized, SECRET_ACCESS_ACTIONS)) {
            return new Decision(
                    ALL_EXTERNAL_TOOL_BEANS,
                    ALL_EXTERNAL_CALLBACKS,
                    "检测到读取或外传敏感凭证的请求，已撤销本轮外部工具访问权限。",
                    false,
                    true
            );
        }
        if (containsAny(normalized, DESTRUCTIVE_DATABASE_SIGNALS)
                && containsAny(normalized, DATABASE_TARGETS)) {
            return new Decision(
                    Set.of("dataBaseTool"),
                    Set.of("databaseQuery"),
                    "检测到数据库写入或结构变更请求，已撤销本轮数据库工具访问权限。",
                    false,
                    true
            );
        }
        return Decision.allowAll();
    }

    private static boolean containsAny(String value, Set<String> signals) {
        return signals.stream().anyMatch(value::contains);
    }

    public record Decision(
            Set<String> blockedToolBeans,
            Set<String> blockedCallbacks,
            String instruction,
            boolean allowReadOnlyFileSystem,
            boolean blockAllExternalCallbacks
    ) {
        static Decision allowAll() {
            return new Decision(Set.of(), Set.of(), "", false, false);
        }

        public boolean allowsToolBean(String name) {
            return !blockedToolBeans.contains(name);
        }

        public boolean allowsCallback(String name) {
            return !blockedCallbacks.contains(name);
        }

        public boolean blocked() {
            return !blockedToolBeans.isEmpty() || !blockedCallbacks.isEmpty();
        }
    }
}

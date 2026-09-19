package com.kama.jmindops.agent;

import java.util.ArrayList;
import java.util.Locale;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Converts explicit user-side execution constraints into a short runtime instruction.
 * This is planning guidance; tool governance and tool-local validation remain the hard boundary.
 */
final class AgentExecutionPolicy {
    private static final Set<String> ORDER_SIGNALS = Set.of(
            "先", "首先", "然后", "接着", "随后", "再", "之后", "最后",
            "第一步", "第二步", "第三步", "第1步", "第2步", "第3步",
            "first", "then", "after", "next", "finally");
    private static final Pattern ORDERED_CLAUSE_SEPARATOR = Pattern.compile(
            "[，,；;。]|(?:然后|接着|随后|之后|最后|再|第二步|第三步|第四步|第2步|第3步|第4步)|\\b(?:then|after|next|finally)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Set<String> LIST_SIGNALS = Set.of(
            "列出", "列表", "清单", "有哪些文件", "有什么文件",
            "目录内容", "文件夹内容", "目录结构", "文件夹结构", "list");
    private static final Set<String> READ_SIGNALS = Set.of(
            "读取", "打开", "查看", "检查", "核查", "核对", "read", "open", "check", "verify");
    private static final Set<String> DATABASE_SIGNALS = Set.of(
            "数据库", "数据表", "sql", "database", "table");
    private static final Set<String> SQL_STATEMENT_SIGNALS = Set.of(
            "select", "insert", "update", "delete from", "grant", "revoke", "alter table", "drop table");
    private static final Set<String> EXECUTE_SIGNALS = Set.of(
            "执行", "运行", "execute", "run");
    private static final Set<String> READ_ONLY_SIGNALS = Set.of(
            "只读", "查询", "检索", "select", "read-only", "query");
    private static final Set<String> KNOWLEDGE_SIGNALS = Set.of(
            "知识库", "上传文档", "上传的文档", "资料中", "资料里");
    private static final Set<String> WORKSPACE_SIGNALS = Set.of(
            "工作区", "本地文件", "配置文件");
    private static final Set<String> FILE_CAPABILITY_EXPLANATION_SIGNALS = Set.of(
            "允许哪些", "支持哪些", "有哪些文件能力", "哪些文件能力",
            "如何阻止", "如何防止", "怎样阻止", "怎样防止");
    private static final Set<String> FILE_SIGNALS = Set.of(
            "文件", ".md", ".txt", ".json", ".yaml", ".yml", "file");
    private static final Set<String> NEGATED_READ_SIGNALS = Set.of(
            "不读取", "不要读取", "不用读取", "无需读取", "禁止读取",
            "不打开", "不要打开", "不用打开", "无需打开");
    private static final Set<String> NEGATED_WRITE_SIGNALS = Set.of(
            "不写入", "不要写入", "不用写入", "无需写入", "禁止写入",
            "不要修改", "不用修改", "无需修改", "只读");
    private static final Set<String> NEGATED_LIST_SIGNALS = Set.of(
            "不列出", "不要列出", "不用列出", "无需列出");
    private static final Set<String> WRITE_SIGNALS = Set.of(
            "写入", "写到", "保存到", "创建文件", "write");
    private static final Set<String> APPEND_SIGNALS = Set.of(
            "追加", "末尾增加", "append");
    private static final Set<String> DELETE_SIGNALS = Set.of(
            "删除", "移除", "delete", "remove");
    private static final Set<String> CREATE_DIRECTORY_SIGNALS = Set.of(
            "创建目录", "创建文件夹", "新建目录", "新建文件夹", "mkdir");
    private static final Set<String> CREATE_SIGNALS = Set.of(
            "创建", "新建", "create", "make");
    private static final Set<String> DIRECTORY_SIGNALS = Set.of(
            "目录", "文件夹", "directory", "folder");
    private static final Set<String> EMAIL_SIGNALS = Set.of(
            "发邮件", "发送邮件", "发送通知", "邮件", "email");
    private static final Set<String> APPROVAL_GATED_TOOLS = Set.of(
            "databaseQuery", "writeFile", "appendToFile", "deleteFile", "createDirectory", "sendEmail");

    private AgentExecutionPolicy() {
    }

    static String instruction(RoutingDecision route, String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return "";
        }
        String normalized = userMessage.trim().toLowerCase(Locale.ROOT);
        StringBuilder instruction = new StringBuilder();

        if (route == RoutingDecision.RAG) {
            instruction.append("知识库检索由运行时在生成答案前强制执行一次，不得跳过或重复调用 KnowledgeTool。");
            if (containsAny(normalized, KNOWLEDGE_SIGNALS) && requestsWorkspaceRead(normalized)) {
                instruction.append(" 检索完成后只调用一次 readFile 核查配置；readFile 成功后立即作答，禁止追加 listFiles 或重复读取。");
            }
            return instruction.toString();
        }

        if (route == RoutingDecision.WEATHER) {
            return "必须调用且只调用一次 weather 获取指定地点和时间的天气，再依据工具结果作答；不得凭模型记忆猜测实时天气。";
        }

        if (route != RoutingDecision.MCP) {
            return "";
        }

        Plan plan = plan(route, userMessage);
        if (plan.requiredToolSequence().equals(List.of("listFiles", "readFile"))) {
            instruction.append("""
                    这是具有前置依赖的文件读取任务。必须严格逐步执行：
                    1. 首次只调用 listFiles 列出用户指定目录；
                    2. 收到 listFiles 的真实结果并确认目标文件后，才调用一次 readFile；
                    3. 禁止在列目录前调用 readFile，禁止把两个调用并行发出，也不要重复已经成功的调用。
                    """);
        } else if (plan.requiredToolSequence().size() > 1) {
            instruction.append("这是有顺序依赖的多步任务。必须严格依次调用：")
                    .append(String.join(" -> ", plan.requiredToolSequence()))
                    .append("。每一步成功后才可进入下一步，禁止并行、跳步、换序或重复调用。");
            if (plan.requiredToolSequence().stream().anyMatch(APPROVAL_GATED_TOOLS::contains)) {
                instruction.append(" 任一步返回需要人工审批时，立即终止本轮后续步骤并明确说明等待审批。");
            }
        }

        if (plan.requiredToolSequence().equals(List.of("databaseQuery"))) {
            if (!instruction.isEmpty()) {
                instruction.append('\n');
            }
            instruction.append("""
                    只读数据库查询最多调用一次 databaseQuery。如果工具返回需要人工审批，立即停止继续调用工具，
                    仅向用户说明正在等待审批；不得重试相同查询，也不得改用其他工具绕过审批。
                    """);
        } else if (plan.requiredToolSequence().size() == 1) {
            String toolName = plan.requiredToolSequence().get(0);
            if (!instruction.isEmpty()) {
                instruction.append('\n');
            }
            instruction.append("本轮必须调用且只调用一次 ")
                    .append(toolName)
                    .append(" 完成用户明确要求的操作；不得跳过工具后声称已经完成。");
            if (APPROVAL_GATED_TOOLS.contains(toolName)) {
                instruction.append(" 如果工具返回需要人工审批，立即停止并明确说明正在等待审批，不得重试或改用其他工具。");
            }
        }
        return instruction.toString().trim();
    }

    static Plan plan(RoutingDecision route, String userMessage) {
        if (route == RoutingDecision.WEATHER) {
            return new Plan(List.of("weather"));
        }
        if (route == RoutingDecision.RAG) {
            String normalized = normalize(userMessage);
            if (requestsWorkspaceRead(normalized)) {
                return new Plan(List.of("KnowledgeTool", "readFile"));
            }
            return new Plan(List.of("KnowledgeTool"));
        }
        if (route != RoutingDecision.MCP || userMessage == null || userMessage.isBlank()) {
            return Plan.none();
        }
        String normalized = normalize(userMessage);
        Plan orderedPlan = orderedMcpPlan(normalized);
        if (!orderedPlan.isEmpty()) {
            return orderedPlan;
        }
        String singleTool = detectClauseTool(normalized);
        return singleTool == null ? Plan.none() : new Plan(List.of(singleTool));
    }

    private static Plan orderedMcpPlan(String normalized) {
        if (!containsAny(normalized, ORDER_SIGNALS)) {
            return Plan.none();
        }
        List<String> sequence = new ArrayList<>();
        for (String clause : splitClauses(normalized)) {
            String toolName = detectClauseTool(clause);
            if (toolName != null) {
                sequence.add(toolName);
            }
        }
        return sequence.size() > 1 ? new Plan(sequence) : Plan.none();
    }

    private static String detectClauseTool(String clause) {
        if (isDatabaseIntent(clause)) {
            return "databaseQuery";
        }
        if (containsAny(clause, EMAIL_SIGNALS)) {
            return "sendEmail";
        }
        if (containsAny(clause, APPEND_SIGNALS) && !containsAny(clause, NEGATED_WRITE_SIGNALS)) {
            return "appendToFile";
        }
        if (containsAny(clause, CREATE_DIRECTORY_SIGNALS)
                || (containsAny(clause, CREATE_SIGNALS)
                && containsAny(clause, DIRECTORY_SIGNALS)
                && !hasSpecificFileReference(clause))) {
            return "createDirectory";
        }
        if (containsAny(clause, DELETE_SIGNALS) && hasSpecificFileReference(clause)) {
            return "deleteFile";
        }
        boolean createFile = containsAny(clause, CREATE_SIGNALS) && hasSpecificFileReference(clause);
        if (!containsAny(clause, NEGATED_WRITE_SIGNALS)
                && (createFile || (containsAny(clause, WRITE_SIGNALS) && hasSpecificFileReference(clause)))) {
            return "writeFile";
        }
        if (isDirectoryListingIntent(clause)) {
            return "listFiles";
        }
        if (isAffirmativeReadIntent(clause)) {
            return "readFile";
        }
        return null;
    }

    private static boolean isDatabaseIntent(String value) {
        boolean databaseTarget = containsAny(value, DATABASE_SIGNALS)
                || containsAny(value, SQL_STATEMENT_SIGNALS);
        boolean databaseAction = containsAny(value, READ_ONLY_SIGNALS)
                || containsAny(value, EXECUTE_SIGNALS);
        return databaseTarget && databaseAction;
    }

    private static boolean isDirectoryListingIntent(String value) {
        if (containsAny(value, NEGATED_LIST_SIGNALS)) {
            return false;
        }
        if (containsAny(value, LIST_SIGNALS)) {
            return true;
        }
        return containsAny(value, DIRECTORY_SIGNALS)
                && containsAny(value, READ_SIGNALS)
                && !hasSpecificFileReference(value);
    }

    private static boolean isAffirmativeReadIntent(String value) {
        return containsAny(value, READ_SIGNALS)
                && !containsAny(value, NEGATED_READ_SIGNALS)
                && hasSpecificFileReference(value);
    }

    private static boolean requestsWorkspaceRead(String normalized) {
        // “允许哪些文件能力”“如何阻止越界读取”是在询问能力边界，并不授权读取文件。
        // 这项保护只应用于 RAG 的附加工作区步骤；明确的“核对 settings.yml”仍会规划 readFile。
        for (String clause : splitClauses(normalized)) {
            if (!containsAny(clause, FILE_CAPABILITY_EXPLANATION_SIGNALS)
                    && containsAny(clause, WORKSPACE_SIGNALS)
                    && isAffirmativeReadIntent(clause)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> splitClauses(String value) {
        return Pattern.compile("(?:并且|并)|" + ORDERED_CLAUSE_SEPARATOR.pattern(), Pattern.CASE_INSENSITIVE)
                .splitAsStream(value)
                .map(String::trim)
                .filter(clause -> !clause.isEmpty())
                .toList();
    }

    private static boolean hasSpecificFileReference(String value) {
        String withoutDirectoryTerms = value
                .replace("文件夹", "")
                .replace("folder", "")
                .replace("directory", "");
        return containsAny(withoutDirectoryTerms, FILE_SIGNALS);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean containsAny(String value, Set<String> signals) {
        return signals.stream().anyMatch(value::contains);
    }

    record Plan(List<String> requiredToolSequence) {
        Plan {
            requiredToolSequence = requiredToolSequence == null
                    ? List.of()
                    : List.copyOf(requiredToolSequence);
        }

        static Plan none() {
            return new Plan(List.of());
        }

        boolean isEmpty() {
            return requiredToolSequence.isEmpty();
        }
    }
}

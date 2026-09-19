package com.kama.jmindops.agent.tools;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.kama.jmindops.governance.RequiresToolApproval;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@ConditionalOnProperty(name = "app.tools.database.enabled", havingValue = "true")
public class DataBaseTools implements Tool {
    private static final Logger log = LoggerFactory.getLogger(DataBaseTools.class);


    private static final int MAX_SQL_LENGTH = 4_000;
    private static final int MAX_ROWS = 20;
    private static final int MAX_COLUMNS = 20;
    private static final int MAX_CELL_LENGTH = 200;
    private static final Pattern FORBIDDEN_SQL = Pattern.compile(
            "(?i)\\b(insert|update|delete|merge|copy|alter|drop|create|grant|revoke|truncate|call|do|into|for\\s+update)\\b"
                    + "|(?i)\\b(pg_sleep|pg_read_file|pg_read_binary_file|pg_ls_dir|lo_import|lo_export|dblink|setval|nextval)\\s*\\(");
    private static final Pattern FORBIDDEN_TABLES = Pattern.compile(
            "(?i)\\b(app_user|tool_approval|tool_audit_log|generation_task|document_index_task|flyway_schema_history|pg_shadow|pg_authid|pg_user)\\b");
    private static final Pattern FORBIDDEN_COLUMNS = Pattern.compile(
            "(?i)\\b(password_hash|salt|secret|api_key)\\b");
    private static final Pattern UNSAFE_IDENTIFIER_SYNTAX = Pattern.compile("(?i)U\\s*&\\s*\"|\"");
    private static final Pattern RELATION_REFERENCE = Pattern.compile(
            "(?i)\\b(?:from|join)\\s+(?:only\\s+)?([a-z_][a-z0-9_$]*(?:\\.[a-z_][a-z0-9_$]*)?)");
    private static final Pattern RELATION_KEYWORD = Pattern.compile("(?i)\\b(from|join)\\b");
    private static final String DEFAULT_ALLOWED_RELATIONS =
            "agent,chat_session,chat_message,knowledge_base,document,chunk_bge_m3";

    private final JdbcTemplate jdbcTemplate;
    private final Set<String> allowedRelations;

    @Autowired
    public DataBaseTools(
            @Qualifier("databaseToolJdbcTemplate") JdbcTemplate jdbcTemplate,
            @Value("${app.tools.database.allowed-relations:" + DEFAULT_ALLOWED_RELATIONS + "}")
            String allowedRelations
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.allowedRelations = parseAllowedRelations(allowedRelations);
        if (this.allowedRelations.isEmpty()) {
            throw new IllegalStateException("Database tool relation allowlist cannot be empty");
        }
    }

    public DataBaseTools(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, DEFAULT_ALLOWED_RELATIONS);
    }

    @Override
    public String getName() {
        return "dataBaseTool";
    }

    @Override
    public String getDescription() {
        return "一个用于执行数据库查询操作的工具，主要用于从 PostgreSQL 中读取数据。";
    }

    @Override
    public ToolType getType() {
        return ToolType.OPTIONAL;
    }

    /**
     * 执行一条 SQL 查询，从数据库中进行查询数据
     *
     * @param sql SQL 查询语句（仅支持 SELECT 查询）
     * @return 格式化的查询结果字符串
     */
    @org.springframework.ai.tool.annotation.Tool(name = "databaseQuery", description = "用于在 PostgreSQL 中执行只读查询（SELECT）。接收由模型生成的查询语句，并返回结构化数据结果。该工具仅用于检索数据，严禁任何写入或修改数据库的语句。")
    @RequiresToolApproval
    public String query(String sql) {
        try {
            String validationError = validateReadOnlySelect(sql);
            if (validationError != null) {
                log.warn("拒绝执行数据库工具查询: reason={}", validationError);
                return "错误：" + validationError;
            }
            String normalizedSql = sql.trim();
            if (normalizedSql.endsWith(";")) {
                normalizedSql = normalizedSql.substring(0, normalizedSql.length() - 1).trim();
            }

            List<String> rows = jdbcTemplate.query(normalizedSql, preparedStatement -> {
                preparedStatement.setQueryTimeout(5);
                preparedStatement.setMaxRows(MAX_ROWS);
            }, (ResultSet rs) -> {
                List<String> resultRows = new ArrayList<>();
                ResultSetMetaData metaData = rs.getMetaData();
                int columnCount = metaData.getColumnCount();

                if (columnCount == 0) {
                    resultRows.add("查询结果为空（无列）");
                    return resultRows;
                }
                if (columnCount > MAX_COLUMNS) {
                    throw new IllegalArgumentException("查询列数超过允许上限");
                }

                // 获取列名和计算每列的最大宽度
                List<String> columnNames = new ArrayList<>();
                List<Integer> columnWidths = new ArrayList<>();
                for (int i = 1; i <= columnCount; i++) {
                    String columnName = metaData.getColumnName(i);
                    columnNames.add(columnName);
                    columnWidths.add(columnName.length());
                }

                // 收集所有行数据并计算列宽
                List<List<String>> dataRows = new ArrayList<>();
                while (rs.next() && dataRows.size() < MAX_ROWS) {
                    List<String> rowData = new ArrayList<>();
                    for (int i = 1; i <= columnCount; i++) {
                        Object value = rs.getObject(i);
                        String valueStr = value == null ? "NULL" : value.toString();
                        if (valueStr.length() > MAX_CELL_LENGTH) {
                            valueStr = valueStr.substring(0, MAX_CELL_LENGTH) + "…";
                        }
                        rowData.add(valueStr);
                        // 更新列宽
                        int currentWidth = columnWidths.get(i - 1);
                        if (valueStr.length() > currentWidth) {
                            columnWidths.set(i - 1, valueStr.length());
                        }
                    }
                    dataRows.add(rowData);
                }

                // 格式化表头
                StringBuilder header = new StringBuilder();
                header.append("| ");
                for (int i = 0; i < columnCount; i++) {
                    String columnName = columnNames.get(i);
                    int width = columnWidths.get(i);
                    header.append(String.format("%-" + width + "s", columnName)).append(" | ");
                }
                resultRows.add(header.toString());

                // 添加分隔线
                StringBuilder separator = new StringBuilder();
                separator.append("|");
                for (int i = 0; i < columnCount; i++) {
                    int width = columnWidths.get(i);
                    separator.append("-".repeat(width + 2)).append("|");
                }
                resultRows.add(separator.toString());

                // 格式化数据行
                if (dataRows.isEmpty()) {
                    StringBuilder emptyRow = new StringBuilder();
                    emptyRow.append("| ");
                    int totalWidth = columnWidths.stream().mapToInt(w -> w + 3).sum() - 1;
                    emptyRow.append(String.format("%-" + (totalWidth - 2) + "s", "(无数据)"));
                    emptyRow.append(" |");
                    resultRows.add(emptyRow.toString());
                } else {
                    for (List<String> rowData : dataRows) {
                        StringBuilder row = new StringBuilder();
                        row.append("| ");
                        for (int i = 0; i < columnCount; i++) {
                            String value = rowData.get(i);
                            int width = columnWidths.get(i);
                            row.append(String.format("%-" + width + "s", value)).append(" | ");
                        }
                        resultRows.add(row.toString());
                    }
                }

                return resultRows;
            });

            int dataRowCount = rows.size() - 2; // 减去表头和分隔线
            if (rows.size() > 2 && rows.get(rows.size() - 1).contains("(无数据)")) {
                dataRowCount = 0;
            }

            log.info("数据库工具查询完成: rowCount={}", dataRowCount);
            // 将结果格式化为字符串
            return "查询结果:\n" + String.join("\n", rows);
        } catch (Exception e) {
            log.error("数据库工具查询失败: exceptionType={}", e.getClass().getSimpleName());
            log.debug("数据库工具查询失败详情", e);
            return "错误：数据库查询失败";
        }
    }

    private String validateReadOnlySelect(String sql) {
        if (sql == null || sql.isBlank()) {
            return "查询语句不能为空";
        }
        if (sql.length() > MAX_SQL_LENGTH) {
            return "查询语句过长";
        }
        String normalized = sql.trim();
        String withoutTrailingSemicolon = normalized.endsWith(";")
                ? normalized.substring(0, normalized.length() - 1)
                : normalized;
        if (withoutTrailingSemicolon.contains(";")
                || normalized.contains("--")
                || normalized.contains("/*")
                || normalized.contains("*/")) {
            return "只允许一条不含注释的查询语句";
        }
        if (UNSAFE_IDENTIFIER_SYNTAX.matcher(withoutTrailingSemicolon).find()) {
            return "查询包含不受支持的标识符编码或引用方式";
        }
        String uppercase = withoutTrailingSemicolon.stripLeading().toUpperCase(Locale.ROOT);
        if (!uppercase.startsWith("SELECT ") && !uppercase.startsWith("SELECT\n")
                && !uppercase.startsWith("SELECT\t")) {
            return "仅支持 SELECT 查询";
        }
        if (FORBIDDEN_SQL.matcher(withoutTrailingSemicolon).find()) {
            return "查询包含被禁止的关键字或函数";
        }
        if (FORBIDDEN_TABLES.matcher(withoutTrailingSemicolon).find()) {
            return "禁止访问系统核心鉴权表与安全审计表";
        }
        if (FORBIDDEN_COLUMNS.matcher(withoutTrailingSemicolon).find()) {
            return "禁止查询包含敏感安全凭证的字段";
        }
        String relationError = validateAllowedRelations(withoutTrailingSemicolon);
        if (relationError != null) {
            return relationError;
        }
        return null;
    }

    private String validateAllowedRelations(String sql) {
        String lowercase = sql.toLowerCase(Locale.ROOT);
        int fromIndex = lowercase.indexOf("from");
        if (fromIndex >= 0 && sql.substring(fromIndex).contains(",")) {
            return "查询中的数据表必须使用显式 JOIN，且只能访问授权关系";
        }
        Matcher matcher = RELATION_REFERENCE.matcher(sql);
        int references = 0;
        while (matcher.find()) {
            references++;
            String relation = canonicalRelation(matcher.group(1));
            if (!allowedRelations.contains(relation)) {
                return "查询访问了未授权的数据表或视图: " + relation;
            }
        }
        if (RELATION_KEYWORD.matcher(sql).find() && references == 0) {
            return "无法安全识别查询中的数据表或视图";
        }
        return null;
    }

    private static Set<String> parseAllowedRelations(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(value.split(","))
                .map(DataBaseTools::canonicalRelation)
                .filter(relation -> !relation.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    private static String canonicalRelation(String relation) {
        String normalized = relation == null ? "" : relation.trim().toLowerCase(Locale.ROOT);
        return normalized.startsWith("public.") ? normalized.substring("public.".length()) : normalized;
    }
}

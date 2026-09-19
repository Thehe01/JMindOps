package com.kama.jmindops.governance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.kama.jmindops.exception.BizException;
import com.kama.jmindops.security.AuthenticatedUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class ToolGovernanceService {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    public ToolGovernanceService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }


    public ApprovalDecision requireApproval(String toolName, Object[] arguments, String riskLevel) {
        AuthenticatedUser user = currentUser();
        String sessionId = requireSessionId();
        String argumentsJson = serialize(arguments);
        String redactedArgumentsJson = maskSensitiveArguments(argumentsJson);
        String fingerprint = sha256("v2|" + user.id() + "|" + sessionId + "|"
                + toolName + "|" + riskLevel + "|" + argumentsJson);

        // Selection and state transition happen in one PostgreSQL statement. The
        // status predicate plus row lock makes a one-shot approval impossible to
        // consume twice under concurrent tool executions.
        List<String> consumedIds = jdbcTemplate.queryForList("""
                WITH candidate AS (
                    SELECT id FROM tool_approval
                    WHERE user_id = CAST(? AS uuid)
                      AND session_id = CAST(? AS uuid)
                      AND tool_name = ? AND fingerprint = ?
                      AND status = 'APPROVED' AND expires_at > NOW()
                    ORDER BY decided_at DESC NULLS LAST
                    LIMIT 1
                    FOR UPDATE SKIP LOCKED
                )
                UPDATE tool_approval approval
                SET status = 'CONSUMED', consumed_at = NOW()
                FROM candidate
                WHERE approval.id = candidate.id
                  AND approval.status = 'APPROVED'
                RETURNING approval.id::text
                """, String.class, user.id(), sessionId, toolName, fingerprint);
        if (!consumedIds.isEmpty()) {
            return ApprovalDecision.granted();
        }

        List<String> pendingIds = jdbcTemplate.queryForList("""
                SELECT id::text FROM tool_approval
                WHERE user_id = CAST(? AS uuid)
                  AND session_id = CAST(? AS uuid)
                  AND tool_name = ? AND fingerprint = ?
                  AND status = 'PENDING' AND expires_at > NOW()
                ORDER BY created_at DESC LIMIT 1
                """, String.class, user.id(), sessionId, toolName, fingerprint);
        if (!pendingIds.isEmpty()) return ApprovalDecision.waiting(pendingIds.get(0));

        String approvalId = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO tool_approval
                (id, user_id, session_id, tool_name, arguments_json, fingerprint, status, created_at, expires_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, CAST(? AS jsonb), ?, 'PENDING', NOW(), NOW() + INTERVAL '10 minutes')
                """, approvalId, user.id(), sessionId, toolName, redactedArgumentsJson, fingerprint);
        return ApprovalDecision.waiting(approvalId);
    }

    private String requireSessionId() {
        String sessionId = ToolExecutionContext.getSessionId();
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalStateException("A chat session is required for approval-protected tools");
        }
        try {
            UUID.fromString(sessionId);
            return sessionId;
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Tool approval session is not a valid UUID", exception);
        }
    }

    public void audit(String toolName, String riskLevel, String status, Object[] arguments, Object result, long latencyMs) {
        AuthenticatedUser user = currentUserOrNull();
        String serializedArguments = serialize(arguments);
        String argumentSummary = serialize(Map.of(
                "redacted", true,
                "argumentCount", arguments == null ? 0 : arguments.length,
                "serializedLength", serializedArguments.length(),
                "sha256", sha256(serializedArguments)
        ));
        String summary = summarizeResult(result);
        jdbcTemplate.update("""
                INSERT INTO tool_audit_log
                (id, user_id, session_id, tool_name, risk_level, status, arguments_json, result_summary, latency_ms, created_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?, ?, CAST(? AS jsonb), ?, ?, NOW())
                """, UUID.randomUUID().toString(), user == null ? null : user.id(), ToolExecutionContext.getSessionId(),
                toolName, riskLevel, status, argumentSummary, summary, latencyMs);
    }

    private static final Pattern SENSITIVE_PATTERN = Pattern.compile(
            "(?i)\"(password|secret|token|apiKey|api_key|authorization|auth)\"\\s*:\\s*\"([^\"]+)\""
    );
    private static final Set<String> SENSITIVE_FIELD_NAMES = Set.of(
            "password", "passwordhash", "secret", "clientsecret", "token", "accesstoken",
            "refreshtoken", "apikey", "authorization", "auth"
    );

    public List<Map<String, Object>> getApprovalsForCurrentUser(String status) {
        AuthenticatedUser user = currentUser();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id::text AS id, session_id::text AS "sessionId", tool_name AS "toolName",
                       arguments_json::text AS "arguments", status, created_at AS "createdAt", expires_at AS "expiresAt"
                FROM tool_approval
                WHERE user_id = CAST(? AS uuid) AND (? IS NULL OR status = ?)
                ORDER BY created_at DESC
                """, user.id(), status, status);
        for (Map<String, Object> row : rows) {
            String rawArgs = (String) row.get("arguments");
            if (rawArgs != null) {
                row.put("arguments", maskSensitiveArguments(rawArgs));
            }
        }
        return rows;
    }

    public ApprovalRecord decide(String approvalId, boolean approved) {
        AuthenticatedUser user = currentUser();
        String validatedApprovalId = validateApprovalId(approvalId);
        List<Map<String, Object>> records = jdbcTemplate.queryForList("""
                SELECT session_id::text AS "sessionId", tool_name AS "toolName"
                FROM tool_approval
                WHERE id = CAST(? AS uuid) AND user_id = CAST(? AS uuid)
                  AND status = 'PENDING' AND expires_at > NOW()
                """, validatedApprovalId, user.id());
        if (records.isEmpty()) {
            throw new BizException(409, "Approval does not exist or can no longer be decided");
        }
        String sessionId = (String) records.get(0).get("sessionId");
        String toolName = (String) records.get(0).get("toolName");

        int updated = jdbcTemplate.update("""
                UPDATE tool_approval SET status = ?, decided_at = NOW()
                WHERE id = CAST(? AS uuid) AND user_id = CAST(? AS uuid)
                  AND status = 'PENDING' AND expires_at > NOW()
                """, approved ? "APPROVED" : "REJECTED", validatedApprovalId, user.id());
        if (updated == 0) {
            throw new BizException(409, "Approval does not exist or can no longer be decided");
        }
        return new ApprovalRecord(validatedApprovalId, sessionId, toolName, approved);
    }

    public String maskSensitiveArguments(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return argumentsJson;
        }
        try {
            JsonNode parsed = objectMapper.readTree(argumentsJson);
            return objectMapper.writeValueAsString(redactNode(parsed));
        } catch (Exception ignored) {
            return SENSITIVE_PATTERN.matcher(argumentsJson).replaceAll("\"$1\":\"******\"");
        }
    }

    private JsonNode redactNode(JsonNode node) {
        if (node == null || node.isNull()) {
            return node;
        }
        if (node.isObject()) {
            ObjectNode redacted = ((ObjectNode) node).deepCopy();
            List<String> fieldNames = new ArrayList<>();
            redacted.fieldNames().forEachRemaining(fieldNames::add);
            for (String fieldName : fieldNames) {
                if (isSensitiveField(fieldName)) {
                    redacted.put(fieldName, "******");
                } else {
                    redacted.set(fieldName, redactNode(redacted.get(fieldName)));
                }
            }
            return redacted;
        }
        if (node.isArray()) {
            ArrayNode redacted = ((ArrayNode) node).deepCopy();
            for (int index = 0; index < redacted.size(); index++) {
                redacted.set(index, redactNode(redacted.get(index)));
            }
            return redacted;
        }
        if (node.isTextual()) {
            String value = node.textValue();
            String trimmed = value == null ? "" : value.trim();
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                try {
                    JsonNode nested = objectMapper.readTree(trimmed);
                    return TextNode.valueOf(objectMapper.writeValueAsString(redactNode(nested)));
                } catch (Exception ignored) {
                    // Preserve non-JSON strings exactly; the outer fallback remains available.
                }
            }
        }
        return node.deepCopy();
    }

    private boolean isSensitiveField(String fieldName) {
        String normalized = fieldName == null
                ? ""
                : fieldName.replace("_", "").replace("-", "").toLowerCase(java.util.Locale.ROOT);
        return SENSITIVE_FIELD_NAMES.contains(normalized);
    }

    private String validateApprovalId(String approvalId) {
        try {
            UUID parsed = UUID.fromString(approvalId);
            if (!parsed.toString().equalsIgnoreCase(approvalId)) {
                throw new IllegalArgumentException("non-canonical uuid");
            }
            return parsed.toString();
        } catch (RuntimeException exception) {
            throw new BizException(400, "Approval id is invalid");
        }
    }

    private AuthenticatedUser currentUser() {
        AuthenticatedUser user = currentUserOrNull();
        if (user == null) throw new IllegalStateException("Authenticated user is required for tool execution");
        return user;
    }

    private AuthenticatedUser currentUserOrNull() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser user ? user : null;
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "[\"serialization-failed\"]";
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to fingerprint tool request", e);
        }
    }

    private String summarizeResult(Object result) {
        if (result == null) {
            return null;
        }
        String value = String.valueOf(result);
        return "type=" + result.getClass().getSimpleName()
                + ", length=" + value.length()
                + ", sha256=" + sha256(value);
    }

    public record ApprovalDecision(boolean approved, String approvalId) {
        static ApprovalDecision granted() { return new ApprovalDecision(true, null); }
        static ApprovalDecision waiting(String approvalId) { return new ApprovalDecision(false, approvalId); }
    }

    public record ApprovalRecord(String approvalId, String sessionId, String toolName, boolean approved) {
    }
}

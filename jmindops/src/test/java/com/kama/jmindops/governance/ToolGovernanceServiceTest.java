package com.kama.jmindops.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jmindops.exception.BizException;
import com.kama.jmindops.security.AuthenticatedUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ToolGovernanceServiceTest {
    private static final String USER_ID = "59d8c62f-fd2c-4b3d-86da-105281dacaa2";
    private static final String SESSION_ID = "f284b8a1-7800-4a3f-bbb4-e92d8554a5f5";

    @Mock
    private JdbcTemplate jdbcTemplate;

    private ToolGovernanceService service;

    @BeforeEach
    void setUp() {
        service = new ToolGovernanceService(jdbcTemplate, new ObjectMapper());
        AuthenticatedUser principal = new AuthenticatedUser(USER_ID, "alice", "USER");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of())
        );
    }

    @AfterEach
    void tearDown() {
        ToolExecutionContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void consumesApprovalAtomicallyWithinUserSession() {
        ToolExecutionContext.setSessionId(SESSION_ID);
        when(jdbcTemplate.queryForList(
                anyString(), eq(String.class), eq(USER_ID), eq(SESSION_ID),
                eq("sendEmail"), any()
        )).thenReturn(List.of("approval-id"));

        ToolGovernanceService.ApprovalDecision decision = service.requireApproval(
                "sendEmail", new Object[]{"recipient@example.com"}, "HIGH"
        );

        assertTrue(decision.approved());
        verify(jdbcTemplate).queryForList(
                org.mockito.ArgumentMatchers.argThat(sql ->
                        sql.contains("FOR UPDATE SKIP LOCKED")
                                && sql.contains("session_id = CAST(? AS uuid)")
                                && sql.contains("status = 'CONSUMED'")),
                eq(String.class), eq(USER_ID), eq(SESSION_ID), eq("sendEmail"), any()
        );
    }

    @Test
    void rejectsApprovalProtectedToolWithoutSessionContext() {
        assertThrows(IllegalStateException.class,
                () -> service.requireApproval("sendEmail", new Object[0], "HIGH"));
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void masksSensitiveArgumentsProperly() {
        String input = "{\"username\":\"alice\",\"password\":\"secret123\",\"token\":\"jwt-xyz\",\"query\":\"SELECT 1\"}";
        String masked = service.maskSensitiveArguments(input);
        org.assertj.core.api.Assertions.assertThat(masked).contains("\"password\":\"******\"");
        org.assertj.core.api.Assertions.assertThat(masked).contains("\"token\":\"******\"");
        org.assertj.core.api.Assertions.assertThat(masked).contains("\"username\":\"alice\"");
    }

    @Test
    void masksSensitiveValuesInsideSerializedCallbackJson() {
        String input = "[\"{\\\"username\\\":\\\"alice\\\",\\\"password\\\":\\\"secret123\\\",\\\"token\\\":\\\"jwt-xyz\\\"}\"]";

        String masked = service.maskSensitiveArguments(input);

        assertThat(masked).doesNotContain("secret123", "jwt-xyz");
        assertThat(masked).contains("alice", "******");
    }

    @Test
    void storesOnlyRedactedApprovalArgumentsWhileFingerprintingOriginalInput() {
        ToolExecutionContext.setSessionId(SESSION_ID);
        when(jdbcTemplate.queryForList(
                anyString(), eq(String.class), eq(USER_ID), eq(SESSION_ID),
                eq("externalWrite"), any()
        )).thenReturn(List.of());
        service.requireApproval(
                "externalWrite",
                new Object[]{"{\"password\":\"do-not-store\"}"},
                "HIGH");

        verify(jdbcTemplate).update(
                org.mockito.ArgumentMatchers.argThat(sql -> sql.contains("INSERT INTO tool_approval")),
                any(), eq(USER_ID), eq(SESSION_ID), eq("externalWrite"),
                org.mockito.ArgumentMatchers.argThat((String value) ->
                        value.contains("******") && !value.contains("do-not-store")),
                any());
    }

    @Test
    void rejectsMalformedApprovalIdAsBadRequestWithoutQueryingDatabase() {
        BizException exception = assertThrows(BizException.class,
                () -> service.decide("not-a-uuid", true));

        assertThat(exception.getCode()).isEqualTo(400);
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void reportsExpiredOrAlreadyDecidedApprovalAsConflict() {
        String approvalId = "a1b2c3d4-e5f6-7890-abcd-ef1234567890";
        when(jdbcTemplate.queryForList(anyString(), eq(approvalId), eq(USER_ID)))
                .thenReturn(List.of());

        BizException exception = assertThrows(BizException.class,
                () -> service.decide(approvalId, true));

        assertThat(exception.getCode()).isEqualTo(409);
    }

    @Test
    void decidesApprovalAndReturnsApprovalRecord() {
        String approvalId = "a1b2c3d4-e5f6-7890-abcd-ef1234567890";
        when(jdbcTemplate.queryForList(anyString(), eq(approvalId), eq(USER_ID)))
                .thenReturn(List.of(java.util.Map.of("sessionId", SESSION_ID, "toolName", "sendEmail")));
        when(jdbcTemplate.update(anyString(), eq("APPROVED"), eq(approvalId), eq(USER_ID)))
                .thenReturn(1);

        ToolGovernanceService.ApprovalRecord record = service.decide(approvalId, true);
        org.assertj.core.api.Assertions.assertThat(record.approvalId()).isEqualTo(approvalId);
        org.assertj.core.api.Assertions.assertThat(record.sessionId()).isEqualTo(SESSION_ID);
        org.assertj.core.api.Assertions.assertThat(record.toolName()).isEqualTo("sendEmail");
        org.assertj.core.api.Assertions.assertThat(record.approved()).isTrue();
    }
}

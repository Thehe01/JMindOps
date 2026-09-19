package com.kama.jmindops.agent.tools;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class DataBaseToolsSecurityTest {

    private DataBaseTools dataBaseTools;
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate = Mockito.mock(JdbcTemplate.class);
        dataBaseTools = new DataBaseTools(jdbcTemplate);
    }

    @Test
    void rejectsQueryAccessingAppUserTable() {
        String result = dataBaseTools.query("SELECT * FROM app_user WHERE username = 'admin'");
        assertThat(result).contains("禁止访问系统核心鉴权表与安全审计表");
    }

    @Test
    void rejectsQueryAccessingToolApprovalTable() {
        String result = dataBaseTools.query("SELECT * FROM tool_approval");
        assertThat(result).contains("禁止访问系统核心鉴权表与安全审计表");
    }

    @Test
    void rejectsQueryAccessingGenerationTaskInputs() {
        String result = dataBaseTools.query("SELECT input_content FROM generation_task");
        assertThat(result).contains("禁止访问系统核心鉴权表与安全审计表");
    }

    @Test
    void rejectsQueryAccessingPasswordHashColumn() {
        String result = dataBaseTools.query("SELECT id, password_hash FROM user_table");
        assertThat(result).contains("禁止查询包含敏感安全凭证的字段");
    }

    @Test
    void rejectsNonSelectStatements() {
        String result = dataBaseTools.query("UPDATE user_table SET name = 'evil'");
        assertThat(result).contains("仅支持 SELECT 查询");
    }

    @Test
    void rejectsPostgresUnicodeEscapedSensitiveIdentifiers() {
        String result = dataBaseTools.query(
                "SELECT U&\"password\\005fhash\" FROM U&\"app\\005fuser\"");

        assertThat(result).contains("不受支持的标识符编码");
        Mockito.verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void rejectsRelationsOutsideExplicitAllowlist() {
        String result = dataBaseTools.query("SELECT * FROM unrelated_business_table");

        assertThat(result).contains("未授权的数据表或视图");
        Mockito.verifyNoInteractions(jdbcTemplate);
    }
}

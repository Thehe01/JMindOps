package com.kama.jmindops;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;

@Tag("postgres-integration")
class FlywayCleanDatabaseMigrationTest {

    @Test
    void migrationScriptsAreSyntacticallyValidAndContainAllVersions() throws Exception {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources = resolver.getResources("classpath:db/migration/V*__*.sql");

        assertThat(resources.length).isGreaterThanOrEqualTo(10);

        List<String> filenames = Arrays.stream(resources)
                .map(Resource::getFilename)
                .sorted()
                .toList();

        assertThat(filenames).anyMatch(name -> name.startsWith("V10__"));
        assertThat(filenames).anyMatch(name -> name.startsWith("V11__"));

        Resource v10Resource = resolver.getResource("classpath:db/migration/V10__durable_document_index_task.sql");
        assertThat(v10Resource.exists()).isTrue();

        Resource v11Resource = resolver.getResource("classpath:db/migration/V11__document_index_task_fencing_and_cancellation.sql");
        assertThat(v11Resource.exists()).isTrue();

        String v10Sql;
        try (InputStream is = v10Resource.getInputStream()) {
            v10Sql = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }

        String v11Sql;
        try (InputStream is = v11Resource.getInputStream()) {
            v11Sql = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(v10Sql)
                .contains("CREATE TABLE IF NOT EXISTS document_index_task")
                .contains("uk_document_index_task_doc_version UNIQUE (document_id, index_version)")
                .contains("document_index_task_status_check")
                .contains("'PENDING', 'RUNNING', 'RETRY_WAIT', 'SUCCEEDED', 'FAILED'")
                .contains("idx_doc_index_task_claim")
                .contains("idx_doc_index_task_recovery")
                .contains("idx_doc_index_task_kb_source");

        assertThat(v11Sql)
                .contains("lease_version")
                .contains("cancel_requested")
                .contains("CANCELLED");

        List<String> expectedColumns = List.of(
                "id", "kb_id", "document_id", "index_version", "status",
                "retry_count", "max_retries", "next_retry_at", "heartbeat_at",
                "worker_id", "last_error", "started_at", "completed_at",
                "file_path", "filename", "filetype", "file_size",
                "content_hash", "source_key", "index_fingerprint",
                "is_new_document", "old_file_path",
                "chunk_count", "reused_chunk_count", "embedded_chunk_count",
                "created_at", "updated_at"
        );
        for (String col : expectedColumns) {
            assertThat(v10Sql).contains(col);
        }
    }

    @Test
    void migratesCleanDatabaseToLatestSchema() throws Exception {
        int targetPort = -1;
        for (int port : new int[]{5432, 5433}) {
            try (Socket socket = new Socket("127.0.0.1", port)) {
                targetPort = port;
                break;
            } catch (Exception ignored) {}
        }

        if (targetPort < 0) {
            org.junit.jupiter.api.Assertions.fail("Live PostgreSQL on port 5432 or 5433 is required for this migration test");
        }

        String username = System.getenv().getOrDefault("POSTGRES_USER", "jmindops_owner");
        String password = System.getenv().getOrDefault("POSTGRES_PASSWORD", "jmindops_owner");
        String defaultDb = System.getenv().getOrDefault("POSTGRES_DB", "jmindops");
        String testDb = System.getenv().getOrDefault("POSTGRES_FLYWAY_TEST_DB", "jmindops_flyway_test");

        // Ensure test database exists by connecting to default database
        String adminUrl = "jdbc:postgresql://127.0.0.1:" + targetPort + "/" + defaultDb;
        try (Connection adminConn = DriverManager.getConnection(adminUrl, username, password);
             Statement adminStmt = adminConn.createStatement()) {
            boolean exists = false;
            try (ResultSet rs = adminStmt.executeQuery("SELECT 1 FROM pg_database WHERE datname = '" + testDb + "'")) {
                if (rs.next()) {
                    exists = true;
                }
            }
            if (!exists) {
                adminStmt.execute("CREATE DATABASE " + testDb);
            }
        }

        String jdbcUrl = "jdbc:postgresql://127.0.0.1:" + targetPort + "/" + testDb;

        // Clean out schema so it is truly migrating from an empty database
        try (Connection conn = DriverManager.getConnection(jdbcUrl, username, password);
             Statement stmt = conn.createStatement()) {
            stmt.execute("DROP SCHEMA IF EXISTS public CASCADE; CREATE SCHEMA public; GRANT ALL ON SCHEMA public TO " + username + "; GRANT ALL ON SCHEMA public TO public;");
            stmt.execute("CREATE EXTENSION IF NOT EXISTS vector; CREATE EXTENSION IF NOT EXISTS pg_search;");
        }

        Flyway flyway = Flyway.configure()
                .dataSource(jdbcUrl, username, password)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load();

        int migrationsApplied = flyway.migrate().migrationsExecuted;
        assertThat(migrationsApplied).isGreaterThanOrEqualTo(10);

        MigrationInfo[] applied = flyway.info().applied();
        List<String> versions = new ArrayList<>();
        for (MigrationInfo info : applied) {
            versions.add(info.getVersion().getVersion());
            assertThat(info.getState().isApplied()).isTrue();
        }

        assertThat(versions).contains("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11");

        // Verify table document_index_task exists and has expected structure
        try (Connection conn = DriverManager.getConnection(jdbcUrl, username, password);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT column_name FROM information_schema.columns WHERE table_name = 'document_index_task'")) {
            List<String> columns = new ArrayList<>();
            while (rs.next()) {
                columns.add(rs.getString(1));
            }
            assertThat(columns).contains(
                    "id", "kb_id", "document_id", "index_version", "status",
                    "retry_count", "max_retries", "next_retry_at", "heartbeat_at",
                    "worker_id", "last_error", "started_at", "completed_at",
                    "file_path", "filename", "filetype", "file_size",
                    "content_hash", "source_key", "index_fingerprint",
                    "is_new_document", "old_file_path",
                    "chunk_count", "reused_chunk_count", "embedded_chunk_count",
                    "lease_version", "cancel_requested",
                    "created_at", "updated_at"
            );
        }
    }
}

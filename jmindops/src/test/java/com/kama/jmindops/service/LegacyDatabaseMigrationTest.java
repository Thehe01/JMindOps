package com.kama.jmindops.service;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("postgres-integration")
public class LegacyDatabaseMigrationTest {

    @Test
    public void testLegacyMigrationV8ToLatest() throws Exception {
        int targetPort = -1;
        for (int port : new int[]{5432, 5433}) {
            try (java.net.Socket socket = new java.net.Socket("127.0.0.1", port)) {
                targetPort = port;
                break;
            } catch (Exception ignored) {}
        }

        if (targetPort < 0) {
            org.junit.jupiter.api.Assertions.fail("Live PostgreSQL on port 5432 or 5433 is required for this migration test");
        }

        String user = System.getenv().getOrDefault("POSTGRES_USER", "jmindops_owner");
        String pass = System.getenv().getOrDefault("POSTGRES_PASSWORD", "jmindops_owner");
        String defaultDb = System.getenv().getOrDefault("POSTGRES_DB", "jmindops");
        String legacyDb = System.getenv().getOrDefault("POSTGRES_LEGACY_TEST_DB", "jmindops_legacy_test");

        // Ensure legacyDb exists by connecting to default database
        String adminUrl = "jdbc:postgresql://127.0.0.1:" + targetPort + "/" + defaultDb;
        try (java.sql.Connection adminConn = java.sql.DriverManager.getConnection(adminUrl, user, pass);
             java.sql.Statement adminStmt = adminConn.createStatement()) {
            boolean exists = false;
            try (java.sql.ResultSet rs = adminStmt.executeQuery("SELECT 1 FROM pg_database WHERE datname = '" + legacyDb + "'")) {
                if (rs.next()) {
                    exists = true;
                }
            }
            if (!exists) {
                adminStmt.execute("CREATE DATABASE " + legacyDb);
            }
        }

        String url = "jdbc:postgresql://127.0.0.1:" + targetPort + "/" + legacyDb;
        DriverManagerDataSource dataSource = new DriverManagerDataSource(url, user, pass);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        // 1. Clean DB
        Flyway flyway = Flyway.configure().dataSource(dataSource).cleanDisabled(false).load();
        flyway.clean();

        // 2. Migrate to V8
        Flyway flywayV8 = Flyway.configure().dataSource(dataSource).target("8").load();
        flywayV8.migrate();

        // 3. Insert V8 legacy data
        String kbId = UUID.randomUUID().toString();
        String docId = UUID.randomUUID().toString();
        
        jdbcTemplate.update("INSERT INTO knowledge_base (id, name, created_at, updated_at) VALUES (CAST(? AS uuid), ?, ?, ?)",
                kbId, "Legacy KB", Timestamp.valueOf(LocalDateTime.now()), Timestamp.valueOf(LocalDateTime.now()));

        // Insert legacy document: READY, chunk_count > 0, index_fingerprint IS NULL
        jdbcTemplate.update("INSERT INTO document (id, kb_id, filename, source_key, index_status, chunk_count, created_at, updated_at) " +
                "VALUES (CAST(? AS uuid), CAST(? AS uuid), ?, ?, ?, ?, ?, ?)",
                docId, kbId, "Legacy Doc.md", "legacy_doc.md", "READY", 1, Timestamp.valueOf(LocalDateTime.now()), Timestamp.valueOf(LocalDateTime.now()));

        String chunkId = UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO chunk_bge_m3 (id, kb_id, doc_id, content, chunk_hash, chunk_index, document_version, created_at, updated_at) " +
                "VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?, ?, ?, ?, ?)",
                chunkId, kbId, docId, "Legacy Chunk Content", "dummy-hash", 0, 1, Timestamp.valueOf(LocalDateTime.now()), Timestamp.valueOf(LocalDateTime.now()));

        // 4. Migrate to latest
        Flyway flywayLatest = Flyway.configure().dataSource(dataSource).load();
        flywayLatest.migrate();

        // 5. Assert that the status became STALE (which is what V9 does to READY docs with missing fingerprints)
        String newStatus = jdbcTemplate.queryForObject("SELECT index_status FROM document WHERE id = CAST(? AS uuid)", String.class, docId);
        assertThat(newStatus).isEqualTo("STALE");

        // Clean up
        flyway.clean();
    }
}

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
    public void testLegacyMigrationV8ToLatest() {
        String url = System.getenv().getOrDefault("SPRING_DATASOURCE_URL", "jdbc:postgresql://127.0.0.1:5432/jmindops");
        String user = System.getenv().getOrDefault("SPRING_DATASOURCE_USERNAME", "jmindops_owner");
        String pass = System.getenv().getOrDefault("SPRING_DATASOURCE_PASSWORD", "jmindops_owner");

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
        jdbcTemplate.update("INSERT INTO document (id, kb_id, name, status, index_status, chunk_count, created_at, updated_at) " +
                "VALUES (CAST(? AS uuid), CAST(? AS uuid), ?, ?, ?, ?, ?, ?)",
                docId, kbId, "Legacy Doc", "READY", "READY", 10, Timestamp.valueOf(LocalDateTime.now()), Timestamp.valueOf(LocalDateTime.now()));

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

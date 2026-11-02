package com.maiconamaral.acompanhaai;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.sql.DriverManager;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "spring.datasource.url", matches = "jdbc:postgresql:.*")
class PostgresMigrationIntegrationTest {
    @Test
    void upgradesV5WithExistingUserAndApplicationAndPreservesData() throws Exception {
        String url = System.getProperty("spring.datasource.url");
        String username = System.getProperty("spring.datasource.username");
        String password = System.getProperty("spring.datasource.password", "");
        String schema = "kan25_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(url, username, password);
             var sql = connection.createStatement()) {
            try {
                var previous = Flyway.configure().dataSource(url, username, password)
                        .schemas(schema).defaultSchema(schema).target("5").load();
                assertThat(previous.migrate().migrationsExecuted).isEqualTo(5);
                sql.execute("SET search_path TO " + schema);
                sql.executeUpdate("""
                        INSERT INTO tb_user(id, name, email, password_hash, auth_version, created_at)
                        VALUES (1001, 'Usuário V5', 'upgrade@example.test', 'test-hash-preserved', 7,
                                TIMESTAMPTZ '2026-01-01 00:00:00+00')
                        """);
                sql.executeUpdate("INSERT INTO tb_job(id, user_id, title) VALUES (1001, 1001, 'Vaga V5')");
                sql.executeUpdate("""
                        INSERT INTO tb_application(id, user_id, job_id, status, notes, created_at, updated_at, version)
                        VALUES (1001, 1001, 1001, 'INTERVIEW', 'Preservar candidatura', now(), now(), 3)
                        """);
                var current = Flyway.configure().dataSource(url, username, password)
                        .schemas(schema).defaultSchema(schema).load();
                assertThat(current.migrate().migrationsExecuted).isEqualTo(1);
                current.validate();
                assertThat(current.info().current().getVersion().getVersion()).isEqualTo("6");
                try (var row = sql.executeQuery("SELECT name, email, password_hash, auth_version FROM tb_user WHERE id=1001")) {
                    assertThat(row.next()).isTrue();
                    assertThat(row.getString(1)).isEqualTo("Usuário V5");
                    assertThat(row.getString(2)).isEqualTo("upgrade@example.test");
                    assertThat(row.getString(3)).isEqualTo("test-hash-preserved");
                    assertThat(row.getLong(4)).isEqualTo(7);
                }
                try (var row = sql.executeQuery("SELECT status, notes, version FROM tb_application WHERE id=1001")) {
                    assertThat(row.next()).isTrue();
                    assertThat(row.getString(1)).isEqualTo("INTERVIEW");
                    assertThat(row.getString(2)).isEqualTo("Preservar candidatura");
                    assertThat(row.getLong(3)).isEqualTo(3);
                }
                try (var row = sql.executeQuery("SELECT count(*) FROM tb_password_reset_token")) {
                    row.next();
                    assertThat(row.getLong(1)).isZero();
                }
                assertThat(current.migrate().migrationsExecuted).isZero();
            } finally {
                // Apenas o schema aleatório criado por este teste; nunca public.
                sql.execute("SET search_path TO public");
                sql.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }
}

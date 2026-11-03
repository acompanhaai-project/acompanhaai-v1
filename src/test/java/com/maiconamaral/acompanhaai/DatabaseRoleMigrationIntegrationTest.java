package com.maiconamaral.acompanhaai;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@EnabledIfSystemProperty(named = "kan29.isolated", matches = "true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DatabaseRoleMigrationIntegrationTest {

    @Test
    @Order(1)
    void adoptsExistingV5AndMigratesV6WithoutLosingUser() throws Exception {
        String url = System.getProperty("spring.datasource.url");
        var old = Flyway.configure().dataSource(url, "acompanhaai_qa_admin", "").target("5").load();
        assertThat(old.migrate().migrationsExecuted).isEqualTo(5);
        try (var admin = admin(url); var sql = admin.createStatement()) {
            sql.executeUpdate(
                """
                INSERT INTO public.tb_user(id, name, email, password_hash, auth_version, created_at)
                VALUES (1001, 'QA preservado', 'kan29-preservado@example.test', 'hash-ficticio', 7, now())
                """
            );
            provision(admin, "acompanhaai");
        }
        var upgraded = migration(url);
        assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(1);
        upgraded.validate();
        try (
            var runtime = DriverManager.getConnection(url, "acompanhaai_app", "");
            var sql = runtime.createStatement();
            var row = sql.executeQuery("SELECT name, auth_version FROM public.tb_user WHERE id = 1001")
        ) {
            assertThat(row.next()).isTrue();
            assertThat(row.getString(1)).isEqualTo("QA preservado");
            assertThat(row.getLong(2)).isEqualTo(7);
        }
        assertThat(upgraded.migrate().migrationsExecuted).isZero();
    }

    @Test
    @Order(2)
    void createsCleanDatabaseWithMigratorAndGrantsRuntimeAccess() throws Exception {
        String primary = System.getProperty("spring.datasource.url");
        String clean = primary.replace("/acompanhaai", "/acompanhaai_kan29_clean");
        try (var admin = admin(primary); var sql = admin.createStatement()) {
            sql.execute("CREATE DATABASE acompanhaai_kan29_clean");
        }
        try (var admin = admin(clean)) {
            provision(admin, "acompanhaai_kan29_clean");
        }
        var flyway = migration(clean);
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(6);
        flyway.validate();
        try (
            var runtime = DriverManager.getConnection(clean, "acompanhaai_app", "");
            var sql = runtime.createStatement();
            var row = sql.executeQuery("SELECT count(*) FROM public.tb_password_reset_token")
        ) {
            assertThat(row.next()).isTrue();
            assertThat(row.getLong(1)).isZero();
        }
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }

    private Connection admin(String url) throws Exception {
        if (url == null || !url.startsWith("jdbc:postgresql://127.0.0.1:")) {
            throw new IllegalStateException("Teste exclusivo para o cluster local descartável da KAN-29.");
        }
        var connection = DriverManager.getConnection(url, "acompanhaai_qa_admin", "");
        try (var sql = connection.createStatement(); var row = sql.executeQuery("SELECT session_user")) {
            row.next();
            assertThat(row.getString(1)).isEqualTo("acompanhaai_qa_admin");
        }
        return connection;
    }

    private void provision(Connection admin, String database) throws Exception {
        try (var setting = admin.prepareStatement("SELECT set_config('acompanhaai.expected_database', ?, false)")) {
            setting.setString(1, database);
            setting.execute();
        }
        for (String file : new String[] { "create-database-roles.sql", "adopt-database-objects.sql" }) {
            try (var sql = admin.createStatement()) {
                sql.execute(Files.readString(Path.of("scripts/sql", file)));
            }
        }
    }

    private Flyway migration(String url) {
        return Flyway.configure()
            .dataSource(url, "acompanhaai_migrator", "")
            .locations("classpath:db/migration", "classpath:db/callback")
            .load();
    }
}

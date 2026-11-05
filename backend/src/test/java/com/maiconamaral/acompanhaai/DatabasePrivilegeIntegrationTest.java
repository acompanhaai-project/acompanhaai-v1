package com.maiconamaral.acompanhaai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@EnabledIfSystemProperty(named = "kan29.isolated", matches = "true")
class DatabasePrivilegeIntegrationTest {

    @Test
    void runtimeCanCrudButCannotChangeSchemaOrFlywayHistory() throws Exception {
        try (var connection = connect("acompanhaai_app"); var sql = connection.createStatement()) {
            connection.setAutoCommit(false);
            try {
                sql.executeUpdate(
                    """
                    INSERT INTO public.tb_user(name, email, password_hash, auth_version, created_at)
                    VALUES ('QA CRUD', 'kan29-crud@example.test', 'hash-ficticio', 0, now())
                    """
                );
                assertThat(
                    sql.executeUpdate(
                        "UPDATE public.tb_user SET name = 'QA alterado' WHERE email = 'kan29-crud@example.test'"
                    )
                ).isEqualTo(1);
                try (
                    var row = sql.executeQuery(
                        "SELECT name FROM public.tb_user WHERE email = 'kan29-crud@example.test'"
                    )
                ) {
                    assertThat(row.next()).isTrue();
                    assertThat(row.getString(1)).isEqualTo("QA alterado");
                }
                assertThat(
                    sql.executeUpdate("DELETE FROM public.tb_user WHERE email = 'kan29-crud@example.test'")
                ).isEqualTo(1);
            } finally {
                connection.rollback();
            }
        }
        denied("CREATE TABLE public.kan29_forbidden(id integer)");
        denied("ALTER TABLE public.tb_user ADD COLUMN kan29_forbidden integer");
        denied("TRUNCATE public.tb_user CASCADE");
        denied("UPDATE public.flyway_schema_history SET success = false WHERE false");
        denied("SELECT * FROM public.flyway_schema_history");
        denied("SET ROLE acompanhaai_migrator");
    }

    @Test
    void runtimeAndMigratorHaveNoClusterAdministrationOrMemberships() throws Exception {
        for (String name : new String[] { "acompanhaai_app", "acompanhaai_migrator" }) {
            try (
                var connection = connect(name);
                var sql = connection.createStatement();
                var row = sql.executeQuery(
                    """
                    SELECT rolsuper, rolcreatedb, rolcreaterole, rolreplication, rolbypassrls,
                        (SELECT count(*) FROM pg_auth_members WHERE member = role.oid),
                        has_database_privilege(current_database(), 'CREATE'),
                        has_database_privilege(current_database(), 'TEMP')
                    FROM pg_roles AS role WHERE rolname = current_user
                    """
                )
            ) {
                assertThat(row.next()).isTrue();
                for (int column = 1; column <= 5; column++) {
                    assertThat(row.getBoolean(column)).isFalse();
                }
                assertThat(row.getLong(6)).isZero();
                assertThat(row.getBoolean(7)).isFalse();
                assertThat(row.getBoolean(8)).isFalse();
            }
        }
    }

    @Test
    void migratorOwnsTablesAndCanPerformTransactionalDdl() throws Exception {
        try (var connection = connect("acompanhaai_migrator"); var sql = connection.createStatement()) {
            connection.setAutoCommit(false);
            try {
                sql.execute("CREATE TABLE public.kan29_migration_probe(id integer)");
                sql.execute("ALTER TABLE public.kan29_migration_probe ADD COLUMN note text");
                try (
                    var row = sql.executeQuery(
                        """
                        SELECT count(*) FROM pg_class AS relation
                        JOIN pg_namespace AS schema ON schema.oid = relation.relnamespace
                        WHERE schema.nspname = 'public' AND relation.relkind = 'r'
                            AND pg_get_userbyid(relation.relowner) <> 'acompanhaai_migrator'
                        """
                    )
                ) {
                    row.next();
                    assertThat(row.getLong(1)).isZero();
                }
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    void futureTablesRequireExplicitGrantAndSequenceCannotBeResetByRuntime() throws Exception {
        try (var migration = connect("acompanhaai_migrator"); var sql = migration.createStatement()) {
            try {
                sql.execute("CREATE TABLE public.kan29_future_probe(id integer)");
                denied("SELECT * FROM public.kan29_future_probe");
                try (
                    var runtime = connect("acompanhaai_app");
                    var check = runtime.createStatement();
                    var row = check.executeQuery(
                        """
                        SELECT has_sequence_privilege('public.tb_user_id_seq', 'USAGE'),
                            has_sequence_privilege('public.tb_user_id_seq', 'UPDATE'),
                            has_schema_privilege('public', 'CREATE')
                        """
                    )
                ) {
                    row.next();
                    assertThat(row.getBoolean(1)).isTrue();
                    assertThat(row.getBoolean(2)).isFalse();
                    assertThat(row.getBoolean(3)).isFalse();
                }
            } finally {
                sql.execute("DROP TABLE IF EXISTS public.kan29_future_probe");
            }
        }
    }

    private Connection connect(String role) throws SQLException {
        String url = System.getProperty("spring.datasource.url");
        if (url == null || !url.startsWith("jdbc:postgresql://127.0.0.1:")) {
            throw new IllegalStateException("Teste exclusivo para o cluster descartável da KAN-29.");
        }
        return DriverManager.getConnection(url, role, "");
    }

    private void denied(String command) throws Exception {
        try (var connection = connect("acompanhaai_app"); var sql = connection.createStatement()) {
            connection.setAutoCommit(false);
            try {
                assertThatThrownBy(() -> sql.execute(command))
                    .isInstanceOf(SQLException.class)
                    .satisfies(error -> assertThat(((SQLException) error).getSQLState()).isEqualTo("42501"));
            } finally {
                connection.rollback();
            }
        }
    }
}

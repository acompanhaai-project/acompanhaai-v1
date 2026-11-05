package com.maiconamaral.acompanhaai;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class SingleSalaryMigrationIntegrationTest {

    @Test
    void upgradesV6PreservingRangesAndOnlyConvertsExactPositiveBrlSalaryWithPeriod() throws Exception {
        String url =
            "jdbc:h2:mem:kan30_" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        // Keep physical sessions alive: H2 CHECK expressions can retain the DDL session.
        try (var dataSource = new HikariDataSource()) {
            dataSource.setJdbcUrl(url);
            dataSource.setUsername("sa");
            dataSource.setPassword("");
            dataSource.setMaximumPoolSize(4);
            var previous = Flyway.configure().dataSource(dataSource).target("6").load();
            assertThat(previous.migrate().migrationsExecuted).isEqualTo(6);
            try (var connection = dataSource.getConnection(); var sql = connection.createStatement()) {
                sql.executeUpdate(
                    """
                    INSERT INTO tb_user(id, name, email, password_hash, auth_version, created_at)
                    VALUES (1, 'Teste migração', 'kan30-migration@example.test', 'hash-ficticio', 0, now())
                    """
                );
                sql.executeUpdate(
                    """
                    INSERT INTO tb_job(id, user_id, title, salary_min, salary_max, salary_currency, salary_period,
                                       salary_original_text)
                    VALUES (1, 1, 'Exato BRL', 5000.25, 5000.25, 'BRL', 'MONTH', 'R$ 5.000,25 por mês'),
                           (2, 1, 'Faixa', 4000, 6000, 'BRL', 'MONTH', 'R$ 4 mil a 6 mil'),
                           (3, 1, 'Só mínimo', 4000, NULL, 'BRL', 'MONTH', 'A partir de R$ 4 mil'),
                           (4, 1, 'Só máximo', NULL, 6000, 'BRL', 'MONTH', 'Até R$ 6 mil'),
                           (5, 1, 'Outra moeda', 5000, 5000, 'USD', 'MONTH', 'USD 5000'),
                           (6, 1, 'Sem período', 5000, 5000, 'BRL', NULL, 'R$ 5000'),
                           (7, 1, 'Sem salário', NULL, NULL, NULL, NULL, 'A combinar'),
                           (8, 1, 'Zero legado', 0, 0, 'BRL', 'MONTH', 'Legado')
                    """
                );
                var current = Flyway.configure().dataSource(dataSource).load();
                assertThat(current.migrate().migrationsExecuted).isEqualTo(1);
                current.validate();
                try (var rows = sql.executeQuery("SELECT * FROM tb_job ORDER BY id")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getBigDecimal("salary")).isEqualByComparingTo("5000.25");
                    assertThat(rows.getString("salary_original_text")).isEqualTo("R$ 5.000,25 por mês");
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getBigDecimal("salary")).isNull();
                    assertThat(rows.getBigDecimal("salary_min")).isEqualByComparingTo("4000");
                    assertThat(rows.getBigDecimal("salary_max")).isEqualByComparingTo("6000");
                    assertThat(rows.getString("salary_original_text")).isEqualTo("R$ 4 mil a 6 mil");
                    int remaining = 0;
                    while (rows.next()) {
                        assertThat(rows.getBigDecimal("salary")).isNull();
                        remaining++;
                    }
                    assertThat(remaining).isEqualTo(6);
                }
                assertThat(current.migrate().migrationsExecuted).isZero();
            }
        }
    }
}

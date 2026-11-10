package com.maiconamaral.acompanhaai.services;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Objects;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class DatabaseClock {

    private final JdbcTemplate jdbc;

    public DatabaseClock(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Instant now() {
        return Objects.requireNonNull(jdbc.execute((ConnectionCallback<Instant>) connection -> {
            // No PostgreSQL, CURRENT_TIMESTAMP permanece fixo enquanto a transação aguarda um lock.
            String expression = "PostgreSQL".equals(connection.getMetaData().getDatabaseProductName())
                ? "clock_timestamp()"
                : "CURRENT_TIMESTAMP";
            try (
                var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT " + expression)
            ) {
                result.next();
                return result.getObject(1, OffsetDateTime.class).toInstant();
            }
        }));
    }
}

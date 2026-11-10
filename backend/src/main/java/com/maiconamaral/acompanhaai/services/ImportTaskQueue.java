package com.maiconamaral.acompanhaai.services;

import com.maiconamaral.acompanhaai.entities.ImportTask;
import com.maiconamaral.acompanhaai.entities.enums.ImportInputType;
import com.maiconamaral.acompanhaai.entities.enums.ImportStatus;
import com.maiconamaral.acompanhaai.repositories.ImportTaskRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ImportTaskQueue {

    private static final Duration RETENTION = Duration.ofHours(24);
    private static final Duration LEASE = Duration.ofMinutes(2);
    private static final int MAX_ATTEMPTS = 3;
    static final int BATCH_SIZE = 100;

    private final ImportTaskRepository tasks;
    private final JdbcTemplate jdbc;

    public ImportTaskQueue(ImportTaskRepository tasks, JdbcTemplate jdbc) {
        this.tasks = tasks;
        this.jdbc = jdbc;
    }

    @Transactional
    public Optional<Claim> claim() {
        var now = databaseTime();
        var pending = tasks.lockNext(now, now.minus(RETENTION), MAX_ATTEMPTS);
        if (pending.isEmpty()) {
            return Optional.empty();
        }

        var task = pending.get();
        var startedAt = databaseTime();
        var expiresAt = task.getCreatedAt().plus(RETENTION);
        if (!expiresAt.isAfter(startedAt)) {
            return Optional.empty();
        }

        var deadline = startedAt.plus(LEASE);
        if (expiresAt.isBefore(deadline)) {
            deadline = expiresAt;
        }
        var token = UUID.randomUUID().toString();
        task.setStatus(ImportStatus.PROCESSING);
        task.setLockToken(token);
        task.setLockedUntil(deadline);
        task.setAttemptCount(task.getAttemptCount() + 1);
        task.setNextAttemptAt(null);
        task.setStartedAt(startedAt);
        task.setFinishedAt(null);

        var input = task.getInputType() == ImportInputType.URL ? task.getSourceUrl() : task.getInputText();
        return Optional.of(new Claim(task.getId(), token, task.getInputType(), input, deadline));
    }

    @Transactional
    public boolean finish(Claim claim, ImportTaskProcessor.Outcome outcome) {
        var owned = tasks.lockOwned(claim.id(), claim.token());
        if (owned.isEmpty()) {
            return false;
        }

        var task = owned.get();
        var now = databaseTime();
        if (!task.getLockedUntil().isAfter(now) || !task.getCreatedAt().plus(RETENTION).isAfter(now)) {
            return false;
        }

        switch (outcome) {
            case ImportTaskProcessor.Ready ready -> {
                task.setStatus(ImportStatus.READY_FOR_REVIEW);
                task.setCollectedText(ready.collectedText());
                task.setExtractedData(ready.proposal());
                task.setResultSchemaVersion(1);
            }
            case ImportTaskProcessor.NeedsInput needsInput -> {
                if (task.getInputType() == ImportInputType.URL) {
                    task.setStatus(ImportStatus.NEEDS_INPUT);
                    task.setErrorCode(needsInput.reason().name());
                    task.setErrorMessage(needsInput.reason().message);
                } else {
                    fail(task, ImportTaskProcessor.Failure.PROCESSING_FAILED);
                }
            }
            case ImportTaskProcessor.Failed failed -> fail(task, failed.reason());
            case ImportTaskProcessor.Retry retry -> requeueOrFail(task, now);
        }
        release(task, now);
        return true;
    }

    @Transactional
    public int recoverInterrupted() {
        var now = databaseTime();
        var interrupted = tasks.lockInterrupted(now, now.minus(RETENTION), BATCH_SIZE);
        for (var task : interrupted) {
            requeueOrFail(task, now);
            release(task, now);
        }
        return interrupted.size();
    }

    @Transactional
    public int purgeExpiredBatch() {
        var expired = tasks.lockExpired(databaseTime().minus(RETENTION), BATCH_SIZE);
        if (!expired.isEmpty()) {
            tasks.deleteAllByIdInBatch(expired);
        }
        return expired.size();
    }

    private void fail(ImportTask task, ImportTaskProcessor.Failure reason) {
        task.setStatus(ImportStatus.FAILED);
        task.setErrorCode(reason.name());
        task.setErrorMessage(reason.message);
    }

    private void requeueOrFail(ImportTask task, Instant now) {
        if (task.getAttemptCount() >= MAX_ATTEMPTS) {
            task.setStatus(ImportStatus.FAILED);
            task.setNextAttemptAt(null);
            task.setErrorCode("ATTEMPTS_EXHAUSTED");
            task.setErrorMessage("O processamento não terminou após novas tentativas.");
            return;
        }

        task.setStatus(ImportStatus.PENDING);
        task.setNextAttemptAt(now.plusSeconds(15L * task.getAttemptCount()));
    }

    private void release(ImportTask task, Instant now) {
        task.setLockToken(null);
        task.setLockedUntil(null);
        task.setFinishedAt(now);
    }

    private Instant databaseTime() {
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

    public record Claim(
        long id,
        String token,
        ImportInputType inputType,
        String input,
        Instant deadline
    ) {
    }
}

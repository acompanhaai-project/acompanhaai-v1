package com.maiconamaral.acompanhaai.services;

import com.maiconamaral.acompanhaai.entities.ImportTask;
import com.maiconamaral.acompanhaai.entities.enums.ImportInputType;
import com.maiconamaral.acompanhaai.entities.enums.ImportStatus;
import com.maiconamaral.acompanhaai.repositories.ImportTaskRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ImportTaskQueue {

    static final Duration RETENTION = Duration.ofHours(24);
    static final int BATCH_SIZE = 100;
    private static final Logger log = LoggerFactory.getLogger(ImportTaskQueue.class);

    private final ImportTaskRepository tasks;
    private final DatabaseClock clock;
    private final Duration lease;
    private final int maxAttempts;

    @Autowired
    public ImportTaskQueue(
        ImportTaskRepository tasks,
        DatabaseClock clock,
        @Value("${app.import-worker.lease:2m}") Duration lease,
        @Value("${app.import-worker.max-attempts:3}") int maxAttempts
    ) {
        if (lease.isNegative() || lease.isZero() || maxAttempts < 1) {
            throw new IllegalArgumentException("Posse e tentativas da importação devem ser positivas.");
        }
        this.tasks = tasks;
        this.clock = clock;
        this.lease = lease;
        this.maxAttempts = maxAttempts;
    }

    @Transactional
    public Optional<Claim> claim() {
        var now = databaseTime();
        var pending = tasks.lockNext(now, now.minus(RETENTION), maxAttempts);
        if (pending.isEmpty()) {
            return Optional.empty();
        }

        var task = pending.get();
        var startedAt = databaseTime();
        var expiresAt = task.getCreatedAt().plus(RETENTION);
        if (!expiresAt.isAfter(startedAt)) {
            return Optional.empty();
        }

        var deadline = startedAt.plus(lease);
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
                task.setExtractorProvider(ready.extractorProvider());
                task.setExtractorModel(ready.extractorModel());
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
        record(task, now);
        release(task, now);
        return true;
    }

    @Transactional
    public int recoverInterrupted() {
        var now = databaseTime();
        var interrupted = tasks.lockInterrupted(now, now.minus(RETENTION), BATCH_SIZE);
        for (var task : interrupted) {
            requeueOrFail(task, now);
            record(task, now);
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
        if (task.getAttemptCount() >= maxAttempts) {
            task.setStatus(ImportStatus.FAILED);
            task.setNextAttemptAt(null);
            task.setErrorCode("ATTEMPTS_EXHAUSTED");
            task.setErrorMessage("O processamento não terminou após novas tentativas.");
            return;
        }

        task.setStatus(ImportStatus.PENDING);
        task.setNextAttemptAt(now.plusSeconds(15L * task.getAttemptCount()));
    }

    private void record(ImportTask task, Instant now) {
        // Somente identificadores e categorias: URL, descrição e proposta ficam fora dos logs.
        log.info(
            "importacao id={} tipo={} tentativa={} status={} codigo={} duracaoMs={}",
            task.getId(),
            task.getInputType(),
            task.getAttemptCount(),
            task.getStatus(),
            task.getErrorCode() == null ? "-" : task.getErrorCode(),
            task.getStartedAt() == null ? 0 : Duration.between(task.getStartedAt(), now).toMillis()
        );
    }

    private void release(ImportTask task, Instant now) {
        task.setLockToken(null);
        task.setLockedUntil(null);
        task.setFinishedAt(now);
    }

    private Instant databaseTime() {
        return clock.now();
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

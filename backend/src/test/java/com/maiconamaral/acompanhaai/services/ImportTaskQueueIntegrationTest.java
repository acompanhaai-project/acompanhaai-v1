package com.maiconamaral.acompanhaai.services;

import static org.assertj.core.api.Assertions.assertThat;

import com.maiconamaral.acompanhaai.entities.ImportTask;
import com.maiconamaral.acompanhaai.entities.Job;
import com.maiconamaral.acompanhaai.entities.User;
import com.maiconamaral.acompanhaai.entities.enums.ImportInputType;
import com.maiconamaral.acompanhaai.entities.enums.ImportStatus;
import com.maiconamaral.acompanhaai.repositories.ImportTaskRepository;
import com.maiconamaral.acompanhaai.repositories.JobRepository;
import com.maiconamaral.acompanhaai.repositories.UserRepository;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = "spring.config.import=")
@ActiveProfiles("test")
class ImportTaskQueueIntegrationTest {

    private static final String URL = "https://example.test/vagas/java";
    private static final String DESCRIPTION = "Vaga Java em trabalho remoto.";

    @Autowired
    private ImportTaskQueue queue;

    @Autowired
    private ImportTaskRepository tasks;

    @Autowired
    private UserRepository users;

    @Autowired
    private JobRepository jobs;

    @Autowired
    private JdbcTemplate jdbc;

    private User user;

    @BeforeEach
    void createUser() {
        user = users.saveAndFlush(new User(
            "Pessoa Teste",
            "importacao+" + UUID.randomUUID() + "@example.test",
            "hash-ficticio"
        ));
    }

    @AfterEach
    void removeUser() {
        if (user != null) {
            users.deleteById(user.getId());
        }
    }

    @Test
    void claimsTheSameTaskOnlyOnceUnderConcurrency() throws Exception {
        var task = enqueue(ImportInputType.TEXT, DESCRIPTION);
        var barrier = new CyclicBarrier(2);
        Callable<Optional<ImportTaskQueue.Claim>> contender = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            return queue.claim();
        };

        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = workers.submit(contender);
            var second = workers.submit(contender);
            var claims = Stream.of(
                first.get(10, TimeUnit.SECONDS),
                second.get(10, TimeUnit.SECONDS)
            ).flatMap(Optional::stream).toList();
            assertThat(claims).hasSize(1);
            assertThat(claims.getFirst().id()).isEqualTo(task.getId());
        }

        var processing = stored(task);
        assertThat(processing.getStatus()).isEqualTo(ImportStatus.PROCESSING);
        assertThat(processing.getAttemptCount()).isEqualTo(1);
        assertThat(processing.getLockToken()).isNotBlank();
        assertThat(processing.getLockedUntil()).isAfter(processing.getStartedAt());
    }

    @Test
    void recoversExpiredLeaseAndRejectsOldAndDuplicateResults() {
        var task = enqueue(ImportInputType.TEXT, DESCRIPTION);
        var old = queue.claim().orElseThrow();
        expireLease(task);

        assertThat(queue.finish(old, ready())).isFalse();
        assertThat(queue.recoverInterrupted()).isEqualTo(1);
        var pending = stored(task);
        assertThat(pending.getStatus()).isEqualTo(ImportStatus.PENDING);
        assertThat(pending.getLockToken()).isNull();
        assertThat(pending.getLockedUntil()).isNull();
        assertThat(pending.getNextAttemptAt()).isAfter(pending.getFinishedAt());
        assertThat(queue.claim()).isEmpty();

        makeDue(task);
        var current = queue.claim().orElseThrow();
        assertThat(current.token()).isNotEqualTo(old.token());
        assertThat(queue.finish(old, ready())).isFalse();
        assertThat(queue.finish(current, ready())).isTrue();
        assertThat(queue.finish(current, ready())).isFalse();

        var review = stored(task);
        assertThat(review.getStatus()).isEqualTo(ImportStatus.READY_FOR_REVIEW);
        assertThat(review.getExtractedData()).containsEntry("title", "Pessoa desenvolvedora Java");
        assertThat(review.getResultSchemaVersion()).isEqualTo(1);
        assertThat(review.getAttemptCount()).isEqualTo(2);
        assertThat(review.getLockToken()).isNull();
    }

    @Test
    void limitsRetriesIncludingAnInterruptedLastAttempt() {
        var task = enqueue(ImportInputType.URL, URL);
        for (int attempt = 1; attempt <= 2; attempt++) {
            var claim = queue.claim().orElseThrow();
            assertThat(queue.finish(claim, new ImportTaskProcessor.Retry())).isTrue();
            assertThat(stored(task).getAttemptCount()).isEqualTo(attempt);
            assertThat(queue.claim()).isEmpty();
            makeDue(task);
        }

        var last = queue.claim().orElseThrow();
        expireLease(task);
        assertThat(queue.recoverInterrupted()).isEqualTo(1);
        var failed = stored(task);
        assertThat(failed.getStatus()).isEqualTo(ImportStatus.FAILED);
        assertThat(failed.getAttemptCount()).isEqualTo(3);
        assertThat(failed.getErrorCode()).isEqualTo("ATTEMPTS_EXHAUSTED");
        assertThat(failed.getNextAttemptAt()).isNull();
        assertThat(queue.claim()).isEmpty();
        assertThat(queue.finish(last, ready())).isFalse();
    }

    @Test
    void discardsResultsAfterTaskOrAccountDeletion() {
        var cancelled = enqueue(ImportInputType.TEXT, DESCRIPTION);
        var first = queue.claim().orElseThrow();
        tasks.deleteById(cancelled.getId());
        assertThat(queue.finish(first, ready())).isFalse();

        var removedWithUser = enqueue(ImportInputType.TEXT, DESCRIPTION);
        var second = queue.claim().orElseThrow();
        users.deleteById(user.getId());
        user = null;
        assertThat(tasks.findById(removedWithUser.getId())).isEmpty();
        assertThat(queue.finish(second, ready())).isFalse();
    }

    @Test
    void purgesExpiredTasksWithoutRemovingConfirmedJob() {
        var job = jobs.saveAndFlush(new Job(user, "Vaga confirmada"));
        var completed = new ImportTask(user, ImportInputType.TEXT, DESCRIPTION);
        completed.setJob(job);
        completed.setStatus(ImportStatus.COMPLETED);
        completed.setReviewedAt(Instant.now());
        tasks.saveAndFlush(completed);
        expireTask(completed);

        var stale = enqueue(ImportInputType.TEXT, DESCRIPTION);
        expireTask(stale);
        assertThat(queue.claim()).isEmpty();

        var inFlight = enqueue(ImportInputType.TEXT, DESCRIPTION);
        var claim = queue.claim().orElseThrow();
        expireTask(inFlight);
        assertThat(queue.finish(claim, ready())).isFalse();
        assertThat(queue.purgeExpiredBatch()).isEqualTo(3);
        assertThat(tasks.existsById(completed.getId())).isFalse();
        assertThat(tasks.existsById(stale.getId())).isFalse();
        assertThat(tasks.existsById(inFlight.getId())).isFalse();
        assertThat(jobs.existsById(job.getId())).isTrue();
    }

    @Test
    void workerWaitsForProcessorAndStoresControlledFallback() {
        var task = enqueue(ImportInputType.URL, URL);
        var beans = new StaticListableBeanFactory();
        var worker = new ImportWorker(queue, beans.getBeanProvider(ImportTaskProcessor.class));
        worker.poll();
        assertThat(stored(task).getStatus()).isEqualTo(ImportStatus.PENDING);

        ImportTaskProcessor processor = (type, input, deadline) -> {
            assertThat(type).isEqualTo(ImportInputType.URL);
            assertThat(input).isEqualTo(URL);
            assertThat(deadline).isAfter(Instant.now());
            return new ImportTaskProcessor.NeedsInput(ImportTaskProcessor.Fallback.SOURCE_REQUIRES_LOGIN);
        };
        beans.addBean("processor", processor);
        worker.poll();

        var fallback = stored(task);
        assertThat(fallback.getStatus()).isEqualTo(ImportStatus.NEEDS_INPUT);
        assertThat(fallback.getErrorCode()).isEqualTo("SOURCE_REQUIRES_LOGIN");
        assertThat(fallback.getErrorMessage()).contains("Cole a descrição");
        assertThat(fallback.getExtractedData()).isNull();
        assertThat(queue.claim()).isEmpty();
    }

    private ImportTask enqueue(ImportInputType type, String input) {
        return tasks.saveAndFlush(new ImportTask(user, type, input));
    }

    private ImportTask stored(ImportTask task) {
        return tasks.findById(task.getId()).orElseThrow();
    }

    private ImportTaskProcessor.Ready ready() {
        return new ImportTaskProcessor.Ready(null, Map.of("title", "Pessoa desenvolvedora Java"));
    }

    private void makeDue(ImportTask task) {
        jdbc.update("UPDATE tb_import_task SET next_attempt_at = ? WHERE id = ?", earlier(1), task.getId());
    }

    private void expireLease(ImportTask task) {
        jdbc.update("UPDATE tb_import_task SET locked_until = ? WHERE id = ?", earlier(1), task.getId());
    }

    private void expireTask(ImportTask task) {
        jdbc.update("UPDATE tb_import_task SET created_at = ? WHERE id = ?", earlier(25), task.getId());
    }

    private OffsetDateTime earlier(int hours) {
        return Instant.now().minus(hours, ChronoUnit.HOURS).atOffset(ZoneOffset.UTC);
    }
}

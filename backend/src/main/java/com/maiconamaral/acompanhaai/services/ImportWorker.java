package com.maiconamaral.acompanhaai.services;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
@ConditionalOnProperty(name = "app.import-worker.scheduling-enabled", matchIfMissing = true)
public class ImportWorker {

    private final ImportTaskQueue queue;
    private final ObjectProvider<ImportTaskProcessor> processors;

    public ImportWorker(ImportTaskQueue queue, ObjectProvider<ImportTaskProcessor> processors) {
        this.queue = queue;
        this.processors = processors;
    }

    @Scheduled(fixedDelay = 5, timeUnit = TimeUnit.SECONDS)
    void poll() {
        queue.recoverInterrupted();
        var processor = processors.getIfAvailable();
        if (processor == null) {
            return;
        }

        queue.claim().ifPresent(claim -> {
            ImportTaskProcessor.Outcome outcome;
            try {
                outcome = Objects.requireNonNull(processor.process(
                    claim.inputType(),
                    claim.input(),
                    claim.deadline()
                ));
            } catch (RuntimeException failure) {
                outcome = new ImportTaskProcessor.Retry();
            }
            queue.finish(claim, outcome);
        });
    }

    @Scheduled(fixedDelay = 1, timeUnit = TimeUnit.HOURS)
    void purgeExpired() {
        while (queue.purgeExpiredBatch() == ImportTaskQueue.BATCH_SIZE) {
            // Cada lote confirma a própria transação.
        }
    }
}

package com.maiconamaral.acompanhaai.services;

import com.maiconamaral.acompanhaai.services.JobExtractionException.Reason;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class GuardedJobExtractor implements JobExtractor {

    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private static final Logger log = LoggerFactory.getLogger(GuardedJobExtractor.class);
    private static final Set<Reason> TRANSIENT = EnumSet.of(Reason.RATE_LIMITED, Reason.UNAVAILABLE, Reason.TIMEOUT);
    private static final Duration MINIMUM_CALL = Duration.ofSeconds(8);

    private final JobExtractor delegate;
    private final Clock clock;
    private final JobExtractors.Guard guard;
    private final Sleeper sleeper;
    private LocalDate day;
    private int usedToday;
    private int consecutiveFailures;
    private Instant suspendedUntil = Instant.MIN;

    GuardedJobExtractor(JobExtractor delegate, Clock clock, JobExtractors.Guard guard, Sleeper sleeper) {
        this.delegate = delegate;
        this.clock = clock;
        this.guard = guard;
        this.sleeper = sleeper;
    }

    static void sleep(Duration duration) throws InterruptedException {
        Thread.sleep(duration);
    }

    @Override
    public String provider() {
        return delegate.provider();
    }

    @Override
    public String model() {
        return delegate.model();
    }

    @Override
    public Map<String, Object> extract(String text, Instant deadline) {
        for (int attempt = 1; ; attempt++) {
            reserve();
            try {
                var proposal = delegate.extract(text, deadline);
                succeeded();
                return proposal;
            } catch (JobExtractionException e) {
                var wait = guard.retryDelay().multipliedBy(1L << (attempt - 1));
                boolean retry = TRANSIENT.contains(e.getReason()) &&
                    attempt < guard.maxAttempts() &&
                    clock.instant().plus(wait).plus(MINIMUM_CALL).isBefore(deadline);
                if (!retry) {
                    failed(e.getReason());
                    throw e;
                }
                log.info("extracao provedor={} nova tentativa={} motivo={}", provider(), attempt + 1, e.getReason());
                pause(wait);
            }
        }
    }

    private synchronized void reserve() {
        var now = clock.instant();
        if (now.isBefore(suspendedUntil)) {
            throw new JobExtractionException(Reason.SUSPENDED);
        }
        var today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        if (!today.equals(day)) {
            day = today;
            usedToday = 0;
        }
        if (guard.dailyLimit() > 0 && usedToday >= guard.dailyLimit()) {
            throw new JobExtractionException(Reason.BUDGET_EXHAUSTED);
        }
        usedToday++;
    }

    private synchronized void succeeded() {
        consecutiveFailures = 0;
    }

    private synchronized void failed(Reason reason) {
        if (!TRANSIENT.contains(reason)) {
            return;
        }
        consecutiveFailures++;
        if (consecutiveFailures >= guard.failureThreshold()) {
            suspendedUntil = clock.instant().plus(guard.cooldown());
            consecutiveFailures = 0;
            log.warn(
                "extracao provedor={} pausada por {}s apos falhas seguidas",
                provider(),
                guard.cooldown().toSeconds()
            );
        }
    }

    private void pause(Duration wait) {
        try {
            sleeper.sleep(wait);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JobExtractionException(Reason.UNAVAILABLE);
        }
    }
}

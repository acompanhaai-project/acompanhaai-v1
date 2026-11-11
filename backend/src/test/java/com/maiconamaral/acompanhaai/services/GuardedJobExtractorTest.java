package com.maiconamaral.acompanhaai.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.maiconamaral.acompanhaai.services.JobExtractionException.Reason;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class GuardedJobExtractorTest {

    private static final Map<String, Object> PROPOSAL = Map.of("title", "Analista");

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-11T12:00:00Z"));
    private final List<Duration> sleeps = new ArrayList<>();
    private final Deque<Object> responses = new ArrayDeque<>();
    private int calls;

    private final JobExtractor provider = new JobExtractor() {
        @Override
        public String provider() {
            return "teste";
        }

        @Override
        public String model() {
            return "modelo";
        }

        @Override
        @SuppressWarnings("unchecked")
        public Map<String, Object> extract(String text, Instant deadline) {
            calls++;
            var next = responses.isEmpty() ? PROPOSAL : responses.pop();
            if (next instanceof Reason reason) {
                throw new JobExtractionException(reason);
            }
            return (Map<String, Object>) next;
        }
    };

    private GuardedJobExtractor guarded(int attempts, int dailyLimit, int threshold) {
        var guard = new JobExtractors.Guard(
            attempts,
            Duration.ofSeconds(2),
            dailyLimit,
            threshold,
            Duration.ofMinutes(5)
        );
        return new GuardedJobExtractor(provider, clock, guard, duration -> {
            sleeps.add(duration);
            clock.advance(duration);
        });
    }

    private Instant lease() {
        return clock.instant().plusSeconds(120);
    }

    @Test
    void retriesTransientFailuresWithProgressiveWait() {
        responses.addAll(List.of(Reason.RATE_LIMITED, Reason.UNAVAILABLE));

        var proposal = guarded(3, 0, 5).extract("vaga", lease());

        assertThat(proposal).isEqualTo(PROPOSAL);
        assertThat(calls).isEqualTo(3);
        assertThat(sleeps).containsExactly(Duration.ofSeconds(2), Duration.ofSeconds(4));
    }

    @ParameterizedTest
    @EnumSource(value = Reason.class, names = { "REJECTED", "INVALID_RESPONSE" })
    void doesNotRetryPermanentFailures(Reason reason) {
        responses.add(reason);

        assertThatThrownBy(() -> guarded(3, 0, 5).extract("vaga", lease()))
            .isInstanceOfSatisfying(JobExtractionException.class, e -> assertThat(e.getReason()).isEqualTo(reason));
        assertThat(calls).isOne();
        assertThat(sleeps).isEmpty();
    }

    @Test
    void stopsRetryingAtTheLimitOrWhenTheLeaseIsEnding() {
        responses.addAll(List.of(Reason.TIMEOUT, Reason.TIMEOUT, Reason.TIMEOUT));
        assertThatThrownBy(() -> guarded(2, 0, 5).extract("vaga", lease())).isInstanceOf(JobExtractionException.class);
        assertThat(calls).isEqualTo(2);

        calls = 0;
        responses.clear();
        responses.add(Reason.RATE_LIMITED);
        assertThatThrownBy(() -> guarded(3, 0, 5).extract("vaga", clock.instant().plusSeconds(9)))
            .isInstanceOf(JobExtractionException.class);
        assertThat(calls).isOne();
    }

    @Test
    void pausesTheProviderAfterConsecutiveFailuresAndResumesAfterCooldown() {
        var extractor = guarded(1, 0, 2);
        responses.addAll(List.of(Reason.UNAVAILABLE, Reason.REJECTED, Reason.UNAVAILABLE));

        for (int index = 0; index < 3; index++) {
            assertThatThrownBy(() -> extractor.extract("vaga", lease())).isInstanceOf(JobExtractionException.class);
        }
        assertThatThrownBy(() -> extractor.extract("vaga", lease()))
            .isInstanceOfSatisfying(JobExtractionException.class, e ->
                assertThat(e.getReason()).isEqualTo(Reason.SUSPENDED)
            );
        assertThat(calls).isEqualTo(3);

        clock.advance(Duration.ofMinutes(5));
        assertThat(extractor.extract("vaga", lease())).isEqualTo(PROPOSAL);
    }

    @Test
    void successResetsTheFailureCounter() {
        var extractor = guarded(1, 0, 2);
        responses.addAll(List.of(Reason.UNAVAILABLE, PROPOSAL, Reason.UNAVAILABLE));

        assertThatThrownBy(() -> extractor.extract("vaga", lease())).isInstanceOf(JobExtractionException.class);
        extractor.extract("vaga", lease());
        assertThatThrownBy(() -> extractor.extract("vaga", lease()))
            .isInstanceOfSatisfying(JobExtractionException.class, e ->
                assertThat(e.getReason()).isEqualTo(Reason.UNAVAILABLE)
            );
        assertThat(extractor.extract("vaga", lease())).isEqualTo(PROPOSAL);
    }

    @Test
    void enforcesTheDailyBudgetIncludingRetries() {
        var extractor = guarded(2, 3, 10);
        responses.add(Reason.RATE_LIMITED);

        extractor.extract("vaga", lease());
        extractor.extract("vaga", lease());
        assertThatThrownBy(() -> extractor.extract("vaga", lease()))
            .isInstanceOfSatisfying(JobExtractionException.class, e ->
                assertThat(e.getReason()).isEqualTo(Reason.BUDGET_EXHAUSTED)
            );
        assertThat(calls).isEqualTo(3);

        clock.advance(Duration.ofHours(12));
        assertThat(extractor.extract("vaga", lease())).isEqualTo(PROPOSAL);
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}

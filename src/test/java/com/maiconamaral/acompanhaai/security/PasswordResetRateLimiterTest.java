package com.maiconamaral.acompanhaai.security;

import static com.maiconamaral.acompanhaai.security.PasswordResetRateLimiter.Route.*;
import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class PasswordResetRateLimiterTest {

    @Test
    void enforcesPerIpAndGlobalLimitsIndependentlyOfAccount() {
        var limiter = new PasswordResetRateLimiter(2, 3, () -> 0);
        assertThat(limiter.acquire(REQUEST, "ip-a")).isZero();
        assertThat(limiter.acquire(REQUEST, "ip-a")).isZero();
        assertThat(limiter.acquire(REQUEST, "ip-a")).isEqualTo(60);
        assertThat(limiter.acquire(REQUEST, "ip-b")).isZero();
        assertThat(limiter.acquire(REQUEST, "ip-c")).isEqualTo(60);
        assertThat(limiter.acquire(CONFIRM, "ip-a")).isZero();
    }

    @Test
    void windowExpiresExactlyAtSixtySecondsAndRetryAfterRoundsUp() {
        var now = new AtomicLong();
        var limiter = new PasswordResetRateLimiter(1, 1, now::get);
        assertThat(limiter.acquire(REQUEST, "ip")).isZero();
        now.set(Duration.ofSeconds(59).toNanos() + 1);
        assertThat(limiter.acquire(REQUEST, "ip")).isEqualTo(1);
        now.set(Duration.ofSeconds(60).toNanos());
        assertThat(limiter.acquire(REQUEST, "ip")).isZero();
    }

    @Test
    void concurrentRequestsCannotExceedGlobalQuota() throws Exception {
        var limiter = new PasswordResetRateLimiter(10, 7, System::nanoTime);
        try (var executor = Executors.newFixedThreadPool(8)) {
            var calls = IntStream.range(0, 40)
                .mapToObj(index -> executor.submit(() -> limiter.acquire(REQUEST, "ip-" + index)))
                .toList();
            int accepted = 0;
            for (var call : calls) if (call.get(5, TimeUnit.SECONDS) == 0) accepted++;
            assertThat(accepted).isEqualTo(7);
        }
    }

    @Test
    void invalidConfigurationIsRejected() {
        assertThatThrownBy(() -> new PasswordResetRateLimiter(0, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PasswordResetRateLimiter(1, -1)).isInstanceOf(IllegalArgumentException.class);
    }
}

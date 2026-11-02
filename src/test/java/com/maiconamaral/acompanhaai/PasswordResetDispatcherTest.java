package com.maiconamaral.acompanhaai;

import com.maiconamaral.acompanhaai.security.PasswordResetRateLimiter;
import com.maiconamaral.acompanhaai.services.PasswordResetRequestDispatcher;
import com.maiconamaral.acompanhaai.services.PasswordResetService;
import org.junit.jupiter.api.Test;
import java.util.ArrayDeque;
import java.util.concurrent.RejectedExecutionException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PasswordResetDispatcherTest {
    @Test
    void accountLookupDoesNotRunBeforeGenericResponseCanBeReturned() {
        var service = mock(PasswordResetService.class);
        var queue = new ArrayDeque<Runnable>();
        var dispatcher = new PasswordResetRequestDispatcher(service, new PasswordResetRateLimiter(10, 100), queue::add);
        for (String email : new String[]{"existing@example.test", "unknown@example.test", "existing@example.test"}) {
            dispatcher.request(email, "ip");
            verifyNoInteractions(service);
        }
        assertThat(queue).hasSize(3);
        queue.forEach(Runnable::run);
        verify(service, times(2)).requestReset("existing@example.test");
        verify(service).requestReset("unknown@example.test");
    }

    @Test
    void rateLimitDropsWorkWithoutLookingUpAccount() {
        var service = mock(PasswordResetService.class);
        var queue = new ArrayDeque<Runnable>();
        var dispatcher = new PasswordResetRequestDispatcher(service, new PasswordResetRateLimiter(1, 100), queue::add);
        dispatcher.request("first@example.test", "ip");
        dispatcher.request("second@example.test", "ip");
        assertThat(queue).hasSize(1);
        verifyNoInteractions(service);
    }

    @Test
    void saturatedQueueDoesNotExposeErrorsOrExecuteLookupOnCallerThread() {
        var service = mock(PasswordResetService.class);
        var dispatcher = new PasswordResetRequestDispatcher(service, new PasswordResetRateLimiter(10, 100),
                task -> { throw new RejectedExecutionException(); });
        assertThatCode(() -> dispatcher.request("test@example.test", "ip")).doesNotThrowAnyException();
        verifyNoInteractions(service);
    }

    @Test
    void workerFailureDoesNotPropagateToRequestCaller() {
        var service = mock(PasswordResetService.class);
        doThrow(new IllegalStateException("sensitive internal error")).when(service).requestReset(anyString());
        var queue = new ArrayDeque<Runnable>();
        var dispatcher = new PasswordResetRequestDispatcher(service, new PasswordResetRateLimiter(10, 100), queue::add);
        dispatcher.request("test@example.test", "ip");
        assertThatCode(queue.remove()::run).doesNotThrowAnyException();
    }
}

package com.maiconamaral.acompanhaai.services;

import com.maiconamaral.acompanhaai.security.PasswordResetRateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

@Service
public class PasswordResetRequestDispatcher {
    private static final Logger LOG = LoggerFactory.getLogger(PasswordResetRequestDispatcher.class);
    private final PasswordResetService service;
    private final PasswordResetRateLimiter limiter;
    private final Executor executor;

    public PasswordResetRequestDispatcher(PasswordResetService service, PasswordResetRateLimiter limiter,
            @Qualifier("passwordResetRequestExecutor") Executor executor) {
        this.service = service;
        this.limiter = limiter;
        this.executor = executor;
    }

    public void request(String email, String remoteAddress) {
        if (limiter.acquire(PasswordResetRateLimiter.Route.REQUEST, remoteAddress) != 0) return;
        try {
            executor.execute(() -> {
                try {
                    service.requestReset(email);
                } catch (RuntimeException exception) {
                    // Nenhuma exceção de persistência ou conteúdo do pedido vai para logs/resposta.
                    LOG.warn("Não foi possível processar a solicitação de recuperação.");
                }
            });
        } catch (RejectedExecutionException exception) {
            LOG.warn("Fila de solicitações de recuperação indisponível.");
        }
    }
}

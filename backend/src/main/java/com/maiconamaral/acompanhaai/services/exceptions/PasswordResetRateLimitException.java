package com.maiconamaral.acompanhaai.services.exceptions;

public class PasswordResetRateLimitException extends RuntimeException {

    private final long retryAfter;

    public PasswordResetRateLimitException(long retryAfter) {
        super("Limite de solicitações atingido. Tente novamente mais tarde.");
        this.retryAfter = retryAfter;
    }

    public long getRetryAfter() {
        return retryAfter;
    }
}

package com.maiconamaral.acompanhaai.services;

public class JobExtractionException extends RuntimeException {

    public enum Reason {
        TIMEOUT,
        RATE_LIMITED,
        UNAVAILABLE,
        REJECTED,
        INVALID_RESPONSE,
        SUSPENDED,
        BUDGET_EXHAUSTED
    }

    private final Reason reason;

    public JobExtractionException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}

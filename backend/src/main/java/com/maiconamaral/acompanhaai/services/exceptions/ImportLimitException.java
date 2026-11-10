package com.maiconamaral.acompanhaai.services.exceptions;

import org.springframework.http.HttpStatus;

public class ImportLimitException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final long retryAfter;

    private ImportLimitException(HttpStatus status, String code, String message, long retryAfter) {
        super(message);
        this.status = status;
        this.code = code;
        this.retryAfter = retryAfter;
    }

    public static ImportLimitException hourlyQuota(long retryAfter) {
        return new ImportLimitException(
            HttpStatus.TOO_MANY_REQUESTS,
            "IMPORT_QUOTA_EXCEEDED",
            "Limite de importações por hora atingido. Tente novamente mais tarde.",
            retryAfter
        );
    }

    public static ImportLimitException activeLimit() {
        return new ImportLimitException(
            HttpStatus.TOO_MANY_REQUESTS,
            "IMPORT_ACTIVE_LIMIT",
            "Aguarde o processamento das importações em andamento.",
            30
        );
    }

    public static ImportLimitException overloaded() {
        return new ImportLimitException(
            HttpStatus.SERVICE_UNAVAILABLE,
            "IMPORT_OVERLOADED",
            "A importação está temporariamente sobrecarregada. Cadastre a vaga manualmente ou tente mais tarde.",
            60
        );
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public long getRetryAfter() {
        return retryAfter;
    }
}

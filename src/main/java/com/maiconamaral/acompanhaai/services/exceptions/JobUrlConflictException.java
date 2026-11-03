package com.maiconamaral.acompanhaai.services.exceptions;

public class JobUrlConflictException extends RuntimeException {

    private final String code;

    private JobUrlConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public static JobUrlConflictException duplicate() {
        return new JobUrlConflictException("JOB_URL_ALREADY_EXISTS", "Esta vaga já foi cadastrada com esse link.");
    }

    public static JobUrlConflictException hashCollision() {
        return new JobUrlConflictException(
            "JOB_URL_HASH_COLLISION",
            "Não foi possível registrar este link por um conflito de identificação."
        );
    }

    public String getCode() {
        return code;
    }
}

package com.maiconamaral.acompanhaai.services.exceptions;

public class ImportConflictException extends RuntimeException {

    private final String code;

    private ImportConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public static ImportConflictException alreadyCompleted() {
        return new ImportConflictException("IMPORT_ALREADY_COMPLETED", "A importação já foi confirmada.");
    }

    public static ImportConflictException notReady() {
        return new ImportConflictException(
            "IMPORT_NOT_READY",
            "A importação ainda não tem uma proposta pronta para revisão."
        );
    }

    public String getCode() {
        return code;
    }
}

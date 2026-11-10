package com.maiconamaral.acompanhaai.services.exceptions;

public class ImportAlreadyCompletedException extends RuntimeException {

    public ImportAlreadyCompletedException() {
        super("A importação já foi confirmada.");
    }
}

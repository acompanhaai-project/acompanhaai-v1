package com.maiconamaral.acompanhaai.services.exceptions;

public class IncorrectCurrentPasswordException extends RuntimeException {

    public IncorrectCurrentPasswordException() {
        super("Senha atual incorreta.");
    }
}

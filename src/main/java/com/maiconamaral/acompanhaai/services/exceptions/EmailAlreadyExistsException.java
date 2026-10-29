package com.maiconamaral.acompanhaai.services.exceptions;

public class EmailAlreadyExistsException extends RuntimeException {
    public EmailAlreadyExistsException() { super("E-mail já cadastrado."); }
}

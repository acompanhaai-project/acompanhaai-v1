package com.maiconamaral.acompanhaai.services.exceptions;

public class InvalidPasswordResetTokenException extends RuntimeException {
    public InvalidPasswordResetTokenException() {
        super("Token de recuperação inválido ou indisponível.");
    }
}

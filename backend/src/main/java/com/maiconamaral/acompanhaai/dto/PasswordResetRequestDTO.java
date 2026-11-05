package com.maiconamaral.acompanhaai.dto;

import jakarta.validation.constraints.*;

public record PasswordResetRequestDTO(@NotBlank @Email @Size(max = 254) String email) {
    public PasswordResetRequestDTO {
        email = email == null ? null : email.strip();
    }
}

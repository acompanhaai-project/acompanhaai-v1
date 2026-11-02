package com.maiconamaral.acompanhaai.dto;

import jakarta.validation.constraints.*;

public record PasswordResetConfirmationDTO(
        @NotBlank @Size(max = 128) String token,
        @NotBlank @Size(min = 8, max = 128) String newPassword) {
    @Override
    public String toString() { return "PasswordResetConfirmationDTO[redacted]"; }
}

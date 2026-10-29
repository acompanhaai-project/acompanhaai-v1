package com.maiconamaral.acompanhaai.dto;

import jakarta.validation.constraints.*;

public record LoginDTO(
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Size(max = 128) String password) {
    public LoginDTO { email = email == null ? null : email.strip(); }
}

package com.maiconamaral.acompanhaai.dto;

import jakarta.validation.constraints.*;

public record UserInsertDTO(
        @NotBlank @Size(min = 2, max = 100) String name,
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Size(min = 8, max = 128) String password) {
    public UserInsertDTO {
        name = name == null ? null : name.strip();
        email = email == null ? null : email.strip();
    }
}

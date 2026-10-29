package com.maiconamaral.acompanhaai.dto;

import jakarta.validation.constraints.*;

public record UserUpdateDTO(
        @NotBlank @Size(min = 2, max = 100) String name,
        @NotBlank @Email @Size(max = 254) String email) {
    public UserUpdateDTO {
        name = name == null ? null : name.strip();
        email = email == null ? null : email.strip();
    }
}

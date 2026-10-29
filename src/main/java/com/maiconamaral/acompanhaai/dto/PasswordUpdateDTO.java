package com.maiconamaral.acompanhaai.dto;

import jakarta.validation.constraints.*;

public record PasswordUpdateDTO(
        @NotBlank @Size(max = 128) String currentPassword,
        @NotBlank @Size(min = 8, max = 128) String newPassword) { }

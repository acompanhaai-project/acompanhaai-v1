package com.maiconamaral.acompanhaai.dto;

import jakarta.validation.constraints.*;

public record JobInputDTO(
        @NotBlank @Size(max = 255) String title,
        @Size(max = 255) String companyName,
        @Size(max = 2048) @Pattern(regexp = "https?://[^\\s]+", message = "Informe um link HTTP ou HTTPS.") String sourceUrl,
        @Size(max = 30000) String description) {
    public JobInputDTO {
        title = title == null ? null : title.strip();
        companyName = optionalText(companyName);
        sourceUrl = optionalText(sourceUrl);
        description = optionalText(description);
    }
    private static String optionalText(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}

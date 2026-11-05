package com.maiconamaral.acompanhaai.dto;

import com.maiconamaral.acompanhaai.entities.enums.SalaryPeriod;
import com.maiconamaral.acompanhaai.entities.enums.WorkMode;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.List;

public record JobInputDTO(
    @NotBlank @Size(max = 255) String title,
    @Size(max = 255) String companyName,
    @Size(max = 2048)
    @Pattern(regexp = "(?i:https?)://[^\\s]+", message = "Informe um link HTTP ou HTTPS.")
    String sourceUrl,
    @Size(max = 30000) String description,
    @Size(max = 255) String location,
    WorkMode workMode,
    @Positive @Digits(integer = 13, fraction = 2) BigDecimal salary,
    @Pattern(regexp = "BRL", message = "A moeda deve ser BRL.") String salaryCurrency,
    SalaryPeriod salaryPeriod,
    @Size(max = 50) List<@NotBlank @Size(max = 150) String> hardSkills,
    @Size(max = 50) List<@NotBlank @Size(max = 150) String> softSkills
) {
    public JobInputDTO {
        title = title == null ? null : title.strip();
        companyName = optionalText(companyName);
        sourceUrl = optionalText(sourceUrl);
        description = optionalText(description);
        location = optionalText(location);
        hardSkills = normalizeSkills(hardSkills);
        softSkills = normalizeSkills(softSkills);
    }

    private static List<String> normalizeSkills(List<String> skills) {
        if (skills == null) {
            return List.of();
        }
        return skills
            .stream()
            .map(value -> value == null ? null : value.replaceAll("(?U)\\s+", " ").strip())
            .toList();
    }

    private static String optionalText(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}

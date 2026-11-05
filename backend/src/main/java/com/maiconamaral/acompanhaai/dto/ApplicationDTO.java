package com.maiconamaral.acompanhaai.dto;

import com.maiconamaral.acompanhaai.entities.Application;
import com.maiconamaral.acompanhaai.entities.enums.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;

public record ApplicationDTO(
    Long id,
    JobDTO job,
    ApplicationStatus status,
    LocalDate appliedOn,
    String notes,
    Instant createdAt,
    Instant updatedAt,
    long version
) {
    public ApplicationDTO(Application entity) {
        this(
            entity.getId(),
            new JobDTO(
                entity.getJob().getId(),
                entity.getJob().getTitle(),
                entity.getJob().getCompanyName(),
                entity.getJob().getSourceUrl(),
                entity.getJob().getDescription(),
                entity.getJob().getLocation(),
                entity.getJob().getWorkMode(),
                entity.getJob().getSalary(),
                entity.getJob().getSalary() == null ? null : entity.getJob().getSalaryCurrency(),
                entity.getJob().getSalary() == null ? null : entity.getJob().getSalaryPeriod(),
                entity.getJob().getSkills(SkillType.HARD),
                entity.getJob().getSkills(SkillType.SOFT)
            ),
            entity.getStatus(),
            entity.getAppliedOn(),
            entity.getNotes(),
            entity.getCreatedAt(),
            entity.getUpdatedAt(),
            entity.getVersion()
        );
    }

    public record JobDTO(
        Long id,
        String title,
        String companyName,
        String sourceUrl,
        String description,
        String location,
        WorkMode workMode,
        BigDecimal salary,
        String salaryCurrency,
        SalaryPeriod salaryPeriod,
        List<String> hardSkills,
        List<String> softSkills
    ) {}
}

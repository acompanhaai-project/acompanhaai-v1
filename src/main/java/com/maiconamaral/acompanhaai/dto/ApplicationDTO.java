package com.maiconamaral.acompanhaai.dto;

import com.maiconamaral.acompanhaai.entities.Application;
import com.maiconamaral.acompanhaai.entities.enums.ApplicationStatus;
import java.time.*;

public record ApplicationDTO(Long id, JobDTO job, ApplicationStatus status, LocalDate appliedOn,
        String notes, Instant createdAt, Instant updatedAt, long version) {
    public ApplicationDTO(Application entity) {
        this(entity.getId(), new JobDTO(entity.getJob().getId(), entity.getJob().getTitle(),
                        entity.getJob().getCompanyName(), entity.getJob().getSourceUrl(), entity.getJob().getDescription()),
                entity.getStatus(), entity.getAppliedOn(), entity.getNotes(), entity.getCreatedAt(),
                entity.getUpdatedAt(), entity.getVersion());
    }
    public record JobDTO(Long id, String title, String companyName, String sourceUrl, String description) { }
}

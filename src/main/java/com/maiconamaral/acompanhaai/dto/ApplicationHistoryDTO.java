package com.maiconamaral.acompanhaai.dto;

import com.maiconamaral.acompanhaai.entities.ApplicationStatusHistory;
import com.maiconamaral.acompanhaai.entities.enums.ApplicationStatus;
import java.time.Instant;

public record ApplicationHistoryDTO(Long id, ApplicationStatus fromStatus, ApplicationStatus toStatus, Instant changedAt) {
    public ApplicationHistoryDTO(ApplicationStatusHistory entity) {
        this(entity.getId(), entity.getFromStatus(), entity.getToStatus(), entity.getChangedAt());
    }
}

package com.maiconamaral.acompanhaai.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.maiconamaral.acompanhaai.entities.ImportTask;
import com.maiconamaral.acompanhaai.entities.enums.ImportInputType;
import com.maiconamaral.acompanhaai.entities.enums.ImportStatus;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ImportTaskDTO(
    Long id,
    ImportInputType inputType,
    ImportStatus status,
    Instant expiresAt,
    Failure failure,
    Map<String, Object> result,
    Long jobId
) {
    public ImportTaskDTO(ImportTask entity, Duration retention) {
        this(
            entity.getId(),
            entity.getInputType(),
            entity.getStatus(),
            entity.getCreatedAt().plus(retention).truncatedTo(ChronoUnit.SECONDS),
            failure(entity),
            entity.getStatus() == ImportStatus.READY_FOR_REVIEW ? entity.getExtractedData() : null,
            entity.getStatus() == ImportStatus.COMPLETED ? entity.getJob().getId() : null
        );
    }

    private static Failure failure(ImportTask entity) {
        var status = entity.getStatus();
        if ((status != ImportStatus.NEEDS_INPUT && status != ImportStatus.FAILED) || entity.getErrorCode() == null) {
            return null;
        }
        return new Failure(entity.getErrorCode(), entity.getErrorMessage());
    }

    public record Failure(String code, String message) {}
}

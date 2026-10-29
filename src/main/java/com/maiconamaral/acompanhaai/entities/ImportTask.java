package com.maiconamaral.acompanhaai.entities;

import jakarta.persistence.*;
import com.maiconamaral.acompanhaai.entities.enums.*;
import java.util.Objects;
import java.time.Instant;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "tb_import_task")
public class ImportTask extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_id")
    private Job job;

    @Enumerated(EnumType.STRING)
    @Column(name = "input_type", length = 32, nullable = false)
    private ImportInputType inputType;

    @Column(name = "source_url", columnDefinition = "text")
    private String sourceUrl;

    @Column(name = "input_text", columnDefinition = "text")
    private String inputText;

    @Column(name = "collected_text", columnDefinition = "text")
    private String collectedText;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 32, nullable = false)
    private ImportStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "lock_token", length = 36)
    private String lockToken;

    @Column(name = "extractor_provider", length = 100)
    private String extractorProvider;

    @Column(name = "extractor_model", length = 100)
    private String extractorModel;

    @Column(name = "result_schema_version")
    private Integer resultSchemaVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "extracted_data", columnDefinition = "jsonb")
    private Map<String, Object> extractedData;

    @Column(name = "error_code", length = 100)
    private String errorCode;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    protected ImportTask() { }

    public ImportTask(User user, ImportInputType inputType, String input) {
        this.user = Objects.requireNonNull(user);
        this.inputType = Objects.requireNonNull(inputType);
        Objects.requireNonNull(input);
        if (inputType == ImportInputType.URL) this.sourceUrl = input;
        else this.inputText = input;
        this.status = ImportStatus.PENDING;
    }

    public User getUser() { return user; }
    public Job getJob() { return job; }
    public ImportInputType getInputType() { return inputType; }
    public String getSourceUrl() { return sourceUrl; }
    public String getInputText() { return inputText; }
    public String getCollectedText() { return collectedText; }
    public ImportStatus getStatus() { return status; }
    public int getAttemptCount() { return attemptCount; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public Instant getLockedUntil() { return lockedUntil; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public Instant getReviewedAt() { return reviewedAt; }
    public String getLockToken() { return lockToken; }
    public String getExtractorProvider() { return extractorProvider; }
    public String getExtractorModel() { return extractorModel; }
    public Integer getResultSchemaVersion() { return resultSchemaVersion; }
    public Map<String, Object> getExtractedData() { return extractedData; }
    public String getErrorCode() { return errorCode; }
    public String getErrorMessage() { return errorMessage; }

    public void setJob(Job job) { this.job = job; }
    public void setInputType(ImportInputType inputType) { this.inputType = inputType; }
    public void setSourceUrl(String sourceUrl) { this.sourceUrl = sourceUrl; }
    public void setInputText(String inputText) { this.inputText = inputText; }
    public void setCollectedText(String collectedText) { this.collectedText = collectedText; }
    public void setStatus(ImportStatus status) { this.status = status; }
    public void setAttemptCount(int attemptCount) { this.attemptCount = attemptCount; }
    public void setNextAttemptAt(Instant nextAttemptAt) { this.nextAttemptAt = nextAttemptAt; }
    public void setLockedUntil(Instant lockedUntil) { this.lockedUntil = lockedUntil; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
    public void setReviewedAt(Instant reviewedAt) { this.reviewedAt = reviewedAt; }
    public void setLockToken(String lockToken) { this.lockToken = lockToken; }
    public void setExtractorProvider(String extractorProvider) { this.extractorProvider = extractorProvider; }
    public void setExtractorModel(String extractorModel) { this.extractorModel = extractorModel; }
    public void setResultSchemaVersion(Integer resultSchemaVersion) { this.resultSchemaVersion = resultSchemaVersion; }
    public void setExtractedData(Map<String, Object> extractedData) { this.extractedData = extractedData; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
}


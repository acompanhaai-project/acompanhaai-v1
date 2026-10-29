package com.maiconamaral.acompanhaai.entities;

import jakarta.persistence.*;
import com.maiconamaral.acompanhaai.dto.JobInputDTO;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import com.maiconamaral.acompanhaai.entities.enums.*;
import java.util.Objects;
import java.math.BigDecimal;

@Entity
@Table(name = "tb_job")
public class Job extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "company_name", length = 255)
    private String companyName;

    @Column(name = "source_url", columnDefinition = "text")
    private String sourceUrl;

    @Column(name = "normalized_url", columnDefinition = "text")
    private String normalizedUrl;

    @Column(name = "url_hash", length = 64)
    private String urlHash;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "location", length = 255)
    private String location;

    @Enumerated(EnumType.STRING)
    @Column(name = "work_mode", length = 32)
    private WorkMode workMode;

    @Column(name = "salary_min", precision = 15, scale = 2)
    private BigDecimal salaryMin;

    @Column(name = "salary_max", precision = 15, scale = 2)
    private BigDecimal salaryMax;

    @Column(name = "salary_currency", length = 3)
    private String salaryCurrency;

    @Enumerated(EnumType.STRING)
    @Column(name = "salary_period", length = 32)
    private SalaryPeriod salaryPeriod;

    @Column(name = "salary_original_text", columnDefinition = "text")
    private String salaryOriginalText;

    @Version
    @Column(nullable = false)
    private long version;

    protected Job() { }

    public Job(User user, String title) {
        this.user = Objects.requireNonNull(user);
        this.title = Objects.requireNonNull(title);
    }

    public Job(User user, JobInputDTO dto) {
        this(user, dto.title());
        update(dto);
    }

    public void update(JobInputDTO dto) {
        this.title = dto.title();
        this.companyName = dto.companyName();
        this.description = dto.description();
        this.sourceUrl = dto.sourceUrl();
        this.normalizedUrl = sourceUrl;
        try {
            this.urlHash = sourceUrl == null ? null : HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(sourceUrl.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    public User getUser() { return user; }
    public String getTitle() { return title; }
    public String getCompanyName() { return companyName; }
    public String getSourceUrl() { return sourceUrl; }
    public String getNormalizedUrl() { return normalizedUrl; }
    public String getUrlHash() { return urlHash; }
    public String getDescription() { return description; }
    public String getLocation() { return location; }
    public WorkMode getWorkMode() { return workMode; }
    public BigDecimal getSalaryMin() { return salaryMin; }
    public BigDecimal getSalaryMax() { return salaryMax; }
    public String getSalaryCurrency() { return salaryCurrency; }
    public SalaryPeriod getSalaryPeriod() { return salaryPeriod; }
    public String getSalaryOriginalText() { return salaryOriginalText; }
    public long getVersion() { return version; }

    public void setTitle(String title) { this.title = title; }
    public void setCompanyName(String companyName) { this.companyName = companyName; }
    public void setSourceUrl(String sourceUrl) { this.sourceUrl = sourceUrl; }
    public void setNormalizedUrl(String normalizedUrl) { this.normalizedUrl = normalizedUrl; }
    public void setUrlHash(String urlHash) { this.urlHash = urlHash; }
    public void setDescription(String description) { this.description = description; }
    public void setLocation(String location) { this.location = location; }
    public void setWorkMode(WorkMode workMode) { this.workMode = workMode; }
    public void setSalaryMin(BigDecimal salaryMin) { this.salaryMin = salaryMin; }
    public void setSalaryMax(BigDecimal salaryMax) { this.salaryMax = salaryMax; }
    public void setSalaryCurrency(String salaryCurrency) { this.salaryCurrency = salaryCurrency; }
    public void setSalaryPeriod(SalaryPeriod salaryPeriod) { this.salaryPeriod = salaryPeriod; }
    public void setSalaryOriginalText(String salaryOriginalText) { this.salaryOriginalText = salaryOriginalText; }
}


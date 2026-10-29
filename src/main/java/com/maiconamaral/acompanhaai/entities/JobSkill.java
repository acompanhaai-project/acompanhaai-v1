package com.maiconamaral.acompanhaai.entities;

import jakarta.persistence.*;
import com.maiconamaral.acompanhaai.entities.enums.*;
import java.util.Objects;

@Entity
@Table(name = "tb_job_skill")
public class JobSkill {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_id", nullable = false, updatable = false)
    private Job job;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "normalized_name", nullable = false, length = 150)
    private String normalizedName;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", length = 32, nullable = false)
    private SkillType type;

    @Column(name = "source_excerpt", columnDefinition = "text")
    private String sourceExcerpt;

    protected JobSkill() { }

    public JobSkill(Job job, String name, String normalizedName, SkillType type) {
        this.job = Objects.requireNonNull(job);
        this.name = Objects.requireNonNull(name);
        this.normalizedName = Objects.requireNonNull(normalizedName);
        this.type = Objects.requireNonNull(type);
    }

    public Long getId() { return id; }
    public Job getJob() { return job; }
    public String getName() { return name; }
    public String getNormalizedName() { return normalizedName; }
    public SkillType getType() { return type; }
    public String getSourceExcerpt() { return sourceExcerpt; }

    public void setName(String name) { this.name = name; }
    public void setNormalizedName(String normalizedName) { this.normalizedName = normalizedName; }
    public void setType(SkillType type) { this.type = type; }
    public void setSourceExcerpt(String sourceExcerpt) { this.sourceExcerpt = sourceExcerpt; }
}


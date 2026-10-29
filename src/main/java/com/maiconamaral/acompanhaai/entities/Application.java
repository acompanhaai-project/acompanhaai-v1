package com.maiconamaral.acompanhaai.entities;

import jakarta.persistence.*;
import com.maiconamaral.acompanhaai.entities.enums.*;
import java.util.Objects;
import java.time.LocalDate;

@Entity
@Table(name = "tb_application")
public class Application extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_id", nullable = false, updatable = false)
    private Job job;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 32, nullable = false)
    private ApplicationStatus status;

    @Column(name = "applied_on")
    private LocalDate appliedOn;

    @Column(name = "notes", columnDefinition = "text")
    private String notes;

    @Version
    @Column(nullable = false)
    private long version;

    protected Application() { }

    public Application(Job job) {
        this.job = Objects.requireNonNull(job);
        this.user = Objects.requireNonNull(job.getUser());
        this.status = ApplicationStatus.SAVED;
    }

    public Application(User user, Job job, ApplicationStatus status, LocalDate appliedOn, String notes) {
        this(job);
        if (!Objects.equals(user.getId(), job.getUser().getId())) {
            throw new IllegalArgumentException("A vaga deve pertencer ao usuário da candidatura.");
        }
        update(status, appliedOn, notes);
    }

    public void update(ApplicationStatus status, LocalDate appliedOn, String notes) {
        this.status = Objects.requireNonNull(status);
        this.appliedOn = appliedOn;
        this.notes = notes;
        onUpdate();
    }

    public User getUser() { return user; }
    public Job getJob() { return job; }
    public ApplicationStatus getStatus() { return status; }
    public LocalDate getAppliedOn() { return appliedOn; }
    public String getNotes() { return notes; }
    public long getVersion() { return version; }

    public void setStatus(ApplicationStatus status) { this.status = status; }
    public void setAppliedOn(LocalDate appliedOn) { this.appliedOn = appliedOn; }
    public void setNotes(String notes) { this.notes = notes; }
}


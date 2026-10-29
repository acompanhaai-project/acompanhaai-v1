package com.maiconamaral.acompanhaai.entities;

import jakarta.persistence.*;
import com.maiconamaral.acompanhaai.entities.enums.*;
import java.util.Objects;
import java.time.Instant;

@Entity
@Table(name = "tb_application_status_history")
public class ApplicationStatusHistory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "application_id", nullable = false, updatable = false)
    private Application application;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 32)
    private ApplicationStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", length = 32, nullable = false)
    private ApplicationStatus toStatus;

    @Column(name = "changed_at", nullable = false, updatable = false)
    private Instant changedAt;

    @Column(name = "note", columnDefinition = "text")
    private String note;

    protected ApplicationStatusHistory() { }

    public ApplicationStatusHistory(Application application, ApplicationStatus fromStatus, ApplicationStatus toStatus, String note) {
        this.application = Objects.requireNonNull(application);
        this.fromStatus = fromStatus;
        this.toStatus = Objects.requireNonNull(toStatus);
        this.note = note;
        this.changedAt = Instant.now();
    }

    public ApplicationStatusHistory(Application application, ApplicationStatus fromStatus) {
        this(application, fromStatus, application.getStatus(), null);
        this.changedAt = application.getUpdatedAt();
    }

    public Long getId() { return id; }
    public Application getApplication() { return application; }
    public ApplicationStatus getFromStatus() { return fromStatus; }
    public ApplicationStatus getToStatus() { return toStatus; }
    public Instant getChangedAt() { return changedAt; }
    public String getNote() { return note; }


}


package com.maiconamaral.acompanhaai.entities;

import jakarta.persistence.*;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(
    name = "tb_password_reset_token",
    uniqueConstraints = @UniqueConstraint(name = "uk_password_reset_token_hash", columnNames = "token_hash")
)
public class PasswordResetToken {

    private static final Duration MAX_LIFETIME = Duration.ofMinutes(30);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @Column(name = "token_hash", nullable = false, length = 64, updatable = false)
    private String tokenHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected PasswordResetToken() {}

    public PasswordResetToken(User user, String tokenHash, Instant createdAt, Instant expiresAt) {
        this.user = Objects.requireNonNull(user);
        if (tokenHash == null || !tokenHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Informe o hash SHA-256 hexadecimal do token.");
        }
        this.createdAt = Objects.requireNonNull(createdAt);
        this.expiresAt = Objects.requireNonNull(expiresAt);
        if (!expiresAt.isAfter(createdAt) || expiresAt.isAfter(createdAt.plus(MAX_LIFETIME))) {
            throw new IllegalArgumentException("O token deve expirar em até 30 minutos após a criação.");
        }
        this.tokenHash = tokenHash;
    }

    public boolean isUsableAt(Instant instant) {
        Objects.requireNonNull(instant);
        return usedAt == null && !instant.isBefore(createdAt) && instant.isBefore(expiresAt);
    }

    public void markUsed(Instant instant) {
        if (!isUsableAt(instant)) {
            throw new IllegalStateException("Token indisponível para utilização.");
        }
        usedAt = instant;
    }

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getUsedAt() {
        return usedAt;
    }

    public long getVersion() {
        return version;
    }
}

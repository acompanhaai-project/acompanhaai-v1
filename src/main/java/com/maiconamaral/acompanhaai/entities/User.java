package com.maiconamaral.acompanhaai.entities;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "tb_user", uniqueConstraints = @UniqueConstraint(name = "uk_user_email", columnNames = "email"))
public class User {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	@Column(nullable = false, length = 100)
	private String name;
	@Column(nullable = false, length = 254)
	private String email;
	@Column(name = "password_hash", nullable = false)
	private String passwordHash;
	@Column(name = "auth_version", nullable = false)
	private long authVersion;
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected User() {
	}

	public User(String name, String email, String passwordHash) {
		this.name = name;
		this.email = email;
		this.passwordHash = passwordHash;
		this.createdAt = Instant.now();
	}

	public Long getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public String getEmail() {
		return email;
	}

	public String getPasswordHash() {
		return passwordHash;
	}

	public long getAuthVersion() {
		return authVersion;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public void setName(String name) {
		this.name = name;
	}

	public void setEmail(String email) {
		this.email = email;
	}

	public void changePassword(String passwordHash) {
		this.passwordHash = passwordHash;
		this.authVersion++;
	}

	@Override
	public boolean equals(Object other) {
		if (this == other)
			return true;
		return other instanceof User user && id != null && Objects.equals(id, user.id);
	}

	@Override
	public int hashCode() {
		return User.class.hashCode();
	}
}

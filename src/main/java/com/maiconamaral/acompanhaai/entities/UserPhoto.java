package com.maiconamaral.acompanhaai.entities;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;

@Entity
@Table(name = "tb_user_photo")
public class UserPhoto {
    @Id
    private Long userId;
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId
    @JoinColumn(name = "user_id")
    private User user;
    @JdbcTypeCode(SqlTypes.VARBINARY)
    @Column(nullable = false)
    private byte[] content;
    @Column(name = "content_type", nullable = false, length = 20)
    private String contentType;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected UserPhoto() { }
    public UserPhoto(User user) { this.user = user; }
    public void replace(byte[] content, String contentType) {
        this.content = content;
        this.contentType = contentType;
        this.updatedAt = Instant.now();
    }
    public byte[] getContent() { return content; }
    public String getContentType() { return contentType; }
}

package com.maiconamaral.acompanhaai.dto;

import com.maiconamaral.acompanhaai.entities.User;
import java.time.Instant;

public record UserDTO(Long id, String name, String email, Instant createdAt, String photoUrl) {
    public UserDTO(User user, boolean hasPhoto) {
        this(user.getId(), user.getName(), user.getEmail(), user.getCreatedAt(),
                hasPhoto ? "/api/users/me/photo" : null);
    }
}

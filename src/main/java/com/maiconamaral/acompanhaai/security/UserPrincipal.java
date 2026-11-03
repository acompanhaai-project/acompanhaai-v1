package com.maiconamaral.acompanhaai.security;

import com.maiconamaral.acompanhaai.entities.User;
import java.util.List;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

public class UserPrincipal extends org.springframework.security.core.userdetails.User {

    private final Long id;
    private final long authVersion;

    public UserPrincipal(User user) {
        super(user.getEmail(), user.getPasswordHash(), List.of(new SimpleGrantedAuthority("ROLE_USER")));
        this.id = user.getId();
        this.authVersion = user.getAuthVersion();
    }

    public Long getId() {
        return id;
    }

    public long getAuthVersion() {
        return authVersion;
    }
}

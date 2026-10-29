package com.maiconamaral.acompanhaai.services;

import com.maiconamaral.acompanhaai.dto.LoginDTO;
import org.springframework.security.authentication.*;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

@Service
public class AuthService {
    private final AuthenticationManager authenticationManager;

    public AuthService(AuthenticationManager authenticationManager) {
        this.authenticationManager = authenticationManager;
    }

    public Authentication login(LoginDTO dto) {
        return authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(dto.email(), dto.password()));
    }
}

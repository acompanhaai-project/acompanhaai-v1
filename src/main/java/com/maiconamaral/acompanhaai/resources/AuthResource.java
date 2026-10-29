package com.maiconamaral.acompanhaai.resources;

import com.maiconamaral.acompanhaai.dto.*;
import com.maiconamaral.acompanhaai.security.UserPrincipal;
import com.maiconamaral.acompanhaai.services.*;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthResource {
    private final AuthService authService;
    private final UserService userService;
    private final SecurityContextRepository contextRepository;
    private final SessionAuthenticationStrategy sessionStrategy;
    private final SecurityContextLogoutHandler logoutHandler;

    public AuthResource(AuthService authService, UserService userService, SecurityContextRepository contextRepository,
                        SessionAuthenticationStrategy sessionStrategy, SecurityContextLogoutHandler logoutHandler) {
        this.authService = authService;
        this.userService = userService;
        this.contextRepository = contextRepository;
        this.sessionStrategy = sessionStrategy;
        this.logoutHandler = logoutHandler;
    }

    @GetMapping("/csrf")
    public CsrfDTO csrf(CsrfToken token) { return new CsrfDTO(token.getHeaderName(), token.getToken()); }

    @PostMapping("/login")
    public UserDTO login(@Valid @RequestBody LoginDTO dto, HttpServletRequest request, HttpServletResponse response) {
        Authentication authentication = authService.login(dto);
        sessionStrategy.onAuthentication(authentication, request, response);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);
        return userService.findById(((UserPrincipal) authentication.getPrincipal()).getId());
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response,
                                       Authentication authentication) {
        logoutHandler.logout(request, response, authentication);
        return ResponseEntity.noContent().build();
    }

    public record CsrfDTO(String headerName, String token) { }
}

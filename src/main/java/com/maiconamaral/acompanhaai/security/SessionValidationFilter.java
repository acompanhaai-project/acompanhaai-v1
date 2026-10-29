package com.maiconamaral.acompanhaai.security;

import com.maiconamaral.acompanhaai.repositories.UserRepository;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

public class SessionValidationFilter extends OncePerRequestFilter {
    private final UserRepository repository;

    public SessionValidationFilter(UserRepository repository) { this.repository = repository; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                  FilterChain chain) throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof UserPrincipal principal
                && !repository.existsByIdAndAuthVersion(principal.getId(), principal.getAuthVersion())) {
            var session = request.getSession(false);
            if (session != null) session.invalidate();
            SecurityContextHolder.clearContext();
        }
        chain.doFilter(request, response);
    }
}

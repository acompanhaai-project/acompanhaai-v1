package com.maiconamaral.acompanhaai.config;

import com.maiconamaral.acompanhaai.repositories.UserRepository;
import com.maiconamaral.acompanhaai.security.*;
import com.maiconamaral.acompanhaai.services.UserService;
import org.springframework.context.annotation.*;
import org.springframework.http.*;
import org.springframework.security.authentication.*;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.authentication.session.*;
import org.springframework.security.web.context.*;
import org.springframework.security.web.csrf.*;

@Configuration
public class SecurityConfig {
    @Bean
    public PasswordEncoder passwordEncoder() {
        // PBKDF2 aceita senhas longas sem o limite de 72 bytes do BCrypt.
        return new org.springframework.security.crypto.password.DelegatingPasswordEncoder(
                "pbkdf2@SpringSecurity_v5_8", java.util.Map.of(
                "pbkdf2@SpringSecurity_v5_8",
                org.springframework.security.crypto.password.Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8()));
    }

    @Bean
    public AuthenticationManager authenticationManager(UserRepository repository, PasswordEncoder encoder) {
        var provider = new DaoAuthenticationProvider(email -> repository.findByEmail(UserService.normalizeEmail(email))
                .map(UserPrincipal::new).orElseThrow(() -> new UsernameNotFoundException("Credenciais inválidas.")));
        provider.setPasswordEncoder(encoder);
        return new ProviderManager(provider);
    }

    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    public SessionAuthenticationStrategy sessionAuthenticationStrategy() {
        return new CompositeSessionAuthenticationStrategy(java.util.List.of(
                new ChangeSessionIdAuthenticationStrategy(),
                new CsrfAuthenticationStrategy(new HttpSessionCsrfTokenRepository())));
    }

    @Bean
    public SecurityContextLogoutHandler logoutHandler() { return new SecurityContextLogoutHandler(); }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityContextRepository contextRepository,
                                                   UserRepository repository) throws Exception {
        return http
                .securityContext(context -> context.securityContextRepository(contextRepository))
                .csrf(csrf -> csrf.csrfTokenRepository(new HttpSessionCsrfTokenRepository()))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/api/auth/csrf").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/users", "/api/auth/login").permitAll()
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> {
                            response.setStatus(401);
                            response.setCharacterEncoding("UTF-8");
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            response.getWriter().write("{\"status\":401,\"error\":\"Autenticação necessária.\"}");
                        })
                        .accessDeniedHandler((request, response, exception) -> {
                            response.setStatus(403);
                            response.setCharacterEncoding("UTF-8");
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            response.getWriter().write("{\"status\":403,\"error\":\"Acesso negado ou token CSRF inválido.\"}");
                        }))
                .addFilterAfter(new SessionValidationFilter(repository), SecurityContextHolderFilter.class)
                .logout(logout -> logout.disable())
                .build();
    }
}

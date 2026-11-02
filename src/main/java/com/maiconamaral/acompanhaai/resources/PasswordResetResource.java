package com.maiconamaral.acompanhaai.resources;

import com.maiconamaral.acompanhaai.dto.*;
import com.maiconamaral.acompanhaai.services.PasswordResetService;
import com.maiconamaral.acompanhaai.services.PasswordResetRequestDispatcher;
import com.maiconamaral.acompanhaai.security.PasswordResetRateLimiter;
import com.maiconamaral.acompanhaai.services.exceptions.PasswordResetRateLimitException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth/password-reset")
public class PasswordResetResource {
    private static final PasswordResetResponseDTO ACCEPTED = new PasswordResetResponseDTO(
            "Se houver uma conta para este e-mail, a solicitação de recuperação será processada.");
    private final PasswordResetService service;
    private final PasswordResetRequestDispatcher dispatcher;
    private final PasswordResetRateLimiter limiter;

    public PasswordResetResource(PasswordResetService service, PasswordResetRequestDispatcher dispatcher,
                                 PasswordResetRateLimiter limiter) {
        this.service = service;
        this.dispatcher = dispatcher;
        this.limiter = limiter;
    }

    @PostMapping("/request")
    public ResponseEntity<PasswordResetResponseDTO> request(@Valid @RequestBody PasswordResetRequestDTO dto,
                                                           HttpServletRequest request) {
        dispatcher.request(dto.email(), request.getRemoteAddr());
        return ResponseEntity.accepted().body(ACCEPTED);
    }

    @PostMapping("/confirm")
    public ResponseEntity<Void> confirm(@Valid @RequestBody PasswordResetConfirmationDTO dto,
                                        HttpServletRequest request) {
        long retryAfter = limiter.acquire(PasswordResetRateLimiter.Route.CONFIRM, request.getRemoteAddr());
        if (retryAfter != 0) throw new PasswordResetRateLimitException(retryAfter);
        service.resetPassword(dto.token(), dto.newPassword());
        return ResponseEntity.noContent().build();
    }
}

package com.maiconamaral.acompanhaai.services;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.maiconamaral.acompanhaai.entities.PasswordResetToken;
import com.maiconamaral.acompanhaai.repositories.PasswordResetTokenRepository;
import com.maiconamaral.acompanhaai.repositories.UserRepository;
import com.maiconamaral.acompanhaai.services.exceptions.InvalidPasswordResetTokenException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PasswordResetService {

    private static final Duration TOKEN_LIFETIME = Duration.ofMinutes(30);
    private static final Duration REQUEST_COOLDOWN = Duration.ofMinutes(1);

    private final UserRepository users;
    private final PasswordResetTokenRepository tokens;
    private final Clock clock;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher events;
    private final SecureRandom random = new SecureRandom();

    public PasswordResetService(
        UserRepository users,
        PasswordResetTokenRepository tokens,
        Clock clock,
        PasswordEncoder passwordEncoder,
        ApplicationEventPublisher events
    ) {
        this.users = users;
        this.tokens = tokens;
        this.clock = clock;
        this.passwordEncoder = passwordEncoder;
        this.events = events;
    }

    @Transactional
    public void requestReset(String email) {
        // O listener só recebe o evento após confirmação desta transação.
        issueToken(email).ifPresent(events::publishEvent);
    }

    @Transactional
    public Optional<IssuedToken> issueToken(String email) {
        if (email == null || email.isBlank() || email.strip().length() > 254) {
            return Optional.empty();
        }

        // O bloqueio da conta serializa emissão e reemissão do mesmo usuário.
        var user = users.findByEmailForUpdate(UserService.normalizeEmail(email)).orElse(null);
        if (user == null) {
            return Optional.empty();
        }

        Instant now = clock.instant();
        var latest = tokens.findFirstByUser_IdOrderByCreatedAtDescIdDesc(user.getId());
        if (latest.isPresent() && latest.get().getCreatedAt().plus(REQUEST_COOLDOWN).isAfter(now)) {
            return Optional.empty();
        }

        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant expiresAt = now.plus(TOKEN_LIFETIME);

        // A reemissão substitui tokens não utilizados, preservando os já consumidos.
        tokens.deleteUnusedByUserId(user.getId());
        tokens.saveAndFlush(new PasswordResetToken(user, hashToken(rawToken), now, expiresAt));

        return Optional.of(new IssuedToken(user.getEmail(), rawToken, expiresAt));
    }

    @Transactional
    public void resetPassword(String rawToken, String newPassword) {
        if (rawToken == null || !rawToken.matches("[A-Za-z0-9_-]{43}")) {
            throw new InvalidPasswordResetTokenException();
        }
        if (newPassword == null || newPassword.isBlank() || newPassword.length() < 8 || newPassword.length() > 128) {
            throw new IllegalArgumentException("A nova senha deve conter de 8 a 128 caracteres e não pode ser vazia.");
        }

        String hash = hashToken(rawToken);
        Long userId = tokens.findUserIdByTokenHash(hash).orElseThrow(InvalidPasswordResetTokenException::new);
        // Mesmo bloqueio da emissão: depois de esperar, consultar novamente o token.
        var user = users.findByIdForUpdate(userId).orElseThrow(InvalidPasswordResetTokenException::new);
        var token = tokens.findByTokenHash(hash).orElseThrow(InvalidPasswordResetTokenException::new);
        Instant now = clock.instant();
        if (!token.isUsableAt(now)) {
            throw new InvalidPasswordResetTokenException();
        }

        token.markUsed(now);
        // Incrementar authVersion revoga as sessões anteriores na próxima requisição.
        user.changePassword(passwordEncoder.encode(newPassword));
        tokens.deleteUnusedByUserId(userId);
    }

    private static String hashToken(String rawToken) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(rawToken.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 indisponível.", exception);
        }
    }

    // Resultado interno para entrega de e-mail; não é uma resposta HTTP.
    public record IssuedToken(String recipient, @JsonIgnore String token, Instant expiresAt) {
        @Override
        public String toString() {
            return "IssuedToken[expiresAt=" + expiresAt + "]";
        }
    }
}

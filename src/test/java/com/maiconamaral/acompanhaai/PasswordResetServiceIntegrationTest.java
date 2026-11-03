package com.maiconamaral.acompanhaai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.maiconamaral.acompanhaai.entities.User;
import com.maiconamaral.acompanhaai.repositories.PasswordResetTokenRepository;
import com.maiconamaral.acompanhaai.repositories.UserRepository;
import com.maiconamaral.acompanhaai.services.PasswordResetService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@ActiveProfiles("test")
class PasswordResetServiceIntegrationTest {

    private static final String EMAIL = "reset-service@example.test";
    private static final Instant NOW = Instant.parse("2026-11-02T03:00:00Z");

    @Autowired
    private PasswordResetService service;

    @Autowired
    private PasswordResetTokenRepository tokens;

    @Autowired
    private UserRepository users;

    @Autowired
    private ObjectMapper mapper;

    @MockitoBean
    private Clock clock;

    @BeforeEach
    void prepareIsolatedDatabaseAndClock() {
        users.deleteAll();
        when(clock.instant()).thenReturn(NOW);
    }

    @AfterEach
    void cleanAccounts() {
        users.deleteAll();
    }

    @Test
    void issuesRandomTokenAndStoresOnlyItsHashWithThirtyMinuteExpiry() throws Exception {
        User user = user(EMAIL);

        var issued = service.issueToken("  RESET-SERVICE@example.test  ").orElseThrow();

        assertThat(issued.recipient()).isEqualTo(EMAIL);
        assertThat(issued.token()).matches("[A-Za-z0-9_-]{43}");
        assertThat(Base64.getUrlDecoder().decode(issued.token())).hasSize(32);
        assertThat(issued.expiresAt()).isEqualTo(NOW.plusSeconds(1800));
        var stored = tokens.findByTokenHash(hash(issued.token())).orElseThrow();
        assertThat(stored.getUser().getId()).isEqualTo(user.getId());
        assertThat(stored.getTokenHash()).hasSize(64).isNotEqualTo(issued.token());
        assertThat(stored.getCreatedAt()).isEqualTo(NOW);
        assertThat(stored.getExpiresAt()).isEqualTo(issued.expiresAt());
        assertThat(stored.getUsedAt()).isNull();
        assertThat(tokens.count()).isEqualTo(1);
    }

    @Test
    void unknownEmailDoesNotCreateAccountOrToken() {
        assertThat(service.issueToken("unknown@example.test")).isEmpty();
        assertThat(users.count()).isZero();
        assertThat(tokens.count()).isZero();
    }

    @Test
    void nullOrBlankEmailDoesNotCreateTokens() {
        assertThat(service.issueToken(null)).isEmpty();
        assertThat(service.issueToken("")).isEmpty();
        assertThat(service.issueToken("   ")).isEmpty();
        assertThat(service.issueToken("a".repeat(255))).isEmpty();
        assertThat(tokens.count()).isZero();
    }

    @Test
    void repeatedRequestDuringCooldownDoesNotReplaceExistingToken() throws Exception {
        user(EMAIL);
        var first = service.issueToken(EMAIL).orElseThrow();
        when(clock.instant()).thenReturn(NOW.plusSeconds(59));

        assertThat(service.issueToken("RESET-SERVICE@example.test")).isEmpty();
        assertThat(tokens.findByTokenHash(hash(first.token()))).isPresent();
        assertThat(tokens.count()).isEqualTo(1);
    }

    @Test
    void requestAtCooldownBoundaryReplacesUnusedTokenWithFreshRandomToken() throws Exception {
        user(EMAIL);
        var first = service.issueToken(EMAIL).orElseThrow();
        when(clock.instant()).thenReturn(NOW.plusSeconds(60));

        var second = service.issueToken(EMAIL).orElseThrow();

        assertThat(second.token()).isNotEqualTo(first.token());
        assertThat(second.expiresAt()).isEqualTo(NOW.plusSeconds(1860));
        assertThat(tokens.findByTokenHash(hash(first.token()))).isEmpty();
        assertThat(tokens.findByTokenHash(hash(second.token()))).isPresent();
        assertThat(tokens.count()).isEqualTo(1);
    }

    @Test
    void reissuePreservesAnotherAccountsToken() throws Exception {
        user(EMAIL);
        user("other-reset@example.test");
        var first = service.issueToken(EMAIL).orElseThrow();
        var other = service.issueToken("other-reset@example.test").orElseThrow();
        when(clock.instant()).thenReturn(NOW.plusSeconds(60));

        service.issueToken(EMAIL).orElseThrow();

        assertThat(tokens.findByTokenHash(hash(first.token()))).isEmpty();
        assertThat(tokens.findByTokenHash(hash(other.token()))).isPresent();
        assertThat(tokens.count()).isEqualTo(2);
    }

    @Test
    void reissueKeepsAlreadyConsumedTokenForHistory() throws Exception {
        user(EMAIL);
        var first = service.issueToken(EMAIL).orElseThrow();
        var stored = tokens.findByTokenHash(hash(first.token())).orElseThrow();
        stored.markUsed(NOW.plusSeconds(1));
        tokens.saveAndFlush(stored);
        when(clock.instant()).thenReturn(NOW.plusSeconds(60));

        service.issueToken(EMAIL).orElseThrow();

        assertThat(tokens.findByTokenHash(hash(first.token())).orElseThrow().getUsedAt()).isEqualTo(NOW.plusSeconds(1));
        assertThat(tokens.count()).isEqualTo(2);
    }

    @Test
    void concurrentRequestsForSameAccountIssueOnlyOneToken() throws Exception {
        user(EMAIL);
        var start = new CyclicBarrier(2);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return service.issueToken(EMAIL);
            });
            var second = executor.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return service.issueToken(EMAIL);
            });

            var results = Stream.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
            assertThat(results.filter(Optional::isPresent).count()).isEqualTo(1);
        }
        assertThat(tokens.count()).isEqualTo(1);
    }

    @Test
    void internalResultDoesNotExposeTokenThroughJsonOrToString() {
        user(EMAIL);
        var issued = service.issueToken(EMAIL).orElseThrow();

        assertThat(issued.toString()).doesNotContain(issued.token(), issued.recipient());
        String json = mapper.writeValueAsString(issued);
        assertThat(json).doesNotContain(issued.token(), "\"token\"");
    }

    @Test
    void issuanceDoesNotChangePasswordOrAuthenticationVersion() {
        User user = user(EMAIL);
        String originalHash = user.getPasswordHash();
        long originalVersion = user.getAuthVersion();

        service.issueToken(EMAIL).orElseThrow();

        User reloaded = users.findById(user.getId()).orElseThrow();
        assertThat(reloaded.getPasswordHash()).isEqualTo(originalHash);
        assertThat(reloaded.getAuthVersion()).isEqualTo(originalVersion);
    }

    private User user(String email) {
        return users.saveAndFlush(new User("Teste", email, "hash-only-for-service-test"));
    }

    private String hash(String token) throws Exception {
        return HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))
        );
    }
}

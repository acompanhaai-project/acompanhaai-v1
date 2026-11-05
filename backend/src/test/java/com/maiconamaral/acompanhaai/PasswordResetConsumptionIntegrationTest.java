package com.maiconamaral.acompanhaai;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.maiconamaral.acompanhaai.entities.PasswordResetToken;
import com.maiconamaral.acompanhaai.entities.User;
import com.maiconamaral.acompanhaai.repositories.PasswordResetTokenRepository;
import com.maiconamaral.acompanhaai.repositories.UserRepository;
import com.maiconamaral.acompanhaai.services.PasswordResetService;
import com.maiconamaral.acompanhaai.services.exceptions.InvalidPasswordResetTokenException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PasswordResetConsumptionIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-11-02T03:00:00Z");
    private static final String EMAIL = "reset-consumption@example.test";
    private static final String OLD_PASSWORD = "SenhaAnterior123!";
    private static final String NEW_PASSWORD = "NovaSenha123!";

    @Autowired
    private PasswordResetService service;

    @Autowired
    private PasswordResetTokenRepository tokens;

    @Autowired
    private UserRepository users;

    @Autowired
    private PasswordEncoder encoder;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private PlatformTransactionManager transactions;

    @MockitoBean
    private Clock clock;

    @BeforeEach
    void prepare() {
        users.deleteAll();
        when(clock.instant()).thenReturn(NOW);
    }

    @AfterEach
    void clean() {
        users.deleteAll();
    }

    @Test
    void resetStoresEncodedPasswordConsumesTokenAndIncrementsAuthenticationVersion() throws Exception {
        var user = user(EMAIL);
        var issued = service.issueToken(EMAIL).orElseThrow();
        service.resetPassword(issued.token(), NEW_PASSWORD);

        var updated = users.findById(user.getId()).orElseThrow();
        assertThat(updated.getPasswordHash()).isNotEqualTo(NEW_PASSWORD).isNotEqualTo(user.getPasswordHash());
        assertThat(encoder.matches(NEW_PASSWORD, updated.getPasswordHash())).isTrue();
        assertThat(encoder.matches(OLD_PASSWORD, updated.getPasswordHash())).isFalse();
        assertThat(updated.getAuthVersion()).isEqualTo(user.getAuthVersion() + 1);
        assertThat(tokens.findByTokenHash(hash(issued.token())).orElseThrow().getUsedAt()).isEqualTo(NOW);
    }

    @Test
    void unknownAndMalformedTokensDoNotChangePasswordOrConsumeValidToken() throws Exception {
        var user = user(EMAIL);
        var issued = service.issueToken(EMAIL).orElseThrow();
        for (String token : new String[] { null, "", " ", "abc", "a".repeat(43), "a".repeat(1000) }) {
            assertThatThrownBy(() -> service.resetPassword(token, NEW_PASSWORD))
                .isInstanceOf(InvalidPasswordResetTokenException.class)
                .hasMessage("Token de recuperação inválido ou indisponível.");
        }
        assertUnchanged(user, issued.token());
    }

    @Test
    void expiryBoundaryAndExpiredTokensAreRejected() throws Exception {
        var user = user(EMAIL);
        var issued = service.issueToken(EMAIL).orElseThrow();
        for (Instant instant : new Instant[] { NOW.plusSeconds(1800), NOW.plusSeconds(1801) }) {
            when(clock.instant()).thenReturn(instant);
            assertThatThrownBy(() -> service.resetPassword(issued.token(), NEW_PASSWORD)).isInstanceOf(
                InvalidPasswordResetTokenException.class
            );
        }
        assertUnchanged(user, issued.token());
    }

    @Test
    void tokenCannotBeConsumedBeforeItsCreation() throws Exception {
        var user = user(EMAIL);
        var issued = service.issueToken(EMAIL).orElseThrow();
        when(clock.instant()).thenReturn(NOW.minusSeconds(1));
        assertThatThrownBy(() -> service.resetPassword(issued.token(), NEW_PASSWORD)).isInstanceOf(
            InvalidPasswordResetTokenException.class
        );
        assertUnchanged(user, issued.token());
    }

    @Test
    void tokenIsAcceptedImmediatelyBeforeExpiry() {
        user(EMAIL);
        var issued = service.issueToken(EMAIL).orElseThrow();
        when(clock.instant()).thenReturn(NOW.plusSeconds(1799));
        service.resetPassword(issued.token(), NEW_PASSWORD);
        assertThat(encoder.matches(NEW_PASSWORD, users.findByEmail(EMAIL).orElseThrow().getPasswordHash())).isTrue();
    }

    @Test
    void reusedTokenCannotChangePasswordAgain() {
        var user = user(EMAIL);
        var issued = service.issueToken(EMAIL).orElseThrow();
        service.resetPassword(issued.token(), NEW_PASSWORD);
        var updated = users.findById(user.getId()).orElseThrow();
        assertThatThrownBy(() -> service.resetPassword(issued.token(), "OutraSenha123!")).isInstanceOf(
            InvalidPasswordResetTokenException.class
        );
        var reloaded = users.findById(user.getId()).orElseThrow();
        assertThat(reloaded.getPasswordHash()).isEqualTo(updated.getPasswordHash());
        assertThat(reloaded.getAuthVersion()).isEqualTo(updated.getAuthVersion());
    }

    @Test
    void invalidNewPasswordDoesNotConsumeToken() throws Exception {
        var user = user(EMAIL);
        var issued = service.issueToken(EMAIL).orElseThrow();
        for (String password : new String[] { null, "", " ".repeat(8), "1234567", "a".repeat(129) }) {
            assertThatThrownBy(() -> service.resetPassword(issued.token(), password)).isInstanceOf(
                IllegalArgumentException.class
            );
        }
        assertUnchanged(user, issued.token());
        service.resetPassword(issued.token(), NEW_PASSWORD);
    }

    @Test
    void reissuedTokenRejectsOldLink() {
        user(EMAIL);
        var old = service.issueToken(EMAIL).orElseThrow();
        when(clock.instant()).thenReturn(NOW.plusSeconds(60));
        var current = service.issueToken(EMAIL).orElseThrow();
        assertThatThrownBy(() -> service.resetPassword(old.token(), NEW_PASSWORD)).isInstanceOf(
            InvalidPasswordResetTokenException.class
        );
        service.resetPassword(current.token(), NEW_PASSWORD);
    }

    @Test
    void resetRemovesOtherUnusedTokensOfOwnerAndPreservesAnotherAccount() throws Exception {
        var owner = user(EMAIL);
        var other = user("other-consumption@example.test");
        var issued = service.issueToken(EMAIL).orElseThrow();
        var otherToken = service.issueToken(other.getEmail()).orElseThrow();
        // Simula registros legados: o fluxo normal de emissão mantém apenas um ativo.
        String legacy = "b".repeat(43);
        tokens.saveAndFlush(new PasswordResetToken(owner, hash(legacy), NOW, NOW.plusSeconds(1800)));

        service.resetPassword(issued.token(), NEW_PASSWORD);

        assertThat(tokens.findByTokenHash(hash(legacy))).isEmpty();
        assertThatThrownBy(() -> service.resetPassword(legacy, NEW_PASSWORD)).isInstanceOf(
            InvalidPasswordResetTokenException.class
        );
        assertUnchanged(other, otherToken.token());
        service.resetPassword(otherToken.token(), "OutraSenha123!");
    }

    @Test
    void simultaneousConsumptionHasExactlyOneWinner() throws Exception {
        var user = user(EMAIL);
        var issued = service.issueToken(EMAIL).orElseThrow();
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> consumeConcurrently(barrier, issued.token(), NEW_PASSWORD));
            var second = executor.submit(() -> consumeConcurrently(barrier, issued.token(), "Concorrente123!"));
            boolean firstWon = first.get(20, TimeUnit.SECONDS);
            boolean secondWon = second.get(20, TimeUnit.SECONDS);
            assertThat(firstWon).isNotEqualTo(secondWon);
            var updated = users.findById(user.getId()).orElseThrow();
            assertThat(updated.getAuthVersion()).isEqualTo(1);
            assertThat(
                encoder.matches(firstWon ? NEW_PASSWORD : "Concorrente123!", updated.getPasswordHash())
            ).isTrue();
        }
    }

    @Test
    void transactionRollbackRestoresPasswordTokenAndOtherTokens() throws Exception {
        var owner = user(EMAIL);
        var issued = service.issueToken(EMAIL).orElseThrow();
        String legacy = "c".repeat(43);
        tokens.saveAndFlush(new PasswordResetToken(owner, hash(legacy), NOW, NOW.plusSeconds(1800)));
        var transaction = new TransactionTemplate(transactions);
        assertThatThrownBy(() ->
            transaction.executeWithoutResult(status -> {
                service.resetPassword(issued.token(), NEW_PASSWORD);
                // O Service participa da transação externa, inclusive após o flush.
                throw new IllegalStateException("Falha simulada antes do commit");
            })
        ).isInstanceOf(IllegalStateException.class);
        assertUnchanged(owner, issued.token());
        assertThat(tokens.findByTokenHash(hash(legacy))).isPresent();
        service.resetPassword(issued.token(), NEW_PASSWORD);
    }

    @Test
    void resetRevokesAllOldOwnerSessionsPreservesOthersAndRequiresNewPasswordForLogin() throws Exception {
        user(EMAIL);
        user("other-session@example.test");
        var first = login(EMAIL, OLD_PASSWORD);
        var second = login(EMAIL, OLD_PASSWORD);
        var other = login("other-session@example.test", OLD_PASSWORD);
        var issued = service.issueToken(EMAIL).orElseThrow();
        service.resetPassword(issued.token(), NEW_PASSWORD);

        mvc.perform(get("/api/users/me").session(first)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/users/me").session(second)).andExpect(status().isUnauthorized());
        assertThat(first.isInvalid()).isTrue();
        assertThat(second.isInvalid()).isTrue();
        mvc.perform(get("/api/users/me").session(other)).andExpect(status().isOk());
        mvc.perform(
            post("/api/auth/login")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(loginJson(EMAIL, OLD_PASSWORD))
        ).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/users/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/users/me").session(login(EMAIL, NEW_PASSWORD))).andExpect(status().isOk());
    }

    private boolean consumeConcurrently(CyclicBarrier barrier, String token, String password) throws Exception {
        barrier.await(10, TimeUnit.SECONDS);
        try {
            service.resetPassword(token, password);
            return true;
        } catch (InvalidPasswordResetTokenException exception) {
            return false;
        }
    }

    private void assertUnchanged(User user, String token) throws Exception {
        var reloaded = users.findById(user.getId()).orElseThrow();
        assertThat(reloaded.getPasswordHash()).isEqualTo(user.getPasswordHash());
        assertThat(reloaded.getAuthVersion()).isEqualTo(user.getAuthVersion());
        assertThat(tokens.findByTokenHash(hash(token)).orElseThrow().getUsedAt()).isNull();
    }

    private User user(String email) {
        return users.saveAndFlush(new User("Teste", email, encoder.encode(OLD_PASSWORD)));
    }

    private String hash(String token) throws Exception {
        return HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))
        );
    }

    private MockHttpSession login(String email, String password) throws Exception {
        return (MockHttpSession) mvc
            .perform(
                post("/api/auth/login")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(loginJson(email, password))
            )
            .andExpect(status().isOk())
            .andReturn()
            .getRequest()
            .getSession(false);
    }

    private String loginJson(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }
}

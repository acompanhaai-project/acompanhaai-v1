package com.maiconamaral.acompanhaai;

import static org.assertj.core.api.Assertions.*;

import com.maiconamaral.acompanhaai.entities.PasswordResetToken;
import com.maiconamaral.acompanhaai.entities.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PasswordResetTokenIntegrationTest {

    private static final Instant ISSUED_AT = Instant.parse("2026-11-02T00:00:00Z");
    private static final String HASH = "a".repeat(64);

    @Autowired
    private EntityManager em;

    @Test
    void persistsOwnerHashExpiryAndVersionedUsage() {
        User user = user("reset@example.test");
        var token = new PasswordResetToken(user, HASH, ISSUED_AT, ISSUED_AT.plusSeconds(1800));
        em.persist(token);
        em.flush();
        Long tokenId = token.getId();
        Long userId = user.getId();
        long initialVersion = token.getVersion();
        em.clear();

        var loaded = em.find(PasswordResetToken.class, tokenId);
        assertThat(loaded.getUser().getId()).isEqualTo(userId);
        assertThat(loaded.getTokenHash()).isEqualTo(HASH);
        assertThat(loaded.getCreatedAt()).isEqualTo(ISSUED_AT);
        assertThat(loaded.getExpiresAt()).isEqualTo(ISSUED_AT.plusSeconds(1800));
        assertThat(loaded.getUsedAt()).isNull();
        loaded.markUsed(ISSUED_AT.plusSeconds(600));
        em.flush();
        em.clear();

        var used = em.find(PasswordResetToken.class, tokenId);
        assertThat(used.getUsedAt()).isEqualTo(ISSUED_AT.plusSeconds(600));
        assertThat(used.getVersion()).isGreaterThan(initialVersion);
        assertThat(used.isUsableAt(ISSUED_AT.plusSeconds(601))).isFalse();
    }

    @Test
    void rejectsDuplicateHashes() {
        User user = user("duplicate-reset@example.test");
        em.persist(new PasswordResetToken(user, HASH, ISSUED_AT, ISSUED_AT.plusSeconds(1800)));
        em.flush();

        assertThatThrownBy(() -> {
            em.persist(new PasswordResetToken(user, HASH, ISSUED_AT, ISSUED_AT.plusSeconds(1800)));
            em.flush();
        }).isInstanceOf(PersistenceException.class);
    }

    @Test
    void deletingUserRemovesOwnTokensAndPreservesAnotherUsersTokens() {
        User first = user("first-reset@example.test");
        User second = user("second-reset@example.test");
        var firstToken = new PasswordResetToken(first, HASH, ISSUED_AT, ISSUED_AT.plusSeconds(1800));
        var secondToken = new PasswordResetToken(second, "b".repeat(64), ISSUED_AT, ISSUED_AT.plusSeconds(1800));
        em.persist(firstToken);
        em.persist(secondToken);
        em.flush();
        Long firstId = first.getId();
        Long firstTokenId = firstToken.getId();
        Long secondTokenId = secondToken.getId();
        em.clear();

        em.remove(em.find(User.class, firstId));
        em.flush();
        em.clear();

        assertThat(em.find(PasswordResetToken.class, firstTokenId)).isNull();
        assertThat(em.find(PasswordResetToken.class, secondTokenId)).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(ints = { -1, 0, 31 })
    void databaseRejectsInvalidExpirationWindow(int lifetimeMinutes) {
        User user = user("invalid-expiry@example.test");
        em.flush();

        assertThatThrownBy(() -> insertDirectly(user, HASH, ISSUED_AT.plusSeconds(lifetimeMinutes * 60L))).isInstanceOf(
            PersistenceException.class
        );
    }

    @Test
    void databaseRejectsHashWithWrongLength() {
        User user = user("invalid-hash@example.test");
        em.flush();

        assertThatThrownBy(() -> insertDirectly(user, "token-em-texto", ISSUED_AT.plusSeconds(1800))).isInstanceOf(
            PersistenceException.class
        );
    }

    @Test
    void rejectsInvalidHashAndLifetimeBeforePersistence() {
        User user = user("invalid-constructor@example.test");
        assertThatIllegalArgumentException().isThrownBy(() ->
            new PasswordResetToken(user, "token-em-texto", ISSUED_AT, ISSUED_AT.plusSeconds(1800))
        );
        assertThatIllegalArgumentException().isThrownBy(() -> new PasswordResetToken(user, HASH, ISSUED_AT, ISSUED_AT));
        assertThatIllegalArgumentException().isThrownBy(() ->
            new PasswordResetToken(user, HASH, ISSUED_AT, ISSUED_AT.plusSeconds(1801))
        );
    }

    @Test
    void rejectsUseBeforeIssuanceAtExpiryAndAfterConsumption() {
        User user = user("usage@example.test");
        var token = new PasswordResetToken(user, HASH, ISSUED_AT, ISSUED_AT.plusSeconds(1800));

        assertThatIllegalStateException().isThrownBy(() -> token.markUsed(ISSUED_AT.minusSeconds(1)));
        assertThatIllegalStateException().isThrownBy(() -> token.markUsed(ISSUED_AT.plusSeconds(1800)));
        assertThat(token.getUsedAt()).isNull();
        token.markUsed(ISSUED_AT);
        assertThatIllegalStateException().isThrownBy(() -> token.markUsed(ISSUED_AT.plusSeconds(1)));
    }

    private User user(String email) {
        var user = new User("Teste", email, "hash-only-for-persistence-test");
        em.persist(user);
        return user;
    }

    private void insertDirectly(User user, String hash, Instant expiry) {
        em.createNativeQuery(
            """
            INSERT INTO tb_password_reset_token
                (user_id, token_hash, created_at, expires_at, version)
            VALUES (:owner, :hash, :created, :expires, 0)
            """
        )
            .setParameter("owner", user.getId())
            .setParameter("hash", hash)
            .setParameter("created", ISSUED_AT)
            .setParameter("expires", expiry)
            .executeUpdate();
    }
}

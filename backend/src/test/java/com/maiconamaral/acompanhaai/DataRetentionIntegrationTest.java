package com.maiconamaral.acompanhaai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.maiconamaral.acompanhaai.entities.PasswordResetToken;
import com.maiconamaral.acompanhaai.repositories.PasswordResetTokenRepository;
import com.maiconamaral.acompanhaai.repositories.UserRepository;
import com.maiconamaral.acompanhaai.services.DataRetention;
import com.maiconamaral.acompanhaai.services.ImportTaskProcessor;
import com.maiconamaral.acompanhaai.services.ImportTaskQueue;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** Política de retenção: expiração de dados temporários e exclusão completa da conta. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DataRetentionIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private PasswordResetTokenRepository resetTokens;

    @Autowired
    private DataRetention retention;

    @Autowired
    private ImportTaskQueue queue;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        users.deleteAll();
    }

    @Test
    void removesResetTokensOneDayAfterTheyExpire() throws Exception {
        account("retencao-token@example.com");
        var user = users.findByEmail("retencao-token@example.com").orElseThrow();
        var now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        var old = token(user, now.minus(Duration.ofHours(30)));
        var recent = token(user, now.minus(Duration.ofHours(3)));
        var active = token(user, now.minus(Duration.ofMinutes(5)));

        assertThat(retention.purgePasswordResetTokens()).isEqualTo(1);

        assertThat(resetTokens.existsById(old.getId())).isFalse();
        assertThat(resetTokens.existsById(recent.getId())).isTrue();
        assertThat(resetTokens.existsById(active.getId())).isTrue();
        assertThat(users.existsById(user.getId())).isTrue();
    }

    @Test
    void deletingTheAccountDuringAnActiveImportRemovesEveryAssociatedRecord() throws Exception {
        var session = account("retencao-conta@example.com");
        var other = account("retencao-outra@example.com");
        var user = users.findByEmail("retencao-conta@example.com").orElseThrow();
        long userId = user.getId();
        application(session, "Vaga da conta excluída");
        application(other, "Vaga de outra conta");
        token(user, Instant.now().minus(Duration.ofMinutes(5)));
        jdbc.update(
            "INSERT INTO tb_user_photo (user_id, content_type, content, updated_at) VALUES (?, 'image/png', ?, ?)",
            userId,
            new byte[] { 1, 2, 3 },
            java.sql.Timestamp.from(Instant.now())
        );
        importText(session, "Descrição temporária que deve sair com a conta.");
        var inFlight = queue.claim().orElseThrow();
        importText(session, "Segunda descrição ainda na fila.");

        mvc.perform(delete("/api/users/me").session(session).with(csrf())).andExpect(status().isNoContent());

        var late = new ImportTaskProcessor.Ready(null, Map.of("description", "Resultado atrasado"));
        assertThat(queue.finish(inFlight, late)).as("worker atrasado não recria dados").isFalse();
        assertThat(queue.claim()).isEmpty();
        for (var table : new String[] {
            "tb_job",
            "tb_application",
            "tb_import_task",
            "tb_password_reset_token",
            "tb_user_photo",
        }) {
            assertThat(count("SELECT COUNT(*) FROM " + table + " WHERE user_id = ?", userId))
                .as(table)
                .isZero();
        }
        assertThat(count(
            "SELECT COUNT(*) FROM tb_application_status_history h WHERE NOT EXISTS "
                + "(SELECT 1 FROM tb_application a WHERE a.id = h.application_id)"
        )).isZero();
        assertThat(users.existsById(userId)).isFalse();
        mvc.perform(get("/api/users/me").session(session)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/applications").session(other)).andExpect(status().isOk());
        assertThat(count("SELECT COUNT(*) FROM tb_application")).isEqualTo(1);
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private PasswordResetToken token(com.maiconamaral.acompanhaai.entities.User user, Instant createdAt) {
        var hash = HexFormat.of().formatHex(UUID.randomUUID().toString().repeat(2).getBytes()).substring(0, 64);
        return resetTokens.saveAndFlush(new PasswordResetToken(user, hash, createdAt, createdAt.plus(Duration.ofMinutes(30))));
    }

    private void application(MockHttpSession session, String title) throws Exception {
        mvc.perform(
            post("/api/applications")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"job\":{\"title\":\"" + title + "\"},\"status\":\"APPLIED\"}")
        ).andExpect(status().isCreated());
    }

    private long importText(MockHttpSession session, String text) throws Exception {
        var content = mvc
            .perform(
                post("/api/import-tasks")
                    .session(session)
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"inputType\":\"TEXT\",\"text\":\"" + text + "\"}")
            )
            .andExpect(status().isAccepted())
            .andReturn()
            .getResponse()
            .getContentAsString();
        return ((Number) JsonPath.read(content, "$.id")).longValue();
    }

    private MockHttpSession account(String email) throws Exception {
        mvc.perform(
            post("/api/users")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Teste\",\"email\":\"" + email + "\",\"password\":\"SenhaTeste123!\"}")
        ).andExpect(status().isCreated());
        var result = mvc
            .perform(
                post("/api/auth/login")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"" + email + "\",\"password\":\"SenhaTeste123!\"}")
            )
            .andExpect(status().isOk())
            .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }
}

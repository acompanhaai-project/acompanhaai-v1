package com.maiconamaral.acompanhaai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.maiconamaral.acompanhaai.repositories.ImportTaskRepository;
import com.maiconamaral.acompanhaai.repositories.UserRepository;
import com.maiconamaral.acompanhaai.services.ImportTaskProcessor;
import com.maiconamaral.acompanhaai.services.ImportTaskQueue;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest(
    properties = {
        "app.import.limits.per-user-hourly=3",
        "app.import.limits.per-user-active=2",
        "app.import.limits.global-active=3"
    }
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class ImportLimitsHttpIntegrationTest {

    private static final String TEXT = "{\"inputType\":\"TEXT\",\"text\":\"Vaga Java\"}";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private ImportTaskRepository tasks;

    @Autowired
    private ImportTaskQueue queue;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        users.deleteAll();
    }

    @Test
    void limitsActiveImportsPerAccount() throws Exception {
        var session = account("limite-ativas@example.com");
        create(session, TEXT).andExpect(status().isAccepted());
        create(session, TEXT).andExpect(status().isAccepted());

        create(session, TEXT)
            .andExpect(status().isTooManyRequests())
            .andExpect(header().string("Retry-After", "30"))
            .andExpect(jsonPath("$.code").value("IMPORT_ACTIVE_LIMIT"));

        finishNext();
        create(session, TEXT).andExpect(status().isAccepted());
        assertThat(tasks.count()).isEqualTo(3);
    }

    @Test
    void limitsImportsPerHourAndReleasesThemAfterTheWindow() throws Exception {
        var session = account("limite-hora@example.com");
        for (int index = 0; index < 3; index++) {
            create(session, TEXT).andExpect(status().isAccepted());
            finishNext();
        }

        var rejected = create(session, TEXT)
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.code").value("IMPORT_QUOTA_EXCEEDED"))
            .andReturn();
        long retryAfter = Long.parseLong(rejected.getResponse().getHeader("Retry-After"));
        assertThat(retryAfter).isBetween(3500L, 3600L);

        jdbc.update("UPDATE tb_import_task SET created_at = ?", OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(61));

        create(session, TEXT).andExpect(status().isAccepted());
    }

    @Test
    void refusesNewImportsWhenTheQueueIsOverloaded(CapturedOutput output) throws Exception {
        var first = account("sobrecarga-1@example.com");
        var second = account("sobrecarga-2@example.com");
        create(first, TEXT).andExpect(status().isAccepted());
        create(first, TEXT).andExpect(status().isAccepted());
        create(second, TEXT).andExpect(status().isAccepted());

        create(second, TEXT)
            .andExpect(status().isServiceUnavailable())
            .andExpect(header().string("Retry-After", "60"))
            .andExpect(jsonPath("$.code").value("IMPORT_OVERLOADED"));
        assertThat(output.getOut()).contains("importacao recusada codigo=IMPORT_OVERLOADED");

        assertThat(tasks.count()).isEqualTo(3);
    }

    @Test
    void validatesInputBeforeConsumingTheQuota() throws Exception {
        var session = account("limite-validacao@example.com");
        create(session, TEXT).andExpect(status().isAccepted());
        create(session, TEXT).andExpect(status().isAccepted());

        create(session, "{\"inputType\":\"TEXT\",\"text\":\" \"}").andExpect(status().is(422));
    }

    @Test
    void logsTheOutcomeWithoutTheImportedContent(CapturedOutput output) throws Exception {
        var session = account("limite-log@example.com");
        var url = "https://vagas.example.test/vaga-confidencial";
        create(session, "{\"inputType\":\"URL\",\"url\":\"" + url + "\"}").andExpect(status().isAccepted());

        var claim = queue.claim().orElseThrow();
        queue.finish(claim, new ImportTaskProcessor.NeedsInput(ImportTaskProcessor.Fallback.SOURCE_BLOCKED));

        assertThat(output.getOut())
            .contains("tipo=URL tentativa=1 status=NEEDS_INPUT codigo=SOURCE_BLOCKED duracaoMs=")
            .doesNotContain("vaga-confidencial");
    }

    private void finishNext() {
        var claim = queue.claim().orElseThrow();
        assertThat(queue.finish(claim, new ImportTaskProcessor.Ready(null, Map.of("description", "Vaga")))).isTrue();
    }

    private ResultActions create(MockHttpSession session, String body) throws Exception {
        return mvc.perform(
            post("/api/import-tasks")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
        );
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

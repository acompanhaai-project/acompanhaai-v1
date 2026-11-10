package com.maiconamaral.acompanhaai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.jayway.jsonpath.JsonPath;
import com.maiconamaral.acompanhaai.entities.ImportTask;
import com.maiconamaral.acompanhaai.entities.Job;
import com.maiconamaral.acompanhaai.entities.enums.ImportStatus;
import com.maiconamaral.acompanhaai.repositories.ImportTaskRepository;
import com.maiconamaral.acompanhaai.repositories.JobRepository;
import com.maiconamaral.acompanhaai.repositories.UserRepository;
import com.maiconamaral.acompanhaai.services.ImportTaskQueue;
import com.maiconamaral.acompanhaai.services.ImportTaskProcessor;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ImportTaskHttpIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private ImportTaskRepository tasks;

    @Autowired
    private JobRepository jobs;

    @Autowired
    private ImportTaskQueue queue;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        users.deleteAll();
    }

    @Test
    void acceptsUrlImportAndExposesItsStateToTheOwner() throws Exception {
        var session = account("importacao-url@example.com");

        var result = create(session, "{\"inputType\":\"URL\",\"url\":\" HTTPS://Vagas.Example.Test:443/java?id=1 \"}")
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.inputType").value("URL"))
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.expiresAt").exists())
            .andExpect(jsonPath("$.failure").doesNotExist())
            .andExpect(jsonPath("$.result").doesNotExist())
            .andExpect(jsonPath("$.jobId").doesNotExist())
            .andReturn();
        long id = ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
        assertThat(result.getResponse().getHeader("Location")).endsWith("/api/import-tasks/" + id);

        var task = tasks.findById(id).orElseThrow();
        assertThat(task.getSourceUrl()).isEqualTo("https://vagas.example.test/java?id=1");
        assertThat(task.getInputText()).isNull();
        String expiresAt = JsonPath.read(result.getResponse().getContentAsString(), "$.expiresAt");
        assertThat(Instant.parse(expiresAt)).isEqualTo(
            task.getCreatedAt().plus(24, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS)
        );

        mvc.perform(get("/api/import-tasks/" + id).session(session))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.id").value(id))
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.expiresAt").value(expiresAt));
    }

    @Test
    void acceptsPastedDescriptionWithoutUrl() throws Exception {
        var session = account("importacao-texto@example.com");

        long id = id(create(session, "{\"inputType\":\"TEXT\",\"text\":\"Pessoa desenvolvedora Java. Remoto.\"}")
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.inputType").value("TEXT")));

        var task = tasks.findById(id).orElseThrow();
        assertThat(task.getInputText()).isEqualTo("Pessoa desenvolvedora Java. Remoto.");
        assertThat(task.getSourceUrl()).isNull();
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
            "{\"inputType\":\"URL\"}",
            "{\"inputType\":\"URL\",\"url\":\"   \"}",
            "{\"inputType\":\"URL\",\"url\":\"ftp://vagas.example.test/java\"}",
            "{\"inputType\":\"URL\",\"url\":\"https://pessoa:senha-ficticia@vagas.example.test/\"}",
            "{\"inputType\":\"URL\",\"url\":\"https://vagas.example.test/\",\"text\":\"Vaga\"}",
            "{\"inputType\":\"TEXT\"}",
            "{\"inputType\":\"TEXT\",\"text\":\" \\n\\t \"}",
            "{\"inputType\":\"TEXT\",\"text\":\"Vaga\",\"url\":\"https://vagas.example.test/\"}",
            "{\"url\":\"https://vagas.example.test/\"}"
        }
    )
    void rejectsInvalidOrAmbiguousInput(String body) throws Exception {
        var session = account("importacao-invalida@example.com");

        create(session, body).andExpect(status().is(422));

        assertThat(tasks.count()).isZero();
    }

    @Test
    void rejectsInputAboveTheLimits() throws Exception {
        var session = account("importacao-limite@example.com");
        var prefix = "https://vagas.example.test/";
        var url = prefix + "a".repeat(2048 - prefix.length());
        var text = "a".repeat(30000);

        create(session, "{\"inputType\":\"URL\",\"url\":\"" + url + "\"}").andExpect(status().isAccepted());
        create(session, "{\"inputType\":\"TEXT\",\"text\":\"" + text + "\"}").andExpect(status().isAccepted());
        create(session, "{\"inputType\":\"URL\",\"url\":\"" + url + "a\"}")
            .andExpect(status().is(422))
            .andExpect(jsonPath("$.errors[0].field").value("url"));
        create(session, "{\"inputType\":\"TEXT\",\"text\":\"" + text + "a\"}")
            .andExpect(status().is(422))
            .andExpect(jsonPath("$.errors[0].field").value("text"));
        create(session, "{\"inputType\":\"PDF\",\"text\":\"Vaga\"}").andExpect(status().isBadRequest());

        assertThat(tasks.count()).isEqualTo(2);
    }

    @Test
    void requiresSessionAndCsrf() throws Exception {
        var session = account("importacao-seguranca@example.com");
        long id = id(create(session, "{\"inputType\":\"TEXT\",\"text\":\"Vaga\"}"));

        mvc.perform(post("/api/import-tasks").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/import-tasks/" + id)).andExpect(status().isUnauthorized());
        mvc.perform(
            post("/api/import-tasks")
                .session(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"inputType\":\"TEXT\",\"text\":\"Vaga\"}")
        ).andExpect(status().isForbidden());
        mvc.perform(delete("/api/import-tasks/" + id).session(session)).andExpect(status().isForbidden());

        assertThat(tasks.count()).isEqualTo(1);
    }

    @Test
    void hidesTasksFromOtherAccountsAndAfterExpiration() throws Exception {
        var owner = account("importacao-dono@example.com");
        var other = account("importacao-outro@example.com");
        long id = id(create(owner, "{\"inputType\":\"TEXT\",\"text\":\"Vaga\"}"));

        mvc.perform(get("/api/import-tasks/" + id).session(other))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.detail").value("Tarefa de importação não encontrada."));
        mvc.perform(delete("/api/import-tasks/" + id).session(other).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(get("/api/import-tasks/999999").session(owner)).andExpect(status().isNotFound());

        jdbc.update(
            "UPDATE tb_import_task SET created_at = ? WHERE id = ?",
            OffsetDateTime.now(ZoneOffset.UTC).minusHours(24).minusSeconds(1),
            id
        );

        mvc.perform(get("/api/import-tasks/" + id).session(owner)).andExpect(status().isNotFound());
        mvc.perform(delete("/api/import-tasks/" + id).session(owner).with(csrf())).andExpect(status().isNotFound());
        assertThat(tasks.existsById(id)).isTrue();
    }

    @Test
    void exposesTheProposalOrTheFailureOnlyInTheMatchingState() throws Exception {
        var session = account("importacao-resultado@example.com");
        long ready = id(create(session, "{\"inputType\":\"TEXT\",\"text\":\"Vaga Java\"}"));
        finish(new ImportTaskProcessor.Ready("Vaga Java", Map.of("description", "Vaga Java")));
        long needsInput = id(create(session, "{\"inputType\":\"URL\",\"url\":\"https://vagas.example.test/1\"}"));
        finish(new ImportTaskProcessor.NeedsInput(ImportTaskProcessor.Fallback.SOURCE_REQUIRES_LOGIN));
        long failed = id(create(session, "{\"inputType\":\"URL\",\"url\":\"https://vagas.example.test/2\"}"));
        finish(new ImportTaskProcessor.Failed(ImportTaskProcessor.Failure.SOURCE_NOT_ALLOWED));

        mvc.perform(get("/api/import-tasks/" + ready).session(session))
            .andExpect(jsonPath("$.status").value("READY_FOR_REVIEW"))
            .andExpect(jsonPath("$.result.description").value("Vaga Java"))
            .andExpect(jsonPath("$.failure").doesNotExist());
        mvc.perform(get("/api/import-tasks/" + needsInput).session(session))
            .andExpect(jsonPath("$.status").value("NEEDS_INPUT"))
            .andExpect(jsonPath("$.failure.code").value("SOURCE_REQUIRES_LOGIN"))
            .andExpect(jsonPath("$.failure.message").value("A página pede login. Cole a descrição para continuar."))
            .andExpect(jsonPath("$.result").doesNotExist());
        mvc.perform(get("/api/import-tasks/" + failed).session(session))
            .andExpect(jsonPath("$.status").value("FAILED"))
            .andExpect(jsonPath("$.failure.code").value("SOURCE_NOT_ALLOWED"));
    }

    @Test
    void cancelsOnceAndDiscardsTheResultOfARunningTask() throws Exception {
        var session = account("importacao-cancelar@example.com");
        long id = id(create(session, "{\"inputType\":\"TEXT\",\"text\":\"Vaga\"}"));
        var claim = queue.claim().orElseThrow();

        mvc.perform(delete("/api/import-tasks/" + id).session(session).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(delete("/api/import-tasks/" + id).session(session).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(get("/api/import-tasks/" + id).session(session)).andExpect(status().isNotFound());

        assertThat(queue.finish(claim, new ImportTaskProcessor.Ready(null, Map.of("title", "Vaga")))).isFalse();
        assertThat(tasks.count()).isZero();
    }

    @Test
    void refusesToCancelACompletedImportAndExposesTheJob() throws Exception {
        var session = account("importacao-concluida@example.com");
        long id = id(create(session, "{\"inputType\":\"TEXT\",\"text\":\"Vaga\"}"));
        var user = users.findAll().getFirst();
        var job = jobs.saveAndFlush(new Job(user, "Pessoa desenvolvedora Java"));
        jdbc.update(
            "UPDATE tb_import_task SET status = 'COMPLETED', job_id = ?, reviewed_at = ? WHERE id = ?",
            job.getId(),
            OffsetDateTime.now(ZoneOffset.UTC),
            id
        );

        mvc.perform(get("/api/import-tasks/" + id).session(session))
            .andExpect(jsonPath("$.status").value("COMPLETED"))
            .andExpect(jsonPath("$.jobId").value(job.getId()));
        mvc.perform(delete("/api/import-tasks/" + id).session(session).with(csrf()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("IMPORT_ALREADY_COMPLETED"));

        assertThat(tasks.findById(id)).map(ImportTask::getStatus).contains(ImportStatus.COMPLETED);
    }

    private void finish(ImportTaskProcessor.Outcome outcome) {
        assertThat(queue.finish(queue.claim().orElseThrow(), outcome)).isTrue();
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

    private static long id(ResultActions result) throws Exception {
        var content = result.andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
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

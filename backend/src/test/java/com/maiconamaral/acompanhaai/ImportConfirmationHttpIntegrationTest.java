package com.maiconamaral.acompanhaai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.jayway.jsonpath.JsonPath;
import com.maiconamaral.acompanhaai.dto.ApplicationInsertDTO;
import com.maiconamaral.acompanhaai.dto.JobInputDTO;
import com.maiconamaral.acompanhaai.entities.enums.ImportStatus;
import com.maiconamaral.acompanhaai.repositories.*;
import com.maiconamaral.acompanhaai.services.ImportTaskProcessor;
import com.maiconamaral.acompanhaai.services.ImportTaskQueue;
import com.maiconamaral.acompanhaai.services.ImportTaskService;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ImportConfirmationHttpIntegrationTest {

    private static final String REVIEWED = """
        {
          "job": {
            "title": "Pessoa desenvolvedora Java",
            "companyName": "Empresa Exemplo",
            "description": "Atuar com Spring e PostgreSQL.",
            "workMode": "REMOTE",
            "salary": 8500.00,
            "salaryCurrency": "BRL",
            "salaryPeriod": "MONTH",
            "hardSkills": ["Java", "Spring"],
            "softSkills": ["Comunicação"]
          },
          "status": "SAVED"
        }
        """;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private ImportTaskRepository tasks;

    @Autowired
    private ApplicationRepository applications;

    @Autowired
    private ApplicationStatusHistoryRepository histories;

    @Autowired
    private JobRepository jobs;

    @Autowired
    private ImportTaskQueue queue;

    @Autowired
    private ImportTaskService service;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        users.deleteAll();
    }

    @Test
    void confirmsTheReviewedProposalAndCompletesTheTaskInOneStep() throws Exception {
        var session = account("confirmar@example.com");
        long id = readyTask(session, "{\"inputType\":\"TEXT\",\"text\":\"Vaga Java\"}");

        var result = confirm(session, id, REVIEWED)
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.job.title").value("Pessoa desenvolvedora Java"))
            .andExpect(jsonPath("$.job.companyName").value("Empresa Exemplo"))
            .andExpect(jsonPath("$.job.salary").value(8500.00))
            .andExpect(jsonPath("$.job.hardSkills.length()").value(2))
            .andExpect(jsonPath("$.status").value("SAVED"))
            .andReturn();
        long applicationId = applicationId(result.getResponse().getContentAsString());
        long jobId = ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.job.id")).longValue();
        assertThat(result.getResponse().getHeader("Location")).endsWith("/api/applications/" + applicationId);

        mvc.perform(get("/api/import-tasks/" + id).session(session))
            .andExpect(jsonPath("$.status").value("COMPLETED"))
            .andExpect(jsonPath("$.jobId").value(jobId))
            .andExpect(jsonPath("$.result").doesNotExist());
        var task = tasks.findById(id).orElseThrow();
        assertThat(task.getReviewedAt()).isNotNull();
        assertThat(task.getCollectedText()).isNull();
        assertThat(task.getExtractedData()).isNull();
        assertThat(applications.count()).isEqualTo(1);
        assertThat(histories.count()).isEqualTo(1);
    }

    @Test
    void repeatedConfirmationReturnsTheSameApplication() throws Exception {
        var session = account("confirmar-repetido@example.com");
        long id = readyTask(session, "{\"inputType\":\"TEXT\",\"text\":\"Vaga Java\"}");
        long first = applicationId(confirm(session, id, REVIEWED).andExpect(status().isCreated()));

        var changed = REVIEWED.replace("Empresa Exemplo", "Outra Empresa");
        confirm(session, id, changed)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(first))
            .andExpect(jsonPath("$.job.companyName").value("Empresa Exemplo"));

        assertThat(applications.count()).isEqualTo(1);
        assertThat(jobs.count()).isEqualTo(1);
    }

    @Test
    void concurrentConfirmationsCreateASingleApplication() throws Exception {
        var session = account("confirmar-concorrente@example.com");
        long id = readyTask(session, "{\"inputType\":\"TEXT\",\"text\":\"Vaga Java\"}");
        long userId = users.findAll().getFirst().getId();
        var dto = new ApplicationInsertDTO(
            new JobInputDTO("Analista", null, null, null, null, null, null, null, null, List.of(), List.of()),
            null,
            null,
            null
        );
        var barrier = new CyclicBarrier(2);
        Callable<ImportTaskService.Confirmation> contender = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            return service.confirm(userId, id, dto);
        };

        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = workers.submit(contender);
            var second = workers.submit(contender);
            var results = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));

            assertThat(results)
                .extracting(ImportTaskService.Confirmation::created)
                .containsExactlyInAnyOrder(true, false);
            assertThat(results.get(0).application().id()).isEqualTo(results.get(1).application().id());
        }
        assertThat(applications.count()).isEqualTo(1);
    }

    @Test
    void usesTheImportedLinkWhenTheReviewOmitsIt() throws Exception {
        var session = account("confirmar-link@example.com");
        long id = readyTask(session, "{\"inputType\":\"URL\",\"url\":\"https://vagas.example.test/java\"}");

        confirm(session, id, REVIEWED)
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.job.sourceUrl").value("https://vagas.example.test/java"));

        long other = readyTask(session, "{\"inputType\":\"URL\",\"url\":\"https://vagas.example.test/python\"}");
        var corrected = REVIEWED.replace(
            "\"title\"",
            "\"sourceUrl\": \"https://vagas.example.test/corrigida\", \"title\""
        );
        confirm(session, other, corrected)
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.job.sourceUrl").value("https://vagas.example.test/corrigida"));
    }

    @Test
    void keepsTheTaskForReviewWhenTheConfirmationFails() throws Exception {
        var session = account("confirmar-falha@example.com");
        long id = readyTask(session, "{\"inputType\":\"URL\",\"url\":\"https://vagas.example.test/java\"}");
        mvc.perform(
            post("/api/applications")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"job\":{\"title\":\"Manual\",\"sourceUrl\":\"https://vagas.example.test/java\"}}")
        ).andExpect(status().isCreated());

        confirm(session, id, REVIEWED)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("JOB_URL_ALREADY_EXISTS"));
        confirm(session, id, "{\"job\":{\"title\":\" \"}}").andExpect(status().is(422));
        confirm(session, id, REVIEWED.replace("\"BRL\"", "\"USD\"")).andExpect(status().is(422));

        assertThat(tasks.findById(id).orElseThrow().getStatus()).isEqualTo(ImportStatus.READY_FOR_REVIEW);
        assertThat(tasks.findById(id).orElseThrow().getExtractedData()).isNotNull();
        assertThat(applications.count()).isEqualTo(1);
        mvc.perform(get("/api/import-tasks/" + id).session(session))
            .andExpect(jsonPath("$.result.description").value("Vaga Java"));
    }

    @Test
    void refusesTasksWithoutAProposal() throws Exception {
        var session = account("confirmar-pendente@example.com");
        long pending = id(create(session, "{\"inputType\":\"TEXT\",\"text\":\"Vaga\"}"));

        confirm(session, pending, REVIEWED)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("IMPORT_NOT_READY"));

        queue.finish(
            queue.claim().orElseThrow(),
            new ImportTaskProcessor.Failed(ImportTaskProcessor.Failure.PROCESSING_FAILED)
        );
        confirm(session, pending, REVIEWED).andExpect(status().isConflict());
        assertThat(applications.count()).isZero();
    }

    @Test
    void protectsOwnershipExpirationAndCsrf() throws Exception {
        var owner = account("confirmar-dono@example.com");
        var other = account("confirmar-outro@example.com");
        long id = readyTask(owner, "{\"inputType\":\"TEXT\",\"text\":\"Vaga Java\"}");

        confirm(other, id, REVIEWED).andExpect(status().isNotFound());
        mvc.perform(
            post("/api/import-tasks/" + id + "/confirm")
                .session(owner)
                .contentType(MediaType.APPLICATION_JSON)
                .content(REVIEWED)
        ).andExpect(status().isForbidden());

        jdbc.update(
            "UPDATE tb_import_task SET created_at = ? WHERE id = ?",
            OffsetDateTime.now(ZoneOffset.UTC).minusHours(25),
            id
        );
        confirm(owner, id, REVIEWED).andExpect(status().isNotFound());
        assertThat(applications.count()).isZero();
    }

    private long readyTask(MockHttpSession session, String body) throws Exception {
        long id = id(create(session, body));
        var claim = queue.claim().orElseThrow();
        assertThat(claim.id()).isEqualTo(id);
        queue.finish(claim, new ImportTaskProcessor.Ready("Vaga Java", Map.of("description", "Vaga Java")));
        return id;
    }

    private ResultActions confirm(MockHttpSession session, long id, String body) throws Exception {
        return mvc.perform(
            post("/api/import-tasks/" + id + "/confirm")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
        );
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

    private static long applicationId(ResultActions result) throws Exception {
        return applicationId(result.andReturn().getResponse().getContentAsString());
    }

    private static long applicationId(String content) {
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

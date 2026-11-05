package com.maiconamaral.acompanhaai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.jayway.jsonpath.JsonPath;
import com.maiconamaral.acompanhaai.entities.Job;
import com.maiconamaral.acompanhaai.repositories.JobRepository;
import com.maiconamaral.acompanhaai.repositories.UserRepository;
import com.maiconamaral.acompanhaai.services.JobUrlService;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApplicationConsistencyIntegrationTest {

    private static final String URL = "https://jobs.example.test/jobs/7?jobId=A%2FB&ref=one#Job-1";
    private static final String EQUIVALENT = "HTTPS://JOBS.EXAMPLE.TEST:443/jobs/7?jobId=A%2FB&ref=one#Job-1";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoSpyBean
    private JobUrlService urls;

    @MockitoSpyBean
    private JobRepository jobs;

    @BeforeEach
    void cleanDatabase() {
        users.deleteAll();
    }

    @Test
    void concurrentEquivalentLinksInSameAccountProduceOneApplicationAndOneConflict() throws Exception {
        var first = account("race-owner@example.test");
        var second = login("race-owner@example.test");
        assertThat(concurrentCreate(first, URL, second, EQUIVALENT)).containsExactlyInAnyOrder(201, 409);
        assertThat(count("tb_application")).isEqualTo(1);
        assertThat(count("tb_job")).isEqualTo(1);
        assertThat(count("tb_application_status_history")).isEqualTo(1);
        assertThat(count("tb_job_skill")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT normalized_url FROM tb_job", String.class)).isEqualTo(URL);
    }

    @Test
    void concurrentSameLinkInDifferentAccountsIsAllowed() throws Exception {
        var first = account("race-first@example.test");
        var second = account("race-second@example.test");
        assertThat(concurrentCreate(first, URL, second, EQUIVALENT)).containsExactlyInAnyOrder(201, 201);
        assertThat(count("tb_application")).isEqualTo(2);
        assertThat(count("tb_job")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT user_id) FROM tb_job", Long.class)).isEqualTo(2);
    }

    @Test
    void duplicateAndForcedHashCollisionHaveDistinctCodesAndNeverMergeJobs() throws Exception {
        var session = account("collision@example.test");
        doReturn("a".repeat(64)).when(urls).hash(anyString());
        long id = id(create(session, URL).andExpect(status().isCreated()));
        create(session, EQUIVALENT)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("JOB_URL_ALREADY_EXISTS"));
        create(session, "https://other.example.test/jobs/8")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("JOB_URL_HASH_COLLISION"));
        assertThat(count("tb_job")).isEqualTo(1);
        assertThat(count("tb_application_status_history")).isEqualTo(1);
        mvc.perform(get("/api/applications/" + id).session(session))
            .andExpect(jsonPath("$.job.sourceUrl").value(URL))
            .andExpect(jsonPath("$.job.hardSkills[0]").value("Java"))
            .andExpect(jsonPath("$.version").value(0));
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void detectsEquivalentLegacyLinkWithOldHashOrMissingMetadataWithoutRewritingIt(boolean missing) throws Exception {
        var session = account("legacy-url@example.test");
        long application = id(create(session, EQUIVALENT).andExpect(status().isCreated()));
        long job = jobId(application);
        String oldHash = missing ? null : urls.hash(EQUIVALENT);
        jdbc.update(
            "UPDATE tb_job SET normalized_url = ?, url_hash = ? WHERE id = ?",
            missing ? null : EQUIVALENT,
            oldHash,
            job
        );
        create(session, URL)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("JOB_URL_ALREADY_EXISTS"));
        assertThat(jdbc.queryForObject("SELECT source_url FROM tb_job WHERE id = ?", String.class, job)).isEqualTo(
            EQUIVALENT
        );
        assertThat(jdbc.queryForObject("SELECT url_hash FROM tb_job WHERE id = ?", String.class, job)).isEqualTo(
            oldHash
        );
        assertThat(count("tb_job")).isEqualTo(1);
    }

    @Test
    void malformedLegacyLinkDoesNotBlockUnrelatedValidLink() throws Exception {
        var session = account("malformed-legacy@example.test");
        long application = id(create(session, URL).andExpect(status().isCreated()));
        jdbc.update(
            "UPDATE tb_job SET source_url = ?, normalized_url = NULL, url_hash = NULL WHERE id = ?",
            "https://legacy.example.test:abc/jobs/7",
            jobId(application)
        );
        create(session, "https://example.test/jobs/9").andExpect(status().isCreated());
        assertThat(count("tb_job")).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(
        strings = { "https:///jobs", "https://user:fake-password@example.test/jobs", "https://example.test/%ZZ" }
    )
    void invalidLinkReturns422WithoutPartialRegistration(String url) throws Exception {
        var session = account("invalid-url@example.test");
        create(session, url).andExpect(status().is(422));
        assertThat(count("tb_job")).isZero();
        assertThat(count("tb_application")).isZero();
        assertThat(count("tb_job_skill")).isZero();
    }

    @Test
    void editingOwnEquivalentLinkIsAllowedButAnotherJobsLinkDoesNotChangeData() throws Exception {
        var session = account("edit-url@example.test");
        long first = id(create(session, URL).andExpect(status().isCreated()));
        String other = "https://jobs.example.test/jobs/other";
        create(session, other).andExpect(status().isCreated());
        update(session, first, EQUIVALENT, 0)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.job.sourceUrl").value(EQUIVALENT))
            .andExpect(jsonPath("$.version").value(1));
        update(session, first, other, 1)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("JOB_URL_ALREADY_EXISTS"));
        mvc.perform(get("/api/applications/" + first).session(session))
            .andExpect(jsonPath("$.job.sourceUrl").value(EQUIVALENT))
            .andExpect(jsonPath("$.version").value(1))
            .andExpect(jsonPath("$.status").value("SCREENING"));
        assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM tb_application_status_history WHERE application_id = ?",
                Long.class,
                first
            )
        ).isEqualTo(2);
    }

    @Test
    void absentLinksAreNotDeduplicatedAndDeletionReleasesUrlForNewRegistration() throws Exception {
        var session = account("optional-url@example.test");
        create(session, null).andExpect(status().isCreated());
        create(session, null).andExpect(status().isCreated());
        long first = id(create(session, URL).andExpect(status().isCreated()));
        mvc.perform(
            delete("/api/applications/" + first)
                .session(session)
                .with(csrf())
        ).andExpect(status().isNoContent());
        create(session, EQUIVALENT).andExpect(status().isCreated());
        assertThat(count("tb_job")).isEqualTo(3);
    }

    @Test
    void deletesOnlyLinkedDataAndAccountDeletionPreservesOtherAccount() throws Exception {
        var owner = account("delete-owner@example.test");
        var other = account("delete-other@example.test");
        long deleted = id(create(owner, URL).andExpect(status().isCreated()));
        long preserved = id(create(owner, "https://example.test/keep").andExpect(status().isCreated()));
        long otherApplication = id(create(other, URL).andExpect(status().isCreated()));
        long ownerId = users.findByEmail("delete-owner@example.test").orElseThrow().getId();
        long otherId = users.findByEmail("delete-other@example.test").orElseThrow().getId();
        task(ownerId, jobId(deleted));
        task(ownerId, jobId(deleted));
        task(ownerId, null);
        task(ownerId, jobId(preserved));
        task(otherId, jobId(otherApplication));
        mvc.perform(
            delete("/api/applications/" + deleted)
                .session(other)
                .with(csrf())
        ).andExpect(status().isNotFound());
        assertThat(count("tb_import_task")).isEqualTo(5);
        mvc.perform(
            delete("/api/applications/" + deleted)
                .session(owner)
                .with(csrf())
        ).andExpect(status().isNoContent());
        assertThat(count("tb_job")).isEqualTo(2);
        assertThat(count("tb_application")).isEqualTo(2);
        assertThat(count("tb_job_skill")).isEqualTo(4);
        assertThat(count("tb_application_status_history")).isEqualTo(2);
        assertThat(count("tb_import_task")).isEqualTo(3);
        mvc.perform(
            delete("/api/applications/" + deleted)
                .session(owner)
                .with(csrf())
        ).andExpect(status().isNotFound());
        mvc.perform(delete("/api/users/me").session(owner).with(csrf())).andExpect(status().isNoContent());
        assertThat(users.count()).isEqualTo(1);
        assertThat(count("tb_job")).isEqualTo(1);
        assertThat(count("tb_application")).isEqualTo(1);
        assertThat(count("tb_job_skill")).isEqualTo(2);
        assertThat(count("tb_application_status_history")).isEqualTo(1);
        assertThat(count("tb_import_task")).isEqualTo(1);
        mvc.perform(get("/api/applications/" + otherApplication).session(other)).andExpect(status().isOk());
    }

    @Test
    void deleteFailureRestoresApplicationHistorySkillsAndLinkedTasks() throws Exception {
        var session = account("delete-rollback@example.test");
        long application = id(create(session, URL).andExpect(status().isCreated()));
        long owner = users.findByEmail("delete-rollback@example.test").orElseThrow().getId();
        task(owner, jobId(application));
        doThrow(new DataIntegrityViolationException("Falha simulada na remoção da vaga"))
            .when(jobs)
            .delete(any(Job.class));
        mvc.perform(
            delete("/api/applications/" + application)
                .session(session)
                .with(csrf())
        ).andExpect(status().isConflict());
        assertThat(count("tb_application")).isEqualTo(1);
        assertThat(count("tb_job")).isEqualTo(1);
        assertThat(count("tb_application_status_history")).isEqualTo(1);
        assertThat(count("tb_job_skill")).isEqualTo(2);
        assertThat(count("tb_import_task")).isEqualTo(1);
        mvc.perform(get("/api/applications/" + application).session(session)).andExpect(status().isOk());
    }

    private List<Integer> concurrentCreate(
        MockHttpSession first,
        String firstUrl,
        MockHttpSession second,
        String secondUrl
    ) throws Exception {
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var one = executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return create(first, firstUrl).andReturn().getResponse().getStatus();
            });
            var two = executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return create(second, secondUrl).andReturn().getResponse().getStatus();
            });
            return List.of(one.get(20, TimeUnit.SECONDS), two.get(20, TimeUnit.SECONDS));
        }
    }

    private void task(long owner, Long job) {
        jdbc.update(
            """
            INSERT INTO tb_import_task(user_id, job_id, input_type, input_text, status, attempt_count, created_at, updated_at)
            VALUES (?, ?, 'TEXT', 'Descrição fictícia', 'PENDING', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            owner,
            job
        );
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private long jobId(long application) {
        return jdbc.queryForObject("SELECT job_id FROM tb_application WHERE id = ?", Long.class, application);
    }

    private long id(ResultActions result) throws Exception {
        return ((Number) JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id")).longValue();
    }

    private MockHttpSession account(String email) throws Exception {
        mvc.perform(
            post("/api/users")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"name":"Teste KAN32","email":"%s","password":"SenhaTeste123!"}
                    """.formatted(email)
                )
        ).andExpect(status().isCreated());
        return login(email);
    }

    private MockHttpSession login(String email) throws Exception {
        var result = mvc
            .perform(
                post("/api/auth/login")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"email":"%s","password":"SenhaTeste123!"}
                        """.formatted(email)
                    )
            )
            .andExpect(status().isOk())
            .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private ResultActions create(MockHttpSession session, String url) throws Exception {
        return mvc.perform(
            post("/api/applications")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"job\":" + job(url) + "}")
        );
    }

    private ResultActions update(MockHttpSession session, long id, String url, long version) throws Exception {
        return mvc.perform(
            put("/api/applications/" + id)
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"job\":" + job(url) + ",\"status\":\"SCREENING\",\"version\":" + version + "}")
        );
    }

    private String job(String url) {
        String source = url == null ? "null" : "\"" + url + "\"";
        return (
            "{\"title\":\"Java\",\"sourceUrl\":" +
            source +
            ",\"hardSkills\":[\"Java\"],\"softSkills\":[\"Comunicação\"]}"
        );
    }
}

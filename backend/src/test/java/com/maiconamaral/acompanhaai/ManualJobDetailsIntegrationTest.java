package com.maiconamaral.acompanhaai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.jayway.jsonpath.JsonPath;
import com.maiconamaral.acompanhaai.repositories.UserRepository;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
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
class ManualJobDetailsIntegrationTest {

    private static final String DETAILS = """
    {"title":"Java", "location":" Porto Alegre, RS ", "workMode":"HYBRID",
     "salary":5000.25,"salaryCurrency":"BRL","salaryPeriod":"MONTH",
     "hardSkills":[" Java ","Spring   Boot"],"softSkills":["Comunicação"]}
    """;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        users.deleteAll();
    }

    @Test
    void persistsDetailsAndReturnsThemOnCreateGetAndList() throws Exception {
        var session = account("details@example.test");
        var created = create(session, DETAILS)
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.job.salary").value(5000.25))
            .andExpect(jsonPath("$.job.salaryCurrency").value("BRL"))
            .andExpect(jsonPath("$.job.salaryPeriod").value("MONTH"))
            .andExpect(jsonPath("$.job.hardSkills[1]").value("Spring Boot"));
        long id = id(created);
        mvc.perform(get("/api/applications/" + id).session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.job.location").value("Porto Alegre, RS"))
            .andExpect(jsonPath("$.job.workMode").value("HYBRID"))
            .andExpect(jsonPath("$.job.salary").value(5000.25))
            .andExpect(jsonPath("$.job.hardSkills[0]").value("Java"))
            .andExpect(jsonPath("$.job.softSkills[0]").value("Comunicação"));
        mvc.perform(get("/api/applications").session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0].job.salary").value(5000.25))
            .andExpect(jsonPath("$.content[0].job.hardSkills.length()").value(2));
        assertThat(jdbc.queryForObject("SELECT salary FROM tb_job", java.math.BigDecimal.class)).isEqualByComparingTo(
            "5000.25"
        );
    }

    @Test
    void acceptsOldPayloadAndLeavesUnknownSalaryNull() throws Exception {
        var session = account("optional@example.test");
        create(session, "{\"title\":\"Java\",\"description\":\"Salário a combinar\"}")
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.job.salary").isEmpty())
            .andExpect(jsonPath("$.job.salaryCurrency").isEmpty())
            .andExpect(jsonPath("$.job.salaryPeriod").isEmpty())
            .andExpect(jsonPath("$.job.hardSkills").isEmpty())
            .andExpect(jsonPath("$.job.softSkills").isEmpty());
    }

    @Test
    void replacesSkillsReusesUnchangedNamesAndCanClearAllDetails() throws Exception {
        var session = account("replace@example.test");
        long id = id(create(session, DETAILS).andExpect(status().isCreated()));
        String replacement = """
        {"title":"Java", "salary":75.10,"salaryCurrency":"BRL","salaryPeriod":"HOUR",
         "hardSkills":["java","SQL"],"softSkills":["Organização"]}
        """;
        update(session, id, 0, replacement)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.job.salary").value(75.10))
            .andExpect(jsonPath("$.job.salaryPeriod").value("HOUR"))
            .andExpect(jsonPath("$.job.hardSkills[0]").value("java"))
            .andExpect(jsonPath("$.job.hardSkills[1]").value("SQL"))
            .andExpect(jsonPath("$.job.softSkills[0]").value("Organização"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tb_job_skill", Long.class)).isEqualTo(3);
        update(session, id, 1, "{\"title\":\"Java\",\"salary\":null,\"hardSkills\":[],\"softSkills\":[]}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.job.salary").isEmpty())
            .andExpect(jsonPath("$.job.salaryCurrency").isEmpty())
            .andExpect(jsonPath("$.job.location").isEmpty())
            .andExpect(jsonPath("$.job.workMode").isEmpty())
            .andExpect(jsonPath("$.job.hardSkills").isEmpty());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tb_job_skill", Long.class)).isZero();
        mvc.perform(get("/api/applications/" + id + "/history").session(session)).andExpect(
            jsonPath("$.length()").value(1)
        );
    }

    @ParameterizedTest
    @MethodSource("invalidDetails")
    void rejectsInvalidDetailsWithoutCreatingPartialData(String fields) throws Exception {
        var session = account("invalid@example.test");
        create(session, "{\"title\":\"Java\"," + fields + "}").andExpect(status().is(422));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tb_job", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tb_job_skill", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tb_application_status_history", Long.class)).isZero();
    }

    static Stream<String> invalidDetails() {
        String money = ",\"salaryCurrency\":\"BRL\",\"salaryPeriod\":\"MONTH\"";
        String tooMany = Stream.iterate(0, n -> n + 1)
            .limit(51)
            .map(n -> "\"Skill " + n + "\"")
            .collect(java.util.stream.Collectors.joining(","));
        return Stream.of(
            "\"salary\":-1" + money,
            "\"salary\":0" + money,
            "\"salary\":10.001" + money,
            "\"salary\":10000000000000" + money,
            "\"salary\":5000",
            "\"salary\":5000,\"salaryCurrency\":\"BRL\"",
            "\"salary\":5000,\"salaryCurrency\":\"USD\",\"salaryPeriod\":\"MONTH\"",
            "\"salaryCurrency\":\"BRL\"",
            "\"salaryPeriod\":\"MONTH\"",
            "\"hardSkills\":[\" Java \",\"JAVA\"]",
            "\"hardSkills\":[\"Spring Boot\"],\"softSkills\":[\"spring   boot\"]",
            "\"softSkills\":[\"Comunicação\",\"comunicação\"]",
            "\"hardSkills\":[null]",
            "\"softSkills\":[\"   \"]",
            "\"hardSkills\":[\"" + "x".repeat(151) + "\"]",
            "\"hardSkills\":[\"" + "İ".repeat(150) + "\"]",
            "\"hardSkills\":[" + tooMany + "]",
            "\"location\":\"" + "x".repeat(256) + "\""
        );
    }

    @Test
    void invalidEditAndStaleVersionPreserveSalarySkillsAndHistory() throws Exception {
        var session = account("rollback@example.test");
        long id = id(create(session, DETAILS).andExpect(status().isCreated()));
        update(session, id, 0, "{\"title\":\"Alterada\",\"hardSkills\":[\"Java\",\"JAVA\"]}").andExpect(
            status().is(422)
        );
        update(session, id, 7, "{\"title\":\"Alterada\"}").andExpect(status().isConflict());
        mvc.perform(get("/api/applications/" + id).session(session))
            .andExpect(jsonPath("$.job.title").value("Java"))
            .andExpect(jsonPath("$.job.salary").value(5000.25))
            .andExpect(jsonPath("$.job.hardSkills.length()").value(2))
            .andExpect(jsonPath("$.version").value(0));
        mvc.perform(get("/api/applications/" + id + "/history").session(session)).andExpect(
            jsonPath("$.length()").value(1)
        );
    }

    @Test
    void conflictingUrlEditPreservesJobSkillsAndStatusHistory() throws Exception {
        var session = account("transaction@example.test");
        String first = DETAILS.replace(
            "\"title\":\"Java\"",
            "\"title\":\"Java\",\"sourceUrl\":\"https://example.test/1\""
        );
        create(session, first).andExpect(status().isCreated());
        String second = DETAILS.replace(
            "\"title\":\"Java\"",
            "\"title\":\"Java\",\"sourceUrl\":\"https://example.test/2\""
        );
        long id = id(create(session, second).andExpect(status().isCreated()));
        String conflicting = """
        {"job":{"title":"Alterada","sourceUrl":"https://example.test/1",
         "hardSkills":["SQL"],"salary":10,"salaryCurrency":"BRL","salaryPeriod":"HOUR"},
         "status":"INTERVIEW","version":0}
        """;
        mvc.perform(
            put("/api/applications/" + id)
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(conflicting)
        ).andExpect(status().isConflict());
        mvc.perform(get("/api/applications/" + id).session(session))
            .andExpect(jsonPath("$.job.title").value("Java"))
            .andExpect(jsonPath("$.job.sourceUrl").value("https://example.test/2"))
            .andExpect(jsonPath("$.job.salary").value(5000.25))
            .andExpect(jsonPath("$.job.hardSkills[0]").value("Java"))
            .andExpect(jsonPath("$.job.softSkills[0]").value("Comunicação"))
            .andExpect(jsonPath("$.status").value("APPLIED"))
            .andExpect(jsonPath("$.version").value(0));
        mvc.perform(get("/api/applications/" + id + "/history").session(session)).andExpect(
            jsonPath("$.length()").value(1)
        );
    }

    @Test
    void otherAccountCannotReadOrChangeDetailsAndDeletionRemovesSkills() throws Exception {
        var owner = account("owner-details@example.test");
        var other = account("other-details@example.test");
        long id = id(create(owner, DETAILS).andExpect(status().isCreated()));
        mvc.perform(get("/api/applications/" + id).session(other)).andExpect(status().isNotFound());
        update(other, id, 0, DETAILS).andExpect(status().isNotFound());
        mvc.perform(get("/api/applications").session(other)).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(
            delete("/api/applications/" + id)
                .session(owner)
                .with(csrf())
        ).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tb_job_skill", Long.class)).isZero();
    }

    @Test
    void movingSkillBetweenTypesDoesNotLeaveOldEntry() throws Exception {
        var session = account("move@example.test");
        long id = id(create(session, DETAILS).andExpect(status().isCreated()));
        update(session, id, 0, "{\"title\":\"Java\",\"softSkills\":[\"Java\"]}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.job.hardSkills").isEmpty())
            .andExpect(jsonPath("$.job.softSkills[0]").value("Java"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tb_job_skill", Long.class)).isEqualTo(1);
    }

    private MockHttpSession account(String email) throws Exception {
        mvc.perform(
            post("/api/users")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"name":"Teste KAN30","email":"%s","password":"SenhaTeste123!"}
                    """.formatted(email)
                )
        ).andExpect(status().isCreated());
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

    private ResultActions create(MockHttpSession session, String job) throws Exception {
        return mvc.perform(
            post("/api/applications")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"job\":" + job + "}")
        );
    }

    private ResultActions update(MockHttpSession session, long id, long version, String job) throws Exception {
        return mvc.perform(
            put("/api/applications/" + id)
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"job\":" + job + ",\"status\":\"APPLIED\",\"version\":" + version + "}")
        );
    }

    private long id(ResultActions result) throws Exception {
        return ((Number) JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id")).longValue();
    }
}

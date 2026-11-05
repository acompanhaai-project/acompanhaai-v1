package com.maiconamaral.acompanhaai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.jayway.jsonpath.JsonPath;
import com.maiconamaral.acompanhaai.entities.ApplicationStatusHistory;
import com.maiconamaral.acompanhaai.entities.enums.ApplicationStatus;
import com.maiconamaral.acompanhaai.repositories.ApplicationStatusHistoryRepository;
import com.maiconamaral.acompanhaai.repositories.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
class ApplicationTrackingIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoSpyBean
    private ApplicationStatusHistoryRepository history;

    @BeforeEach
    void cleanDatabase() {
        users.deleteAll();
    }

    @ParameterizedTest
    @EnumSource(ApplicationStatus.class)
    void createsFiltersAndRecordsEverySupportedStatus(ApplicationStatus state) throws Exception {
        var session = account("status@example.test");
        long id = create(session, "Java", "Empresa", state);
        mvc.perform(get("/api/applications").session(session).param("status", state.name()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.content[0].id").value(id))
            .andExpect(jsonPath("$.content[0].status").value(state.name()));
        mvc.perform(get("/api/applications/" + id + "/history").session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].fromStatus").isEmpty())
            .andExpect(jsonPath("$[0].toStatus").value(state.name()))
            .andExpect(jsonPath("$[0].changedAt").isNotEmpty());
    }

    @ParameterizedTest
    @EnumSource(ApplicationStatus.class)
    void supportsEveryDestinationAndDoesNotRecordUnchangedStatus(ApplicationStatus state) throws Exception {
        var session = account("transition@example.test");
        long id = create(session, "Java", "Empresa", ApplicationStatus.APPLIED);
        update(session, id, state, 0, "Nova observação")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value(state.name()))
            .andExpect(jsonPath("$.notes").value("Nova observação"))
            .andExpect(jsonPath("$.version").value(1));
        int expected = state == ApplicationStatus.APPLIED ? 1 : 2;
        mvc.perform(get("/api/applications/" + id + "/history").session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(expected));
    }

    @Test
    void combinesTitleOrCompanySearchWithStatusWithoutLeakingOtherAccounts() throws Exception {
        var owner = account("search-owner@example.test");
        var other = account("search-other@example.test");
        long titleMatch = create(owner, "Java Developer", "Acme", ApplicationStatus.SCREENING);
        long companyMatch = create(owner, "Analista", "Java Labs", ApplicationStatus.SCREENING);
        create(owner, "Java Legacy", "Outra", ApplicationStatus.OFFER);
        create(owner, "Tester", "Sem correspondência", ApplicationStatus.SCREENING);
        long privateId = create(other, "Java privado", "Java Labs", ApplicationStatus.SCREENING);
        mvc.perform(get("/api/applications").session(owner).param("q", " jAvA ").param("status", "SCREENING"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(2))
            .andExpect(jsonPath("$.content[0].id").value(companyMatch))
            .andExpect(jsonPath("$.content[1].id").value(titleMatch));
        mvc.perform(get("/api/applications").session(owner).param("q", "JAVA")).andExpect(
            jsonPath("$.totalElements").value(3)
        );
        mvc.perform(get("/api/applications").session(owner).param("q", "acme")).andExpect(
            jsonPath("$.totalElements").value(1)
        );
        mvc.perform(get("/api/applications").session(owner).param("q", "inexistente"))
            .andExpect(jsonPath("$.content").isEmpty())
            .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/applications/" + privateId).session(owner)).andExpect(status().isNotFound());
        mvc.perform(get("/api/applications/" + privateId + "/history").session(owner)).andExpect(status().isNotFound());
        update(owner, privateId, ApplicationStatus.OFFER, 0, "Não autorizado").andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(strings = { "%", "_", "!" })
    void searchesWildcardAndEscapeCharactersAsLiteralText(String literal) throws Exception {
        var session = account("literal@example.test");
        long exact = create(session, "Vaga " + literal + " Java", "Empresa", ApplicationStatus.APPLIED);
        create(session, "Vaga X Java", "Empresa", ApplicationStatus.APPLIED);
        mvc.perform(get("/api/applications").session(session).param("q", literal))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.content[0].id").value(exact));
    }

    @Test
    void acceptsBlankAndMaximumQueryAndRejectsOversizedInput() throws Exception {
        var session = account("search-limits@example.test");
        create(session, "x".repeat(100), "Empresa", ApplicationStatus.APPLIED);
        mvc.perform(get("/api/applications").session(session).param("q", "   "))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/applications").session(session).param("q", ""))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/applications").session(session).param("q", "x".repeat(100)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/applications").session(session).param("q", "x".repeat(101))).andExpect(status().is(422));
        mvc.perform(get("/api/applications").session(session).param("q", "' OR 1=1 --"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void paginationUsesIdAsTieBreakerAndSearchOnlyInspectsTitleAndCompany() throws Exception {
        var session = account("pages@example.test");
        long first = create(session, "Java 1", "Empresa", ApplicationStatus.APPLIED);
        long second = create(session, "Java 2", "Empresa", ApplicationStatus.APPLIED);
        long third = create(session, "Java 3", "Empresa", ApplicationStatus.APPLIED);
        jdbc.update("UPDATE tb_application SET updated_at = ?", java.sql.Timestamp.valueOf("2026-01-01 00:00:00"));
        mvc.perform(get("/api/applications").session(session).param("q", "java").param("size", "2"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(3))
            .andExpect(jsonPath("$.totalPages").value(2))
            .andExpect(jsonPath("$.content[0].id").value(third))
            .andExpect(jsonPath("$.content[1].id").value(second));
        mvc.perform(get("/api/applications").session(session).param("q", "java").param("size", "2").param("page", "1"))
            .andExpect(jsonPath("$.page").value(1))
            .andExpect(jsonPath("$.content.length()").value(1))
            .andExpect(jsonPath("$.content[0].id").value(first));
        mvc.perform(get("/api/applications").session(session).param("q", "java").param("size", "2").param("page", "2"))
            .andExpect(jsonPath("$.content").isEmpty())
            .andExpect(jsonPath("$.totalElements").value(3));
        mvc.perform(get("/api/applications").session(session).param("q", "somente-descricao")).andExpect(
            jsonPath("$.totalElements").value(0)
        );
    }

    @Test
    void allowsSkippingAndCorrectingStagesAndKeepsHistoryStableOnTimestampTies() throws Exception {
        var session = account("history-order@example.test");
        long id = create(session, "Java", "Empresa", ApplicationStatus.APPLIED);
        update(session, id, ApplicationStatus.HIRED, 0, "Contratado").andExpect(status().isOk());
        update(session, id, ApplicationStatus.SCREENING, 1, "Correção").andExpect(status().isOk());
        update(session, id, ApplicationStatus.WITHDRAWN, 2, "Desistência").andExpect(status().isOk());
        update(session, id, ApplicationStatus.WITHDRAWN, 3, "Somente observação").andExpect(status().isOk());
        jdbc.update(
            "UPDATE tb_application_status_history SET changed_at = ? WHERE application_id = ?",
            java.sql.Timestamp.valueOf("2026-01-01 00:00:00"),
            id
        );
        mvc.perform(get("/api/applications/" + id + "/history").session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(4))
            .andExpect(jsonPath("$[0].toStatus").value("APPLIED"))
            .andExpect(jsonPath("$[1].fromStatus").value("APPLIED"))
            .andExpect(jsonPath("$[1].toStatus").value("HIRED"))
            .andExpect(jsonPath("$[2].fromStatus").value("HIRED"))
            .andExpect(jsonPath("$[2].toStatus").value("SCREENING"))
            .andExpect(jsonPath("$[3].fromStatus").value("SCREENING"))
            .andExpect(jsonPath("$[3].toStatus").value("WITHDRAWN"));
    }

    @Test
    void historyFailureRollsBackStatusVersionAndNotes() throws Exception {
        var session = account("history-rollback@example.test");
        long id = create(session, "Java", "Empresa", ApplicationStatus.APPLIED);
        doThrow(new DataIntegrityViolationException("Falha simulada na gravação do histórico"))
            .when(history)
            .save(any(ApplicationStatusHistory.class));
        update(session, id, ApplicationStatus.INTERVIEW, 0, "Não deve persistir").andExpect(status().isConflict());
        mvc.perform(get("/api/applications/" + id).session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("APPLIED"))
            .andExpect(jsonPath("$.version").value(0))
            .andExpect(jsonPath("$.notes").value("Original"));
        assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM tb_application_status_history WHERE application_id = ?",
                Long.class,
                id
            )
        ).isEqualTo(1);
    }

    @Test
    void savedWithApplicationDateIsRejectedWithoutChangingExistingData() throws Exception {
        var session = account("saved-date@example.test");
        long id = create(session, "Java", "Empresa", ApplicationStatus.APPLIED);
        mvc.perform(
            put("/api/applications/" + id)
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"job":{"title":"Não deve mudar"},"status":"SAVED","appliedOn":"2020-01-01","version":0}
                    """
                )
        ).andExpect(status().is(422));
        mvc.perform(get("/api/applications/" + id).session(session))
            .andExpect(jsonPath("$.job.title").value("Java"))
            .andExpect(jsonPath("$.status").value("APPLIED"))
            .andExpect(jsonPath("$.version").value(0));
        assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM tb_application_status_history WHERE application_id = ?",
                Long.class,
                id
            )
        ).isEqualTo(1);
    }

    private MockHttpSession account(String email) throws Exception {
        mvc.perform(
            post("/api/users")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"name":"Teste KAN31","email":"%s","password":"SenhaTeste123!"}
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

    private long create(MockHttpSession session, String title, String company, ApplicationStatus state)
        throws Exception {
        var result = mvc
            .perform(
                post("/api/applications")
                    .session(session)
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"job":{"title":"%s","companyName":"%s","description":"somente-descricao"},
                         "status":"%s","notes":"Original"}
                        """.formatted(title, company, state.name())
                    )
            )
            .andExpect(status().isCreated())
            .andReturn();
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
    }

    private ResultActions update(MockHttpSession session, long id, ApplicationStatus state, long version, String notes)
        throws Exception {
        return mvc.perform(
            put("/api/applications/" + id)
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"job":{"title":"Java","companyName":"Empresa"},"status":"%s","version":%d,"notes":"%s"}
                    """.formatted(state.name(), version, notes)
                )
        );
    }
}

package com.maiconamaral.acompanhaai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.maiconamaral.acompanhaai.repositories.UserRepository;
import com.maiconamaral.acompanhaai.services.ImportTaskProcessor;
import com.maiconamaral.acompanhaai.services.ImportTaskQueue;
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
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * Mesmo com o log da aplicação em DEBUG, descrição colada, URL, título revisado e e-mail não
 * aparecem nos logs da importação: só identificadores, categorias e durações.
 */
@SpringBootTest(properties = "logging.level.com.maiconamaral.acompanhaai=DEBUG")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class ImportLogPrivacyIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String EMAIL = "privacidade-logs@example.com";
    private static final String DESCRIPTION = "Descricao-sigilosa-7f3a com dados que nao vao para o log.";
    private static final String URL = "http://127.0.0.1/vaga-sigilosa-9c1d";
    private static final String TITLE = "Titulo-sigiloso-2b8e";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private ImportTaskQueue queue;

    @Autowired
    private ImportTaskProcessor processor;

    @BeforeEach
    void reset() {
        users.deleteAll();
    }

    @Test
    void keepsImportContentOutOfTheLogs(CapturedOutput output) throws Exception {
        var session = account();
        long text = create(session, Map.of("inputType", "TEXT", "text", DESCRIPTION));
        process();
        create(session, Map.of("inputType", "URL", "url", URL));
        process();
        mvc.perform(
            post("/api/import-tasks/" + text + "/confirm")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"job\":{\"title\":\"" + TITLE + "\"},\"status\":\"SAVED\"}")
        ).andExpect(status().isCreated());

        assertThat(output.getAll())
            .contains("importacao id=" + text)
            .doesNotContain("Descricao-sigilosa-7f3a", "vaga-sigilosa-9c1d", TITLE, EMAIL);
    }

    private void process() {
        var claim = queue.claim().orElseThrow();
        queue.finish(claim, processor.process(claim.inputType(), claim.input(), claim.deadline()));
    }

    private long create(MockHttpSession session, Map<String, String> body) throws Exception {
        var content = mvc
            .perform(
                post("/api/import-tasks")
                    .session(session)
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(JSON.writeValueAsString(body))
            )
            .andExpect(status().isAccepted())
            .andReturn()
            .getResponse()
            .getContentAsString();
        return ((Number) JsonPath.read(content, "$.id")).longValue();
    }

    private MockHttpSession account() throws Exception {
        mvc.perform(
            post("/api/users")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Teste\",\"email\":\"" + EMAIL + "\",\"password\":\"SenhaTeste123!\"}")
        ).andExpect(status().isCreated());
        var result = mvc
            .perform(
                post("/api/auth/login")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"" + EMAIL + "\",\"password\":\"SenhaTeste123!\"}")
            )
            .andExpect(status().isOk())
            .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }
}

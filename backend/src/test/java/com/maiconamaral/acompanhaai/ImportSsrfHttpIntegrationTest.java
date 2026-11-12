package com.maiconamaral.acompanhaai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.maiconamaral.acompanhaai.repositories.UserRepository;
import com.maiconamaral.acompanhaai.services.ImportTaskProcessor;
import com.maiconamaral.acompanhaai.services.ImportTaskQueue;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * Importação por link com o coletor e a política de produção: destinos internos terminam em
 * `SOURCE_NOT_ALLOWED` sem conexão. Um servidor local registra qualquer requisição recebida.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ImportSsrfHttpIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private ImportTaskQueue queue;

    @Autowired
    private ImportTaskProcessor processor;

    private final List<String> received = new CopyOnWriteArrayList<>();
    private HttpServer internal;

    @BeforeEach
    void start() throws Exception {
        users.deleteAll();
        internal = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        internal.createContext("/", exchange -> {
            received.add(exchange.getRequestURI().toString());
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        internal.start();
    }

    @AfterEach
    void stop() {
        internal.stop(0);
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
            "http://127.0.0.1:{port}/admin",
            "http://localhost:{port}/admin",
            "http://127.0.0.1/admin",
            "http://localhost/",
            "http://[::1]/",
            "http://[::ffff:127.0.0.1]/",
            "http://0.0.0.0/",
            "http://10.0.0.1/",
            "http://192.168.0.1/",
            "http://169.254.169.254/latest/meta-data/",
            "https://169.254.169.254/"
        }
    )
    void refusesInternalDestinationsThroughTheApi(String url) throws Exception {
        var session = account();
        var target = url.replace("{port}", String.valueOf(internal.getAddress().getPort()));
        long id = create(session, target);

        var claim = queue.claim().orElseThrow();
        assertThat(claim.id()).isEqualTo(id);
        assertThat(queue.finish(claim, processor.process(claim.inputType(), claim.input(), claim.deadline()))).isTrue();

        mvc.perform(get("/api/import-tasks/" + id).session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("FAILED"))
            .andExpect(jsonPath("$.failure.code").value("SOURCE_NOT_ALLOWED"))
            .andExpect(jsonPath("$.failure.message").value("A URL não é permitida pela política de segurança."))
            .andExpect(jsonPath("$.result").doesNotExist());
        assertThat(received).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = { "file:///etc/passwd", "ftp://127.0.0.1/", "http://pessoa:senha-ficticia@127.0.0.1/" })
    void rejectsSchemesAndCredentialsBeforeQueueing(String url) throws Exception {
        var session = account();

        mvc.perform(
            post("/api/import-tasks")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("inputType", "URL", "url", url)))
        ).andExpect(status().isUnprocessableContent());

        assertThat(queue.claim()).isEmpty();
        assertThat(received).isEmpty();
    }

    private long create(MockHttpSession session, String url) throws Exception {
        var content = mvc
            .perform(
                post("/api/import-tasks")
                    .session(session)
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(JSON.writeValueAsString(Map.of("inputType", "URL", "url", url)))
            )
            .andExpect(status().isAccepted())
            .andReturn()
            .getResponse()
            .getContentAsString();
        return ((Number) JsonPath.read(content, "$.id")).longValue();
    }

    private MockHttpSession account() throws Exception {
        var email = "ssrf-" + System.nanoTime() + "@example.com";
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

package com.maiconamaral.acompanhaai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.jayway.jsonpath.JsonPath;
import com.maiconamaral.acompanhaai.dto.PasswordResetConfirmationDTO;
import com.maiconamaral.acompanhaai.entities.User;
import com.maiconamaral.acompanhaai.repositories.PasswordResetTokenRepository;
import com.maiconamaral.acompanhaai.repositories.UserRepository;
import com.maiconamaral.acompanhaai.services.PasswordResetService;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PasswordResetHttpIntegrationTest {

    private static final String REQUEST = "/api/auth/password-reset/request";
    private static final String CONFIRM = "/api/auth/password-reset/confirm";
    private static final String EMAIL = "reset-http@example.test";
    private static final String OLD = "SenhaAnterior123!";
    private static final String NEW = "SenhaNova123!";
    private static final Instant NOW = Instant.parse("2026-11-02T03:00:00Z");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private UserRepository users;

    @Autowired
    private PasswordResetTokenRepository tokens;

    @Autowired
    private PasswordResetService service;

    @Autowired
    private PasswordEncoder encoder;

    @Autowired
    @Qualifier("passwordResetRequestExecutor")
    private ThreadPoolTaskExecutor requests;

    @MockitoBean
    private Clock clock;

    @BeforeEach
    void prepare() {
        idle();
        users.deleteAll();
        when(clock.instant()).thenReturn(NOW);
    }

    @AfterEach
    void clean() {
        idle();
        users.deleteAll();
    }

    @Test
    void requestIsAnonymousGenericAndNeverReturnsAccountOrToken() throws Exception {
        user();
        String existing = postJson(REQUEST, mapper.writeValueAsString(Map.of("email", "  RESET-HTTP@example.test  ")))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.message").isString())
            .andReturn()
            .getResponse()
            .getContentAsString();
        String throttled = postJson(REQUEST, mapper.writeValueAsString(Map.of("email", EMAIL)))
            .andExpect(status().isAccepted())
            .andReturn()
            .getResponse()
            .getContentAsString();
        String unknown = postJson(REQUEST, "{\"email\":\"unknown@example.test\"}")
            .andExpect(status().isAccepted())
            .andReturn()
            .getResponse()
            .getContentAsString();
        assertThat(existing).isEqualTo(throttled).isEqualTo(unknown);
        assertThat(mapper.readTree(existing).size()).isEqualTo(1);
        assertThat(existing).doesNotContain("token", EMAIL, "password", "expiresAt", "recipient");
        assertThat(tokens.count()).isEqualTo(1);
        assertThat(users.count()).isEqualTo(1);
        mvc.perform(get("/api/users/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void bothRoutesRequireCsrfAndDoNotMutateStateWhenMissingOrInvalid() throws Exception {
        var user = user();
        String token = service.issueToken(EMAIL).orElseThrow().token();
        for (String path : new String[] { REQUEST, CONFIRM }) {
            String body = path.equals(REQUEST) ? "{\"email\":\"" + EMAIL + "\"}" : confirmation(token, NEW);
            mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(
                status().isForbidden()
            );
            mvc.perform(
                post(path).with(csrf().useInvalidToken()).contentType(MediaType.APPLICATION_JSON).content(body)
            ).andExpect(status().isForbidden());
        }
        assertThat(users.findById(user.getId()).orElseThrow().getPasswordHash()).isEqualTo(user.getPasswordHash());
        assertThat(tokens.findAll().getFirst().getUsedAt()).isNull();
        assertThat(tokens.count()).isEqualTo(1);
    }

    @Test
    void requestRejectsInvalidFieldsAndMalformedOrAbsentJson() throws Exception {
        for (String body : new String[] {
            "{}",
            "{\"email\":null}",
            "{\"email\":\" \"}",
            "{\"email\":\"invalid\"}",
            mapper.writeValueAsString(Map.of("email", "a".repeat(255)))
        }) {
            postJson(REQUEST, body)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].field").value("email"));
        }
        for (String body : new String[] { "{", "", "null" }) {
            postJson(REQUEST, body).andExpect(status().isBadRequest());
        }
        assertThat(tokens.count()).isZero();
    }

    @Test
    void confirmationRejectsInvalidFieldsWithoutConsumingTokenOrEchoingSecrets() throws Exception {
        user();
        String token = service.issueToken(EMAIL).orElseThrow().token();
        for (String body : new String[] {
            "{}",
            "{\"token\":null,\"newPassword\":null}",
            confirmation(" ", NEW),
            confirmation("a".repeat(129), NEW),
            confirmation(token, "short"),
            confirmation(token, " ".repeat(8)),
            confirmation(token, "a".repeat(129))
        }) {
            String response = postJson(CONFIRM, body)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors").isArray())
                .andReturn()
                .getResponse()
                .getContentAsString();
            assertThat(response).doesNotContain(token, NEW, "rejectedValue", "passwordHash");
        }
        for (String body : new String[] { "{", "", "null" }) {
            postJson(CONFIRM, body).andExpect(status().isBadRequest());
        }
        assertThat(tokens.findAll().getFirst().getUsedAt()).isNull();
        assertThat(encoder.matches(OLD, users.findByEmail(EMAIL).orElseThrow().getPasswordHash())).isTrue();
    }

    @Test
    void invalidExpiredAndReusedTokensReturnSameGenericProblem() throws Exception {
        user();
        String token = service.issueToken(EMAIL).orElseThrow().token();
        for (String invalid : new String[] { "malformed", "a".repeat(43) }) {
            assertInvalidToken(invalid);
        }
        when(clock.instant()).thenReturn(NOW.plusSeconds(1800));
        assertInvalidToken(token);
        when(clock.instant()).thenReturn(NOW);
        postJson(CONFIRM, confirmation(token, NEW)).andExpect(status().isNoContent());
        assertInvalidToken(token);
        assertThat(encoder.matches(NEW, users.findByEmail(EMAIL).orElseThrow().getPasswordHash())).isTrue();
    }

    @Test
    void replacedLinkReturnsGenericErrorAndCurrentLinkWorks() throws Exception {
        user();
        String first = service.issueToken(EMAIL).orElseThrow().token();
        when(clock.instant()).thenReturn(NOW.plusSeconds(60));
        String current = service.issueToken(EMAIL).orElseThrow().token();
        assertInvalidToken(first);
        postJson(CONFIRM, confirmation(current, NEW)).andExpect(status().isNoContent());
    }

    @Test
    void realAnonymousCsrfSessionCanConfirmThenMustLoginWithNewPassword() throws Exception {
        user();
        // Entrega simulada em memória: nenhum e-mail ou token foi obtido pela API.
        String token = service.issueToken(EMAIL).orElseThrow().token();
        var oldSession = (MockHttpSession) postJson("/api/auth/login", loginJson(OLD))
            .andExpect(status().isOk())
            .andReturn()
            .getRequest()
            .getSession(false);
        var csrfResult = mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
        var anonymous = (MockHttpSession) csrfResult.getRequest().getSession(false);
        String csrfToken = JsonPath.read(csrfResult.getResponse().getContentAsString(), "$.token");
        mvc.perform(
            post(CONFIRM)
                .session(anonymous)
                .header("X-CSRF-TOKEN", csrfToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(confirmation(token, NEW))
        )
            .andExpect(status().isNoContent())
            .andExpect(content().string(""));
        mvc.perform(get("/api/users/me").session(anonymous)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/users/me").session(oldSession)).andExpect(status().isUnauthorized());
        postJson("/api/auth/login", loginJson(OLD)).andExpect(status().isUnauthorized());
        postJson("/api/auth/login", loginJson(NEW)).andExpect(status().isOk());
    }

    @Test
    void confirmationDtoToStringDoesNotExposeSecrets() {
        assertThat(new PasswordResetConfirmationDTO("secret-token", NEW).toString()).doesNotContain(
            "secret-token",
            NEW
        );
    }

    private void assertInvalidToken(String token) throws Exception {
        String response = postJson(CONFIRM, confirmation(token, NEW))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.detail").value("Token de recuperação inválido ou indisponível."))
            .andReturn()
            .getResponse()
            .getContentAsString();
        assertThat(response).doesNotContain(token, NEW, EMAIL, "passwordHash");
    }

    private ResultActions postJson(String path, String body) throws Exception {
        var result = mvc.perform(post(path).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body));
        if (REQUEST.equals(path)) idle();
        return result;
    }

    private void idle() {
        org.awaitility.Awaitility.await()
            .atMost(java.time.Duration.ofSeconds(10))
            .until(() -> requests.getActiveCount() == 0 && requests.getThreadPoolExecutor().getQueue().isEmpty());
    }

    private User user() {
        return users.saveAndFlush(new User("Teste HTTP", EMAIL, encoder.encode(OLD)));
    }

    private String confirmation(String token, String password) {
        return mapper.writeValueAsString(Map.of("token", token, "newPassword", password));
    }

    private String loginJson(String password) {
        return mapper.writeValueAsString(Map.of("email", EMAIL, "password", password));
    }
}

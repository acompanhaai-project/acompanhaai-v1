package com.maiconamaral.acompanhaai;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import com.jayway.jsonpath.JsonPath;
import com.maiconamaral.acompanhaai.entities.User;
import com.maiconamaral.acompanhaai.repositories.PasswordResetTokenRepository;
import com.maiconamaral.acompanhaai.repositories.UserRepository;
import com.maiconamaral.acompanhaai.services.PasswordResetService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "app.password-reset.mail.enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PasswordResetMailIntegrationTest {
    private static final String EMAIL = "mail-flow@example.test";
    private static final String OLD = "SenhaAnterior123!";
    private static final String NEW = "NovaSenha123!";
    private static final Instant NOW = Instant.parse("2026-11-02T03:00:00Z");
    private static final GreenMail SMTP = new GreenMail(new ServerSetup(0, "127.0.0.1", "smtp"))
            .withConfiguration(GreenMailConfiguration.aConfig().withDisabledAuthentication());
    static { SMTP.start(); }

    @DynamicPropertySource
    static void smtpProperties(DynamicPropertyRegistry registry) {
        registry.add("app.password-reset.mail.port", () -> SMTP.getSmtp().getPort());
    }

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private UserRepository users;
    @Autowired private PasswordResetTokenRepository tokens;
    @Autowired private PasswordResetService service;
    @Autowired private PasswordEncoder encoder;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private JavaMailSender sender;
    @Autowired @Qualifier("passwordResetMailExecutor") private ThreadPoolTaskExecutor executor;
    @Autowired @Qualifier("passwordResetRequestExecutor") private ThreadPoolTaskExecutor requests;
    @MockitoBean private Clock clock;

    @BeforeEach
    void prepare() throws Exception {
        idle();
        SMTP.purgeEmailFromAllMailboxes();
        ((JavaMailSenderImpl) sender).setPort(SMTP.getSmtp().getPort());
        users.deleteAll();
        users.saveAndFlush(new User("Teste SMTP", EMAIL, encoder.encode(OLD)));
        when(clock.instant()).thenReturn(NOW);
    }

    @AfterEach
    void clean() {
        idle();
        users.deleteAll();
    }

    @AfterAll
    static void stopSmtp() { SMTP.stop(); }

    @Test
    void fullHttpFlowReceivesEmailConsumesTokenRevokesSessionAndLogsInWithNewPassword() throws Exception {
        var oldSession = (MockHttpSession) mvc.perform(post("/api/auth/login").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("email", EMAIL, "password", OLD))))
                .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
        var csrfResult = mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
        var anonymous = (MockHttpSession) csrfResult.getRequest().getSession(false);
        String csrfToken = JsonPath.read(csrfResult.getResponse().getContentAsString(), "$.token");

        String response = mvc.perform(post("/api/auth/password-reset/request").session(anonymous)
                .header("X-CSRF-TOKEN", csrfToken).header("Host", "untrusted.example")
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("email", EMAIL))))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String token = receivedToken();
        var message = SMTP.getReceivedMessages()[0];
        assertThat(message.getAllRecipients()[0].toString()).isEqualTo(EMAIL);
        assertThat(message.getFrom()[0].toString()).isEqualTo("noreply@acompanhaai.test");
        assertThat(message.getSubject()).contains("recuperação de senha");
        String body = (String) message.getContent();
        assertThat(body).contains("http://localhost:4200/reset-password#token=", "2026-11-02T03:30:00Z")
                .doesNotContain("untrusted.example", OLD, NEW);
        assertThat(response).doesNotContain(token, EMAIL);
        assertThat(tokens.findAll().getFirst().getTokenHash()).doesNotContain(token);

        String confirmation = json(Map.of("token", token, "newPassword", NEW));
        mvc.perform(post("/api/auth/password-reset/confirm").session(anonymous)
                .header("X-CSRF-TOKEN", csrfToken).contentType(MediaType.APPLICATION_JSON).content(confirmation))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/auth/password-reset/confirm").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(confirmation)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/users/me").session(anonymous)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/users/me").session(oldSession)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("email", EMAIL, "password", OLD)))).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("email", EMAIL, "password", NEW)))).andExpect(status().isOk());
    }

    @Test
    void deliveryWaitsForCommit() throws Exception {
        long tasks = executor.getThreadPoolExecutor().getTaskCount();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            service.requestReset(EMAIL);
            assertThat(SMTP.getReceivedMessages()).isEmpty();
            assertThat(executor.getThreadPoolExecutor().getTaskCount()).isEqualTo(tasks);
        });
        assertThat(receivedToken()).matches("[A-Za-z0-9_-]{43}");
    }

    @Test
    void rollbackDoesNotSubmitMailAndDoesNotPersistToken() {
        long tasks = executor.getThreadPoolExecutor().getTaskCount();
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            service.requestReset(EMAIL);
            assertThat(SMTP.getReceivedMessages()).isEmpty();
            throw new IllegalStateException("Rollback de teste");
        })).isInstanceOf(IllegalStateException.class);
        idle();
        assertThat(executor.getThreadPoolExecutor().getTaskCount()).isEqualTo(tasks);
        assertThat(SMTP.getReceivedMessages()).isEmpty();
        assertThat(tokens.count()).isZero();
    }

    @Test
    void unknownAccountAndCooldownHaveSameResponseAndDoNotSendExtraMail() throws Exception {
        String accepted = request(EMAIL);
        receivedToken();
        idle();
        long tasks = executor.getThreadPoolExecutor().getTaskCount();
        assertThat(request(EMAIL)).isEqualTo(accepted);
        assertThat(request("unknown@example.test")).isEqualTo(accepted);
        assertThat(executor.getThreadPoolExecutor().getTaskCount()).isEqualTo(tasks);
        assertThat(SMTP.getReceivedMessages()).hasSize(1);
    }

    @Test
    void smtpUnavailableStillReturnsGenericResponseAndNewRequestCanDeliverAfterCooldown() throws Exception {
        SMTP.stop();
        try {
            String accepted = request(EMAIL);
            idle();
            assertThat(tokens.count()).isEqualTo(1);
            assertThat(request("unknown@example.test")).isEqualTo(accepted);
        } finally {
            SMTP.start();
            ((JavaMailSenderImpl) sender).setPort(SMTP.getSmtp().getPort());
        }
        when(clock.instant()).thenReturn(NOW.plusSeconds(60));
        request(EMAIL);
        service.resetPassword(receivedToken(), NEW);
        assertThat(encoder.matches(NEW, users.findByEmail(EMAIL).orElseThrow().getPasswordHash())).isTrue();
    }

    private String request(String email) throws Exception {
        String response = mvc.perform(post("/api/auth/password-reset/request").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("email", email))))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        idle();
        return response;
    }

    private String receivedToken() throws Exception {
        assertThat(SMTP.waitForIncomingEmail(5000, 1)).isTrue();
        String body = (String) SMTP.getReceivedMessages()[0].getContent();
        var match = Pattern.compile("#token=([A-Za-z0-9_-]{43})").matcher(body);
        assertThat(match.find()).isTrue();
        return match.group(1);
    }

    private void idle() {
        await().atMost(Duration.ofSeconds(10)).until(() -> requests.getActiveCount() == 0
                && requests.getThreadPoolExecutor().getQueue().isEmpty());
        await().atMost(Duration.ofSeconds(10)).until(() -> executor.getActiveCount() == 0
                && executor.getThreadPoolExecutor().getQueue().isEmpty());
    }

    private String json(Map<String, String> values) { return mapper.writeValueAsString(values); }
}

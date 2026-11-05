package com.maiconamaral.acompanhaai;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import com.jayway.jsonpath.JsonPath;
import com.maiconamaral.acompanhaai.entities.User;
import com.maiconamaral.acompanhaai.repositories.UserRepository;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.ServletListenerRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "server.address=127.0.0.1")
@ActiveProfiles("test")
@Import(AccountSessionHttpIntegrationTest.ProbeConfiguration.class)
class AccountSessionHttpIntegrationTest {

    private static final String PASSWORD = "SenhaQA123!";

    @LocalServerPort
    private int port;

    @Autowired
    private UserRepository users;

    @Autowired
    private PasswordEncoder encoder;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private SessionProbe probe;

    @BeforeEach
    void prepare() {
        users.deleteAll();
        probe.latest.set(null);
    }

    @AfterEach
    void clean() {
        users.deleteAll();
    }

    @Test
    void inactivityExpiresOnlyTheIdleSessionAndAllowsNormalLoginAgain() throws Exception {
        var owner = user("qa-idle-a@example.test");
        var other = user("qa-idle-b@example.test");
        try (var first = client(); var second = client()) {
            login(first, owner.getEmail(), csrf(first));
            HttpSession firstSession = probe.latest.get();
            assertThat(firstSession.getMaxInactiveInterval()).isEqualTo(1800);
            assertThat(me(first).statusCode()).isEqualTo(200);
            login(second, other.getEmail(), csrf(second));
            assertThat(probe.latest.get().getMaxInactiveInterval()).isEqualTo(1800);
            assertThat(me(second).statusCode()).isEqualTo(200);

            // Somente a sessão A recebe intervalo curto, no container real de testes.
            // Nenhum invalidate() manual nem redução do timeout de produção.
            firstSession.setMaxInactiveInterval(1);
            await()
                .pollDelay(Duration.ofSeconds(2))
                .pollInterval(Duration.ofMillis(200))
                .atMost(Duration.ofSeconds(10))
                .until(() -> me(first).statusCode() == 401);
            assertThatThrownBy(firstSession::getCreationTime).isInstanceOf(IllegalStateException.class);
            assertThat(me(second).statusCode()).isEqualTo(200);
            assertThat(users.findById(owner.getId()).orElseThrow().getPasswordHash()).isEqualTo(
                owner.getPasswordHash()
            );
            assertThat(users.findById(other.getId()).orElseThrow().getAuthVersion()).isEqualTo(other.getAuthVersion());
            assertThat(users.count()).isEqualTo(2);
            login(first, owner.getEmail(), csrf(first));
            assertThat(me(first).statusCode()).isEqualTo(200);
        }
    }

    @Test
    void loginRotatesSessionAndCsrfAndLogoutPreservesAnotherSessionOfSameAccount() throws Exception {
        var owner = user("qa-multi-session@example.test");
        try (var first = client(); var second = client()) {
            String anonymousCsrf = csrf(first);
            String anonymousId = probe.latest.get().getId();
            login(first, owner.getEmail(), anonymousCsrf);
            assertThat(anonymousId.equals(probe.latest.get().getId())).isFalse();
            assertThat(logout(first, anonymousCsrf).statusCode()).isEqualTo(403);
            String firstCsrf = csrf(first);

            login(second, owner.getEmail(), csrf(second));
            assertThat(logout(second, firstCsrf).statusCode()).isEqualTo(403);
            assertThat(me(first).statusCode()).isEqualTo(200);
            assertThat(me(second).statusCode()).isEqualTo(200);

            assertThat(logout(first, firstCsrf).statusCode()).isEqualTo(204);
            assertThat(me(first).statusCode()).isEqualTo(401);
            assertThat(me(second).statusCode()).isEqualTo(200);
            assertThat(users.findById(owner.getId()).orElseThrow().getAuthVersion()).isEqualTo(owner.getAuthVersion());
        }
    }

    private User user(String email) {
        return users.saveAndFlush(new User("QA Sessões", email, encoder.encode(PASSWORD)));
    }

    private HttpClient client() {
        return HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
            .build();
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(15));
    }

    private String csrf(HttpClient client) throws Exception {
        var response = client.send(request("/api/auth/csrf").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return JsonPath.read(response.body(), "$.token");
    }

    private void login(HttpClient client, String email, String csrf) throws Exception {
        var response = client.send(
            request("/api/auth/login")
                .header("X-CSRF-TOKEN", csrf)
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        mapper.writeValueAsString(Map.of("email", email, "password", PASSWORD))
                    )
                )
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(response.body()).has("password")).isFalse();
        assertThat(mapper.readTree(response.body()).has("passwordHash")).isFalse();
    }

    private HttpResponse<String> me(HttpClient client) throws Exception {
        return client.send(request("/api/users/me").GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> logout(HttpClient client, String csrf) throws Exception {
        return client.send(
            request("/api/auth/logout").header("X-CSRF-TOKEN", csrf).POST(HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.ofString()
        );
    }

    static class SessionProbe {

        final AtomicReference<HttpSession> latest = new AtomicReference<>();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfiguration {

        @Bean
        SessionProbe sessionProbe() {
            return new SessionProbe();
        }

        @Bean
        ServletListenerRegistrationBean<HttpSessionListener> sessionListener(SessionProbe probe) {
            return new ServletListenerRegistrationBean<>(
                new HttpSessionListener() {
                    @Override
                    public void sessionCreated(HttpSessionEvent event) {
                        probe.latest.set(event.getSession());
                    }
                }
            );
        }
    }
}

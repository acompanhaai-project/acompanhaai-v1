package com.maiconamaral.acompanhaai;

import com.maiconamaral.acompanhaai.entities.User;
import com.maiconamaral.acompanhaai.repositories.UserRepository;
import com.maiconamaral.acompanhaai.repositories.PasswordResetTokenRepository;
import com.maiconamaral.acompanhaai.services.PasswordResetService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"app.password-reset.limits.per-ip=2", "app.password-reset.limits.global=3"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PasswordResetLimitsHttpIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private UserRepository users;
    @Autowired private PasswordResetTokenRepository tokens;
    @Autowired private PasswordResetService service;
    @Autowired private PasswordEncoder encoder;
    @Autowired @Qualifier("passwordResetRequestExecutor") private ThreadPoolTaskExecutor executor;

    @Test
    void limitsAreIndependentOfAccountsAndHeadersAndDoNotConsumeValidTokenWhenBlocked() throws Exception {
        users.deleteAll();
        try {
            for (String email : new String[]{"first@example.test", "second@example.test", "third@example.test"}) {
                users.saveAndFlush(new User("Teste", email, encoder.encode("SenhaAnterior123!")));
            }
            String accepted = request("192.0.2.1", "first@example.test");
            assertThat(request("192.0.2.1", "unknown@example.test")).isEqualTo(accepted);
            // Alterar headers fornecidos pelo cliente não muda o IP de conexão.
            assertThat(request("192.0.2.1", "second@example.test")).isEqualTo(accepted);
            assertThat(tokens.count()).isEqualTo(1);
            assertThat(request("192.0.2.2", "second@example.test")).isEqualTo(accepted);
            assertThat(request("192.0.2.3", "third@example.test")).isEqualTo(accepted);
            assertThat(tokens.count()).isEqualTo(2);

            // Obtemos tokens diretamente do Service apenas para testar a confirmação.
            String token = service.issueToken("third@example.test").orElseThrow().token();
            confirm("192.0.2.1", "a".repeat(43)).andExpect(status().isBadRequest());
            confirm("192.0.2.1", "b".repeat(43)).andExpect(status().isBadRequest());
            confirm("192.0.2.1", token).andExpect(status().isTooManyRequests())
                    .andExpect(header().string("Retry-After", org.hamcrest.Matchers.matchesPattern("[1-9][0-9]*")))
                    .andExpect(jsonPath("$.status").value(429));
            assertThat(users.findByEmail("third@example.test").orElseThrow().getAuthVersion()).isZero();
            confirm("192.0.2.2", token).andExpect(status().isNoContent());
            confirm("192.0.2.3", "c".repeat(43)).andExpect(status().isTooManyRequests());
            assertThat(users.findByEmail("third@example.test").orElseThrow().getAuthVersion()).isEqualTo(1);
        } finally {
            idle();
            users.deleteAll();
        }
    }

    private String request(String ip, String email) throws Exception {
        String response = mvc.perform(post("/api/auth/password-reset/request").with(csrf())
                .with(request -> { request.setRemoteAddr(ip); return request; })
                .header("X-Forwarded-For", "198.51.100." + System.nanoTime())
                .header("Forwarded", "for=198.51.100.1")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("email", email))))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        idle();
        return response;
    }

    private ResultActions confirm(String ip, String token) throws Exception {
        return mvc.perform(post("/api/auth/password-reset/confirm").with(csrf())
                .with(request -> { request.setRemoteAddr(ip); return request; })
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("token", token, "newPassword", "NovaSenha123!"))));
    }

    private void idle() {
        await().atMost(Duration.ofSeconds(10)).until(() -> executor.getActiveCount() == 0
                && executor.getThreadPoolExecutor().getQueue().isEmpty());
    }
}

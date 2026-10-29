package com.maiconamaral.acompanhaai;

import com.jayway.jsonpath.JsonPath;
import com.maiconamaral.acompanhaai.repositories.UserRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserAuthenticationIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private UserRepository repository;
    @Autowired private PasswordEncoder encoder;

    private static final String EMAIL = "maicon@example.com";
    private static final String PASSWORD = "SenhaTeste123!";
    private static final String USER_JSON = """
            {"name":"Maicon Amaral","email":"MAICON@example.com","password":"SenhaTeste123!"}
            """;

    @BeforeEach
    void cleanDatabase() { repository.deleteAll(); }

    @Test
    void registrationHashesPasswordNormalizesEmailAndDoesNotExposeSecrets() throws Exception {
        register(USER_JSON).andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/users/me"))
                .andExpect(jsonPath("$.email").value(EMAIL))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
        var user = repository.findByEmail(EMAIL).orElseThrow();
        assertThat(user.getPasswordHash()).isNotEqualTo(PASSWORD);
        assertThat(encoder.matches(PASSWORD, user.getPasswordHash())).isTrue();
    }

    @Test
    void registrationRejectsDuplicateEmailIgnoringCase() throws Exception {
        register(USER_JSON).andExpect(status().isCreated());
        register(USER_JSON.replace("MAICON", "maicon")).andExpect(status().isConflict());
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void invalidFieldsAndMalformedJsonAreRejected() throws Exception {
        register("""
                {"name":"  A  ","email":"invalid","password":"123"}
                """).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors").isArray());
        register("{").andExpect(status().isBadRequest());
        assertThat(repository.count()).isZero();
    }

    @Test
    void protectedRoutesRequireAuthenticationAndWritesRequireCsrf() throws Exception {
        mvc.perform(get("/api/users/me")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON).content(USER_JSON))
                .andExpect(status().isForbidden());
        register(USER_JSON).andExpect(status().isCreated());
        var session = login(EMAIL, PASSWORD);
        mvc.perform(put("/api/users/me").session(session).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Outro nome\",\"email\":\"outro@example.com\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void loginReturnsGenericErrorForWrongPasswordAndUnknownEmail() throws Exception {
        register(USER_JSON).andExpect(status().isCreated());
        for (String email : new String[]{EMAIL, "unknown@example.com"}) {
            mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                            .content(loginJson(email, "SenhaErrada123")))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.detail").value("Credenciais inválidas."));
        }
    }

    @Test
    void realCsrfFlowRotatesSessionAndTokenOnLogin() throws Exception {
        var result = mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
        var session = (MockHttpSession) result.getRequest().getSession(false);
        String token = JsonPath.read(result.getResponse().getContentAsString(), "$.token");
        String oldSessionId = session.getId();
        mvc.perform(post("/api/users").session(session).header("X-CSRF-TOKEN", token)
                        .contentType(MediaType.APPLICATION_JSON).content(USER_JSON))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/auth/login").session(session).with(request -> {
                            request.setRequestedSessionId(session.getId());
                            request.setRequestedSessionIdValid(true);
                            return request;
                        }).header("X-CSRF-TOKEN", token)
                        .contentType(MediaType.APPLICATION_JSON).content(loginJson(EMAIL, PASSWORD)))
                .andExpect(status().isOk());
        assertThat(session.getId()).isNotEqualTo(oldSessionId);
        mvc.perform(get("/api/users/me").session(session)).andExpect(status().isOk());
        mvc.perform(post("/api/auth/logout").session(session).header("X-CSRF-TOKEN", token))
                .andExpect(status().isForbidden());
        var fresh = mvc.perform(get("/api/auth/csrf").session(session)).andReturn();
        String freshToken = JsonPath.read(fresh.getResponse().getContentAsString(), "$.token");
        mvc.perform(post("/api/auth/logout").session(session).header("X-CSRF-TOKEN", freshToken))
                .andExpect(status().isNoContent());
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void userCanReadAndUpdateOnlyOwnAccount() throws Exception {
        register(USER_JSON).andExpect(status().isCreated());
        register(USER_JSON.replace("MAICON@example.com", "other@example.com")).andExpect(status().isCreated());
        var otherUser = repository.findByEmail("other@example.com").orElseThrow();
        var session = login(EMAIL, PASSWORD);
        mvc.perform(get("/api/users/me").session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.email").value(EMAIL));
        mvc.perform(put("/api/users/me").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Nome atualizado\",\"email\":\"novo@example.com\",\"id\":" + otherUser.getId() + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.email").value("novo@example.com"));
        assertThat(repository.findById(otherUser.getId()).orElseThrow().getEmail()).isEqualTo("other@example.com");
        mvc.perform(get("/api/users").session(session)).andExpect(status().isMethodNotAllowed());
        mvc.perform(get("/api/users/" + otherUser.getId()).session(session)).andExpect(status().isNotFound());
        login("novo@example.com", PASSWORD);
    }

    @Test
    void updateRejectsAnotherUsersEmail() throws Exception {
        register(USER_JSON).andExpect(status().isCreated());
        register(USER_JSON.replace("MAICON@example.com", "other@example.com")).andExpect(status().isCreated());
        mvc.perform(put("/api/users/me").session(login(EMAIL, PASSWORD)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Maicon\",\"email\":\"OTHER@example.com\"}"))
                .andExpect(status().isConflict());
        assertThat(repository.findByEmail(EMAIL)).isPresent();
    }

    @Test
    void passwordChangeRequiresCurrentPasswordAndRevokesAllSessions() throws Exception {
        register(USER_JSON).andExpect(status().isCreated());
        var session = login(EMAIL, PASSWORD);
        var secondSession = login(EMAIL, PASSWORD);
        mvc.perform(put("/api/users/me/password").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"incorreta\",\"newPassword\":\"NovaSenha123!\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(put("/api/users/me/password").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"SenhaTeste123!\",\"newPassword\":\"NovaSenha123!\"}"))
                .andExpect(status().isNoContent());
        assertThat(session.isInvalid()).isTrue();
        mvc.perform(get("/api/users/me").session(secondSession)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(EMAIL, PASSWORD))).andExpect(status().isUnauthorized());
        login(EMAIL, "NovaSenha123!");
    }

    @Test
    void deleteRemovesAccountAndRevokesOtherSessions() throws Exception {
        register(USER_JSON).andExpect(status().isCreated());
        var session = login(EMAIL, PASSWORD);
        var secondSession = login(EMAIL, PASSWORD);
        mvc.perform(delete("/api/users/me").session(session).with(csrf())).andExpect(status().isNoContent());
        assertThat(repository.count()).isZero();
        assertThat(session.isInvalid()).isTrue();
        mvc.perform(get("/api/users/me").session(secondSession)).andExpect(status().isUnauthorized());
    }

    private ResultActions register(String json) throws Exception {
        return mvc.perform(post("/api/users").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private MockHttpSession login(String email, String password) throws Exception {
        var result = mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(email, password)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.password").doesNotExist()).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private String loginJson(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }
}

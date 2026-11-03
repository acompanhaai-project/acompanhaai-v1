package com.maiconamaral.acompanhaai;

import com.maiconamaral.acompanhaai.entities.User;
import com.maiconamaral.acompanhaai.repositories.UserPhotoRepository;
import com.maiconamaral.acompanhaai.repositories.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserPhotoIntegrationTest {
    private static final String PHOTO = "/api/users/me/photo";
    private static final int TWO_MIB = 2 * 1024 * 1024;
    @Autowired private MockMvc mvc;
    @Autowired private UserRepository users;
    @Autowired private UserPhotoRepository photos;
    @Autowired private PasswordEncoder encoder;

    @BeforeEach
    void clean() { users.deleteAll(); }

    @ParameterizedTest
    @ValueSource(strings = {"png", "jpeg"})
    void detectsAllowedFormatFromContentInsteadOfFilenameOrDeclaredMime(String format) throws Exception {
        var session = account("format@example.test");
        upload(session, image(format, 5, 3)).andExpect(status().isNoContent());
        var response = mvc.perform(get(PHOTO).session(session)).andExpect(status().isOk())
                .andExpect(content().contentType(format.equals("png") ? "image/png" : "image/jpeg"))
                .andExpect(header().string("Cache-Control", "no-store")).andReturn().getResponse();
        var decoded = ImageIO.read(new ByteArrayInputStream(response.getContentAsByteArray()));
        assertThat(decoded.getWidth()).isEqualTo(5);
        assertThat(decoded.getHeight()).isEqualTo(3);
        assertThat(photos.findAll().getFirst().getContentType()).isEqualTo(response.getContentType());
    }

    @Test
    void acceptsExactlyTwoMiBAndReencodesDiscardingTrailingContent() throws Exception {
        var session = account("byte-boundary@example.test");
        byte[] png = image("png", 2, 2);
        byte[] padded = Arrays.copyOf(png, TWO_MIB);
        byte[] marker = "UNTRUSTED_TRAILING_CONTENT".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(marker, 0, padded, png.length, marker.length);
        upload(session, padded).andExpect(status().isNoContent());
        byte[] stored = photoBytes(session);
        assertThat(stored.length).isLessThan(TWO_MIB);
        assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain("UNTRUSTED_TRAILING_CONTENT");
        assertThat(ImageIO.read(new ByteArrayInputStream(stored)).getWidth()).isEqualTo(2);
    }

    @Test
    void acceptsExactlyFourMillionPixelsAndRejectsLargerWithoutReplacingPhoto() throws Exception {
        var session = account("pixel-boundary@example.test");
        upload(session, image("png", 2000, 2000)).andExpect(status().isNoContent());
        byte[] original = photoBytes(session);
        var decoded = ImageIO.read(new ByteArrayInputStream(original));
        assertThat((long) decoded.getWidth() * decoded.getHeight()).isEqualTo(4_000_000);
        upload(session, image("png", 2001, 2000)).andExpect(status().isUnprocessableEntity());
        assertThat(photoBytes(session)).isEqualTo(original);
    }

    @Test
    void invalidEmptyCorruptAndUnsupportedContentDoesNotOverwriteValidPhoto() throws Exception {
        var session = account("invalid-image@example.test");
        upload(session, image("png", 2, 2)).andExpect(status().isNoContent());
        byte[] original = photoBytes(session);
        byte[][] invalid = {
                new byte[0],
                "<svg><script>alert(1)</script></svg>".getBytes(StandardCharsets.UTF_8),
                "not an image".getBytes(StandardCharsets.UTF_8),
                image("gif", 2, 2),
                Arrays.copyOf(image("png", 2, 2), 16),
                Arrays.copyOf(image("jpeg", 2, 2), 12),
                new byte[TWO_MIB + 1]
        };
        for (byte[] bytes : invalid) {
            // Declara JPEG e usa extensão .jpg; bytes inválidos não se tornam imagens.
            mvc.perform(multipart(HttpMethod.PUT, PHOTO).file(new MockMultipartFile("file", "avatar.jpg", "image/jpeg", bytes))
                    .session(session).with(csrf())).andExpect(status().isUnprocessableEntity());
            assertThat(photoBytes(session)).isEqualTo(original);
        }
        assertThat(photos.count()).isEqualTo(1);
    }

    @Test
    void replacementChangesActualContentAndFormatWithoutCreatingAnotherRecord() throws Exception {
        var session = account("replace@example.test");
        upload(session, image("png", 2, 3)).andExpect(status().isNoContent());
        byte[] previous = photoBytes(session);
        upload(session, image("jpeg", 7, 4)).andExpect(status().isNoContent());
        var response = mvc.perform(get(PHOTO).session(session)).andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_JPEG)).andReturn().getResponse();
        assertThat(response.getContentAsByteArray()).isNotEqualTo(previous);
        var decoded = ImageIO.read(new ByteArrayInputStream(response.getContentAsByteArray()));
        assertThat(decoded.getWidth()).isEqualTo(7);
        assertThat(decoded.getHeight()).isEqualTo(4);
        assertThat(photos.count()).isEqualTo(1);
    }

    @Test
    void twoAccountsReadReplaceAndDeleteOnlyTheirOwnPhoto() throws Exception {
        var first = account("owner-photo@example.test");
        var second = account("other-photo@example.test");
        upload(first, image("png", 2, 2)).andExpect(status().isNoContent());
        upload(second, image("png", 3, 3)).andExpect(status().isNoContent());
        byte[] firstPhoto = photoBytes(first);
        byte[] secondPhoto = photoBytes(second);
        assertThat(firstPhoto).isNotEqualTo(secondPhoto);
        Long firstId = users.findByEmail("owner-photo@example.test").orElseThrow().getId();
        mvc.perform(multipart(HttpMethod.PUT, PHOTO).file(file(image("jpeg", 4, 4)))
                .param("userId", firstId.toString()).session(second).with(csrf())).andExpect(status().isNoContent());
        assertThat(photoBytes(first)).isEqualTo(firstPhoto);
        assertThat(photoBytes(second)).isNotEqualTo(secondPhoto);
        mvc.perform(delete(PHOTO).param("userId", firstId.toString()).session(second).with(csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(get(PHOTO).session(second)).andExpect(status().isNotFound());
        assertThat(photoBytes(first)).isEqualTo(firstPhoto);
        assertThat(photos.count()).isEqualTo(1);
    }

    @Test
    void anonymousAccessAndWritesWithoutValidCsrfDoNotChangePhoto() throws Exception {
        var session = account("csrf-photo@example.test");
        byte[] image = image("png", 2, 2);
        upload(session, image).andExpect(status().isNoContent());
        byte[] original = photoBytes(session);
        mvc.perform(get(PHOTO)).andExpect(status().isUnauthorized());
        mvc.perform(multipart(HttpMethod.PUT, PHOTO).file(file(image)).with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(delete(PHOTO).with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(multipart(HttpMethod.PUT, PHOTO).file(file(image)).session(session)).andExpect(status().isForbidden());
        mvc.perform(delete(PHOTO).session(session)).andExpect(status().isForbidden());
        mvc.perform(multipart(HttpMethod.PUT, PHOTO).file(file(image)).session(session).with(csrf().useInvalidToken()))
                .andExpect(status().isForbidden());
        mvc.perform(delete(PHOTO).session(session).with(csrf().useInvalidToken())).andExpect(status().isForbidden());
        assertThat(photoBytes(session)).isEqualTo(original);
    }

    @Test
    void missingMultipartFileIsRejectedWithoutRemovingPhoto() throws Exception {
        var session = account("missing-file@example.test");
        upload(session, image("png", 2, 2)).andExpect(status().isNoContent());
        byte[] original = photoBytes(session);
        mvc.perform(multipart(HttpMethod.PUT, PHOTO).session(session).with(csrf())).andExpect(status().isBadRequest());
        assertThat(photoBytes(session)).isEqualTo(original);
    }

    @Test
    void removalIsIdempotentAndProfileKeepsExplicitNullPhotoUrl() throws Exception {
        var session = account("optional-photo@example.test");
        assertNullPhotoUrl(session);
        mvc.perform(delete(PHOTO).session(session).with(csrf())).andExpect(status().isNoContent());
        upload(session, image("png", 2, 2)).andExpect(status().isNoContent());
        mvc.perform(get("/api/users/me").session(session)).andExpect(jsonPath("$.photoUrl").value(PHOTO));
        for (int count = 0; count < 2; count++) {
            mvc.perform(delete(PHOTO).session(session).with(csrf())).andExpect(status().isNoContent());
            assertNullPhotoUrl(session);
            mvc.perform(get(PHOTO).session(session)).andExpect(status().isNotFound());
        }
        assertThat(photos.count()).isZero();
    }

    @Test
    void deletingAccountCascadesOnlyItsPhotoAndPreservesOtherAccount() throws Exception {
        var first = account("delete-photo@example.test");
        var second = account("preserve-photo@example.test");
        upload(first, image("png", 2, 2)).andExpect(status().isNoContent());
        upload(second, image("jpeg", 3, 3)).andExpect(status().isNoContent());
        Long firstId = users.findByEmail("delete-photo@example.test").orElseThrow().getId();
        byte[] secondPhoto = photoBytes(second);
        mvc.perform(delete("/api/users/me").session(first).with(csrf())).andExpect(status().isNoContent());
        assertThat(photos.existsById(firstId)).isFalse();
        assertThat(photos.count()).isEqualTo(1);
        assertThat(photoBytes(second)).isEqualTo(secondPhoto);
        assertThat(first.isInvalid()).isTrue();
    }

    private void assertNullPhotoUrl(MockHttpSession session) throws Exception {
        mvc.perform(get("/api/users/me").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.photoUrl").hasJsonPath()).andExpect(jsonPath("$.photoUrl").value(nullValue()))
                .andExpect(jsonPath("$.password").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.passwordHash").doesNotHaveJsonPath());
    }

    private MockHttpSession account(String email) throws Exception {
        users.saveAndFlush(new User("Teste Foto", email, encoder.encode("SenhaFoto123!")));
        return (MockHttpSession) mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"SenhaFoto123!\"}"))
                .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
    }

    private ResultActions upload(MockHttpSession session, byte[] bytes) throws Exception {
        return mvc.perform(multipart(HttpMethod.PUT, PHOTO).file(file(bytes)).session(session).with(csrf()));
    }

    private MockMultipartFile file(byte[] bytes) {
        return new MockMultipartFile("file", "untrusted.svg", "application/octet-stream", bytes);
    }

    private byte[] photoBytes(MockHttpSession session) throws Exception {
        return mvc.perform(get(PHOTO).session(session)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
    }

    private byte[] image(String format, int width, int height) throws Exception {
        var output = new ByteArrayOutputStream();
        assertThat(ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), format, output)).isTrue();
        return output.toByteArray();
    }
}

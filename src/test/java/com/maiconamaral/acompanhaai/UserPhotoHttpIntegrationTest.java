package com.maiconamaral.acompanhaai;

import com.jayway.jsonpath.JsonPath;
import com.maiconamaral.acompanhaai.entities.User;
import com.maiconamaral.acompanhaai.repositories.UserPhotoRepository;
import com.maiconamaral.acompanhaai.repositories.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class UserPhotoHttpIntegrationTest {
    @LocalServerPort private int port;
    @Autowired private UserRepository users;
    @Autowired private UserPhotoRepository photos;
    @Autowired private PasswordEncoder encoder;
    @Autowired private ObjectMapper mapper;
    private static final int TWO_MIB = 2 * 1024 * 1024;

    @AfterEach
    void clean() { users.deleteAll(); }

    @Test
    void realMultipartAcceptsExactFileLimitAndRejectsFileAndRequestOversizeWithoutReplacingPhoto() throws Exception {
        users.deleteAll();
        users.saveAndFlush(new User("Foto HTTP", "photo-http@example.test", encoder.encode("SenhaFoto123!")));
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build()) {
            String csrf = csrf(client);
            var login = client.send(HttpRequest.newBuilder(uri("/api/auth/login"))
                    .timeout(Duration.ofSeconds(15)).header("X-CSRF-TOKEN", csrf)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(Map.of(
                            "email", "photo-http@example.test", "password", "SenhaFoto123!"))))
                    .build(), HttpResponse.BodyHandlers.ofByteArray());
            assertThat(login.statusCode()).isEqualTo(200);
            csrf = csrf(client);
            var output = new ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", output);
            byte[] png = output.toByteArray();
            assertThat(upload(client, csrf, Arrays.copyOf(png, TWO_MIB)).statusCode()).isEqualTo(204);
            byte[] original = photos.findAll().getFirst().getContent().clone();
            for (int size : new int[]{TWO_MIB + 1, 3 * 1024 * 1024 + 1}) {
                HttpResponse<byte[]> rejected;
                try {
                    rejected = upload(client, csrf, Arrays.copyOf(png, size));
                } catch (java.io.IOException exception) {
                    throw new java.io.IOException("Resposta incompleta para multipart com arquivo de " + size + " bytes", exception);
                }
                assertThat(rejected.statusCode()).as("multipart de %s bytes", size).isEqualTo(413);
                assertThat(photos.findAll().getFirst().getContent()).isEqualTo(original);
            }
            // Todas as partes ficam no limite individual; somente o total pode causar 413.
            byte[] mainFile = Arrays.copyOf(png, TWO_MIB);
            byte[] extraFile = Arrays.copyOf(png, 1024 * 1024);
            assertThat(mainFile.length).isLessThanOrEqualTo(TWO_MIB);
            assertThat(extraFile.length).isLessThanOrEqualTo(TWO_MIB);
            assertThat(upload(client, csrf, mainFile, Arrays.copyOf(extraFile, extraFile.length - 1024)).statusCode())
                    .as("multipart abaixo de 3 MiB com partes válidas").isEqualTo(204);
            assertThat(upload(client, csrf, mainFile, extraFile).statusCode())
                    .as("multipart acima de 3 MiB somente pela soma das partes e headers").isEqualTo(413);
            assertThat(photos.findAll().getFirst().getContent()).isEqualTo(original);
            var read = client.send(HttpRequest.newBuilder(uri("/api/users/me/photo")).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertThat(read.statusCode()).isEqualTo(200);
            assertThat(read.headers().firstValue("Content-Type")).contains("image/png");
            assertThat(read.body()).isEqualTo(original);
        }
    }

    private String csrf(HttpClient client) throws Exception {
        var response = client.send(HttpRequest.newBuilder(uri("/api/auth/csrf")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return JsonPath.read(response.body(), "$.token");
    }

    private HttpResponse<byte[]> upload(HttpClient client, String csrf, byte[]... files) throws Exception {
        String boundary = "----acompanhaai-kan26-boundary";
        var body = new ByteArrayOutputStream();
        for (int index = 0; index < files.length; index++) {
            String name = index == 0 ? "file" : "extra-" + index;
            body.writeBytes(("--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"" + name + "\"; filename=\"avatar.png\"\r\n"
                    + "Content-Type: image/png\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            body.writeBytes(files[index]);
            body.writeBytes("\r\n".getBytes(StandardCharsets.US_ASCII));
        }
        body.writeBytes(("--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
        return client.send(HttpRequest.newBuilder(uri("/api/users/me/photo"))
                .timeout(Duration.ofSeconds(15)).header("X-CSRF-TOKEN", csrf)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }
}

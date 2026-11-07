package com.maiconamaral.acompanhaai;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.springframework.test.context.DynamicPropertyRegistry;

final class BrowserEnvironment {

    static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    static final Path OUTPUT = Path.of("target", "browser-qa").toAbsolutePath();
    static final int PORT = freePort();
    static final String BASE = "http://127.0.0.1:" + PORT;
    static final GreenMail MAIL = new GreenMail(new ServerSetup(0, "127.0.0.1", "smtp"))
        .withConfiguration(GreenMailConfiguration.aConfig().withDisabledAuthentication());

    private static Process frontend;
    private static boolean stopped;

    private BrowserEnvironment() {
    }

    static void properties(DynamicPropertyRegistry registry) {
        MAIL.start();
        registry.add("spring.config.import", () -> "");
        registry.add("spring.datasource.url", () -> System.getProperty(
            "qa.database.url",
            "jdbc:h2:mem:browser-qa;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
        ));
        registry.add("spring.datasource.username", () -> System.getProperty("qa.database.user", "sa"));
        registry.add("spring.datasource.password", () -> {
            String password = System.getenv("ACOMPANHAAI_QA_DB_PASSWORD");
            return password == null ? "" : password;
        });
        registry.add("app.password-reset.mail.enabled", () -> true);
        registry.add("app.password-reset.mail.host", () -> "127.0.0.1");
        registry.add("app.password-reset.mail.port", () -> MAIL.getSmtp().getPort());
        registry.add("app.password-reset.mail.reset-url", () -> BASE + "/reset-password");
    }

    static void startFrontend(int apiPort) throws Exception {
        Files.createDirectories(OUTPUT);
        Path proxy = OUTPUT.resolve("proxy.json");
        Files.writeString(proxy, """
            {"/api/**":{"target":"http://127.0.0.1:%d","secure":false}}
            """.formatted(apiPort));
        boolean windows = System.getProperty("os.name").startsWith("Windows");
        ProcessBuilder builder;
        if (windows) {
            builder = new ProcessBuilder(
                "cmd.exe", "/c", "npm.cmd", "start", "--", "--host", "127.0.0.1",
                "--port", Integer.toString(PORT), "--proxy-config", proxy.toString()
            );
        } else {
            builder = new ProcessBuilder(
                "npm", "start", "--", "--host", "127.0.0.1",
                "--port", Integer.toString(PORT), "--proxy-config", proxy.toString()
            );
        }
        frontend = builder.directory(ROOT.resolve("frontend").toFile())
            .redirectErrorStream(true)
            .redirectOutput(OUTPUT.resolve("angular.log").toFile())
            .start();
        Runtime.getRuntime().addShutdownHook(new Thread(BrowserEnvironment::stop));
        HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(2))
            .build();
        long deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos();
        while (System.nanoTime() < deadline) {
            if (!frontend.isAlive()) {
                throw new IllegalStateException("Angular encerrou; consulte target/browser-qa/angular.log.");
            }
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(BASE + "/entrar"))
                    .timeout(Duration.ofSeconds(3)).build();
                if (client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200) {
                    return;
                }
            } catch (IOException ignored) {
                // O servidor ainda está compilando ou abrindo o socket.
            }
            TimeUnit.MILLISECONDS.sleep(250);
        }
        throw new IllegalStateException("Angular não ficou pronto em dois minutos.");
    }

    static synchronized void stop() {
        if (stopped) {
            return;
        }
        stopped = true;
        if (frontend != null) {
            frontend.descendants().forEach(ProcessHandle::destroy);
            frontend.destroy();
            frontend = null;
        }
        MAIL.stop();
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0, 0, java.net.InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        } catch (IOException cause) {
            throw new IllegalStateException("Não foi possível reservar porta local.", cause);
        }
    }
}

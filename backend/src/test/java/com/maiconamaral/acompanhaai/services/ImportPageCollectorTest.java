package com.maiconamaral.acompanhaai.services;

import static org.assertj.core.api.Assertions.assertThat;

import com.maiconamaral.acompanhaai.services.ImportTaskProcessor.Failed;
import com.maiconamaral.acompanhaai.services.ImportTaskProcessor.Failure;
import com.maiconamaral.acompanhaai.services.ImportTaskProcessor.Fallback;
import com.maiconamaral.acompanhaai.services.ImportTaskProcessor.NeedsInput;
import com.maiconamaral.acompanhaai.services.ImportTaskProcessor.Outcome;
import com.maiconamaral.acompanhaai.services.ImportTaskProcessor.Retry;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.zip.GZIPOutputStream;
import org.apache.hc.client5.http.DnsResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ImportPageCollectorTest {

    static final DnsResolver TEST_NETWORK = new DnsResolver() {
        private final ImportAddressPolicy policy = new ImportAddressPolicy();

        @Override
        public InetAddress[] resolve(String host) throws UnknownHostException {
            return host.endsWith(".example.test")
                ? new InetAddress[] { InetAddress.getLoopbackAddress() }
                : policy.resolve(host);
        }

        @Override
        public String resolveCanonicalHostname(String host) {
            return host;
        }
    };

    private final List<String> requests = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private ImportPageCollector collector;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        collector = collector(Duration.ofSeconds(10), 64 * 1024);
    }

    @AfterEach
    void stopServer() throws IOException {
        collector.close();
        server.stop(0);
    }

    @Test
    void readsThePageUsingTheCharsetFromTheHeader() {
        var body = "<p>Descrição</p>".getBytes(StandardCharsets.ISO_8859_1);
        respond("/vaga", 200, "text/html; charset=ISO-8859-1", body);

        assertThat(collect("/vaga")).isEqualTo(new ImportPageCollector.Page("<p>Descrição</p>", true));
    }

    @Test
    void readsTheCharsetDeclaredInTheDocumentAndPlainText() {
        var html = "<meta charset=\"windows-1252\"><p>Remuneração</p>";
        respond("/vaga", 200, "text/html", html.getBytes(StandardCharsets.ISO_8859_1));
        respond("/texto", 200, "text/plain", "Vaga Java".getBytes(StandardCharsets.UTF_8));

        assertThat(collect("/vaga")).isEqualTo(new ImportPageCollector.Page(html, true));
        assertThat(collect("/texto")).isEqualTo(new ImportPageCollector.Page("Vaga Java", false));
    }

    @Test
    void followsRelativeRedirects() {
        redirect("/antiga", "nova?id=1");
        respond("/nova", 200, "text/html", "<p>Vaga</p>".getBytes(StandardCharsets.UTF_8));

        assertThat(collect("/antiga")).isEqualTo(new ImportPageCollector.Page("<p>Vaga</p>", true));
        assertThat(requests).containsExactly("/antiga", "/nova?id=1");
    }

    @ParameterizedTest
    @ValueSource(strings = { "127.0.0.1", "localhost", "[::1]", "2130706433", "0x7f.0.0.1", "127.1" })
    void refusesNonPublicDestinationsWhenConnecting(String host) {
        respond("/vaga", 200, "text/html", "<p>Interno</p>".getBytes(StandardCharsets.UTF_8));

        var result = collector.collect("http://" + host + ":" + port() + "/vaga", lease());

        assertThat(result).isEqualTo(refused(new Failed(Failure.SOURCE_NOT_ALLOWED)));
        assertThat(requests).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
            "http://127.0.0.1:{port}/interno",
            "http://localhost:{port}/interno",
            "//169.254.169.254/latest",
            "ftp://vagas.example.test/"
        }
    )
    void refusesRedirectsToNonPublicDestinations(String location) {
        redirect("/vaga", location.replace("{port}", String.valueOf(port())));
        respond("/interno", 200, "text/html", "<p>Interno</p>".getBytes(StandardCharsets.UTF_8));

        assertThat(collect("/vaga")).isEqualTo(refused(new Failed(Failure.SOURCE_NOT_ALLOWED)));
        assertThat(requests).containsExactly("/vaga");
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
            "ftp://vagas.example.test/vaga",
            "file:///etc/passwd",
            "https://pessoa:senha-ficticia@vagas.example.test/vaga",
            "http://vagas.example.test:8080/vaga",
            "https://vagas.example.test:22/vaga",
            "http://[fe80::1%25eth0]/vaga",
            "http:///vaga",
            "http://vagas.example.test/a b"
        }
    )
    void refusesSchemesCredentialsAndPortsOutsideThePolicy(String url) throws IOException {
        var timeout = Duration.ofSeconds(5);
        try (var restricted = new ImportPageCollector(timeout, 1024, Clock.systemUTC(), TEST_NETWORK, false)) {
            assertThat(restricted.collect(url, lease())).isEqualTo(refused(new Failed(Failure.SOURCE_NOT_ALLOWED)));
        }
        assertThat(requests).isEmpty();
    }

    @ParameterizedTest
    @CsvSource(
        {
            "401,SOURCE_REQUIRES_LOGIN",
            "403,SOURCE_BLOCKED",
            "429,SOURCE_BLOCKED",
            "404,SOURCE_NO_USABLE_CONTENT",
            "410,SOURCE_NO_USABLE_CONTENT"
        }
    )
    void asksForTheDescriptionWhenThePageIsUnavailable(int status, Fallback reason) {
        respond("/vaga", status, "text/html", "<p>Indisponível</p>".getBytes(StandardCharsets.UTF_8));

        assertThat(collect("/vaga")).isEqualTo(refused(new NeedsInput(reason)));
    }

    @ParameterizedTest
    @ValueSource(strings = { "/login?next=%2Fvaga", "/conta/signin", "/authwall", "http://login.example.test:{port}/" })
    void asksForTheDescriptionWhenRedirectedToLogin(String location) {
        redirect("/vaga", location.replace("{port}", String.valueOf(port())));

        assertThat(collect("/vaga")).isEqualTo(refused(new NeedsInput(Fallback.SOURCE_REQUIRES_LOGIN)));
        assertThat(requests).containsExactly("/vaga");
    }

    @ParameterizedTest
    @ValueSource(ints = { 500, 502, 503 })
    void retriesServerFailures(int status) {
        respond("/vaga", status, "text/html", new byte[0]);

        assertThat(collect("/vaga")).isEqualTo(refused(new Retry()));
    }

    @Test
    void retriesWhenTheConnectionFailsOrTheLeaseIsEnding() {
        respond("/vaga", 200, "text/html", "<p>Vaga</p>".getBytes(StandardCharsets.UTF_8));
        var url = url("/vaga");
        server.stop(0);

        assertThat(collector.collect(url, lease())).isEqualTo(refused(new Retry()));
        assertThat(collector.collect(url, Instant.now().plusSeconds(3))).isEqualTo(refused(new Retry()));
    }

    @Test
    void refusesBodiesAboveTheLimitWithoutReadingThemToTheEnd() throws IOException {
        handle("/grande", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/html");
            exchange.sendResponseHeaders(200, 0);
            var block = new byte[8192];
            try (var body = exchange.getResponseBody()) {
                while (true) {
                    body.write(block);
                    body.flush();
                    Thread.sleep(20);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        var compressed = new ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(compressed)) {
            gzip.write(new byte[4 * 1024 * 1024]);
        }
        handle("/comprimido", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/html");
            exchange.getResponseHeaders().add("Content-Encoding", "gzip");
            send(exchange, 200, compressed.toByteArray());
        });
        respond("/declarado", 200, "text/html", new byte[64 * 1024 + 1]);
        var started = System.nanoTime();

        assertThat(collect("/grande")).isEqualTo(refused(new NeedsInput(Fallback.SOURCE_NO_USABLE_CONTENT)));
        assertThat(collect("/comprimido")).isEqualTo(refused(new NeedsInput(Fallback.SOURCE_NO_USABLE_CONTENT)));
        assertThat(collect("/declarado")).isEqualTo(refused(new NeedsInput(Fallback.SOURCE_NO_USABLE_CONTENT)));
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void stopsSlowResponsesWithinTheTimeout() throws IOException {
        handle("/cabecalho-lento", exchange -> {
            sleep(Duration.ofSeconds(8));
            send(exchange, 200, "<p>Vaga</p>".getBytes(StandardCharsets.UTF_8));
        });
        handle("/corpo-lento", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/html");
            exchange.sendResponseHeaders(200, 0);
            try (var body = exchange.getResponseBody()) {
                for (int index = 0; index < 80; index++) {
                    body.write('a');
                    body.flush();
                    sleep(Duration.ofMillis(100));
                }
            }
        });

        try (var impatient = collector(Duration.ofSeconds(1), 64 * 1024)) {
            for (var path : List.of("/cabecalho-lento", "/corpo-lento")) {
                var started = System.nanoTime();

                var result = impatient.collect(url(path), lease());

                assertThat(result).isEqualTo(refused(new Retry()));
                assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(4));
            }
        }
    }

    @Test
    void refusesResponsesWithExcessiveHeaders() {
        handle("/muitos", exchange -> {
            for (int index = 0; index < 200; index++) {
                exchange.getResponseHeaders().add("X-Extra-" + index, "valor");
            }
            send(exchange, 200, "<p>Vaga</p>".getBytes(StandardCharsets.UTF_8));
        });
        handle("/longo", exchange -> {
            exchange.getResponseHeaders().add("X-Extra", "a".repeat(32 * 1024));
            send(exchange, 200, "<p>Vaga</p>".getBytes(StandardCharsets.UTF_8));
        });

        assertThat(collect("/muitos")).isEqualTo(refused(new NeedsInput(Fallback.SOURCE_NO_USABLE_CONTENT)));
        assertThat(collect("/longo")).isEqualTo(refused(new NeedsInput(Fallback.SOURCE_NO_USABLE_CONTENT)));
    }

    @Test
    void refusesContentThatIsNotText() {
        respond("/vaga.pdf", 200, "application/pdf", "%PDF-1.7".getBytes(StandardCharsets.UTF_8));

        assertThat(collect("/vaga.pdf")).isEqualTo(refused(new NeedsInput(Fallback.SOURCE_NO_USABLE_CONTENT)));
    }

    @Test
    void stopsFollowingRedirectLoops() {
        redirect("/ciclo", "/ciclo");

        assertThat(collect("/ciclo")).isEqualTo(refused(new NeedsInput(Fallback.SOURCE_NO_USABLE_CONTENT)));
        assertThat(requests).hasSize(6);
    }

    private ImportPageCollector collector(Duration timeout, int maxBytes) {
        return new ImportPageCollector(timeout, maxBytes, Clock.systemUTC(), TEST_NETWORK, true);
    }

    private ImportPageCollector.Result collect(String path) {
        return collector.collect(url(path), lease());
    }

    private String url(String path) {
        return "http://vagas.example.test:" + port() + path;
    }

    private int port() {
        return server.getAddress().getPort();
    }

    private static Instant lease() {
        return Instant.now().plusSeconds(120);
    }

    private static ImportPageCollector.Result refused(Outcome outcome) {
        return new ImportPageCollector.Refused(outcome);
    }

    private void respond(String path, int status, String contentType, byte[] body) {
        handle(path, exchange -> {
            exchange.getResponseHeaders().add("Content-Type", contentType);
            send(exchange, status, body);
        });
    }

    private void redirect(String path, String location) {
        handle(path, exchange -> {
            exchange.getResponseHeaders().add("Location", location);
            send(exchange, 302, new byte[0]);
        });
    }

    private void handle(String path, HttpHandler handler) {
        server.createContext(path, exchange -> {
            requests.add(exchange.getRequestURI().toString());
            try (exchange) {
                handler.handle(exchange);
            }
        });
    }

    private static void send(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            exchange.getResponseBody().write(body);
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}

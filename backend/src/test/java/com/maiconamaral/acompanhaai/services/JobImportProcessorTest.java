package com.maiconamaral.acompanhaai.services;

import static org.assertj.core.api.Assertions.assertThat;

import com.maiconamaral.acompanhaai.entities.enums.ImportInputType;
import com.maiconamaral.acompanhaai.services.ImportTaskProcessor.Failed;
import com.maiconamaral.acompanhaai.services.ImportTaskProcessor.Failure;
import com.maiconamaral.acompanhaai.services.ImportTaskProcessor.Fallback;
import com.maiconamaral.acompanhaai.services.ImportTaskProcessor.NeedsInput;
import com.maiconamaral.acompanhaai.services.ImportTaskProcessor.Outcome;
import com.maiconamaral.acompanhaai.services.ImportTaskProcessor.Ready;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JobImportProcessorTest {

    private static final String DESCRIPTION =
        "Pessoa desenvolvedora Java para atuar em produtos digitais com Spring Boot, PostgreSQL e testes " +
        "automatizados. Trabalho remoto, contratação CLT e participação em um time multidisciplinar com " +
        "revisão de código e entregas contínuas.";

    private HttpServer server;
    private ImportPageCollector collector;
    private JobImportProcessor processor;
    private String contentType = "text/html; charset=UTF-8";
    private String page = "";

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/vaga", exchange -> {
            try (exchange) {
                var body = page.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", contentType);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
        collector = new ImportPageCollector(
            Duration.ofSeconds(10),
            64 * 1024,
            Clock.systemUTC(),
            ImportPageCollectorTest.TEST_NETWORK,
            true
        );
        processor = new JobImportProcessor(collector);
    }

    @AfterEach
    void stopServer() throws IOException {
        collector.close();
        server.stop(0);
    }

    @Test
    void proposesThePastedDescriptionForReview() {
        var text = "  Vaga Java.\r\n\r\n\r\n\r\nTrabalho   remoto. ";

        var outcome = processor.process(ImportInputType.TEXT, text, lease());

        assertThat(outcome).isEqualTo(new Ready(null, Map.of("description", "Vaga Java.\n\nTrabalho remoto.")));
    }

    @ParameterizedTest
    @ValueSource(strings = { " \n\t ", "\u0000\u00A0" })
    void failsWhenThePastedDescriptionHasNoText(String text) {
        var outcome = processor.process(ImportInputType.TEXT, text, lease());

        assertThat(outcome).isEqualTo(new Failed(Failure.PROCESSING_FAILED));
    }

    @Test
    void prefersTheStructuredJobPosting() {
        page = """
            <html><head>
            <script type="application/ld+json">
            {
              "@type": "JobPosting",
              "title": "Pessoa desenvolvedora Java",
              "hiringOrganization": { "name": "Empresa Exemplo" }
            }
            </script></head>
            <body><div id="app"></div><script src="/app.js"></script></body></html>
            """;

        assertThat(importUrl()).isEqualTo(new Ready(
            "",
            Map.of("title", "Pessoa desenvolvedora Java", "companyName", "Empresa Exemplo")
        ));
    }

    @Test
    void proposesThePageTextWhenThereIsNoStructuredData() {
        page = "<html><body><h1>Vaga</h1><p>" + DESCRIPTION + "</p></body></html>";

        var text = "Vaga\n\n" + DESCRIPTION;
        assertThat(importUrl()).isEqualTo(new Ready(text, Map.of("description", text)));

        contentType = "text/plain; charset=UTF-8";
        page = DESCRIPTION;

        assertThat(importUrl()).isEqualTo(new Ready(DESCRIPTION, Map.of("description", DESCRIPTION)));
    }

    @Test
    void asksForTheDescriptionWhenThePageHasNoUsableText() {
        page = "<div id=\"root\"></div><script src=\"/app.js\"></script><noscript>Ative o JavaScript.</noscript>";
        assertThat(importUrl()).isEqualTo(new NeedsInput(Fallback.SOURCE_REQUIRES_JAVASCRIPT));

        page = "<form><input type=\"email\"><input type=\"password\"></form><script src=\"/a.js\"></script>";
        assertThat(importUrl()).isEqualTo(new NeedsInput(Fallback.SOURCE_REQUIRES_LOGIN));

        page = "<html><body><p>Vaga encerrada.</p></body></html>";
        assertThat(importUrl()).isEqualTo(new NeedsInput(Fallback.SOURCE_NO_USABLE_CONTENT));

        contentType = "text/plain";
        page = "Vaga encerrada.";
        assertThat(importUrl()).isEqualTo(new NeedsInput(Fallback.SOURCE_NO_USABLE_CONTENT));
    }

    @Test
    void returnsTheCollectorRefusal() {
        var url = "http://127.0.0.1:" + server.getAddress().getPort() + "/vaga";

        var outcome = processor.process(ImportInputType.URL, url, lease());

        assertThat(outcome).isEqualTo(new Failed(Failure.SOURCE_NOT_ALLOWED));
    }

    private Outcome importUrl() {
        return processor.process(
            ImportInputType.URL,
            "http://vagas.example.test:" + server.getAddress().getPort() + "/vaga",
            lease()
        );
    }

    private static Instant lease() {
        return Instant.now().plusSeconds(120);
    }
}

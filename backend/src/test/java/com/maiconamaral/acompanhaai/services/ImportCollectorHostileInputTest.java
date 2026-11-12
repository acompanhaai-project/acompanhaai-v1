package com.maiconamaral.acompanhaai.services;

import static org.assertj.core.api.Assertions.assertThat;

import com.maiconamaral.acompanhaai.services.ImportTaskProcessor.Failed;
import com.maiconamaral.acompanhaai.services.ImportTaskProcessor.Failure;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import org.apache.hc.client5.http.DnsResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Casos hostis do coletor (KAN-51) contra um servidor local controlado. Endereços internos nunca
 * recebem conexão: a política recusa o destino antes, e o servidor registra cada requisição.
 */
class ImportCollectorHostileInputTest {

    private static final Failed NOT_ALLOWED = new Failed(Failure.SOURCE_NOT_ALLOWED);

    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final Map<String, Deque<InetAddress[]>> answers = new ConcurrentHashMap<>();
    private final List<String> lookups = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private ImportPageCollector collector;

    /**
     * Resolve `vagas.example.test` para o servidor local e nomes roteirizados pelas respostas
     * definidas no teste, conferidas pela política. Outros nomes seguem a política real.
     */
    private final DnsResolver network = new DnsResolver() {
        private final ImportAddressPolicy policy = new ImportAddressPolicy();

        @Override
        public InetAddress[] resolve(String host) throws UnknownHostException {
            lookups.add(host);
            var scripted = answers.get(host);
            if (scripted != null) {
                var next = scripted.size() > 1 ? scripted.poll() : scripted.peek();
                for (var address : next) {
                    // O loopback representa aqui o servidor de teste; o resto passa pela política.
                    if (!address.isLoopbackAddress() && !ImportAddressPolicy.isPublic(address)) {
                        throw new ImportAddressPolicy.Blocked();
                    }
                }
                return next;
            }
            return host.equals("vagas.example.test")
                ? new InetAddress[] { InetAddress.getLoopbackAddress() }
                : policy.resolve(host);
        }

        @Override
        public String resolveCanonicalHostname(String host) {
            return host;
        }
    };

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", exchange -> {
            requests.add(exchange.getRequestHeaders().getFirst("Host") + exchange.getRequestURI());
            try (exchange) {
                var redirect = exchange.getRequestURI().getQuery();
                if (redirect != null && redirect.startsWith("para=")) {
                    exchange.getResponseHeaders().add(
                        "Location",
                        URLDecoder.decode(redirect.substring(5), StandardCharsets.UTF_8)
                    );
                    exchange.sendResponseHeaders(302, -1);
                    return;
                }
                var body = "<p>Conteúdo interno que não pode ser lido</p>".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
        collector = new ImportPageCollector(Duration.ofSeconds(5), 64 * 1024, Clock.systemUTC(), network, true);
    }

    @AfterEach
    void stop() throws IOException {
        collector.close();
        server.stop(0);
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
            "0.0.0.0",
            "[::]",
            "[::ffff:127.0.0.1]",
            "[::ffff:7f00:1]",
            "[64:ff9b::7f00:1]",
            "[2002:7f00:1::]",
            "[fd00::1]",
            "[fe80::1]",
            "[::ffff:a9fe:a9fe]",
            "169.254.169.254",
            "100.64.0.1",
            "LOCALHOST",
            "127.0.0.2"
        }
    )
    void refusesLiteralAndMappedInternalAddressesBeforeConnecting(String host) {
        var result = collector.collect("http://" + host + ":" + port() + "/vaga", lease());

        assertThat(result).isEqualTo(new ImportPageCollector.Refused(NOT_ALLOWED));
        assertThat(requests).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
            "file:///etc/passwd",
            "gopher://vagas.example.test/_interno",
            "javascript:alert(1)",
            "data:text/html,vaga",
            "http://[::ffff:127.0.0.1]:{port}/interno",
            "http://0.0.0.0:{port}/interno",
            "HTTP://LOCALHOST:{port}/interno",
            "http://vagas.example.test@127.0.0.1:{port}/interno",
            "http://169.254.169.254/latest/meta-data/",
            "http://[fd00::1]/interno"
        }
    )
    void refusesRedirectsToOtherSchemesAndInternalForms(String location) {
        var target = location.replace("{port}", String.valueOf(port()));

        var result = collector.collect(redirectTo(target), lease());

        assertThat(result).isEqualTo(new ImportPageCollector.Refused(NOT_ALLOWED));
        assertThat(requests).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
            "http://127.0.0.1\\@vagas.example.test/vaga",
            "  http://localhost:{port}/vaga  ",
            "http://vagas.example.test:{port}@127.0.0.1:{port}/vaga",
            "http://%31%32%37.0.0.1:{port}/vaga",
            "http://127.0.0.1#@vagas.example.test/vaga"
        }
    )
    void refusesDisguisedInternalAddressesInTheInput(String url) {
        var result = collector.collect(url.replace("{port}", String.valueOf(port())), lease());

        assertThat(result).isInstanceOf(ImportPageCollector.Refused.class);
        assertThat(requests).isEmpty();
    }

    @Test
    void refusesANameThatResolvesToAPublicAndAnInternalAddress() throws Exception {
        answers.put(
            "misto.example.test",
            new ArrayDeque<>(List.<InetAddress[]>of(new InetAddress[] {
                InetAddress.getByName("203.0.114.10"),
                InetAddress.getByName("10.0.0.5")
            }))
        );

        var result = collector.collect("http://misto.example.test:" + port() + "/vaga", lease());

        assertThat(result).isEqualTo(new ImportPageCollector.Refused(NOT_ALLOWED));
        assertThat(requests).isEmpty();
    }

    @Test
    void resolvesTheHostAgainOnEveryRedirectAndRefusesARebind() throws Exception {
        answers.put(
            "troca.example.test",
            new ArrayDeque<>(List.<InetAddress[]>of(
                new InetAddress[] { InetAddress.getLoopbackAddress() },
                new InetAddress[] { InetAddress.getByName("169.254.169.254") }
            ))
        );
        var again = "http://troca.example.test:" + port() + "/interno";

        var result = collector.collect(
            "http://troca.example.test:" + port() + "/vaga?para=" + URLEncoder.encode(again, StandardCharsets.UTF_8),
            lease()
        );

        assertThat(result).isEqualTo(new ImportPageCollector.Refused(NOT_ALLOWED));
        assertThat(lookups).containsExactly("troca.example.test", "troca.example.test");
        assertThat(requests).hasSize(1);
    }

    @Test
    void refusesNonStandardPortsWithTheProductionPolicy() throws IOException {
        try (var strict = new ImportPageCollector(Duration.ofSeconds(5), 64 * 1024, Clock.systemUTC(), network, false)) {
            var result = strict.collect("http://vagas.example.test:22/vaga", lease());

            assertThat(result).isEqualTo(new ImportPageCollector.Refused(NOT_ALLOWED));
        }
        assertThat(requests).isEmpty();
    }

    private String redirectTo(String location) {
        return "http://vagas.example.test:" + port() + "/vaga?para="
            + URLEncoder.encode(location, StandardCharsets.UTF_8);
    }

    private int port() {
        return server.getAddress().getPort();
    }

    private static Instant lease() {
        return Instant.now().plusSeconds(120);
    }
}

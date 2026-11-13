package com.maiconamaral.acompanhaai;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Servidor local das jornadas de importação: publica páginas de vaga controladas e responde no
 * formato `generateContent` do Gemini a partir das linhas "Cargo:", "Empresa:", "Local:",
 * "Salário:" e "Requisitos:" do texto recebido. Texto com o marcador {@link #UNAVAILABLE}
 * recebe 503, para exercitar a revisão manual. Nenhuma chamada sai da máquina.
 */
final class ImportSimulation {

    static final String HOST = com.maiconamaral.acompanhaai.services.BrowserImportNetwork.HOST;
    static final String UNAVAILABLE = "[provedor-indisponivel]";

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern SALARY = Pattern.compile("R\\$ ?([\\d.]+) por mês");
    private static final HttpServer SERVER = start();
    private static final AtomicInteger EXTRACTIONS = new AtomicInteger();

    private ImportSimulation() {
    }

    static String providerUrl() {
        return "http://127.0.0.1:" + SERVER.getAddress().getPort();
    }

    /** Endereço de uma página de vaga com JSON-LD `JobPosting`. */
    static String pageUrl(String title, String company) {
        return "http://" + HOST + ":" + SERVER.getAddress().getPort() + "/vaga?titulo="
            + java.net.URLEncoder.encode(title, StandardCharsets.UTF_8) + "&empresa="
            + java.net.URLEncoder.encode(company, StandardCharsets.UTF_8);
    }

    static int extractions() {
        return EXTRACTIONS.get();
    }

    static void stop() {
        SERVER.stop(0);
    }

    private static HttpServer start() {
        try {
            var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.createContext("/vaga", ImportSimulation::page);
            server.createContext("/", ImportSimulation::extraction);
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void page(HttpExchange exchange) throws IOException {
        try (exchange) {
            var query = parameters(exchange.getRequestURI().getRawQuery());
            var posting = new LinkedHashMap<String, Object>();
            posting.put("@context", "https://schema.org");
            posting.put("@type", "JobPosting");
            posting.put("title", query.getOrDefault("titulo", "Vaga"));
            posting.put("hiringOrganization", Map.of("@type", "Organization", "name", query.getOrDefault("empresa", "")));
            posting.put("description", "Atuação com Java, Spring Boot e PostgreSQL em produto próprio.");
            posting.put("jobLocationType", "TELECOMMUTE");
            var html = """
                <!DOCTYPE html>
                <html lang="pt-BR"><head><title>%s</title>
                <script type="application/ld+json">%s</script></head>
                <body><h1>%s</h1><p>Atuação com Java, Spring Boot e PostgreSQL em produto próprio.</p></body></html>
                """.formatted(posting.get("title"), JSON.writeValueAsString(posting), posting.get("title"));
            send(exchange, 200, "text/html; charset=utf-8", html);
        }
    }

    private static void extraction(HttpExchange exchange) throws IOException {
        try (exchange) {
            EXTRACTIONS.incrementAndGet();
            var request = JSON.readTree(exchange.getRequestBody().readAllBytes());
            var text = String.join("\n", texts(request));
            if (text.contains(UNAVAILABLE)) {
                send(exchange, 503, "application/json", "{\"error\":{\"status\":\"UNAVAILABLE\"}}");
                return;
            }
            var answer = new LinkedHashMap<String, Object>();
            var evidence = new LinkedHashMap<String, Object>();
            line(text, "Cargo:").ifPresent(value -> {
                answer.put("title", value);
                evidence.put("title", value);
            });
            line(text, "Empresa:").ifPresent(value -> {
                answer.put("companyName", value);
                evidence.put("companyName", value);
            });
            line(text, "Local:").ifPresent(value -> {
                answer.put("location", value);
                evidence.put("location", value);
            });
            Matcher salary = SALARY.matcher(text);
            if (salary.find()) {
                answer.put("salary", Long.parseLong(salary.group(1).replace(".", "")));
                answer.put("salaryCurrency", "BRL");
                answer.put("salaryPeriod", "MONTH");
                evidence.put("salary", salary.group());
            }
            line(text, "Requisitos:").ifPresent(value ->
                answer.put("hardSkills", List.of(value.replace(".", "").split(",\\s*|\\s+e\\s+")))
            );
            answer.put("evidence", evidence);
            var body = Map.of(
                "candidates",
                List.of(Map.of(
                    "content", Map.of("parts", List.of(Map.of("text", JSON.writeValueAsString(answer)))),
                    "finishReason", "STOP"
                ))
            );
            send(exchange, 200, "application/json", JSON.writeValueAsString(body));
        }
    }

    private static List<String> texts(JsonNode request) {
        return request.findValues("text").stream().map(JsonNode::asString).toList();
    }

    private static java.util.Optional<String> line(String text, String label) {
        for (var line : text.split("\\R")) {
            var trimmed = line.strip();
            if (trimmed.startsWith(label)) {
                var value = trimmed.substring(label.length()).strip();
                return value.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(value);
            }
        }
        return java.util.Optional.empty();
    }

    private static Map<String, String> parameters(String query) {
        var values = new LinkedHashMap<String, String>();
        if (query == null) {
            return values;
        }
        for (var pair : query.split("&")) {
            var parts = pair.split("=", 2);
            values.put(
                java.net.URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                parts.length > 1 ? java.net.URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : ""
            );
        }
        return values;
    }

    private static void send(HttpExchange exchange, int status, String type, String body) throws IOException {
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", type);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}

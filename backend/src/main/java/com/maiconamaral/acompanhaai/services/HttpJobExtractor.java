package com.maiconamaral.acompanhaai.services;

import com.maiconamaral.acompanhaai.services.JobExtractionException.Reason;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

abstract class HttpJobExtractor implements JobExtractor {

    private static final Duration MARGIN = Duration.ofSeconds(5);
    private static final Duration MINIMUM = Duration.ofSeconds(2);

    private final HttpClient client;
    private final JsonMapper json;
    private final Clock clock;
    private final Duration timeout;
    private final int maxInput;

    HttpJobExtractor(JsonMapper json, Clock clock, Duration timeout, int maxInput) {
        this.json = json;
        this.clock = clock;
        this.timeout = timeout;
        this.maxInput = maxInput;
        this.client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    }

    @Override
    public final Map<String, Object> extract(String text, Instant deadline) {
        var remaining = Duration.between(clock.instant(), deadline.minus(MARGIN));
        if (remaining.compareTo(MINIMUM) < 0) {
            throw new JobExtractionException(Reason.TIMEOUT);
        }
        var source = text.length() > maxInput ? text.substring(0, maxInput) : text;
        var builder = HttpRequest.newBuilder(endpoint())
            .timeout(remaining.compareTo(timeout) < 0 ? remaining : timeout)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body(source))));
        headers().forEach(builder::header);

        HttpResponse<String> response;
        try {
            response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            throw new JobExtractionException(Reason.TIMEOUT);
        } catch (IOException e) {
            throw new JobExtractionException(Reason.UNAVAILABLE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JobExtractionException(Reason.UNAVAILABLE);
        }

        int status = response.statusCode();
        if (status == 429) {
            throw new JobExtractionException(Reason.RATE_LIMITED);
        }
        if (status == 408 || status >= 500) {
            throw new JobExtractionException(Reason.UNAVAILABLE);
        }
        if (status < 200 || status >= 300) {
            throw new JobExtractionException(Reason.REJECTED);
        }
        try {
            var content = content(json.readTree(response.body()));
            if (content == null || content.isBlank()) {
                throw new JobExtractionException(Reason.INVALID_RESPONSE);
            }
            return JobExtractionContract.validate(json.readTree(content), source);
        } catch (JacksonException e) {
            throw new JobExtractionException(Reason.INVALID_RESPONSE);
        }
    }

    abstract URI endpoint();

    abstract Map<String, String> headers();

    abstract Object body(String text);

    /** Texto JSON produzido pelo modelo, ou null quando ausente. */
    abstract String content(JsonNode response);
}

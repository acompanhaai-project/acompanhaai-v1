package com.maiconamaral.acompanhaai.services;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class OllamaJobExtractor extends HttpJobExtractor {

    private final URI endpoint;
    private final String model;

    OllamaJobExtractor(JsonMapper json, Clock clock, Duration timeout, int maxInput, String baseUrl, String model) {
        super(json, clock, timeout, maxInput);
        this.endpoint = URI.create(baseUrl.replaceAll("/+$", "") + "/api/chat");
        this.model = model;
    }

    @Override
    public String provider() {
        return "ollama";
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    URI endpoint() {
        return endpoint;
    }

    @Override
    Map<String, String> headers() {
        return Map.of();
    }

    @Override
    Object body(String text) {
        return Map.of(
            "model", model,
            "stream", false,
            "format", JobExtractionContract.jsonSchema(),
            "options", Map.of("temperature", 0),
            "messages", List.of(
                Map.of("role", "system", "content", JobExtractionContract.INSTRUCTIONS),
                Map.of("role", "user", "content", JobExtractionContract.userMessage(text))
            )
        );
    }

    @Override
    String content(JsonNode response) {
        var content = response.path("message").path("content");
        return content.isString() ? content.stringValue() : null;
    }
}

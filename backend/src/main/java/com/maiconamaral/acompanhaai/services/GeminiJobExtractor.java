package com.maiconamaral.acompanhaai.services;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class GeminiJobExtractor extends HttpJobExtractor {

    private final URI endpoint;
    private final String apiKey;
    private final String model;
    private final int thinkingBudget;

    GeminiJobExtractor(
        JsonMapper json,
        Clock clock,
        Duration timeout,
        int maxInput,
        String baseUrl,
        String model,
        String apiKey,
        int thinkingBudget
    ) {
        super(json, clock, timeout, maxInput);
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Defina GEMINI_API_KEY para usar o provedor Gemini.");
        }
        this.endpoint = URI.create(baseUrl.replaceAll("/+$", "") + "/v1beta/models/" + model + ":generateContent");
        this.apiKey = apiKey.strip();
        this.model = model;
        this.thinkingBudget = thinkingBudget;
    }

    @Override
    public String provider() {
        return "gemini";
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
        return Map.of("x-goog-api-key", apiKey);
    }

    @Override
    Object body(String text) {
        var generation = new LinkedHashMap<String, Object>();
        generation.put("temperature", 0);
        generation.put("responseMimeType", "application/json");
        generation.put("responseSchema", JobExtractionContract.geminiSchema());
        if (thinkingBudget >= 0) {
            generation.put("thinkingConfig", Map.of("thinkingBudget", thinkingBudget));
        }
        return Map.of(
            "systemInstruction", Map.of("parts", List.of(Map.of("text", JobExtractionContract.INSTRUCTIONS))),
            "contents", List.of(
                Map.of("role", "user", "parts", List.of(Map.of("text", JobExtractionContract.userMessage(text))))
            ),
            "generationConfig", generation
        );
    }

    @Override
    String content(JsonNode response) {
        var parts = response.path("candidates").path(0).path("content").path("parts");
        var text = new StringBuilder();
        for (var part : parts) {
            if (part.path("text").isString() && !part.path("thought").asBoolean(false)) {
                text.append(part.path("text").stringValue());
            }
        }
        return text.isEmpty() ? null : text.toString();
    }
}

package com.maiconamaral.acompanhaai.services;

import java.time.Clock;
import java.time.Duration;
import tools.jackson.databind.json.JsonMapper;

public final class JobExtractors {

    private JobExtractors() {
    }

    public static JobExtractor gemini(
        JsonMapper json,
        Clock clock,
        Duration timeout,
        int maxInput,
        String baseUrl,
        String model,
        String apiKey,
        int thinkingBudget
    ) {
        return new GeminiJobExtractor(json, clock, timeout, maxInput, baseUrl, model, apiKey, thinkingBudget);
    }

    public static JobExtractor ollama(
        JsonMapper json,
        Clock clock,
        Duration timeout,
        int maxInput,
        String baseUrl,
        String model
    ) {
        return new OllamaJobExtractor(json, clock, timeout, maxInput, baseUrl, model);
    }
}

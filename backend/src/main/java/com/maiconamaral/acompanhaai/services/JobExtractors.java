package com.maiconamaral.acompanhaai.services;

import com.maiconamaral.acompanhaai.services.HttpJobExtractor.ExtractionLimits;
import java.time.Clock;
import java.time.Duration;
import tools.jackson.databind.json.JsonMapper;

public final class JobExtractors {

    private JobExtractors() {
    }

    public static JobExtractor gemini(
        JsonMapper json,
        Clock clock,
        Limits limits,
        String baseUrl,
        String model,
        String apiKey,
        String thinkingLevel
    ) {
        return new GeminiJobExtractor(json, clock, limits.http(), baseUrl, model, apiKey, thinkingLevel);
    }

    public static JobExtractor ollama(JsonMapper json, Clock clock, Limits limits, String baseUrl, String model) {
        return new OllamaJobExtractor(json, clock, limits.http(), baseUrl, model);
    }

    public static JobExtractor guarded(JobExtractor delegate, Clock clock, Guard guard) {
        return new GuardedJobExtractor(delegate, clock, guard, GuardedJobExtractor::sleep);
    }

    public record Limits(Duration timeout, int maxInputChars, int maxOutputTokens) {
        ExtractionLimits http() {
            return new ExtractionLimits(timeout, maxInputChars, maxOutputTokens);
        }
    }

    /**
     * Regras de proteção do provedor: novas tentativas com espera progressiva, limite diário de
     * chamadas e pausa após falhas seguidas.
     */
    public record Guard(
        int maxAttempts,
        Duration retryDelay,
        int dailyLimit,
        int failureThreshold,
        Duration cooldown
    ) {
        public Guard {
            if (maxAttempts < 1 || failureThreshold < 1 || dailyLimit < 0) {
                throw new IllegalArgumentException("Limites da extração devem ser positivos.");
            }
        }
    }
}

package com.maiconamaral.acompanhaai.config;

import com.maiconamaral.acompanhaai.services.JobExtractor;
import com.maiconamaral.acompanhaai.services.JobExtractors;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

@Configuration
public class JobExtractionConfig {

    @Bean
    @ConditionalOnExpression("'${app.extraction.provider:none}'.toLowerCase() != 'none'")
    public JobExtractor jobExtractor(
        JsonMapper json,
        Clock clock,
        @Value("${app.extraction.provider}") String provider,
        @Value("${app.extraction.timeout:30s}") Duration timeout,
        @Value("${app.extraction.max-input-chars:20000}") int maxInput,
        @Value("${app.extraction.max-output-tokens:1024}") int maxOutput,
        @Value("${app.extraction.max-attempts:2}") int maxAttempts,
        @Value("${app.extraction.retry-delay:2s}") Duration retryDelay,
        @Value("${app.extraction.daily-limit:200}") int dailyLimit,
        @Value("${app.extraction.failure-threshold:3}") int failureThreshold,
        @Value("${app.extraction.cooldown:5m}") Duration cooldown,
        @Value("${app.extraction.gemini.base-url:https://generativelanguage.googleapis.com}") String geminiUrl,
        @Value("${app.extraction.gemini.model:gemini-3.5-flash-lite}") String geminiModel,
        @Value("${app.extraction.gemini.api-key:}") String geminiKey,
        @Value("${app.extraction.gemini.thinking-level:minimal}") String thinkingLevel,
        @Value("${app.extraction.ollama.base-url:http://localhost:11434}") String ollamaUrl,
        @Value("${app.extraction.ollama.model:llama3.2}") String ollamaModel
    ) {
        var limits = new JobExtractors.Limits(timeout, maxInput, maxOutput);
        var extractor = switch (provider.strip().toLowerCase(Locale.ROOT)) {
            case "gemini" -> JobExtractors.gemini(
                json, clock, limits, geminiUrl, geminiModel, geminiKey, thinkingLevel
            );
            case "ollama" -> JobExtractors.ollama(json, clock, limits, ollamaUrl, ollamaModel);
            default -> throw new IllegalStateException(
                "Provedor de extração desconhecido. Use none, gemini ou ollama."
            );
        };
        var guard = new JobExtractors.Guard(maxAttempts, retryDelay, dailyLimit, failureThreshold, cooldown);
        return JobExtractors.guarded(extractor, clock, guard);
    }
}

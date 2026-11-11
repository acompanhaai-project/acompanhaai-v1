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
        @Value("${app.extraction.gemini.base-url:https://generativelanguage.googleapis.com}") String geminiUrl,
        @Value("${app.extraction.gemini.model:gemini-2.5-flash-lite}") String geminiModel,
        @Value("${app.extraction.gemini.api-key:}") String geminiKey,
        @Value("${app.extraction.gemini.thinking-budget:0}") int thinkingBudget,
        @Value("${app.extraction.ollama.base-url:http://localhost:11434}") String ollamaUrl,
        @Value("${app.extraction.ollama.model:llama3.2}") String ollamaModel
    ) {
        return switch (provider.strip().toLowerCase(Locale.ROOT)) {
            case "gemini" -> JobExtractors.gemini(
                json, clock, timeout, maxInput, geminiUrl, geminiModel, geminiKey, thinkingBudget
            );
            case "ollama" -> JobExtractors.ollama(json, clock, timeout, maxInput, ollamaUrl, ollamaModel);
            default -> throw new IllegalStateException(
                "Provedor de extração desconhecido. Use none, gemini ou ollama."
            );
        };
    }
}

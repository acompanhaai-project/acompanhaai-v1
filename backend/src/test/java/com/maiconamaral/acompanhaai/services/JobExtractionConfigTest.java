package com.maiconamaral.acompanhaai.services;

import static org.assertj.core.api.Assertions.assertThat;

import com.maiconamaral.acompanhaai.config.JobExtractionConfig;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.json.JsonMapper;

class JobExtractionConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(JobExtractionConfig.class)
        .withBean(JsonMapper.class, () -> JsonMapper.builder().build())
        .withBean(Clock.class, Clock::systemUTC)
        .withInitializer(context ->
            context.getBeanFactory().setConversionService(ApplicationConversionService.getSharedInstance())
        );

    @Test
    void disablesExtractionByDefault() {
        runner.run(context -> assertThat(context).doesNotHaveBean(JobExtractor.class));
        runner
            .withPropertyValues("app.extraction.provider=none")
            .run(context -> assertThat(context).doesNotHaveBean(JobExtractor.class));
    }

    @Test
    void selectsTheProviderByConfiguration() {
        runner
            .withPropertyValues("app.extraction.provider=ollama", "app.extraction.ollama.model=qwen2.5")
            .run(context -> {
                assertThat(context.getBean(JobExtractor.class).provider()).isEqualTo("ollama");
                assertThat(context.getBean(JobExtractor.class)).isInstanceOf(GuardedJobExtractor.class);
                assertThat(context.getBean(JobExtractor.class).model()).isEqualTo("qwen2.5");
            });
        runner
            .withPropertyValues("app.extraction.provider=GEMINI", "app.extraction.gemini.api-key=chave-ficticia")
            .run(context -> {
                assertThat(context.getBean(JobExtractor.class).provider()).isEqualTo("gemini");
                assertThat(context.getBean(JobExtractor.class).model()).isEqualTo("gemini-2.5-flash-lite");
            });
    }

    @Test
    void failsFastWithoutKeyOrWithUnknownProvider() {
        runner
            .withPropertyValues("app.extraction.provider=gemini")
            .run(context ->
                assertThat(context)
                    .getFailure()
                    .rootCause()
                    .hasMessage("Defina GEMINI_API_KEY para usar o provedor Gemini.")
            );
        runner
            .withPropertyValues("app.extraction.provider=outro")
            .run(context ->
                assertThat(context)
                    .getFailure()
                    .rootCause()
                    .hasMessage("Provedor de extração desconhecido. Use none, gemini ou ollama.")
            );
    }
}

package com.maiconamaral.acompanhaai.services;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.json.JsonMapper;

/**
 * Avaliação com o provedor real. Não roda por padrão nem no CI; consome a cota do provedor.
 * Uso: {@code ./mvnw test -Dtest=ExtractionEvaluationLiveTest -Dextraction.eval=true
 * -Dapp.extraction.provider=gemini}
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfSystemProperty(named = "extraction.eval", matches = "true")
class ExtractionEvaluationLiveTest {

    @Autowired
    private Optional<JobExtractor> extractor;

    @Autowired
    private JsonMapper json;

    @Test
    void evaluatesTheConfiguredProvider() throws Exception {
        assertThat(extractor).as("Defina -Dapp.extraction.provider=gemini ou ollama").isPresent();
        var provider = extractor.get();
        var pause = Duration.parse("PT" + System.getProperty("extraction.eval.pause", "4S"));
        var dataset = ExtractionEvaluation.load(json);
        var calls = ExtractionEvaluation.using(provider);

        var report = ExtractionEvaluation.run(dataset, evaluated -> {
            try {
                Thread.sleep(pause);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return calls.apply(evaluated);
        });

        var markdown = ExtractionEvaluation.markdown(report, provider.provider(), provider.model());
        var name = "report-" + dataset.version() + "-" + provider.provider() + ".md";
        var output = Path.of("target", "extraction-eval", name);
        Files.createDirectories(output.getParent());
        Files.writeString(output, markdown, StandardCharsets.UTF_8);
        System.out.println(markdown);
        System.out.println("Relatório: " + output.toAbsolutePath());

        assertThat(report.salary().invented()).as("salários inventados ou errados").isZero();
        assertThat(report.fields().get("title").accuracy()).as("acerto de título").isGreaterThanOrEqualTo(0.9);
        assertThat(report.fields().get("companyName").accuracy()).as("acerto de empresa").isGreaterThanOrEqualTo(0.9);
    }
}

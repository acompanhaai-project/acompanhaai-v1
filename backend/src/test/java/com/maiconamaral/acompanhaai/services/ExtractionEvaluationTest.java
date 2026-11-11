package com.maiconamaral.acompanhaai.services;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ExtractionEvaluationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final List<String> MODE_WORDS = List.of("remot", "híbrid", "hybrid", "presencial", "onsite");
    private static ExtractionEvaluation.Dataset dataset;

    @BeforeAll
    static void load() throws Exception {
        dataset = ExtractionEvaluation.load(JSON);
    }

    @Test
    void datasetIsVersionedAndCoversTheRequiredScenarios() {
        assertThat(dataset.version()).isEqualTo("v1");
        assertThat(dataset.cases()).hasSizeGreaterThanOrEqualTo(20);
        assertThat(new HashSet<>(dataset.cases().stream().map(ExtractionEvaluation.Case::id).toList()))
            .hasSameSizeAs(dataset.cases());

        var salaries = dataset.cases().stream().filter(c -> c.expected().containsKey("salary")).count();
        var withoutSalary = dataset.cases().stream()
            .filter(c -> !c.expected().containsKey("salary") && c.description().matches("(?s).*(R\\$|USD|EUR).*"))
            .count();
        var english = dataset.cases().stream()
            .filter(c -> c.description().matches("(?s).*\\b(Company|Required|Salary|Compensation)\\b.*"))
            .count();
        assertThat(salaries).isGreaterThanOrEqualTo(5);
        assertThat(withoutSalary).isGreaterThanOrEqualTo(5);
        assertThat(english).isGreaterThanOrEqualTo(3);
    }

    @Test
    void expectedValuesAreWrittenInTheDescription() {
        for (var evaluated : dataset.cases()) {
            var text = evaluated.description().toLowerCase(Locale.ROOT);
            var expected = evaluated.expected();
            for (var field : List.of("title", "companyName", "location")) {
                if (expected.get(field) instanceof String value) {
                    assertThat(text).as(evaluated.id() + " " + field).contains(value.toLowerCase(Locale.ROOT));
                }
            }
            if (expected.get("salary") instanceof BigDecimal amount) {
                assertThat(JobExtractionContract.mentions(evaluated.description(), amount)).as(evaluated.id()).isTrue();
                assertThat(expected).containsEntry("salaryCurrency", "BRL").containsKey("salaryPeriod");
            }
            for (var field : List.of("hardSkills", "softSkills")) {
                for (var skill : (List<?>) expected.get(field)) {
                    assertThat(text)
                        .as(evaluated.id() + " " + skill)
                        .contains(String.valueOf(skill).toLowerCase(Locale.ROOT));
                }
            }
        }
    }

    @Test
    void responsesThatMatchTheAnswerKeyReachTheTargetsThroughTheContract() {
        var report = ExtractionEvaluation.run(dataset, ExtractionEvaluationTest::oracle);

        assertThat(report.failures()).isZero();
        report.fields().forEach((field, score) -> {
            assertThat(score.accuracy()).as(field).isEqualTo(1.0);
            assertThat(score.unexpected()).as(field).isZero();
        });
        assertThat(report.salary().invented()).isZero();
        assertThat(report.salary().missed()).isZero();
        assertThat(report.skills().precision()).isEqualTo(1.0);
        assertThat(report.skills().recall()).isEqualTo(1.0);
        assertThat(report.meetsTargets()).isTrue();
    }

    @Test
    void detectsInventedSalariesAndMissingFields() {
        Function<ExtractionEvaluation.Case, Map<String, Object>> careless = evaluated -> {
            var proposal = new LinkedHashMap<>(oracle(evaluated));
            if (evaluated.id().equals("faixa-salarial")) {
                proposal.put("salary", new BigDecimal("5500"));
                proposal.put("salaryCurrency", "BRL");
                proposal.put("salaryPeriod", "MONTH");
            }
            if (evaluated.id().equals("agencia-recrutadora")) {
                proposal.put("companyName", "Talento Certo RH");
            }
            if (evaluated.id().equals("anuncio-minimo")) {
                throw new JobExtractionException(JobExtractionException.Reason.RATE_LIMITED);
            }
            return proposal;
        };

        var report = ExtractionEvaluation.run(dataset, careless);
        var markdown = ExtractionEvaluation.markdown(report, "teste", "modelo");

        assertThat(report.salary().invented()).isOne();
        var company = report.fields().get("companyName");
        assertThat(company.correct()).isEqualTo(company.expected() - 1);
        assertThat(report.failures()).isOne();
        assertThat(report.meetsTargets()).isFalse();
        assertThat(markdown)
            .contains("Metas: não atingidas")
            .contains("**1 inventados ou errados** (meta: 0)")
            .contains("| agencia-recrutadora | ok | erro |")
            .contains("RATE_LIMITED");
    }

    /** Resposta que um modelo correto daria, aplicada pelas mesmas regras da extração real. */
    private static Map<String, Object> oracle(ExtractionEvaluation.Case evaluated) {
        var expected = evaluated.expected();
        var response = new LinkedHashMap<String, Object>();
        var evidence = new LinkedHashMap<String, Object>();
        for (var field : List.of("title", "companyName", "location", "workMode")) {
            response.put(field, expected.get(field));
        }
        if (expected.get("workMode") != null) {
            evidence.put("workMode", modeSentence(evaluated.description()));
        }
        response.put("salary", expected.get("salary"));
        response.put("salaryCurrency", expected.get("salaryCurrency"));
        response.put("salaryPeriod", expected.get("salaryPeriod"));
        response.put("hardSkills", expected.get("hardSkills"));
        response.put("softSkills", expected.get("softSkills"));
        response.put("evidence", evidence);
        return JobExtractionContract.validate(JSON.valueToTree(response), evaluated.description());
    }

    private static String modeSentence(String description) {
        for (var line : description.split("\\R")) {
            for (var sentence : line.split("(?<=[.:])\\s+")) {
                var lower = sentence.toLowerCase(Locale.ROOT);
                if (MODE_WORDS.stream().anyMatch(lower::contains)) {
                    return sentence.strip();
                }
            }
        }
        return null;
    }
}

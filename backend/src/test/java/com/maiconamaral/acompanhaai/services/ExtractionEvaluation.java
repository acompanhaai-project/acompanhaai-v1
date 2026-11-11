package com.maiconamaral.acompanhaai.services;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Compara a extração com o gabarito do conjunto de avaliação e calcula as métricas por campo. */
final class ExtractionEvaluation {

    static final String DATASET = "/extraction-eval/v1/cases.json";
    static final List<String> TEXT_FIELDS = List.of("title", "companyName", "location", "workMode");
    static final double MIN_TITLE_COMPANY = 0.9;

    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final Pattern EDGE_PUNCTUATION = Pattern.compile("^[\\p{Punct}\\s]+|[\\p{Punct}\\s]+$");

    private ExtractionEvaluation() {
    }

    record Case(String id, String note, String description, Map<String, Object> expected) {}

    record Dataset(String version, List<Case> cases) {}

    record Outcome(Case evaluated, Map<String, Object> actual, String failure) {}

    record FieldScore(int correct, int expected, int unexpected) {
        double accuracy() {
            return expected == 0 ? 1 : (double) correct / expected;
        }
    }

    record SalaryScore(int correct, int missed, int invented, int expected) {}

    record SkillScore(int found, int extracted, int expected) {
        double precision() {
            return extracted == 0 ? 1 : (double) found / extracted;
        }

        double recall() {
            return expected == 0 ? 1 : (double) found / expected;
        }
    }

    record Report(
        String version,
        List<Outcome> outcomes,
        Map<String, FieldScore> fields,
        SalaryScore salary,
        SkillScore skills,
        int failures
    ) {
        boolean meetsTargets() {
            return salary.invented() == 0 &&
                fields.get("title").accuracy() >= MIN_TITLE_COMPANY &&
                fields.get("companyName").accuracy() >= MIN_TITLE_COMPANY;
        }
    }

    static Dataset load(JsonMapper json) throws IOException {
        try (InputStream input = ExtractionEvaluation.class.getResourceAsStream(DATASET)) {
            var root = json.readTree(Objects.requireNonNull(input, DATASET));
            var cases = new ArrayList<Case>();
            for (var node : root.path("cases")) {
                cases.add(new Case(
                    node.path("id").stringValue(),
                    node.path("note").stringValue(),
                    node.path("description").stringValue(),
                    expected(node.path("expected"))
                ));
            }
            return new Dataset(root.path("version").stringValue(), List.copyOf(cases));
        }
    }

    static Report run(Dataset dataset, Function<Case, Map<String, Object>> extractor) {
        var outcomes = new ArrayList<Outcome>();
        for (var evaluated : dataset.cases()) {
            try {
                outcomes.add(new Outcome(evaluated, extractor.apply(evaluated), null));
            } catch (JobExtractionException e) {
                outcomes.add(new Outcome(evaluated, Map.of(), e.getReason().name()));
            }
        }
        return score(dataset.version(), outcomes);
    }

    static Function<Case, Map<String, Object>> using(JobExtractor extractor) {
        return evaluated -> extractor.extract(evaluated.description(), Instant.now().plusSeconds(120));
    }

    static Report score(String version, List<Outcome> outcomes) {
        var fields = new LinkedHashMap<String, FieldScore>();
        for (var field : TEXT_FIELDS) {
            int correct = 0;
            int expected = 0;
            int unexpected = 0;
            for (var outcome : outcomes) {
                var want = normalize(outcome.evaluated().expected().get(field));
                var got = normalize(outcome.actual().get(field));
                if (want != null) {
                    expected++;
                    correct += want.equals(got) ? 1 : 0;
                } else if (got != null) {
                    unexpected++;
                }
            }
            fields.put(field, new FieldScore(correct, expected, unexpected));
        }

        int salaryCorrect = 0;
        int missed = 0;
        int invented = 0;
        int salaryExpected = 0;
        int found = 0;
        int extracted = 0;
        int skillsExpected = 0;
        int failures = 0;
        for (var outcome : outcomes) {
            failures += outcome.failure() == null ? 0 : 1;
            var want = salary(outcome.evaluated().expected());
            var got = salary(outcome.actual());
            if (want != null) {
                salaryExpected++;
            }
            if (got == null) {
                missed += want == null ? 0 : 1;
                salaryCorrect += want == null ? 1 : 0;
            } else if (got.equals(want)) {
                salaryCorrect++;
            } else {
                invented++;
            }
            var wantSkills = skills(outcome.evaluated().expected());
            var gotSkills = skills(outcome.actual());
            skillsExpected += wantSkills.size();
            extracted += gotSkills.size();
            found += (int) gotSkills.stream().filter(wantSkills::contains).count();
        }
        return new Report(
            version,
            List.copyOf(outcomes),
            Map.copyOf(fields),
            new SalaryScore(salaryCorrect, missed, invented, salaryExpected),
            new SkillScore(found, extracted, skillsExpected),
            failures
        );
    }

    static String markdown(Report report, String provider, String model) {
        var out = new StringBuilder();
        out.append("# Avaliação da extração — conjunto ").append(report.version()).append("\n\n");
        out.append("- Provedor: ").append(provider).append(" (").append(model).append(")\n");
        out.append("- Data: ").append(Instant.now()).append("\n");
        out.append("- Casos: ").append(report.outcomes().size()).append(", falhas do provedor: ");
        out.append(report.failures()).append("\n");
        out.append("- Metas: ").append(report.meetsTargets() ? "atingidas" : "não atingidas").append("\n\n");
        out.append("| Campo | Acertos | Casos com valor | Valor indevido |\n| --- | --- | --- | --- |\n");
        for (var field : TEXT_FIELDS) {
            var score = report.fields().get(field);
            out.append("| ").append(field).append(" | ").append(score.correct()).append(" (");
            out.append(percent(score.accuracy())).append(") | ").append(score.expected()).append(" | ");
            out.append(score.unexpected()).append(" |\n");
        }
        var salary = report.salary();
        out.append("\nSalário: ").append(salary.correct()).append(" corretos, ").append(salary.missed());
        out.append(" ausentes e **").append(salary.invented()).append(" inventados ou errados** (meta: 0).\n");
        var skills = report.skills();
        out.append("Habilidades: precisão ").append(percent(skills.precision())).append(", cobertura ");
        out.append(percent(skills.recall())).append(".\n\n");
        out.append("| Caso | Título | Empresa | Local | Modalidade | Salário | Falha |\n");
        out.append("| --- | --- | --- | --- | --- | --- | --- |\n");
        for (var outcome : report.outcomes()) {
            var expected = outcome.evaluated().expected();
            var actual = outcome.actual();
            out.append("| ").append(outcome.evaluated().id());
            for (var field : TEXT_FIELDS) {
                out.append(" | ").append(mark(normalize(expected.get(field)), normalize(actual.get(field))));
            }
            out.append(" | ").append(mark(salary(expected), salary(actual)));
            out.append(" | ").append(outcome.failure() == null ? "" : outcome.failure()).append(" |\n");
        }
        return out.toString();
    }

    private static String mark(Object expected, Object actual) {
        if (Objects.equals(expected, actual)) {
            return expected == null ? "—" : "ok";
        }
        return actual == null ? "ausente" : "erro";
    }

    private static String percent(double value) {
        return String.format(Locale.ROOT, "%.0f%%", value * 100);
    }

    private static Map<String, Object> expected(JsonNode node) {
        var expected = new LinkedHashMap<String, Object>();
        for (var field : TEXT_FIELDS) {
            if (node.path(field).isString()) {
                expected.put(field, node.path(field).stringValue());
            }
        }
        if (node.path("salary").isNumber()) {
            expected.put("salary", node.path("salary").decimalValue());
            expected.put("salaryCurrency", node.path("salaryCurrency").stringValue());
            expected.put("salaryPeriod", node.path("salaryPeriod").stringValue());
        }
        for (var field : List.of("hardSkills", "softSkills")) {
            var skills = new ArrayList<String>();
            node.path(field).forEach(skill -> skills.add(skill.stringValue()));
            expected.put(field, List.copyOf(skills));
        }
        return expected;
    }

    static String normalize(Object value) {
        if (!(value instanceof String text)) {
            return null;
        }
        var normalized = EDGE_PUNCTUATION.matcher(SPACES.matcher(text).replaceAll(" ")).replaceAll("");
        return normalized.isEmpty() ? null : normalized.toLowerCase(Locale.ROOT);
    }

    private static String salary(Map<String, Object> values) {
        if (!(values.get("salary") instanceof BigDecimal amount)) {
            return null;
        }
        return amount.stripTrailingZeros().toPlainString() + " " + values.get("salaryCurrency") + "/" +
            values.get("salaryPeriod");
    }

    private static Set<String> skills(Map<String, Object> values) {
        var skills = new LinkedHashSet<String>();
        for (var field : List.of("hardSkills", "softSkills")) {
            if (values.get(field) instanceof List<?> list) {
                list.forEach(skill -> skills.add(String.valueOf(skill).toLowerCase(Locale.ROOT)));
            }
        }
        return skills;
    }
}

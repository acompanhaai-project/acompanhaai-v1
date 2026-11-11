package com.maiconamaral.acompanhaai.services;

import com.maiconamaral.acompanhaai.entities.enums.ImportInputType;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class JobImportProcessor implements ImportTaskProcessor {

    private static final int MIN_PAGE_TEXT = 200;
    private static final Logger log = LoggerFactory.getLogger(JobImportProcessor.class);

    private final ImportPageCollector collector;
    private final Optional<JobExtractor> extractor;

    public JobImportProcessor(ImportPageCollector collector, Optional<JobExtractor> extractor) {
        this.collector = collector;
        this.extractor = extractor;
    }

    @Override
    public Outcome process(ImportInputType inputType, String input, Instant deadline) {
        if (inputType == ImportInputType.TEXT) {
            var description = ImportPageReader.clean(input);
            return description.isEmpty()
                ? new Failed(Failure.PROCESSING_FAILED)
                : ready(null, Map.of("description", description), false, description, deadline);
        }

        return switch (collector.collect(input, deadline)) {
            case ImportPageCollector.Refused refused -> refused.outcome();
            case ImportPageCollector.Page page when page.html() -> fromHtml(page.content(), deadline);
            case ImportPageCollector.Page page -> fromText(ImportPageReader.clean(page.content()), deadline);
        };
    }

    private Outcome fromHtml(String html, Instant deadline) {
        var content = ImportPageReader.read(html);
        if (!content.jobPosting().isEmpty()) {
            var posting = content.jobPosting();
            var source = posting.get("description") instanceof String description
                ? description + "\n\n" + content.text()
                : content.text();
            return ready(content.text(), posting, true, source, deadline);
        }
        if (content.text().length() >= MIN_PAGE_TEXT) {
            return fromText(content.text(), deadline);
        }
        if (content.passwordField()) {
            return new NeedsInput(Fallback.SOURCE_REQUIRES_LOGIN);
        }
        return new NeedsInput(
            content.scripted() ? Fallback.SOURCE_REQUIRES_JAVASCRIPT : Fallback.SOURCE_NO_USABLE_CONTENT
        );
    }

    private Outcome fromText(String text, Instant deadline) {
        return text.length() < MIN_PAGE_TEXT
            ? new NeedsInput(Fallback.SOURCE_NO_USABLE_CONTENT)
            : ready(text, Map.of("description", text), false, text, deadline);
    }

    /**
     * Completa a proposta com a extração, quando houver provedor configurado. Dados estruturados
     * publicados pela página têm prioridade, e uma falha do provedor mantém a proposta sem IA.
     */
    private Outcome ready(
        String collected,
        Map<String, Object> base,
        boolean structured,
        String source,
        Instant deadline
    ) {
        var evidence = new LinkedHashMap<String, Object>();
        if (structured) {
            for (var field : JobExtractionContract.EVIDENCE_FIELDS) {
                if (base.containsKey(field)) {
                    evidence.put(field, JobExtractionContract.fromStructuredData());
                }
            }
        }
        if (extractor.isEmpty() || source.isBlank()) {
            return new Ready(collected, withEvidence(new LinkedHashMap<>(base), evidence));
        }
        var provider = extractor.get();
        long started = System.nanoTime();
        try {
            var extracted = new LinkedHashMap<>(provider.extract(source, deadline));
            var found = extracted.remove(JobExtractionContract.EVIDENCE) instanceof Map<?, ?> map ? map : Map.of();
            extracted.keySet().removeAll(base.keySet());
            found.forEach((field, excerpt) -> {
                if (extracted.containsKey(field)) {
                    evidence.putIfAbsent(String.valueOf(field), excerpt);
                }
            });
            log.info(
                "extracao provedor={} campos={} duracaoMs={}",
                provider.provider(),
                extracted.size(),
                (System.nanoTime() - started) / 1_000_000
            );
            var proposal = new LinkedHashMap<String, Object>(base);
            proposal.putAll(extracted);
            return new Ready(collected, withEvidence(proposal, evidence), provider.provider(), provider.model());
        } catch (JobExtractionException e) {
            log.info(
                "extracao provedor={} falha={} duracaoMs={}",
                provider.provider(),
                e.getReason(),
                (System.nanoTime() - started) / 1_000_000
            );
            return new Ready(collected, withEvidence(new LinkedHashMap<>(base), evidence));
        }
    }

    private static Map<String, Object> withEvidence(Map<String, Object> proposal, Map<String, Object> evidence) {
        if (!evidence.isEmpty()) {
            proposal.put(JobExtractionContract.EVIDENCE, Map.copyOf(evidence));
        }
        return proposal;
    }
}

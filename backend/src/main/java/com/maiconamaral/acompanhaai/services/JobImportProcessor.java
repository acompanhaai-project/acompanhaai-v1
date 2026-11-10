package com.maiconamaral.acompanhaai.services;

import com.maiconamaral.acompanhaai.entities.enums.ImportInputType;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class JobImportProcessor implements ImportTaskProcessor {

    private static final int MIN_PAGE_TEXT = 200;

    private final ImportPageCollector collector;

    public JobImportProcessor(ImportPageCollector collector) {
        this.collector = collector;
    }

    @Override
    public Outcome process(ImportInputType inputType, String input, Instant deadline) {
        if (inputType == ImportInputType.TEXT) {
            var description = ImportPageReader.clean(input);
            return description.isEmpty()
                ? new Failed(Failure.PROCESSING_FAILED)
                : new Ready(null, Map.of("description", description));
        }

        return switch (collector.collect(input, deadline)) {
            case ImportPageCollector.Refused refused -> refused.outcome();
            case ImportPageCollector.Page page when page.html() -> fromHtml(page.content());
            case ImportPageCollector.Page page -> fromText(ImportPageReader.clean(page.content()));
        };
    }

    private Outcome fromHtml(String html) {
        var content = ImportPageReader.read(html);
        if (!content.jobPosting().isEmpty()) {
            return new Ready(content.text(), content.jobPosting());
        }
        if (content.text().length() >= MIN_PAGE_TEXT) {
            return fromText(content.text());
        }
        if (content.passwordField()) {
            return new NeedsInput(Fallback.SOURCE_REQUIRES_LOGIN);
        }
        return new NeedsInput(
            content.scripted() ? Fallback.SOURCE_REQUIRES_JAVASCRIPT : Fallback.SOURCE_NO_USABLE_CONTENT
        );
    }

    private Outcome fromText(String text) {
        return text.length() < MIN_PAGE_TEXT
            ? new NeedsInput(Fallback.SOURCE_NO_USABLE_CONTENT)
            : new Ready(text, Map.of("description", text));
    }
}

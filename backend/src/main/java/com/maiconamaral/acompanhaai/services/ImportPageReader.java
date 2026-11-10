package com.maiconamaral.acompanhaai.services;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.web.util.HtmlUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

final class ImportPageReader {

    static final int MAX_TEXT = 30000;
    private static final int MAX_FIELD = 255;
    private static final int MAX_DEPTH = 4;
    private static final Set<String> SKIPPED = Set.of("script", "style", "noscript", "template", "svg", "title");
    private static final Set<String> BLOCKS = Set.of(
        "address", "article", "aside", "blockquote", "br", "dd", "div", "dl", "dt", "footer", "form",
        "h1", "h2", "h3", "h4", "h5", "h6", "header", "hr", "li", "main", "nav", "ol", "p", "pre",
        "section", "table", "td", "th", "tr", "ul"
    );
    private static final Set<String> PERIODS = Set.of("HOUR", "DAY", "WEEK", "MONTH", "YEAR");
    private static final Pattern TYPE = Pattern.compile("(?i)(?<![\\w-])type\\s*=\\s*[\"']?([^\"'\\s>]+)");
    private static final Pattern MARKUP = Pattern.compile("</?[a-zA-Z][^>]*>");
    private static final Pattern CONTROLS = Pattern.compile("[\\p{Cntrl}&&[^\\n\\r\\t]]");
    private static final Pattern SPACES = Pattern.compile("[ \\t\\x0B\\f\\u00A0\\u200B]+");
    private static final Pattern LINE_EDGES = Pattern.compile(" ?\\R ?");
    private static final Pattern BLANK_LINES = Pattern.compile("\\n{3,}");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ImportPageReader() {
    }

    static Content read(String html) {
        var text = new StringBuilder();
        var structured = new ArrayList<String>();
        boolean scripted = false;
        boolean passwordField = false;
        int length = html.length();
        int position = 0;
        while (position < length) {
            int open = html.indexOf('<', position);
            if (open < 0) {
                text.append(html, position, length);
                break;
            }
            text.append(html, position, open);
            if (html.startsWith("<!--", open)) {
                int end = html.indexOf("-->", open + 4);
                position = end < 0 ? length : end + 3;
                continue;
            }

            int nameStart = open + 1;
            boolean closing = nameStart < length && html.charAt(nameStart) == '/';
            if (closing) {
                nameStart++;
            }
            int nameEnd = nameStart;
            while (nameEnd < length && Character.isLetterOrDigit(html.charAt(nameEnd))) {
                nameEnd++;
            }
            char marker = nameStart < length ? html.charAt(nameStart) : 0;
            if (nameEnd == nameStart && marker != '!' && marker != '?') {
                text.append('<');
                position = open + 1;
                continue;
            }

            int close = tagEnd(html, nameEnd);
            var name = html.substring(nameStart, nameEnd).toLowerCase(Locale.ROOT);
            var attributes = html.substring(nameEnd, close);
            position = Math.min(close + 1, length);
            if (!closing && SKIPPED.contains(name)) {
                int end = indexOfIgnoreCase(html, "</" + name, position);
                if (name.equals("script")) {
                    scripted = true;
                    var type = TYPE.matcher(attributes);
                    if (type.find() && type.group(1).equalsIgnoreCase("application/ld+json")) {
                        structured.add(html.substring(position, end));
                    }
                }
                position = Math.min(tagEnd(html, end) + 1, length);
            } else if (name.equals("input")) {
                var type = TYPE.matcher(attributes);
                passwordField |= type.find() && type.group(1).equalsIgnoreCase("password");
            }
            if (BLOCKS.contains(name)) {
                text.append('\n');
            } else if (SKIPPED.contains(name)) {
                text.append(' ');
            }
        }
        var visible = clean(HtmlUtils.htmlUnescape(text.toString()));
        return new Content(jobPosting(structured), visible, scripted, passwordField);
    }

    static String clean(String text) {
        var normalized = CONTROLS.matcher(text).replaceAll("");
        normalized = SPACES.matcher(normalized).replaceAll(" ");
        normalized = LINE_EDGES.matcher(normalized).replaceAll("\n");
        normalized = BLANK_LINES.matcher(normalized).replaceAll("\n\n").strip();
        return limit(normalized, MAX_TEXT);
    }

    private static int tagEnd(String html, int from) {
        char quote = 0;
        for (int index = from; index < html.length(); index++) {
            char current = html.charAt(index);
            if (quote != 0) {
                if (current == quote) {
                    quote = 0;
                }
            } else if (current == '"' || current == '\'') {
                quote = current;
            } else if (current == '>') {
                return index;
            }
        }
        return html.length();
    }

    private static int indexOfIgnoreCase(String html, String value, int from) {
        for (int index = from; index <= html.length() - value.length(); index++) {
            if (html.regionMatches(true, index, value, 0, value.length())) {
                return index;
            }
        }
        return html.length();
    }

    private static Map<String, Object> jobPosting(List<String> structured) {
        for (var block : structured) {
            try {
                var posting = findJobPosting(JSON.readTree(block), 0);
                if (posting != null) {
                    var proposal = proposal(posting);
                    if (proposal.containsKey("title") || proposal.containsKey("description")) {
                        return proposal;
                    }
                }
            } catch (JacksonException invalid) {
                // Bloco estruturado inválido não impede a leitura dos demais.
            }
        }
        return Map.of();
    }

    private static JsonNode findJobPosting(JsonNode node, int depth) {
        if (depth > MAX_DEPTH) {
            return null;
        }
        if (node.isArray()) {
            for (var item : node) {
                var found = findJobPosting(item, depth + 1);
                if (found != null) {
                    return found;
                }
            }
            return null;
        }
        if (!node.isObject()) {
            return null;
        }
        if (contains(node.path("@type"), "JobPosting")) {
            return node;
        }
        return findJobPosting(node.path("@graph"), depth + 1);
    }

    private static Map<String, Object> proposal(JsonNode posting) {
        var proposal = new LinkedHashMap<String, Object>();
        put(proposal, "title", text(posting.path("title")));
        var organization = posting.path("hiringOrganization");
        put(proposal, "companyName", text(organization.isObject() ? organization.path("name") : organization));
        put(proposal, "description", description(posting.path("description")));
        put(proposal, "location", limit(location(first(posting.path("jobLocation"))), MAX_FIELD));
        if (contains(posting.path("jobLocationType"), "TELECOMMUTE")) {
            proposal.put("workMode", "REMOTE");
        }
        salary(proposal, posting);
        return proposal;
    }

    private static String description(JsonNode node) {
        if (!node.isString()) {
            return null;
        }
        var description = read(node.stringValue()).text();
        if (MARKUP.matcher(description).find()) {
            description = read(description).text();
        }
        return description.isEmpty() ? null : description;
    }

    private static String location(JsonNode place) {
        var address = place.path("address");
        if (!address.isObject()) {
            return text(address);
        }
        var parts = new ArrayList<String>();
        for (var field : List.of("addressLocality", "addressRegion")) {
            var part = text(address.path(field));
            if (part != null) {
                parts.add(part);
            }
        }
        if (parts.isEmpty()) {
            var country = address.path("addressCountry");
            return text(country.isObject() ? country.path("name") : country);
        }
        return String.join(", ", parts);
    }

    private static void salary(Map<String, Object> proposal, JsonNode posting) {
        var base = posting.path("baseSalary");
        var value = base.path("value");
        var currency = text(base.path("currency"));
        var period = text(value.path("unitText"));
        var amount = amount(value.isObject() ? value.path("value") : value);
        if (amount == null && value.isObject()) {
            var minimum = amount(value.path("minValue"));
            var maximum = amount(value.path("maxValue"));
            amount = minimum != null && maximum != null && minimum.compareTo(maximum) == 0 ? minimum : null;
        }
        if (amount == null || !"BRL".equalsIgnoreCase(currency) || period == null) {
            return;
        }
        period = period.toUpperCase(Locale.ROOT);
        if (PERIODS.contains(period)) {
            proposal.put("salary", amount);
            proposal.put("salaryCurrency", "BRL");
            proposal.put("salaryPeriod", period);
        }
    }

    private static BigDecimal amount(JsonNode node) {
        BigDecimal amount;
        if (node.isNumber()) {
            amount = node.decimalValue();
        } else if (node.isString()) {
            try {
                amount = new BigDecimal(node.stringValue().strip());
            } catch (NumberFormatException invalid) {
                return null;
            }
        } else {
            return null;
        }
        amount = amount.stripTrailingZeros();
        if (amount.signum() <= 0 || amount.scale() > 2 || amount.precision() - amount.scale() > 13) {
            return null;
        }
        return amount.scale() < 0 ? amount.setScale(0) : amount;
    }

    private static JsonNode first(JsonNode node) {
        return node.isArray() ? node.path(0) : node;
    }

    private static boolean contains(JsonNode node, String expected) {
        if (node.isArray()) {
            for (var item : node) {
                if (contains(item, expected)) {
                    return true;
                }
            }
            return false;
        }
        return node.isString() && node.stringValue().strip().equalsIgnoreCase(expected);
    }

    private static String text(JsonNode node) {
        if (!node.isString()) {
            return null;
        }
        var value = limit(clean(node.stringValue()).replace('\n', ' '), MAX_FIELD);
        return value.isEmpty() ? null : value;
    }

    private static String limit(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        int end = Character.isHighSurrogate(value.charAt(max - 1)) ? max - 1 : max;
        return value.substring(0, end).strip();
    }

    private static void put(Map<String, Object> proposal, String field, Object value) {
        if (value != null) {
            proposal.put(field, value);
        }
    }

    record Content(Map<String, Object> jobPosting, String text, boolean scripted, boolean passwordField) {
    }
}

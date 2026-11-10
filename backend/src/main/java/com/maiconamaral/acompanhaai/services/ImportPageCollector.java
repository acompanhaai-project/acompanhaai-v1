package com.maiconamaral.acompanhaai.services;

import com.maiconamaral.acompanhaai.services.ImportTaskProcessor.Failed;
import com.maiconamaral.acompanhaai.services.ImportTaskProcessor.Failure;
import com.maiconamaral.acompanhaai.services.ImportTaskProcessor.Fallback;
import com.maiconamaral.acompanhaai.services.ImportTaskProcessor.NeedsInput;
import com.maiconamaral.acompanhaai.services.ImportTaskProcessor.Outcome;
import com.maiconamaral.acompanhaai.services.ImportTaskProcessor.Retry;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import javax.net.ssl.SSLException;
import org.apache.hc.client5.http.ClientProtocolException;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.ManagedHttpClientConnectionFactory;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.MessageConstraintException;
import org.apache.hc.core5.http.config.Http1Config;
import org.apache.hc.core5.io.Closer;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ImportPageCollector implements AutoCloseable {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration LEASE_MARGIN = Duration.ofSeconds(5);
    private static final int MAX_REDIRECTS = 5;
    private static final int MAX_HEADERS = 100;
    private static final int MAX_HEADER_LINE = 16 * 1024;
    private static final Set<String> TEXT_TYPES = Set.of("text/html", "application/xhtml+xml", "text/plain");
    private static final Pattern LOGIN_PATH = Pattern.compile(
        "(?i)(^|[/._-])(login|signin|sign-in|sign_in|authwall|sso|entrar)([/._-]|$)"
    );
    private static final Pattern LOGIN_HOST = Pattern.compile("(?i)^(login|signin|sso|auth|accounts?)\\.");
    private static final Pattern META_CHARSET = Pattern.compile(
        "(?i)<meta[^>]{0,200}charset\\s*=\\s*[\"']?([a-z0-9_-]{1,40})"
    );

    private final Duration timeout;
    private final int maxBytes;
    private final Clock clock;
    private final boolean anyPort;
    private final CloseableHttpClient client;
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(task -> {
        var thread = new Thread(task, "import-collector-timeout");
        thread.setDaemon(true);
        return thread;
    });

    @Autowired
    public ImportPageCollector(
        @Value("${app.import-collector.timeout:20s}") Duration timeout,
        @Value("${app.import-collector.max-bytes:2097152}") int maxBytes,
        Clock clock
    ) {
        this(timeout, maxBytes, clock, new ImportAddressPolicy(), false);
    }

    ImportPageCollector(Duration timeout, int maxBytes, Clock clock, DnsResolver resolver, boolean anyPort) {
        this.timeout = timeout;
        this.maxBytes = maxBytes;
        this.clock = clock;
        this.anyPort = anyPort;
        var limits = Http1Config.custom().setMaxLineLength(MAX_HEADER_LINE).setMaxHeaderCount(MAX_HEADERS).build();
        var connections = PoolingHttpClientConnectionManagerBuilder.create()
            .setDnsResolver(resolver)
            .setConnectionFactory(ManagedHttpClientConnectionFactory.builder().http1Config(limits).build())
            .setDefaultConnectionConfig(ConnectionConfig.custom()
                .setConnectTimeout(Timeout.of(CONNECT_TIMEOUT))
                .setSocketTimeout(Timeout.of(READ_TIMEOUT))
                .build())
            .build();
        this.client = HttpClients.custom()
            .setConnectionManager(connections)
            .setDefaultRequestConfig(RequestConfig.custom().setResponseTimeout(Timeout.of(READ_TIMEOUT)).build())
            .setUserAgent("AcompanhaaiImporter/1.0")
            .setConnectionReuseStrategy((request, response, context) -> false)
            .disableRedirectHandling()
            .disableAutomaticRetries()
            .disableCookieManagement()
            .disableAuthCaching()
            .disableConnectionState()
            .build();
    }

    public Result collect(String url, Instant deadline) {
        var now = clock.instant();
        var limit = now.plus(timeout);
        if (deadline.minus(LEASE_MARGIN).isBefore(limit)) {
            limit = deadline.minus(LEASE_MARGIN);
        }

        URI target;
        try {
            target = target(new URI(url.strip()));
        } catch (URISyntaxException invalid) {
            target = null;
        }
        if (target == null) {
            return refused(new Failed(Failure.SOURCE_NOT_ALLOWED));
        }

        for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
            long remaining = Duration.between(clock.instant(), limit).toMillis();
            if (remaining <= 0) {
                return refused(new Retry());
            }
            var request = new HttpGet(target);
            request.setHeader("Accept", "text/html,application/xhtml+xml;q=0.9,text/plain;q=0.5");
            request.setHeader("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.5");
            var timer = watchdog.schedule(request::cancel, remaining, TimeUnit.MILLISECONDS);
            ClassicHttpResponse response = null;
            try {
                response = client.executeOpen(null, request, null);
                int status = response.getCode();
                if (status >= 200 && status < 300) {
                    return read(response.getEntity(), limit);
                }
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    var location = response.getFirstHeader("Location");
                    if (location == null) {
                        return unavailable(Fallback.SOURCE_NO_USABLE_CONTENT);
                    }
                    target = target(target.resolve(new URI(location.getValue().strip())));
                    if (target == null) {
                        return refused(new Failed(Failure.SOURCE_NOT_ALLOWED));
                    }
                    if (LOGIN_PATH.matcher(target.getRawPath()).find() || LOGIN_HOST.matcher(target.getHost()).find()) {
                        return unavailable(Fallback.SOURCE_REQUIRES_LOGIN);
                    }
                    continue;
                }
                if (status == 401 || status == 407) {
                    return unavailable(Fallback.SOURCE_REQUIRES_LOGIN);
                }
                if (status == 403 || status == 429 || status == 451 || status == 999) {
                    return unavailable(Fallback.SOURCE_BLOCKED);
                }
                if (status == 408 || status == 425 || status >= 500) {
                    return refused(new Retry());
                }
                return unavailable(Fallback.SOURCE_NO_USABLE_CONTENT);
            } catch (ImportAddressPolicy.Blocked e) {
                return refused(new Failed(Failure.SOURCE_NOT_ALLOWED));
            } catch (URISyntaxException | SSLException | ClientProtocolException | MessageConstraintException e) {
                return unavailable(Fallback.SOURCE_NO_USABLE_CONTENT);
            } catch (IOException e) {
                return refused(new Retry());
            } finally {
                timer.cancel(false);
                // Fechar a resposta sem abortar faria o cliente ler o restante do corpo.
                request.cancel();
                Closer.closeQuietly(response);
            }
        }
        return unavailable(Fallback.SOURCE_NO_USABLE_CONTENT);
    }

    @Override
    public void close() throws IOException {
        watchdog.shutdownNow();
        client.close();
    }

    private Result read(HttpEntity entity, Instant limit) throws IOException {
        if (entity == null) {
            return unavailable(Fallback.SOURCE_NO_USABLE_CONTENT);
        }
        var type = ContentType.parseLenient(entity.getContentType());
        var mime = type == null ? "text/html" : type.getMimeType().toLowerCase(Locale.ROOT);
        if (!TEXT_TYPES.contains(mime) || entity.getContentLength() > maxBytes) {
            return unavailable(Fallback.SOURCE_NO_USABLE_CONTENT);
        }

        var body = entity.getContent();
        var buffer = new ByteArrayOutputStream();
        var chunk = new byte[8192];
        int read;
        while ((read = body.read(chunk)) != -1) {
            if (buffer.size() + read > maxBytes) {
                return unavailable(Fallback.SOURCE_NO_USABLE_CONTENT);
            }
            if (!clock.instant().isBefore(limit)) {
                return refused(new Retry());
            }
            buffer.write(chunk, 0, read);
        }

        var bytes = buffer.toByteArray();
        var charset = type == null ? null : type.getCharset();
        if (charset == null) {
            charset = declaredCharset(bytes);
        }
        return new Page(new String(bytes, charset), !mime.equals("text/plain"));
    }

    private Charset declaredCharset(byte[] bytes) {
        var head = new String(bytes, 0, Math.min(bytes.length, 2048), StandardCharsets.ISO_8859_1);
        var declared = META_CHARSET.matcher(head);
        if (declared.find()) {
            try {
                return Charset.forName(declared.group(1));
            } catch (IllegalArgumentException unknown) {
                return StandardCharsets.UTF_8;
            }
        }
        return StandardCharsets.UTF_8;
    }

    private URI target(URI uri) throws URISyntaxException {
        uri = uri.parseServerAuthority();
        var scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        boolean https = scheme.equals("https");
        var host = uri.getHost();
        int port = uri.getPort();
        if (
            (!https && !scheme.equals("http")) ||
            host == null ||
            host.contains("%") ||
            uri.getRawUserInfo() != null ||
            (port != -1 && port != (https ? 443 : 80) && !anyPort)
        ) {
            return null;
        }
        var path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        var query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
        return new URI(scheme + "://" + host + (port == -1 ? "" : ":" + port) + path + query);
    }

    private static Result unavailable(Fallback reason) {
        return refused(new NeedsInput(reason));
    }

    private static Result refused(Outcome outcome) {
        return new Refused(outcome);
    }

    public sealed interface Result permits Page, Refused {
    }

    public record Page(String content, boolean html) implements Result {
    }

    public record Refused(Outcome outcome) implements Result {
    }
}

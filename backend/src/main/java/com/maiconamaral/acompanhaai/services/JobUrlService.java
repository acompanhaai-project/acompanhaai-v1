package com.maiconamaral.acompanhaai.services;

import com.maiconamaral.acompanhaai.services.exceptions.InvalidDataException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import org.springframework.stereotype.Service;

@Service
public class JobUrlService {

    public String normalize(String sourceUrl) {
        if (sourceUrl == null || sourceUrl.isBlank()) {
            return null;
        }
        try {
            URI uri = new URI(sourceUrl.strip()).parseServerAuthority();
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (
                (!scheme.equals("http") && !scheme.equals("https")) ||
                uri.getHost() == null ||
                uri.getRawUserInfo() != null ||
                uri.getPort() == 0 ||
                uri.getPort() > 65535
            ) {
                throw invalidUrl();
            }
            String rawHost = uri.getHost();
            int zone = rawHost.indexOf('%');
            String host =
                zone < 0
                    ? rawHost.toLowerCase(Locale.ROOT)
                    : rawHost.substring(0, zone).toLowerCase(Locale.ROOT) + rawHost.substring(zone);
            int port = uri.getPort();
            boolean defaultPort = (scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443);
            String authority = host + (port == -1 || defaultPort ? "" : ":" + port);
            String path = uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
            String query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
            String fragment = uri.getRawFragment() == null ? "" : "#" + uri.getRawFragment();
            return scheme + "://" + authority + path + query + fragment;
        } catch (URISyntaxException exception) {
            throw invalidUrl();
        }
    }

    public String hash(String normalizedUrl) {
        if (normalizedUrl == null) {
            return null;
        }
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(normalizedUrl.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 indisponível.", exception);
        }
    }

    private InvalidDataException invalidUrl() {
        return new InvalidDataException("Informe uma URL HTTP ou HTTPS válida, com host e sem credenciais embutidas.");
    }
}

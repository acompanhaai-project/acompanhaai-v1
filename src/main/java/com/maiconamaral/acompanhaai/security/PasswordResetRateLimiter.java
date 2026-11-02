package com.maiconamaral.acompanhaai.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

@Component
public class PasswordResetRateLimiter {
    public enum Route { REQUEST, CONFIRM }
    private static final long WINDOW = Duration.ofMinutes(1).toNanos();
    private final int perIp;
    private final int global;
    private final LongSupplier ticker;
    private final Map<Route, Window> windows = new EnumMap<>(Route.class);

    @org.springframework.beans.factory.annotation.Autowired
    public PasswordResetRateLimiter(
            @Value("${app.password-reset.limits.per-ip:10}") int perIp,
            @Value("${app.password-reset.limits.global:100}") int global) {
        this(perIp, global, System::nanoTime);
    }

    PasswordResetRateLimiter(int perIp, int global, LongSupplier ticker) {
        if (perIp < 1 || global < 1) throw new IllegalArgumentException("Limites devem ser positivos.");
        this.perIp = perIp;
        this.global = global;
        this.ticker = ticker;
    }

    // Retorna zero se aceito; caso contrário, segundos até o fim da janela.
    public synchronized long acquire(Route route, String remoteAddress) {
        long now = ticker.getAsLong();
        Window window = windows.get(route);
        if (window == null || now - window.startedAt >= WINDOW) {
            window = new Window(now);
            windows.put(route, window);
        }
        int count = window.byIp.getOrDefault(remoteAddress, 0);
        if (window.total >= global || count >= perIp) {
            return Math.max(1, (WINDOW - (now - window.startedAt) + 999_999_999L) / 1_000_000_000L);
        }
        window.total++;
        window.byIp.put(remoteAddress, count + 1);
        return 0;
    }

    private static class Window {
        final long startedAt;
        int total;
        // Número de chaves limitado pela cota global; descartadas na próxima janela.
        final Map<String, Integer> byIp = new HashMap<>();
        Window(long startedAt) { this.startedAt = startedAt; }
    }
}

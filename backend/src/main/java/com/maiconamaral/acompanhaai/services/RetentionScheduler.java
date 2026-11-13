package com.maiconamaral.acompanhaai.services;

import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
@ConditionalOnProperty(name = "app.retention.scheduling-enabled", matchIfMissing = true)
public class RetentionScheduler {

    private static final Logger log = LoggerFactory.getLogger(RetentionScheduler.class);

    private final DataRetention retention;

    public RetentionScheduler(DataRetention retention) {
        this.retention = retention;
    }

    @Scheduled(fixedDelay = 1, initialDelay = 1, timeUnit = TimeUnit.HOURS)
    void purge() {
        int tokens = retention.purgePasswordResetTokens();
        if (tokens > 0) {
            log.info("retencao tokens_redefinicao_removidos={}", tokens);
        }
    }
}

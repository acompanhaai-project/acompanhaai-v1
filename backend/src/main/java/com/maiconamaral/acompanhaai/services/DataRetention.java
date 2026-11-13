package com.maiconamaral.acompanhaai.services;

import com.maiconamaral.acompanhaai.repositories.PasswordResetTokenRepository;
import java.time.Clock;
import java.time.Duration;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Remoção de dados temporários que não têm mais uso. As importações expiradas são removidas
 * pelo {@link ImportWorker}; aqui ficam os demais registros com prazo.
 */
@Service
public class DataRetention {

    /** Tokens de redefinição ficam 24 horas após o vencimento, para investigar pedidos abusivos. */
    static final Duration RESET_TOKEN_RETENTION = Duration.ofHours(24);

    private final PasswordResetTokenRepository resetTokens;
    private final Clock clock;

    public DataRetention(PasswordResetTokenRepository resetTokens, Clock clock) {
        this.resetTokens = resetTokens;
        this.clock = clock;
    }

    @Transactional
    public int purgePasswordResetTokens() {
        return resetTokens.deleteExpiredBefore(clock.instant().minus(RESET_TOKEN_RETENTION));
    }
}

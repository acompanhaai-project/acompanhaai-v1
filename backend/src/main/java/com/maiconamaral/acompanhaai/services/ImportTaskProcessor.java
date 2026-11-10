package com.maiconamaral.acompanhaai.services;

import com.maiconamaral.acompanhaai.entities.enums.ImportInputType;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public interface ImportTaskProcessor {

    Outcome process(ImportInputType inputType, String input, Instant deadline);

    sealed interface Outcome permits Ready, NeedsInput, Failed, Retry {
    }

    record Ready(String collectedText, Map<String, Object> proposal) implements Outcome {
        public Ready {
            proposal = Map.copyOf(proposal);
            if (proposal.isEmpty()) {
                throw new IllegalArgumentException("A proposta deve conter pelo menos um campo.");
            }
        }
    }

    record NeedsInput(Fallback reason) implements Outcome {
        public NeedsInput {
            Objects.requireNonNull(reason);
        }
    }

    record Failed(Failure reason) implements Outcome {
        public Failed {
            Objects.requireNonNull(reason);
        }
    }

    record Retry() implements Outcome {
    }

    enum Fallback {
        SOURCE_REQUIRES_LOGIN("A página pede login. Cole a descrição para continuar."),
        SOURCE_BLOCKED("A página bloqueou a leitura. Cole a descrição para continuar."),
        SOURCE_REQUIRES_JAVASCRIPT("A página exige JavaScript. Cole a descrição para continuar."),
        SOURCE_NO_USABLE_CONTENT("Não foi possível obter a descrição da página. Cole o texto para continuar.");

        final String message;

        Fallback(String message) {
            this.message = message;
        }
    }

    enum Failure {
        SOURCE_NOT_ALLOWED("A URL não é permitida pela política de segurança."),
        PROCESSING_FAILED("Não foi possível extrair os dados da vaga.");

        final String message;

        Failure(String message) {
            this.message = message;
        }
    }
}

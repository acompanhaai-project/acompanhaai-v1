package com.maiconamaral.acompanhaai.services;

import java.time.Instant;
import java.util.Map;

public interface JobExtractor {

    String provider();

    String model();

    /**
     * Extrai campos da vaga a partir do texto informado. Retorna somente campos validados
     * pelo contrato da proposta; campos sem evidência ficam ausentes.
     */
    Map<String, Object> extract(String text, Instant deadline);
}

package com.maiconamaral.acompanhaai.dto;

import com.maiconamaral.acompanhaai.entities.enums.ImportInputType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ImportTaskInputDTO(
    @NotNull(message = "Informe o tipo de entrada.") ImportInputType inputType,
    @Size(max = 2048, message = "Informe uma URL com até 2048 caracteres.") String url,
    @Size(max = 30000, message = "Informe uma descrição com até 30000 caracteres.") String text
) {}

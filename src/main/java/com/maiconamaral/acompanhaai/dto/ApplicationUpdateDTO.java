package com.maiconamaral.acompanhaai.dto;

import com.maiconamaral.acompanhaai.entities.enums.ApplicationStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;

public record ApplicationUpdateDTO(@NotNull @Valid JobInputDTO job, @NotNull ApplicationStatus status,
        @PastOrPresent LocalDate appliedOn, @Size(max = 5000) String notes,
        @NotNull @PositiveOrZero Long version) { }

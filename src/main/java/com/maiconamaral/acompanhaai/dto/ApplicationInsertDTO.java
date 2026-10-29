package com.maiconamaral.acompanhaai.dto;

import com.maiconamaral.acompanhaai.entities.enums.ApplicationStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;

public record ApplicationInsertDTO(@NotNull @Valid JobInputDTO job, ApplicationStatus status,
        @PastOrPresent LocalDate appliedOn, @Size(max = 5000) String notes) { }

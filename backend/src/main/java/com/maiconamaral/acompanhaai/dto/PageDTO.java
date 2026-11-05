package com.maiconamaral.acompanhaai.dto;

import java.util.List;
import org.springframework.data.domain.Page;

public record PageDTO<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
    public PageDTO(Page<T> page) {
        this(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }
}

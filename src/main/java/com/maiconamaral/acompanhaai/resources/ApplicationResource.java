package com.maiconamaral.acompanhaai.resources;

import com.maiconamaral.acompanhaai.dto.*;
import com.maiconamaral.acompanhaai.entities.enums.ApplicationStatus;
import com.maiconamaral.acompanhaai.security.UserPrincipal;
import com.maiconamaral.acompanhaai.services.ApplicationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import java.util.List;

@RestController
@RequestMapping("/api/applications")
public class ApplicationResource {
    private final ApplicationService service;
    public ApplicationResource(ApplicationService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<ApplicationDTO> insert(@AuthenticationPrincipal UserPrincipal principal,
                                                @Valid @RequestBody ApplicationInsertDTO dto) {
        var result = service.insert(principal.getId(), dto);
        var location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(result.id()).toUri();
        return ResponseEntity.created(location).body(result);
    }

    @GetMapping
    public PageDTO<ApplicationDTO> findAll(@AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) ApplicationStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.findAll(principal.getId(), status, page, size);
    }

    @GetMapping("/{id}")
    public ApplicationDTO findById(@AuthenticationPrincipal UserPrincipal principal, @PathVariable Long id) {
        return service.findById(principal.getId(), id);
    }

    @PutMapping("/{id}")
    public ApplicationDTO update(@AuthenticationPrincipal UserPrincipal principal, @PathVariable Long id,
                                 @Valid @RequestBody ApplicationUpdateDTO dto) {
        return service.update(principal.getId(), id, dto);
    }

    @GetMapping("/{id}/history")
    public List<ApplicationHistoryDTO> history(@AuthenticationPrincipal UserPrincipal principal, @PathVariable Long id) {
        return service.history(principal.getId(), id);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal UserPrincipal principal, @PathVariable Long id) {
        service.delete(principal.getId(), id);
        return ResponseEntity.noContent().build();
    }
}

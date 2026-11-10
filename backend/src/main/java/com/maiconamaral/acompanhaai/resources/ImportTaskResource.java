package com.maiconamaral.acompanhaai.resources;

import com.maiconamaral.acompanhaai.dto.ApplicationDTO;
import com.maiconamaral.acompanhaai.dto.ApplicationInsertDTO;
import com.maiconamaral.acompanhaai.dto.ImportTaskDTO;
import com.maiconamaral.acompanhaai.dto.ImportTaskInputDTO;
import com.maiconamaral.acompanhaai.security.UserPrincipal;
import com.maiconamaral.acompanhaai.services.ImportTaskService;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/import-tasks")
public class ImportTaskResource {

    private final ImportTaskService service;

    public ImportTaskResource(ImportTaskService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ImportTaskDTO> insert(
        @AuthenticationPrincipal UserPrincipal principal,
        @Valid @RequestBody ImportTaskInputDTO dto
    ) {
        var result = service.insert(principal.getId(), dto);
        var location = ServletUriComponentsBuilder.fromCurrentRequest()
            .path("/{id}")
            .buildAndExpand(result.id())
            .toUri();
        return ResponseEntity.accepted().location(location).cacheControl(CacheControl.noStore()).body(result);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ImportTaskDTO> findById(
        @AuthenticationPrincipal UserPrincipal principal,
        @PathVariable Long id
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.findById(principal.getId(), id));
    }

    @PostMapping("/{id}/confirm")
    public ResponseEntity<ApplicationDTO> confirm(
        @AuthenticationPrincipal UserPrincipal principal,
        @PathVariable Long id,
        @Valid @RequestBody ApplicationInsertDTO dto
    ) {
        var confirmation = service.confirm(principal.getId(), id, dto);
        var application = confirmation.application();
        if (!confirmation.created()) {
            return ResponseEntity.ok(application);
        }
        var location = ServletUriComponentsBuilder.fromCurrentContextPath()
            .path("/api/applications/{id}")
            .buildAndExpand(application.id())
            .toUri();
        return ResponseEntity.created(location).body(application);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal UserPrincipal principal, @PathVariable Long id) {
        service.delete(principal.getId(), id);
        return ResponseEntity.noContent().build();
    }
}

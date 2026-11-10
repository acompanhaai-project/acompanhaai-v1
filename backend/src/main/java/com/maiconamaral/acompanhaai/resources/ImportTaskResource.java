package com.maiconamaral.acompanhaai.resources;

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

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal UserPrincipal principal, @PathVariable Long id) {
        service.delete(principal.getId(), id);
        return ResponseEntity.noContent().build();
    }
}

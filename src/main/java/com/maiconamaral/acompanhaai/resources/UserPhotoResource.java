package com.maiconamaral.acompanhaai.resources;

import com.maiconamaral.acompanhaai.security.UserPrincipal;
import com.maiconamaral.acompanhaai.services.UserPhotoService;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/users/me/photo")
public class UserPhotoResource {
    private final UserPhotoService service;
    public UserPhotoResource(UserPhotoService service) { this.service = service; }

    @PutMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Void> replace(@AuthenticationPrincipal UserPrincipal principal,
                                        @RequestParam("file") MultipartFile file) {
        service.replace(principal.getId(), file);
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    public ResponseEntity<byte[]> find(@AuthenticationPrincipal UserPrincipal principal) {
        var photo = service.find(principal.getId());
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(photo.contentType()))
                .cacheControl(CacheControl.noStore()).body(photo.content());
    }

    @DeleteMapping
    public ResponseEntity<Void> delete(@AuthenticationPrincipal UserPrincipal principal) {
        service.delete(principal.getId());
        return ResponseEntity.noContent().build();
    }
}

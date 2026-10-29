package com.maiconamaral.acompanhaai.resources;

import com.maiconamaral.acompanhaai.dto.*;
import com.maiconamaral.acompanhaai.security.UserPrincipal;
import com.maiconamaral.acompanhaai.services.UserService;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.bind.annotation.*;
import java.net.URI;

@RestController
@RequestMapping("/api/users")
public class UserResource {
    private final UserService service;
    private final SecurityContextLogoutHandler logoutHandler;

    public UserResource(UserService service, SecurityContextLogoutHandler logoutHandler) {
        this.service = service;
        this.logoutHandler = logoutHandler;
    }

    @PostMapping
    public ResponseEntity<UserDTO> insert(@Valid @RequestBody UserInsertDTO dto) {
        return ResponseEntity.created(URI.create("/api/users/me")).body(service.insert(dto));
    }

    @GetMapping("/me")
    public UserDTO findMe(@AuthenticationPrincipal UserPrincipal principal) {
        return service.findById(principal.getId());
    }

    @PutMapping("/me")
    public UserDTO update(@AuthenticationPrincipal UserPrincipal principal, @Valid @RequestBody UserUpdateDTO dto) {
        return service.update(principal.getId(), dto);
    }

    @PutMapping("/me/password")
    public ResponseEntity<Void> updatePassword(@AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody PasswordUpdateDTO dto, HttpServletRequest request, HttpServletResponse response,
            Authentication authentication) {
        service.updatePassword(principal.getId(), dto);
        logoutHandler.logout(request, response, authentication);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/me")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal UserPrincipal principal, HttpServletRequest request,
                                       HttpServletResponse response, Authentication authentication) {
        service.delete(principal.getId());
        logoutHandler.logout(request, response, authentication);
        return ResponseEntity.noContent().build();
    }
}

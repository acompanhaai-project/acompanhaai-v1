package com.maiconamaral.acompanhaai.services;

import com.maiconamaral.acompanhaai.dto.*;
import com.maiconamaral.acompanhaai.entities.User;
import com.maiconamaral.acompanhaai.repositories.UserRepository;
import com.maiconamaral.acompanhaai.repositories.UserPhotoRepository;
import com.maiconamaral.acompanhaai.services.exceptions.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Locale;

@Service
public class UserService {
    private final UserRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final UserPhotoRepository photos;

    public UserService(UserRepository repository, PasswordEncoder passwordEncoder, UserPhotoRepository photos) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.photos = photos;
    }

    @Transactional
    public UserDTO insert(UserInsertDTO dto) {
        String email = normalizeEmail(dto.email());
        if (repository.existsByEmail(email)) throw new EmailAlreadyExistsException();
        User user = new User(dto.name().strip(), email, passwordEncoder.encode(dto.password()));
        return save(user);
    }

    @Transactional(readOnly = true)
    public UserDTO findById(Long id) { return new UserDTO(findEntity(id), photos.existsById(id)); }

    @Transactional
    public UserDTO update(Long id, UserUpdateDTO dto) {
        User user = findEntity(id);
        String email = normalizeEmail(dto.email());
        if (repository.existsByEmailAndIdNot(email, id)) throw new EmailAlreadyExistsException();
        user.setName(dto.name().strip());
        user.setEmail(email);
        return save(user);
    }

    @Transactional
    public void updatePassword(Long id, PasswordUpdateDTO dto) {
        User user = findEntity(id);
        if (!passwordEncoder.matches(dto.currentPassword(), user.getPasswordHash())) {
            throw new BadCredentialsException("Senha atual incorreta.");
        }
        user.changePassword(passwordEncoder.encode(dto.newPassword()));
    }

    @Transactional
    public void delete(Long id) { repository.delete(findEntity(id)); }

    private User findEntity(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Usuário não encontrado."));
    }

    private UserDTO save(User user) {
        try {
            var saved = repository.saveAndFlush(user);
            return new UserDTO(saved, photos.existsById(saved.getId()));
        } catch (DataIntegrityViolationException exception) {
            // A restrição do banco também impede cadastros simultâneos com o mesmo e-mail.
            throw new EmailAlreadyExistsException();
        }
    }

    public static String normalizeEmail(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }
}

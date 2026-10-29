package com.maiconamaral.acompanhaai.repositories;

import com.maiconamaral.acompanhaai.entities.User;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);
    boolean existsByEmailAndIdNot(String email, Long id);
    boolean existsByIdAndAuthVersion(Long id, long authVersion);
}

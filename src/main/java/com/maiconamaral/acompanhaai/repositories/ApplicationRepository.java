package com.maiconamaral.acompanhaai.repositories;

import com.maiconamaral.acompanhaai.entities.Application;
import com.maiconamaral.acompanhaai.entities.enums.ApplicationStatus;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import java.util.Optional;

public interface ApplicationRepository extends JpaRepository<Application, Long> {
    @EntityGraph(attributePaths = "job")
    Optional<Application> findByIdAndUser_Id(Long id, Long userId);
    @EntityGraph(attributePaths = "job")
    Page<Application> findByUser_Id(Long userId, Pageable pageable);
    @EntityGraph(attributePaths = "job")
    Page<Application> findByUser_IdAndStatus(Long userId, ApplicationStatus status, Pageable pageable);
}

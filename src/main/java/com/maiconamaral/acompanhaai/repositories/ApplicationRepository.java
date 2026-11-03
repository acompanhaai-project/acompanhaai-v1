package com.maiconamaral.acompanhaai.repositories;

import com.maiconamaral.acompanhaai.entities.Application;
import com.maiconamaral.acompanhaai.entities.enums.ApplicationStatus;
import java.util.Optional;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface ApplicationRepository extends JpaRepository<Application, Long> {
    @EntityGraph(attributePaths = "job")
    Optional<Application> findByIdAndUser_Id(Long id, Long userId);

    @EntityGraph(attributePaths = "job")
    @Query(
        """
        SELECT a FROM Application a JOIN a.job j
        WHERE a.user.id = :userId
          AND (:status IS NULL OR a.status = :status)
          AND (LOWER(j.title) LIKE LOWER(:pattern) ESCAPE '!'
               OR LOWER(j.companyName) LIKE LOWER(:pattern) ESCAPE '!')
        """
    )
    Page<Application> search(
        @Param("userId") Long userId,
        @Param("status") ApplicationStatus status,
        @Param("pattern") String pattern,
        Pageable pageable
    );
}

package com.maiconamaral.acompanhaai.repositories;

import com.maiconamaral.acompanhaai.entities.ApplicationStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ApplicationStatusHistoryRepository extends JpaRepository<ApplicationStatusHistory, Long> {
    List<ApplicationStatusHistory> findByApplication_IdOrderByChangedAtAscIdAsc(Long applicationId);
}

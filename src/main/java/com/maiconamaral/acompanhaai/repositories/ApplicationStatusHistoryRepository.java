package com.maiconamaral.acompanhaai.repositories;

import com.maiconamaral.acompanhaai.entities.ApplicationStatusHistory;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApplicationStatusHistoryRepository extends JpaRepository<ApplicationStatusHistory, Long> {
    List<ApplicationStatusHistory> findByApplication_IdOrderByChangedAtAscIdAsc(Long applicationId);
}

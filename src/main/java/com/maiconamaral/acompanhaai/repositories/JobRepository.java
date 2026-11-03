package com.maiconamaral.acompanhaai.repositories;

import com.maiconamaral.acompanhaai.dto.JobUrlCandidateDTO;
import com.maiconamaral.acompanhaai.entities.Job;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JobRepository extends JpaRepository<Job, Long> {
    @Query(
        """
        SELECT new com.maiconamaral.acompanhaai.dto.JobUrlCandidateDTO(j.id, j.sourceUrl, j.urlHash)
        FROM Job j WHERE j.user.id = :userId AND j.sourceUrl IS NOT NULL
        """
    )
    List<JobUrlCandidateDTO> findUrlCandidates(@Param("userId") Long userId);
}

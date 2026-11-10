package com.maiconamaral.acompanhaai.repositories;

import com.maiconamaral.acompanhaai.entities.ImportTask;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ImportTaskRepository extends JpaRepository<ImportTask, Long> {

    @Query(value = """
        SELECT * FROM tb_import_task
        WHERE status = 'PENDING' AND attempt_count < :maxAttempts
          AND created_at > :cutoff
          AND (next_attempt_at IS NULL OR next_attempt_at <= :now)
        ORDER BY COALESCE(next_attempt_at, created_at), id
        LIMIT 1 FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    Optional<ImportTask> lockNext(
        @Param("now") Instant now,
        @Param("cutoff") Instant cutoff,
        @Param("maxAttempts") int maxAttempts
    );

    @Query(value = """
        SELECT * FROM tb_import_task
        WHERE id = :id AND status = 'PROCESSING' AND lock_token = :token
        FOR UPDATE
        """, nativeQuery = true)
    Optional<ImportTask> lockOwned(@Param("id") long id, @Param("token") String token);

    @Query(value = """
        SELECT * FROM tb_import_task
        WHERE status = 'PROCESSING' AND locked_until <= :now AND created_at > :cutoff
        ORDER BY locked_until, id
        LIMIT :batchSize FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<ImportTask> lockInterrupted(
        @Param("now") Instant now,
        @Param("cutoff") Instant cutoff,
        @Param("batchSize") int batchSize
    );

    @Query(value = """
        SELECT id FROM tb_import_task
        WHERE created_at <= :cutoff
        ORDER BY created_at, id
        LIMIT :batchSize FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<Long> lockExpired(@Param("cutoff") Instant cutoff, @Param("batchSize") int batchSize);
}

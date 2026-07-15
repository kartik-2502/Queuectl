package com.queuectl.repository;

import com.queuectl.model.Job;
import com.queuectl.model.JobState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface JobRepository extends JpaRepository<Job, String> {

    List<Job> findByStateOrderByCreatedAtAsc(JobState state);

    List<Job> findByStateOrderByPriorityDescCreatedAtAsc(JobState state);

    long countByState(JobState state);

    @Query(value = "SELECT * FROM jobs " +
            "WHERE (state = 'pending' AND run_at <= :now) " +
            "   OR (state = 'failed' AND attempts < max_retries AND run_at <= :now) " +
            "ORDER BY priority DESC, created_at ASC " +
            "LIMIT 1 FOR UPDATE SKIP LOCKED", nativeQuery = true)
    Optional<Job> findNextJobForUpdate(@Param("now") Instant now);

    @Query(value = "SELECT * FROM jobs " +
            "WHERE state = 'processing' AND worker_id = :workerId", nativeQuery = true)
    List<Job> findJobsProcessingByWorker(@Param("workerId") String workerId);

    @Query(value = "SELECT AVG(execution_duration_ms) FROM jobs WHERE state = 'completed'", nativeQuery = true)
    Double getAverageExecutionDurationMs();
}

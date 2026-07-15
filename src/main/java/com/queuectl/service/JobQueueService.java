package com.queuectl.service;

import com.queuectl.model.Job;
import com.queuectl.model.JobState;
import com.queuectl.model.Configuration;
import com.queuectl.model.WorkerState;
import com.queuectl.repository.JobRepository;
import com.queuectl.repository.ConfigurationRepository;
import com.queuectl.repository.WorkerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class JobQueueService {

    private final JobRepository jobRepository;
    private final ConfigurationRepository configRepository;
    private final WorkerRepository workerRepository;

    public JobQueueService(JobRepository jobRepository,
                           ConfigurationRepository configRepository,
                           WorkerRepository workerRepository) {
        this.jobRepository = jobRepository;
        this.configRepository = configRepository;
        this.workerRepository = workerRepository;
    }

    // Config helpers
    public int getMaxRetriesConfig() {
        Optional<Configuration> config = configRepository.findById("max-retries");
        if (config.isPresent()) {
            try {
                return Integer.parseInt(config.get().getValue());
            } catch (NumberFormatException e) {
                // fall through
            }
        }
        return 3;
    }

    public double getBackoffBaseConfig() {
        Optional<Configuration> config = configRepository.findById("backoff-base");
        if (config.isPresent()) {
            try {
                return Double.parseDouble(config.get().getValue());
            } catch (NumberFormatException e) {
                // fall through
            }
        }
        return 2.0;
    }

    @Transactional
    public void setConfig(String key, String value) {
        Configuration config = new Configuration(key, value);
        configRepository.save(config);
    }

    public Map<String, String> getAllConfig() {
        Map<String, String> configs = new HashMap<>();
        configs.put("max-retries", String.valueOf(getMaxRetriesConfig()));
        configs.put("backoff-base", String.valueOf(getBackoffBaseConfig()));
        return configs;
    }

    // Job CRUD & Lifecycle
    @Transactional
    public Job enqueue(Job job) {
        if (job.getId() == null || job.getId().trim().isEmpty()) {
            job.setId(UUID.randomUUID().toString());
        }

        // Apply defaults if not set
        if (job.getMaxRetries() <= 0) {
            job.setMaxRetries(getMaxRetriesConfig());
        }
        if (job.getState() == null) {
            job.setState(JobState.pending);
        }
        
        Instant now = Instant.now();
        if (job.getCreatedAt() == null) {
            job.setCreatedAt(now);
        }
        job.setUpdatedAt(now);
        
        if (job.getRunAt() == null) {
            job.setRunAt(now);
        }

        return jobRepository.save(job);
    }

    public List<Job> getJobsByState(JobState state) {
        return jobRepository.findByStateOrderByPriorityDescCreatedAtAsc(state);
    }

    public Optional<Job> getJobById(String id) {
        return jobRepository.findById(id);
    }

    @Transactional
    public void retryDlqJob(String jobId) {
        Job job = jobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Job with ID " + jobId + " not found."));

        if (job.getState() != JobState.dead) {
            throw new IllegalStateException("Job with ID " + jobId + " is not in the Dead Letter Queue (DLQ). State is: " + job.getState());
        }

        job.setState(JobState.pending);
        job.setAttempts(0);
        job.setRunAt(Instant.now());
        job.setWorkerId(null);
        job.setResultCode(null);
        job.setExecutionDurationMs(null);
        // Prepend retry info to output log
        String log = job.getOutputLog();
        job.setOutputLog(log == null ? "[System: Retried from DLQ]" : log + "\n[System: Retried from DLQ]\n");

        jobRepository.save(job);
    }

    // Metrics and Stats
    public Map<String, Object> getMetrics() {
        Map<String, Object> metrics = new HashMap<>();

        long pending = jobRepository.countByState(JobState.pending);
        long processing = jobRepository.countByState(JobState.processing);
        long completed = jobRepository.countByState(JobState.completed);
        long failed = jobRepository.countByState(JobState.failed);
        long dead = jobRepository.countByState(JobState.dead);
        long total = pending + processing + completed + failed + dead;

        metrics.put("pendingJobs", pending);
        metrics.put("processingJobs", processing);
        metrics.put("completedJobs", completed);
        metrics.put("failedJobs", failed);
        metrics.put("deadJobs", dead);
        metrics.put("totalJobs", total);

        double successRate = 0.0;
        long finishedJobs = completed + dead;
        if (finishedJobs > 0) {
            successRate = ((double) completed / finishedJobs) * 100.0;
        }
        metrics.put("successRate", String.format("%.2f%%", successRate));

        Double avgDuration = jobRepository.getAverageExecutionDurationMs();
        metrics.put("avgExecutionTimeMs", avgDuration != null ? Math.round(avgDuration) : 0L);

        long activeWorkers = workerRepository.countByState(WorkerState.ACTIVE);
        metrics.put("activeWorkers", activeWorkers);

        return metrics;
    }
}

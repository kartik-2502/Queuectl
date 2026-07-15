package com.queuectl.service;

import com.queuectl.model.Job;
import com.queuectl.model.JobState;
import com.queuectl.model.Worker;
import com.queuectl.model.WorkerState;
import com.queuectl.model.Configuration;
import com.queuectl.repository.JobRepository;
import com.queuectl.repository.WorkerRepository;
import com.queuectl.repository.ConfigurationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class WorkerManagerService {

    private final JobRepository jobRepository;
    private final WorkerRepository workerRepository;
    private final ConfigurationRepository configRepository;

    public WorkerManagerService(JobRepository jobRepository,
                                WorkerRepository workerRepository,
                                ConfigurationRepository configRepository) {
        this.jobRepository = jobRepository;
        this.workerRepository = workerRepository;
        this.configRepository = configRepository;
    }

    private double getBackoffBaseConfig() {
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
    public Optional<Job> claimNextJob(String workerId) {
        Instant now = Instant.now();
        Optional<Job> jobOpt = jobRepository.findNextJobForUpdate(now);
        if (jobOpt.isPresent()) {
            Job job = jobOpt.get();
            job.setState(JobState.processing);
            job.setWorkerId(workerId);
            job.setUpdatedAt(now);
            return Optional.of(jobRepository.save(job));
        }
        return Optional.empty();
    }

    @Transactional
    public void saveJobResult(String jobId, JobState state, Integer resultCode, Long durationMs, String logContent, int attempts, Instant runAt) {
        Optional<Job> jobOpt = jobRepository.findById(jobId);
        if (jobOpt.isPresent()) {
            Job job = jobOpt.get();
            job.setState(state);
            job.setResultCode(resultCode);
            job.setExecutionDurationMs(durationMs);
            job.setOutputLog(logContent);
            job.setAttempts(attempts);
            job.setRunAt(runAt);
            jobRepository.save(job);
        }
    }

    @Transactional
    public void registerWorker(String workerId, long pid, int threadCount) {
        Worker worker = new Worker(workerId, pid, threadCount);
        worker.setLastHeartbeat(Instant.now());
        worker.setState(WorkerState.ACTIVE);
        workerRepository.save(worker);
    }

    @Transactional
    public void updateHeartbeat(String workerId) {
        Optional<Worker> workerOpt = workerRepository.findById(workerId);
        if (workerOpt.isPresent()) {
            Worker worker = workerOpt.get();
            worker.setLastHeartbeat(Instant.now());
            workerRepository.save(worker);
        }
    }

    @Transactional
    public void markWorkerStopped(String workerId) {
        Optional<Worker> workerOpt = workerRepository.findById(workerId);
        if (workerOpt.isPresent()) {
            Worker worker = workerOpt.get();
            worker.setState(WorkerState.STOPPED);
            workerRepository.save(worker);
        }
    }

    @Transactional
    public void requestAllWorkersStop() {
        List<Worker> activeWorkers = workerRepository.findByState(WorkerState.ACTIVE);
        for (Worker worker : activeWorkers) {
            worker.setState(WorkerState.STOPPING);
            workerRepository.save(worker);
        }
    }

    @Transactional
    public void cleanOrphanedJobs() {
        Instant limit = Instant.now().minusSeconds(10);
        List<Worker> activeWorkers = workerRepository.findByState(WorkerState.ACTIVE);
        double backoffBase = getBackoffBaseConfig();

        for (Worker w : activeWorkers) {
            if (w.getLastHeartbeat().isBefore(limit)) {
                System.out.println("System: Worker " + w.getId() + " (PID " + w.getPid() + ") has timed out. Cleaning up...");
                w.setState(WorkerState.STOPPED);
                workerRepository.save(w);

                List<Job> orphanedJobs = jobRepository.findJobsProcessingByWorker(w.getId());
                for (Job job : orphanedJobs) {
                    job.setWorkerId(null);
                    int attempts = job.getAttempts() + 1;
                    job.setAttempts(attempts);

                    String prefix = job.getOutputLog() == null ? "" : job.getOutputLog() + "\n";
                    if (attempts >= job.getMaxRetries()) {
                        job.setState(JobState.dead);
                        job.setOutputLog(prefix + "[System: Worker process died. Job moved to DLQ.]");
                    } else {
                        job.setState(JobState.failed);
                        double delay = Math.pow(backoffBase, attempts);
                        job.setRunAt(Instant.now().plusSeconds((long) delay));
                        job.setOutputLog(prefix + "[System: Worker process died. Job rescheduled for retry.]");
                    }
                    jobRepository.save(job);
                }
            }
        }
    }

    // Runs a worker process in the foreground
    public void startWorkerProcess(int threadCount) {
        // Run cleanOrphanedJobs at startup to clean up after previous crashes
        try {
            cleanOrphanedJobs();
        } catch (Exception e) {
            System.err.println("Warning: Failed to clean orphaned jobs on startup: " + e.getMessage());
        }

        String workerId = UUID.randomUUID().toString();
        long pid = ProcessHandle.current().pid();
        System.out.println("System: Starting worker process [ID: " + workerId + ", PID: " + pid + "] with " + threadCount + " worker threads.");

        registerWorker(workerId, pid, threadCount);

        // Heartbeat scheduler
        ScheduledExecutorService heartbeatScheduler = Executors.newSingleThreadScheduledExecutor();
        heartbeatScheduler.scheduleAtFixedRate(() -> {
            try {
                updateHeartbeat(workerId);
            } catch (Exception e) {
                // Ignore silent heartbeat errors
            }
        }, 2, 2, TimeUnit.SECONDS);

        // Worker thread pool
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        AtomicBoolean running = new AtomicBoolean(true);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                while (running.get()) {
                    try {
                        Optional<Job> jobOpt = claimNextJob(workerId);
                        if (jobOpt.isPresent()) {
                            Job job = jobOpt.get();
                            System.out.println("WorkerThread: Claimed job " + job.getId() + " - command: " + job.getCommand());
                            executeJob(job, workerId);
                        } else {
                            Thread.sleep(1000); // Poll every second if queue empty
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (Exception e) {
                        System.err.println("Error in worker thread polling loop: " + e.getMessage());
                    }
                }
            });
        }

        // Monitoring loop to check for graceful stop requests
        try {
            while (true) {
                Optional<Worker> workerOpt = workerRepository.findById(workerId);
                if (workerOpt.isPresent() && workerOpt.get().getState() == WorkerState.STOPPING) {
                    System.out.println("System: Received stop request. Initiating graceful shutdown...");
                    break;
                }
                // Also trigger active worker orphan checks periodically
                try {
                    cleanOrphanedJobs();
                } catch (Exception e) {
                    // ignore
                }
                Thread.sleep(1000);
            }
        } catch (InterruptedException e) {
            System.out.println("System: Worker manager interrupted.");
        } finally {
            // Initiate shutdown
            running.set(false);
            executor.shutdown();
            System.out.println("System: Waiting for running jobs to complete...");
            try {
                if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
            }

            markWorkerStopped(workerId);
            heartbeatScheduler.shutdown();
            System.out.println("System: Worker process " + workerId + " shutdown complete.");
        }
    }

    private void executeJob(Job job, String workerId) {
        Instant startTime = Instant.now();
        int exitCode = -1;
        String logContent = "";
        
        ProcessBuilder pb;
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            pb = new ProcessBuilder("cmd.exe", "/c", job.getCommand());
        } else {
            pb = new ProcessBuilder("sh", "-c", job.getCommand());
        }
        
        pb.redirectErrorStream(true); // Merge stdout & stderr

        try {
            Process process = pb.start();

            // Read output asynchronously to prevent process buffer blocking
            CompletableFuture<String> outputFuture = CompletableFuture.supplyAsync(() -> {
                StringBuilder sb = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line).append("\n");
                    }
                } catch (Exception e) {
                    sb.append("[System Error reading output: ").append(e.getMessage()).append("]\n");
                }
                return sb.toString();
            });

            // Wait for execution with timeout
            boolean finished = process.waitFor(job.getTimeout(), TimeUnit.SECONDS);

            if (finished) {
                exitCode = process.exitValue();
                logContent = outputFuture.get(2, TimeUnit.SECONDS);
            } else {
                process.destroyForcibly();
                exitCode = -143; // Standard SIGTERM code
                logContent = outputFuture.get(2, TimeUnit.SECONDS) + 
                             "\n[System: Job execution timed out after " + job.getTimeout() + " seconds. Process terminated.]\n";
            }
        } catch (Exception e) {
            exitCode = -999;
            logContent = "[System Error launching command: " + e.getMessage() + "]\n";
        }

        long durationMs = Duration.between(startTime, Instant.now()).toMillis();
        
        // Handle result
        JobState nextState;
        int nextAttempts = job.getAttempts() + 1;
        Instant nextRunAt = job.getRunAt();

        if (exitCode == 0) {
            nextState = JobState.completed;
            System.out.println("WorkerThread: Job " + job.getId() + " completed successfully in " + durationMs + "ms.");
        } else {
            System.out.println("WorkerThread: Job " + job.getId() + " failed with exit code " + exitCode + ". Attempt " + nextAttempts + "/" + job.getMaxRetries());
            double backoffBase = getBackoffBaseConfig();
            
            if (nextAttempts >= job.getMaxRetries()) {
                nextState = JobState.dead;
                System.out.println("WorkerThread: Job " + job.getId() + " reached max retries. Moved to DLQ.");
            } else {
                nextState = JobState.failed;
                double delaySeconds = Math.pow(backoffBase, nextAttempts);
                nextRunAt = Instant.now().plusSeconds((long) delaySeconds);
                System.out.println("WorkerThread: Job " + job.getId() + " rescheduled in " + delaySeconds + " seconds.");
            }
        }

        // Commit execution details
        try {
            saveJobResult(job.getId(), nextState, exitCode, durationMs, logContent, nextAttempts, nextRunAt);
        } catch (Exception e) {
            System.err.println("Error saving job result to DB: " + e.getMessage());
        }
    }
}

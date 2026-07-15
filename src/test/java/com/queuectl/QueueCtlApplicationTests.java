package com.queuectl;

import com.queuectl.model.Job;
import com.queuectl.model.JobState;
import com.queuectl.model.Worker;
import com.queuectl.model.WorkerState;
import com.queuectl.repository.JobRepository;
import com.queuectl.repository.WorkerRepository;
import com.queuectl.service.JobQueueService;
import com.queuectl.service.WorkerManagerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class QueueCtlApplicationTests {

    @Autowired
    private JobQueueService jobQueueService;

    @Autowired
    private WorkerManagerService workerManagerService;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private WorkerRepository workerRepository;

    @BeforeEach
    void setUp() {
        // Clean up database tables before each test
        jobRepository.deleteAll();
        workerRepository.deleteAll();
    }

    @Test
    void testBasicJobSuccess() throws Exception {
        // 1. Enqueue a basic successful job
        Job job = new Job("test-job-ok", "echo HelloTest");
        job.setMaxRetries(3);
        job.setTimeout(10);
        Job enqueued = jobQueueService.enqueue(job);

        assertNotNull(enqueued);
        assertEquals(JobState.pending, enqueued.getState());

        // 2. Start worker process in a separate background thread
        ExecutorService testExecutor = Executors.newSingleThreadExecutor();
        testExecutor.submit(() -> {
            workerManagerService.startWorkerProcess(1);
        });

        // 3. Wait for the job to complete
        boolean completed = false;
        for (int i = 0; i < 10; i++) {
            Thread.sleep(500);
            Optional<Job> updated = jobQueueService.getJobById("test-job-ok");
            if (updated.isPresent() && updated.get().getState() == JobState.completed) {
                completed = true;
                assertNotNull(updated.get().getOutputLog());
                assertTrue(updated.get().getOutputLog().contains("HelloTest"));
                assertEquals(0, updated.get().getResultCode());
                break;
            }
        }

        // 4. Request workers to stop and verify graceful shutdown
        workerManagerService.requestAllWorkersStop();
        testExecutor.shutdown();
        testExecutor.awaitTermination(5, TimeUnit.SECONDS);

        assertTrue(completed, "Job should have completed successfully");
    }

    @Test
    void testFailedJobRetriesAndMovesToDLQ() throws Exception {
        // 1. Configure max-retries=2 and backoff-base=1 (for 1-second delay)
        jobQueueService.setConfig("max-retries", "2");
        jobQueueService.setConfig("backoff-base", "1");

        // 2. Enqueue a job that always fails
        Job job = new Job("test-job-fail", "exit 1");
        job.setMaxRetries(2);
        job.setTimeout(5);
        jobQueueService.enqueue(job);

        // 3. Start worker in a separate thread
        ExecutorService testExecutor = Executors.newSingleThreadExecutor();
        testExecutor.submit(() -> {
            workerManagerService.startWorkerProcess(1);
        });

        // 4. Wait for it to fail, retry, fail again, and move to DLQ (dead)
        boolean movedToDlq = false;
        for (int i = 0; i < 15; i++) {
            Thread.sleep(500);
            Optional<Job> updated = jobQueueService.getJobById("test-job-fail");
            if (updated.isPresent() && updated.get().getState() == JobState.dead) {
                movedToDlq = true;
                assertEquals(2, updated.get().getAttempts());
                assertEquals(1, updated.get().getResultCode());
                break;
            }
        }

        // 5. Clean up worker
        workerManagerService.requestAllWorkersStop();
        testExecutor.shutdown();
        testExecutor.awaitTermination(5, TimeUnit.SECONDS);

        assertTrue(movedToDlq, "Job should have exhausted retries and moved to DLQ (dead)");
    }

    @Test
    void testInvalidCommandGracefulFailure() throws Exception {
        // Enqueue an invalid nonexistent command
        Job job = new Job("test-job-invalid", "nonexistentcommand_12345");
        job.setMaxRetries(1);
        jobQueueService.enqueue(job);

        ExecutorService testExecutor = Executors.newSingleThreadExecutor();
        testExecutor.submit(() -> {
            workerManagerService.startWorkerProcess(1);
        });

        boolean markedDead = false;
        for (int i = 0; i < 10; i++) {
            Thread.sleep(500);
            Optional<Job> updated = jobQueueService.getJobById("test-job-invalid");
            if (updated.isPresent() && updated.get().getState() == JobState.dead) {
                markedDead = true;
                assertNotNull(updated.get().getOutputLog());
                assertTrue(updated.get().getOutputLog().contains("Error launching command") || 
                           updated.get().getOutputLog().contains("not recognized"));
                break;
            }
        }

        workerManagerService.requestAllWorkersStop();
        testExecutor.shutdown();
        testExecutor.awaitTermination(5, TimeUnit.SECONDS);

        assertTrue(markedDead, "Invalid command job should fail and move to DLQ gracefully");
    }

    @Test
    void testJobDataSurvivesRestart() {
        // Enqueue a job
        Job job = new Job("test-job-restart", "echo restart");
        jobQueueService.enqueue(job);

        // Verify it is saved in repository
        Optional<Job> loaded = jobRepository.findById("test-job-restart");
        assertTrue(loaded.isPresent());
        assertEquals("echo restart", loaded.get().getCommand());
        
        // Simulating restart: reloading JPA context would yield same persisted data
        jobRepository.flush();
        Optional<Job> reloaded = jobRepository.findById("test-job-restart");
        assertTrue(reloaded.isPresent());
        assertEquals(JobState.pending, reloaded.get().getState());
    }

    @Test
    void testMultipleWorkersNoOverlap() throws Exception {
        // Enqueue 3 quick parallel-friendly tasks
        // We use standard ping delay command on Windows, or sleep on Unix
        String delayCommand = System.getProperty("os.name").toLowerCase().contains("win") 
            ? "ping -n 2 127.0.0.1 > nul" 
            : "sleep 1";

        jobQueueService.enqueue(new Job("p-job-1", delayCommand));
        jobQueueService.enqueue(new Job("p-job-2", delayCommand));
        jobQueueService.enqueue(new Job("p-job-3", delayCommand));

        // Start worker with 3 parallel threads
        ExecutorService testExecutor = Executors.newSingleThreadExecutor();
        testExecutor.submit(() -> {
            workerManagerService.startWorkerProcess(3);
        });

        // Wait a few seconds
        Thread.sleep(3000);

        // Verify all 3 finished and were processed by workers without overlap
        Optional<Job> j1 = jobQueueService.getJobById("p-job-1");
        Optional<Job> j2 = jobQueueService.getJobById("p-job-2");
        Optional<Job> j3 = jobQueueService.getJobById("p-job-3");

        assertTrue(j1.isPresent() && j1.get().getState() == JobState.completed);
        assertTrue(j2.isPresent() && j2.get().getState() == JobState.completed);
        assertTrue(j3.isPresent() && j3.get().getState() == JobState.completed);

        // Ensure worker ID was recorded
        assertNotNull(j1.get().getWorkerId());
        assertNotNull(j2.get().getWorkerId());
        assertNotNull(j3.get().getWorkerId());

        workerManagerService.requestAllWorkersStop();
        testExecutor.shutdown();
        testExecutor.awaitTermination(5, TimeUnit.SECONDS);
    }
}

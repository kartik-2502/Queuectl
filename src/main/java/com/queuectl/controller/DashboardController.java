package com.queuectl.controller;

import com.queuectl.model.Job;
import com.queuectl.model.JobState;
import com.queuectl.model.Worker;
import com.queuectl.repository.JobRepository;
import com.queuectl.repository.WorkerRepository;
import com.queuectl.service.JobQueueService;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/dashboard")
@CrossOrigin(origins = "*") // Allow cross-origin requests for testing
public class DashboardController {

    private final JobQueueService jobQueueService;
    private final JobRepository jobRepository;
    private final WorkerRepository workerRepository;

    public DashboardController(JobQueueService jobQueueService,
                               JobRepository jobRepository,
                               WorkerRepository workerRepository) {
        this.jobQueueService = jobQueueService;
        this.jobRepository = jobRepository;
        this.workerRepository = workerRepository;
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        return ResponseEntity.ok(jobQueueService.getMetrics());
    }

    @GetMapping("/jobs")
    public ResponseEntity<List<Job>> getJobs() {
        // Return all jobs sorted by updated_at desc so latest actions are visible
        return ResponseEntity.ok(jobRepository.findAll(Sort.by(Sort.Direction.DESC, "updatedAt")));
    }

    @GetMapping("/workers")
    public ResponseEntity<List<Worker>> getWorkers() {
        return ResponseEntity.ok(workerRepository.findAll());
    }

    @PostMapping("/enqueue")
    public ResponseEntity<Job> enqueueJob(@RequestBody Job jobRequest) {
        try {
            Job enqueued = jobQueueService.enqueue(jobRequest);
            return ResponseEntity.ok(enqueued);
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @PostMapping("/retry/{id}")
    public ResponseEntity<Map<String, String>> retryJob(@PathVariable("id") String id) {
        try {
            jobQueueService.retryDlqJob(id);
            return ResponseEntity.ok(Map.of("message", "Job rescheduled successfully"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/config")
    public ResponseEntity<Map<String, String>> setConfig(@RequestBody Map<String, String> configRequest) {
        try {
            for (Map.Entry<String, String> entry : configRequest.entrySet()) {
                jobQueueService.setConfig(entry.getKey(), entry.getValue());
            }
            return ResponseEntity.ok(Map.of("message", "Configuration updated successfully"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/config")
    public ResponseEntity<Map<String, String>> getConfig() {
        return ResponseEntity.ok(jobQueueService.getAllConfig());
    }
}

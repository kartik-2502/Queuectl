package com.queuectl.cli;

import com.queuectl.service.JobQueueService;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;

import java.util.Map;

@Component
@Command(name = "status", description = "Show summary of all job states & active workers")
public class StatusCommand implements Runnable {

    private final JobQueueService jobQueueService;

    public StatusCommand(JobQueueService jobQueueService) {
        this.jobQueueService = jobQueueService;
    }

    @Override
    public void run() {
        Map<String, Object> metrics = jobQueueService.getMetrics();
        System.out.println("========================================");
        System.out.println("       QueueCTL Status Summary");
        System.out.println("========================================");
        System.out.println("Total Jobs Enqueued:  " + metrics.get("totalJobs"));
        System.out.println("----------------------------------------");
        System.out.println("  Pending:            " + metrics.get("pendingJobs"));
        System.out.println("  Processing:         " + metrics.get("processingJobs"));
        System.out.println("  Completed:          " + metrics.get("completedJobs"));
        System.out.println("  Failed:             " + metrics.get("failedJobs"));
        System.out.println("  Dead (DLQ):         " + metrics.get("deadJobs"));
        System.out.println("----------------------------------------");
        System.out.println("Success Rate:         " + metrics.get("successRate"));
        System.out.println("Avg Exec Duration:    " + metrics.get("avgExecutionTimeMs") + " ms");
        System.out.println("Active JVM Workers:   " + metrics.get("activeWorkers"));
        System.out.println("========================================");
    }
}

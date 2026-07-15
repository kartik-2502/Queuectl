package com.queuectl.cli;

import com.queuectl.model.Job;
import com.queuectl.model.JobState;
import com.queuectl.service.JobQueueService;
import org.springframework.stereotype.Component;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.util.List;

@Component
@Command(name = "dlq", description = "View or retry DLQ jobs", subcommands = {
    DlqCommand.ListDlqCommand.class,
    DlqCommand.RetryDlqCommand.class
})
public class DlqCommand implements Runnable {
    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    @Component
    @Command(name = "list", description = "List all dead letter queue jobs")
    public static class ListDlqCommand implements Runnable {
        private final JobQueueService jobQueueService;

        public ListDlqCommand(JobQueueService jobQueueService) {
            this.jobQueueService = jobQueueService;
        }

        @Override
        public void run() {
            List<Job> jobs = jobQueueService.getJobsByState(JobState.dead);
            System.out.println("========================================================================");
            System.out.printf("   Dead Letter Queue (DLQ) Jobs (%d found)\n", jobs.size());
            System.out.println("========================================================================");
            System.out.printf("%-36s | %-10s | %-20s\n", "Job ID", "Attempts", "Command");
            System.out.println("------------------------------------------------------------------------");
            for (Job job : jobs) {
                System.out.printf("%-36s | %-10s | %-20s\n",
                        job.getId(),
                        job.getAttempts() + "/" + job.getMaxRetries(),
                        job.getCommand().length() > 20 ? job.getCommand().substring(0, 17) + "..." : job.getCommand()
                );
            }
            System.out.println("========================================================================");
        }
    }

    @Component
    @Command(name = "retry", description = "Retry a DLQ job by ID")
    public static class RetryDlqCommand implements Runnable {
        private final JobQueueService jobQueueService;

        public RetryDlqCommand(JobQueueService jobQueueService) {
            this.jobQueueService = jobQueueService;
        }

        @Parameters(index = "0", description = "The ID of the dead job to retry")
        private String jobId;

        @Override
        public void run() {
            try {
                jobQueueService.retryDlqJob(jobId);
                System.out.println("Success: Job " + jobId + " has been reset and moved back to pending queue.");
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                System.exit(1);
            }
        }
    }
}

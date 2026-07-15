package com.queuectl.cli;

import com.queuectl.model.Job;
import com.queuectl.model.JobState;
import com.queuectl.service.JobQueueService;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.List;

@Component
@Command(name = "list", description = "List jobs by state")
public class ListCommand implements Runnable {

    private final JobQueueService jobQueueService;

    public ListCommand(JobQueueService jobQueueService) {
        this.jobQueueService = jobQueueService;
    }

    @Option(names = {"--state"}, required = true, description = "State filter: pending, processing, completed, failed, dead")
    private JobState state;

    @Override
    public void run() {
        List<Job> jobs = jobQueueService.getJobsByState(state);
        System.out.println("=================================================================================");
        System.out.printf("   Jobs with State: %s (%d found)\n", state, jobs.size());
        System.out.println("=================================================================================");
        System.out.printf("%-36s | %-10s | %-4s | %-7s | %-20s\n", "Job ID", "State", "Prio", "Retries", "Command");
        System.out.println("---------------------------------------------------------------------------------");
        for (Job job : jobs) {
            System.out.printf("%-36s | %-10s | %-4d | %-7s | %-20s\n",
                    job.getId(),
                    job.getState(),
                    job.getPriority(),
                    job.getAttempts() + "/" + job.getMaxRetries(),
                    job.getCommand().length() > 20 ? job.getCommand().substring(0, 17) + "..." : job.getCommand()
            );
        }
        System.out.println("=================================================================================");
    }
}

package com.queuectl.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.queuectl.model.Job;
import com.queuectl.service.JobQueueService;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@Component
@Command(name = "enqueue", description = "Add a new job to the queue")
public class EnqueueCommand implements Runnable {

    private final JobQueueService jobQueueService;
    private final ObjectMapper objectMapper;

    public EnqueueCommand(JobQueueService jobQueueService, ObjectMapper objectMapper) {
        this.jobQueueService = jobQueueService;
        this.objectMapper = objectMapper;
    }

    @Parameters(index = "0", description = "JSON representation of the job (e.g. '{\"id\":\"job1\",\"command\":\"sleep 2\"}')")
    private String jsonPayload;

    @Override
    public void run() {
        try {
            Job job = objectMapper.readValue(jsonPayload, Job.class);
            if (job.getCommand() == null || job.getCommand().trim().isEmpty()) {
                System.err.println("Error: Command parameter is required.");
                System.exit(1);
            }
            Job enqueued = jobQueueService.enqueue(job);
            System.out.println("Job enqueued successfully. ID: " + enqueued.getId() + " [State: " + enqueued.getState() + "]");
        } catch (Exception e) {
            System.err.println("Error: Invalid job JSON format. Details: " + e.getMessage());
            System.exit(1);
        }
    }
}

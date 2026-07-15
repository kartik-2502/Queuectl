package com.queuectl.cli;

import com.queuectl.service.JobQueueService;
import org.springframework.stereotype.Component;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@Component
@Command(name = "config", description = "Manage configuration (retry, backoff, etc.)", subcommands = {
    ConfigCommand.SetConfigCommand.class
})
public class ConfigCommand implements Runnable {
    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    @Component
    @Command(name = "set", description = "Set configuration key-value")
    public static class SetConfigCommand implements Runnable {
        private final JobQueueService jobQueueService;

        public SetConfigCommand(JobQueueService jobQueueService) {
            this.jobQueueService = jobQueueService;
        }

        @Parameters(index = "0", description = "Config key (e.g. max-retries, backoff-base)")
        private String key;

        @Parameters(index = "1", description = "Config value")
        private String value;

        @Override
        public void run() {
            if (!"max-retries".equals(key) && !"backoff-base".equals(key)) {
                System.err.println("Error: Supported config keys are 'max-retries' and 'backoff-base'.");
                System.exit(1);
            }

            try {
                if ("max-retries".equals(key)) {
                    Integer.parseInt(value);
                } else {
                    Double.parseDouble(value);
                }
                
                jobQueueService.setConfig(key, value);
                System.out.println("Configuration updated: " + key + " = " + value);
            } catch (NumberFormatException e) {
                System.err.println("Error: Key '" + key + "' expects a numeric value.");
                System.exit(1);
            }
        }
    }
}

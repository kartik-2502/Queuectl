package com.queuectl.cli;

import org.springframework.stereotype.Component;
import picocli.CommandLine;
import picocli.CommandLine.Command;

@Component
@Command(
    name = "queuectl",
    mixinStandardHelpOptions = true,
    version = "queuectl 1.0.0",
    description = "QueueCTL - Background Job Queue System CLI manager",
    subcommands = {
        EnqueueCommand.class,
        WorkerCommand.class,
        StatusCommand.class,
        ListCommand.class,
        DlqCommand.class,
        ConfigCommand.class,
        DashboardCommand.class
    }
)
public class QueueCtlCommand implements Runnable {
    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }
}

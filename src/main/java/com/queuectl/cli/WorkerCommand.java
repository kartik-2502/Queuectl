package com.queuectl.cli;

import com.queuectl.service.WorkerManagerService;
import org.springframework.stereotype.Component;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

@Component
@Command(name = "worker", description = "Manage background job workers", subcommands = {
    WorkerCommand.StartCommand.class,
    WorkerCommand.RunCommand.class,
    WorkerCommand.StopCommand.class
})
public class WorkerCommand implements Runnable {
    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    @Component
    @Command(name = "start", description = "Start one or more workers in the background")
    public static class StartCommand implements Runnable {
        private final WorkerManagerService workerManagerService;

        public StartCommand(WorkerManagerService workerManagerService) {
            this.workerManagerService = workerManagerService;
        }

        @Option(names = {"--count"}, defaultValue = "1", description = "Number of worker threads to start")
        private int count;

        @Override
        public void run() {
            try {
                String javaHome = System.getProperty("java.home");
                String javaBin = javaHome + File.separator + "bin" + File.separator + "java";
                String classpath = System.getProperty("java.class.path");
                
                List<String> commandList = new ArrayList<>();
                commandList.add(javaBin);
                commandList.add("-jar");
                
                String jarPath = "target/queuectl-1.0.0.jar";
                if (!new File(jarPath).exists()) {
                    jarPath = "queuectl-1.0.0.jar";
                }
                
                if (classpath.contains("queuectl-1.0.0.jar")) {
                    String[] paths = classpath.split(File.pathSeparator);
                    for (String path : paths) {
                        if (path.endsWith("queuectl-1.0.0.jar")) {
                            jarPath = path;
                            break;
                        }
                    }
                }
                commandList.add(jarPath);
                commandList.add("worker");
                commandList.add("run");
                commandList.add("--count");
                commandList.add(String.valueOf(count));
                
                ProcessBuilder pb = new ProcessBuilder(commandList);
                pb.redirectOutput(ProcessBuilder.Redirect.to(new File("worker.log")));
                pb.redirectError(ProcessBuilder.Redirect.to(new File("worker-error.log")));
                
                Process process = pb.start();
                long pid = process.pid();
                
                System.out.println("Started worker process in background with " + count + " threads. PID: " + pid);
                System.out.println("Worker logs written to worker.log and worker-error.log");
            } catch (Exception e) {
                System.err.println("Error spawning background worker: " + e.getMessage());
                System.exit(1);
            }
        }
    }

    @Component
    @Command(name = "run", description = "Run workers in the foreground (blocking)")
    public static class RunCommand implements Runnable {
        private final WorkerManagerService workerManagerService;

        public RunCommand(WorkerManagerService workerManagerService) {
            this.workerManagerService = workerManagerService;
        }

        @Option(names = {"--count"}, defaultValue = "1", description = "Number of worker threads")
        private int count;

        @Override
        public void run() {
            workerManagerService.startWorkerProcess(count);
        }
    }

    @Component
    @Command(name = "stop", description = "Stop running workers gracefully")
    public static class StopCommand implements Runnable {
        private final WorkerManagerService workerManagerService;

        public StopCommand(WorkerManagerService workerManagerService) {
            this.workerManagerService = workerManagerService;
        }

        @Override
        public void run() {
            System.out.println("Sending shutdown signal to all running worker processes...");
            workerManagerService.requestAllWorkersStop();
            System.out.println("Shutdown command recorded. Active workers will terminate gracefully after completing current tasks.");
        }
    }
}

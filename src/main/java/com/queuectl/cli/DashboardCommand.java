package com.queuectl.cli;

import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;

@Component
@Command(name = "dashboard", description = "Start the web dashboard for monitoring")
public class DashboardCommand implements Runnable {

    @Override
    public void run() {
        System.out.println("=================================================");
        System.out.println("   QueueCTL Monitoring Web Dashboard Started");
        System.out.println("=================================================");
        System.out.println("Web Console is listening on: http://localhost:8080/");
        System.out.println("Press Ctrl+C to stop the dashboard server.");
        System.out.println("=================================================");
        
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            System.out.println("Dashboard server stopped.");
        }
    }
}

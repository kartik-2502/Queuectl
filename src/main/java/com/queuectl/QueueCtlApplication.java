package com.queuectl;

import com.queuectl.cli.QueueCtlCommand;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import picocli.CommandLine;

@SpringBootApplication
public class QueueCtlApplication implements CommandLineRunner, ExitCodeGenerator {

    private final QueueCtlCommand mainCommand;
    private final CommandLine.IFactory factory;
    private int exitCode;

    public QueueCtlApplication(QueueCtlCommand mainCommand, CommandLine.IFactory factory) {
        this.mainCommand = mainCommand;
        this.factory = factory;
    }

    public static void main(String[] args) {
        // Auto-create database if it doesn't exist
        ensureDatabaseExists();

        // Check if web server is needed (only for 'dashboard' or if starting dashboard controller)
        boolean isWeb = false;
        for (String arg : args) {
            if ("dashboard".equalsIgnoreCase(arg)) {
                isWeb = true;
                break;
            }
        }

        SpringApplication app = new SpringApplication(QueueCtlApplication.class);
        if (!isWeb) {
            app.setWebApplicationType(WebApplicationType.NONE);
        } else {
            app.setWebApplicationType(WebApplicationType.SERVLET);
        }
        System.exit(SpringApplication.exit(app.run(args)));
    }

    @Override
    public void run(String... args) throws Exception {
        CommandLine cmd = new CommandLine(mainCommand, factory);
        exitCode = cmd.execute(args);
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }

    private static void ensureDatabaseExists() {
        String dbUrl = System.getenv("DB_URL");
        if (dbUrl == null) {
            dbUrl = "jdbc:postgresql://localhost:5432/queuectl";
        }
        String dbUser = System.getenv("DB_USER");
        if (dbUser == null) {
            dbUser = "postgres";
        }
        String dbPassword = System.getenv("DB_PASSWORD");
        if (dbPassword == null) {
            dbPassword = "postgres";
        }

        String rootUrl = "jdbc:postgresql://localhost:5432/postgres";
        String dbName = "queuectl";

        try {
            if (dbUrl.startsWith("jdbc:postgresql://")) {
                int lastSlash = dbUrl.lastIndexOf('/');
                if (lastSlash > 0) {
                    rootUrl = dbUrl.substring(0, lastSlash) + "/postgres";
                    dbName = dbUrl.substring(lastSlash + 1);
                    int questionMark = dbName.indexOf('?');
                    if (questionMark > 0) {
                        dbName = dbName.substring(0, questionMark);
                    }
                }
            }
        } catch (Exception e) {
            // fallback to defaults
        }

        try (java.sql.Connection conn = java.sql.DriverManager.getConnection(rootUrl, dbUser, dbPassword)) {
            try (java.sql.Statement stmt = conn.createStatement()) {
                String query = "SELECT 1 FROM pg_database WHERE datname = '" + dbName + "'";
                try (java.sql.ResultSet rs = stmt.executeQuery(query)) {
                    if (!rs.next()) {
                        System.out.println("Database '" + dbName + "' not found. Creating it...");
                        stmt.executeUpdate("CREATE DATABASE " + dbName);
                        System.out.println("Database '" + dbName + "' created successfully.");
                    }
                }
            }
        } catch (java.sql.SQLException e) {
            System.err.println("Warning: Could not verify or create database '" + dbName + "': " + e.getMessage());
        }
    }
}

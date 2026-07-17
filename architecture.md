# QueueCTL - System Architecture & Design Document

This document describes the architectural layout, concurrency model, lifecycle flows, and technical design decisions behind **QueueCTL**.

---

## 🏛️ Component Block Diagram

```text
               +----------------------------------------+
               |            CLI Wrapper                 |
               |      (queuectl / queuectl.bat)         |
               +-------------------+--------------------+
                                   |
                                   v
               +-------------------+--------------------+
               |           Picocli CLI Parser           |
               |        (QueueCtlCommand.java)          |
               +-------------------+--------------------+
                                   |
                                   v
  +--------------------------------+--------------------------------+
  |                                                                 |
  v (If 'dashboard' is invoked)                                    v (For other commands)
+--------------------------------+               +----------------------------------+
|      Spring Boot Tomcat        |               |      Spring Boot Context         |
|      Web Server (8080)         |               |     (WebApplicationType.NONE)    |
+----------------+---------------+               +-----------------+----------------+
                 |                                                 |
                 +-----------------------+-------------------------+
                                         |
                                         v
                       +-----------------+-----------------+
                       |         Business Services         |
                       |  - JobQueueService.java           |
                       |  - WorkerManagerService.java      |
                       +-----------------+-----------------+
                                         |
                                         v
                       +-----------------+-----------------+
                       |      Data Persistence Layer       |
                       |  - Spring Data JPA (Repositories) |
                       |  - Row-Level Lock claims          |
                       +-----------------+-----------------+
                                         |
                                         v
                               +---------+---------+
                               |  PostgreSQL / H2  |
                               |    Databases      |
                               +-------------------+
```

---

## 📁 Repository Structure

```text
queuectl/
│
├── frontend/                     # Static Web Console (packaged for Vercel)
│   ├── index.html                # Dark-themed dashboard UI (Glassmorphism layout)
│   └── vercel.json               # Vercel proxy rewrite config mapping to Render
│
├── src/
│   ├── main/
│   │   ├── java/com/queuectl/
│   │   │   ├── QueueCtlApplication.java    # Application entry point & Database Bootstrapper
│   │   │   │
│   │   │   ├── cli/                        # Picocli command implementations
│   │   │   │   ├── QueueCtlCommand.java    # Parent command routing
│   │   │   │   ├── EnqueueCommand.java     # CLI Job submission
│   │   │   │   ├── WorkerCommand.java      # Spawning background worker processes
│   │   │   │   ├── StatusCommand.java      # CLI metrics summary
│   │   │   │   ├── ListCommand.java        # State-based job directory search
│   │   │   │   ├── DlqCommand.java         # Viewing or retrying quarantined jobs
│   │   │   │   ├── ConfigCommand.java      # CLI dynamic parameter overrides
│   │   │   │   └── DashboardCommand.java   # Tomcat server bootstrapper
│   │   │   │
│   │   │   ├── model/                      # JPA Schemas & Data models
│   │   │   │   ├── Job.java                # Job metadata, command strings, timestamps & logs
│   │   │   │   ├── Worker.java             # Registered active JVM instances & heartbeats
│   │   │   │   ├── Configuration.java      # Dyn-configs (max-retries, backoff-base)
│   │   │   │   └── State.java              # Lifecycle State Enums
│   │   │   │
│   │   │   ├── repository/                 # Database Query Access Objects (DAOs)
│   │   │   │   ├── JobRepository.java      # PostgreSQL concurrency locking query
│   │   │   │   ├── WorkerRepository.java   # Heartbeat registry queries
│   │   │   │   └── ConfigurationRepository.java # Key-value parameters selector
│   │   │   │
│   │   │   ├── service/                    # Background core business logic
│   │   │   │   ├── JobQueueService.java    # Core enqueuer, retry calculators & stats logger
│   │   │   │   └── WorkerManagerService.java# Polling threads, process spawners & auto-healer
│   │   │   │
│   │   │   └── controller/                 # REST endpoints for dashboard queries
│   │   │       └── DashboardController.java# JSON APIs supporting dashboard AJAX bindings
│   │   │
│   │   └── resources/
│   │       ├── application.properties      # PostgreSQL connection defaults
│   │       └── static/index.html           # Embedded UI Console serving locally
│   │
│   └── test/
│       ├── java/com/queuectl/
│       │   └── QueueCtlApplicationTests.java # Core Integration test suite
│       └── resources/
│           └── application.properties      # Test database configs (H2 isolation overrides)
│
├── pom.xml                                 # Maven dependencies
├── queuectl.bat                            # Windows command line execution wrapper
├── queuectl                                # Unix shell command line execution wrapper
├── Dockerfile                              # Multi-stage image build manifest
└── docker-compose.yml                      # Container stack runner
```

---

## 🔒 Concurrency & Execution Flow

### 1. Database Row-Level Locking (`FOR UPDATE SKIP LOCKED`)
To support multiple background workers consuming from the same database queue without duplicate executions:
- Workers poll the `jobs` table inside a short database transaction.
- They execute a query matching pending jobs that are eligible for processing:
  ```sql
  SELECT * FROM jobs 
  WHERE (state = 'pending' AND run_at <= NOW()) 
     OR (state = 'failed' AND attempts < max_retries AND run_at <= NOW()) 
  ORDER BY priority DESC, created_at ASC 
  LIMIT 1 FOR UPDATE SKIP LOCKED
  ```
- **How it works**:
  - `FOR UPDATE` places a write lock on the matched row.
  - `SKIP LOCKED` tells database engines to skip any row that is already locked by another worker.
  - Once selected, the thread immediately updates state to `processing` and commits.
  - The lock is released, allowing other threads to poll the next row.
  - The thread then executes the process shell command *outside* the transaction scope, ensuring database pool connections are not blocked during slow commands.

---

## 🛠️ Worker Execution Model

```text
   +-------------------------------------------------------+
   |             ThreadPoolExecutor Service                |
   |                                                       |
   |  +------------+   +------------+   +------------+     |
   |  | Thread #1  |   | Thread #2  |   | Thread #3  |     |
   |  +-----+------+   +-----+------+   +-----+------+     |
   +--------|----------------|----------------|------------+
            v                v                v
      [Claim Job 1]    [Claim Job 2]    [Claim Job 3] (Row locked via SKIP LOCKED)
            |                |                |
            v                v                v
     ProcessBuilder   ProcessBuilder   ProcessBuilder
     (cmd.exe / sh)   (cmd.exe / sh)   (cmd.exe / sh)
            |                |                |
            +----------------+----------------+
                             |
                             v (Captures logs & Exit Code)
                [Update Job States in DB]
```

1. **ThreadPoolExecutor**: The worker daemon maintains a configurable thread pool (configured via `--count`).
2. **Process Spawn**: The thread uses Java's native `ProcessBuilder` (running `cmd.exe /c` on Windows and `sh -c` on Unix) to run the CLI command.
3. **Non-blocking Output Streams**: The thread opens asynchronous input streams to merge `stdout` and `stderr` logs, appending them directly to the database log column.
4. **Timeout Enforcer**: An asynchronous scheduler monitors running threads. If a process execution duration exceeds the job's defined timeout, the thread kills the OS process forcibly and logs a timeout failure.

---

## 🩺 Auto-Healing & Graceful Shutdowns

### 1. Heartbeats & Auto-Healing
- Every worker process starts a background heartbeat thread that updates its database row under `workers` every 2 seconds.
- Every 1 second, a scheduler checks for orphaned workers (last heartbeat > 10 seconds ago).
- Any job still marked `processing` by a dead worker is automatically rescheduled to `failed` or moved to the Dead Letter Queue (DLQ) if retries are exhausted.

### 2. Graceful Shutdown Protocol
- When `queuectl worker stop` is executed, all worker states in the database are updated to `STOPPING`.
- Running workers poll this state. When they see `STOPPING`, they stop taking new jobs.
- The `ThreadPoolExecutor` is ordered to shutdown. The worker process waits up to 60 seconds for active command processes to complete cleanly, updates its state to `STOPPED`, and terminates.

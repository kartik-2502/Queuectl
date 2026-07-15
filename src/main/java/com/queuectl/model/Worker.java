package com.queuectl.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "workers")
public class Worker {

    @Id
    @Column(name = "id")
    private String id;

    @Column(name = "pid", nullable = false)
    private long pid;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false)
    private WorkerState state = WorkerState.ACTIVE;

    @Column(name = "last_heartbeat", nullable = false)
    private Instant lastHeartbeat = Instant.now();

    @Column(name = "thread_count", nullable = false)
    private int threadCount;

    // Constructors
    public Worker() {}

    public Worker(String id, long pid, int threadCount) {
        this.id = id;
        this.pid = pid;
        this.threadCount = threadCount;
    }

    // Getters and Setters
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public long getPid() {
        return pid;
    }

    public void setPid(long pid) {
        this.pid = pid;
    }

    public WorkerState getState() {
        return state;
    }

    public void setState(WorkerState state) {
        this.state = state;
    }

    public Instant getLastHeartbeat() {
        return lastHeartbeat;
    }

    public void setLastHeartbeat(Instant lastHeartbeat) {
        this.lastHeartbeat = lastHeartbeat;
    }

    public int getThreadCount() {
        return threadCount;
    }

    public void setThreadCount(int threadCount) {
        this.threadCount = threadCount;
    }
}

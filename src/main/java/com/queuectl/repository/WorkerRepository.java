package com.queuectl.repository;

import com.queuectl.model.Worker;
import com.queuectl.model.WorkerState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface WorkerRepository extends JpaRepository<Worker, String> {
    List<Worker> findByState(WorkerState state);
    long countByState(WorkerState state);
}

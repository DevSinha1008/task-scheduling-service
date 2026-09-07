package com.scheduler.controller;

import com.scheduler.domain.JobStatus;
import com.scheduler.dto.CreateJobRequest;
import com.scheduler.dto.JobResponse;
import com.scheduler.dto.QueueStatsResponse;
import com.scheduler.service.JobService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Job endpoints. Every route here requires a valid JWT — SecurityConfig
 * permits only /api/auth/** and health anonymously.
 *
 * <p>The controller stays thin on purpose: bind and validate input, call
 * one service method, map the result to a status code. No business logic,
 * no repository access, no transaction handling.
 */
@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private final JobService jobs;

    public JobController(JobService jobs) {
        this.jobs = jobs;
    }

    @PostMapping
    public ResponseEntity<JobResponse> create(@Valid @RequestBody CreateJobRequest req) {
        var job = jobs.create(req);
        return ResponseEntity.status(HttpStatus.CREATED).body(JobResponse.from(job));
    }

    @GetMapping("/{id}")
    public ResponseEntity<JobResponse> getById(@PathVariable UUID id) {
        return jobs.findById(id)
                .map(JobResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping
    public List<JobResponse> listByStatus(
            @RequestParam(defaultValue = "PENDING") JobStatus status) {
        return jobs.findByStatus(status).stream().map(JobResponse::from).toList();
    }

    @GetMapping("/stats")
    public QueueStatsResponse stats() {
        return new QueueStatsResponse(
                jobs.countByStatus(JobStatus.PENDING),
                jobs.countByStatus(JobStatus.RUNNING),
                jobs.countByStatus(JobStatus.SUCCEEDED),
                jobs.countByStatus(JobStatus.DEAD));
    }
}

package com.agri.pit.runs;

import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/runs")
public class RunController {

    private final RunService runs;
    private final RunExecutor executor;

    public RunController(RunService runs, RunExecutor executor) {
        this.runs = runs;
        this.executor = executor;
    }

    @GetMapping
    public List<RunDtos.RunView> list(@RequestParam(required = false) String status) {
        return runs.listRuns(status);
    }

    @GetMapping("/queue/stats")
    public RunDtos.QueueStats stats() {
        return runs.stats();
    }

    @GetMapping("/{runId}")
    public RunDtos.RunDetail get(@PathVariable long runId) {
        return runs.getRun(runId);
    }

    /** Execute a queued run synchronously; useful for deterministic scripted verification. */
    @PostMapping("/{runId}/execute")
    public RunDtos.RunDetail execute(@PathVariable long runId) {
        executor.execute(runId);
        return runs.getRun(runId);
    }
}

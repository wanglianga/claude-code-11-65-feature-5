package com.agri.pit.snapshots;

import com.agri.pit.cases.CaseService;
import com.agri.pit.cases.CaseService.CaseRow;
import com.agri.pit.common.ApiException;
import com.agri.pit.common.Db;
import com.agri.pit.runs.RunDtos;
import com.agri.pit.runs.RunExecutor;
import com.agri.pit.runs.RunService;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class SnapshotController {

    private final SnapshotService snapshots;
    private final CaseService cases;
    private final RunService runs;
    private final RunExecutor executor;
    private final Db db;

    public SnapshotController(SnapshotService snapshots, CaseService cases, RunService runs,
                              RunExecutor executor, Db db) {
        this.snapshots = snapshots;
        this.cases = cases;
        this.runs = runs;
        this.executor = executor;
        this.db = db;
    }

    @GetMapping("/snapshots/{snapshotId}")
    public SnapshotService.SnapshotView getSnapshot(@PathVariable long snapshotId) {
        return snapshots.getSnapshot(snapshotId);
    }

    @GetMapping("/cases/{caseNo}/snapshots")
    public List<Map<String, Object>> listCaseSnapshots(@PathVariable String caseNo) {
        CaseRow row = cases.requireCase(caseNo);
        return snapshots.listSnapshotsForCase(row.id());
    }

    /**
     * One-click reproduction: replay the case at its original cutoff, run it inline, then diff
     * the result against the frozen snapshot. The replay never writes a snapshot.
     */
    @PostMapping("/cases/{caseNo}/reproduce")
    public Map<String, Object> reproduce(@PathVariable String caseNo) {
        CaseRow caseRow = cases.requireCase(caseNo);
        List<Long> snapshotIds = db.jdbc().query(
                "select id from scoring_snapshot where case_id = ? order by created_at desc limit 1",
                (rs, i) -> rs.getLong(1), caseRow.id());
        if (snapshotIds.isEmpty()) {
            throw ApiException.unprocessable("NO_SNAPSHOT", "case has no frozen snapshot to reproduce yet");
        }
        long snapshotId = snapshotIds.get(0);
        Long originalRunId = db.jdbc().queryForObject(
                "select run_id from scoring_snapshot where id = ?", Long.class, snapshotId);
        long replayRunId = runs.queueReplay(caseRow.id(), originalRunId,
                "reproduction requested via API against snapshot " + snapshotId);
        executor.execute(replayRunId);
        SnapshotService.ReplayResult verification = snapshots.verifyReplay(snapshotId, replayRunId);
        RunDtos.RunDetail detail = runs.getRun(replayRunId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("snapshotId", snapshotId);
        result.put("replayRunId", replayRunId);
        result.put("replayStatus", detail.run().status());
        result.put("replayVerdict", detail.run().verdict());
        result.put("valuesMatch", verification.match());
        result.put("differences", verification.differences());
        result.put("snapshotValues", snapshots.getSnapshot(snapshotId).featureValues());
        result.put("replayFeatures", detail.features());
        return result;
    }

    @PostMapping("/snapshots/{snapshotId}/verify-replay")
    public SnapshotService.ReplayResult verify(@PathVariable long snapshotId,
                                               @RequestBody VerifyRequest req) {
        return snapshots.verifyReplay(snapshotId, req.runId());
    }

    @GetMapping("/cases/{caseNo}/replays")
    public List<Map<String, Object>> replays(@PathVariable String caseNo) {
        CaseRow row = cases.requireCase(caseNo);
        return snapshots.replayHistory(row.id());
    }

    public record VerifyRequest(long runId) {
    }
}

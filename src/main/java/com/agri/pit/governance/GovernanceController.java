package com.agri.pit.governance;

import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Shadow-first release governance endpoints: shadow comparisons, release gate records and
 * targeted rollback / batch backfill coordination.
 */
@RestController
@RequestMapping("/api/governance")
public class GovernanceController {

    private final ShadowScorer shadowScorer;
    private final ReleaseGateService gates;
    private final RollbackService rollbacks;
    private final BatchBackfillService batches;

    public GovernanceController(ShadowScorer shadowScorer, ReleaseGateService gates,
                                RollbackService rollbacks, BatchBackfillService batches) {
        this.shadowScorer = shadowScorer;
        this.gates = gates;
        this.rollbacks = rollbacks;
        this.batches = batches;
    }

    // ------------------------------------------------------------ shadow runs

    /** Run a shadow campaign over frozen case snapshots and return every created shadow run. */
    @PostMapping("/shadow/runs")
    public List<GovernanceDtos.ShadowRunView> runShadowCampaign(
            @RequestBody GovernanceDtos.ShadowCampaignRequest req) {
        List<Long> ids = shadowScorer.runCampaign(req);
        return ids.stream().map(shadowScorer::getShadowRun).toList();
    }

    @GetMapping("/shadow/runs")
    public List<Map<String, Object>> listShadowRuns(
            @RequestParam(required = false) String campaignCode) {
        return shadowScorer.listCampaigns(campaignCode);
    }

    @GetMapping("/shadow/runs/{id}")
    public GovernanceDtos.ShadowRunView getShadowRun(@PathVariable long id) {
        return shadowScorer.getShadowRun(id);
    }

    // ------------------------------------------------------------- release gates

    @PostMapping("/gates")
    public GovernanceDtos.GateView submitGate(@RequestBody GovernanceDtos.GateSubmitRequest req) {
        long id = gates.submit(req);
        return gates.getGate(id);
    }

    @GetMapping("/gates")
    public List<GovernanceDtos.GateView> listGates() {
        return gates.list();
    }

    @GetMapping("/gates/{id}")
    public GovernanceDtos.GateView getGate(@PathVariable long id) {
        return gates.getGate(id);
    }

    @PostMapping("/gates/{id}/approve")
    public GovernanceDtos.GateView approve(@PathVariable long id,
                                           @RequestBody(required = false) GovernanceDtos.GateDecisionRequest req) {
        GovernanceDtos.GateDecisionRequest body = req == null
                ? new GovernanceDtos.GateDecisionRequest(null, null) : req;
        return gates.decide(id, true, body.decisionBy(), body.note());
    }

    @PostMapping("/gates/{id}/reject")
    public GovernanceDtos.GateView reject(@PathVariable long id,
                                          @RequestBody(required = false) GovernanceDtos.GateDecisionRequest req) {
        GovernanceDtos.GateDecisionRequest body = req == null
                ? new GovernanceDtos.GateDecisionRequest(null, null) : req;
        return gates.decide(id, false, body.decisionBy(), body.note());
    }

    // --------------------------------------------------------------- rollbacks

    @PostMapping("/rollbacks")
    public GovernanceDtos.RollbackView createRollback(@RequestBody GovernanceDtos.RollbackRequest req) {
        long id = rollbacks.createRollback(req);
        return rollbacks.get(id);
    }

    @GetMapping("/rollbacks")
    public List<GovernanceDtos.RollbackView> listRollbacks() {
        return rollbacks.list();
    }

    @GetMapping("/rollbacks/{id}")
    public GovernanceDtos.RollbackView getRollback(@PathVariable long id) {
        return rollbacks.get(id);
    }

    @PostMapping("/rollbacks/{id}/revoke")
    public GovernanceDtos.RollbackView revoke(@PathVariable long id,
                                              @RequestBody(required = false)
                                              GovernanceDtos.RollbackRevokeRequest req) {
        rollbacks.revoke(id, req == null ? null : req.note());
        return rollbacks.get(id);
    }

    // ---------------------------------------------------------- batch backfills

    @PostMapping("/backfill-batches")
    public GovernanceDtos.BatchView createBatch(@RequestBody GovernanceDtos.BatchCreateRequest req) {
        long id = batches.createBatch(req);
        return batches.getBatch(id);
    }

    @PostMapping("/backfill-batches/{id}/run")
    public GovernanceDtos.BatchView runBatch(@PathVariable long id) {
        return batches.runBatch(id);
    }

    @PostMapping("/backfill-shards/{id}/run")
    public GovernanceDtos.ShardView runShard(@PathVariable long id) {
        return batches.runShard(id);
    }

    @PostMapping("/backfill-shards/{id}/plan")
    public GovernanceDtos.ShardView planShard(@PathVariable long id) {
        return batches.planShardRun(id);
    }

    @GetMapping("/backfill-batches")
    public List<GovernanceDtos.BatchView> listBatches() {
        return batches.list();
    }

    @GetMapping("/backfill-batches/{id}")
    public GovernanceDtos.BatchView getBatch(@PathVariable long id) {
        return batches.getBatch(id);
    }
}

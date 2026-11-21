package com.agri.pit.governance;

import com.agri.pit.catalog.CatalogDtos;
import com.agri.pit.catalog.CatalogService;
import com.agri.pit.common.Db;
import com.agri.pit.demo.DemoSeeder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Scripted demos for the shadow-first release workflow. Every endpoint is deterministic given the
 * seeded data and tolerates repeated execution by reusing existing artefacts where possible.
 */
@RestController
@RequestMapping("/api/demo/governance")
public class GovernanceDemoController {

    private final ShadowScorer shadowScorer;
    private final ReleaseGateService gates;
    private final RollbackService rollbacks;
    private final BatchBackfillService batches;
    private final CatalogService catalog;
    private final Db db;

    public GovernanceDemoController(ShadowScorer shadowScorer, ReleaseGateService gates,
                                    RollbackService rollbacks, BatchBackfillService batches,
                                    CatalogService catalog, Db db) {
        this.shadowScorer = shadowScorer;
        this.gates = gates;
        this.rollbacks = rollbacks;
        this.batches = batches;
        this.catalog = catalog;
        this.db = db;
    }

    /** Index of the three governance demos and what each one proves. */
    @GetMapping
    public Map<String, Object> index() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("demos", List.of(
                Map.of("endpoint", "POST /api/demo/governance/shadow",
                        "proves", "double computation over frozen snapshots never touches production claims; "
                                + "a complete-chain over-threshold diff is classified BUSINESS_DIFFERENCE"),
                Map.of("endpoint", "POST /api/demo/governance/gates",
                        "proves", "only threshold- and temporal-passing definitions can be promoted; a gate "
                                + "whose catalog changed while open is INVALIDATED and cannot be approved"),
                Map.of("endpoint", "POST /api/demo/governance/rollback",
                        "proves", "a targeted rollback terminates only the shards reading the rolled-back "
                                + "version; shards on other versions keep their results")));
        body.put("counts", db.jdbc().queryForMap("""
                select (select count(*) from shadow_run) as shadowRuns,
                       (select count(*) from release_gate) as gates,
                       (select count(*) from feature_rollback) as rollbacks,
                       (select count(*) from backfill_batch) as batches
                """));
        return body;
    }

    /**
     * Shadow comparison demo: score both seeded cases with NDVI_DROUGHT v2 (a material formula
     * change whose input chain is complete). Over-threshold features must come back as
     * BUSINESS_DIFFERENCE routed to the rule owner, not as time-travel failures.
     */
    @PostMapping("/shadow")
    public Map<String, Object> shadowDemo() {
        String campaign = "DEMO-SHADOW-NDVI-v2-" + Instant.now().toEpochMilli();
        List<Long> ids = shadowScorer.runCampaign(new GovernanceDtos.ShadowCampaignRequest(
                campaign, "NDVI_DROUGHT", 2,
                List.of(DemoSeeder.CASE_PRIMARY, DemoSeeder.CASE_SECONDARY),
                0.15d, 0.10d, "demo shadow release",
                "governance demo: business difference on a complete chain", "demo-operator"));
        List<GovernanceDtos.ShadowRunView> runs = ids.stream().map(shadowScorer::getShadowRun).toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("campaignCode", campaign);
        result.put("shadowRuns", runs);
        result.put("expectation", "each run stays temporalVerdict=PASS with chainComplete=true; "
                + "NDVI_DROUGHT over the 0.15 threshold is classified BUSINESS_DIFFERENCE and no snapshot, "
                + "claim status or review candidate is created");
        return result;
    }

    /**
     * Release gate demo covering all three outcomes:
     * <ol>
     *   <li>RAIN_ANOMALY v2 — passes every check and is approved end to end;</li>
     *   <li>NDVI_DROUGHT v3 — effective only after the cutoff, temporal check fails and approval
     *       is refused with GATE_CHECKS_FAILED;</li>
     *   <li>NDVI_DROUGHT v2 — a catalog mutation while the gate is open invalidates its
     *       fingerprint, after which every approval attempt fails with GATE_INVALIDATED.</li>
     * </ol>
     */
    @PostMapping("/gates")
    public Map<String, Object> gateDemo() {
        Map<String, Object> result = new LinkedHashMap<>();

        if (isDraft("RAIN_ANOMALY", 2)) {
            long cleanGateId = gates.submit(new GovernanceDtos.GateSubmitRequest(
                    "RAIN_ANOMALY", 2, "demo clean gate", 0.15d, 0.20d, 0.50d,
                    List.of(DemoSeeder.CASE_PRIMARY, DemoSeeder.CASE_SECONDARY),
                    "demo-operator", "governance gate demo"));
            result.put("cleanGateBeforeApproval", gates.getGate(cleanGateId));
            result.put("cleanGateApproval", gates.decide(cleanGateId, true, "release-manager",
                    "threshold and temporal checks passed; promoted to production"));
        } else {
            result.put("cleanGate", Map.of("state", "SKIPPED",
                    "reason", "RAIN_ANOMALY v2 is no longer DRAFT (already consumed by a previous demo run)"));
        }

        if (isDraft("NDVI_DROUGHT", 2)) {
            long invalidatedGateId = gates.submit(new GovernanceDtos.GateSubmitRequest(
                    "NDVI_DROUGHT", 2, "demo invalidation gate", 0.15d, 0.20d, 0.50d,
                    List.of(DemoSeeder.CASE_PRIMARY), "demo-operator",
                    "gate open while the catalog is about to change"));
            int nextVersion = nextVersionNo("NDVI_DROUGHT");
            catalog.createVersion("NDVI_DROUGHT", new CatalogDtos.CreateVersionRequest(
                    "clamp(1.35 - ndvi_mean + 0.2 * ndvi_range, 0, 1)", true, 0L,
                    "AS_OF_CUTOFF", 30, "draft created after gate submission to invalidate it",
                    Instant.parse("2024-02-15T00:00:00Z")));
            result.put("catalogMutation", "NDVI_DROUGHT v" + nextVersion
                    + " created while gate " + invalidatedGateId + " was PENDING_APPROVAL");
            result.put("invalidatedGate", gates.getGate(invalidatedGateId));
            result.put("invalidatedApprovalAttempt",
                    safeApprove(invalidatedGateId, "GATE_INVALIDATED"));
        } else {
            result.put("invalidatedGate", Map.of("state", "SKIPPED",
                    "reason", "NDVI_DROUGHT v2 is no longer DRAFT (already consumed by a previous demo run)"));
        }

        // Submitted after the catalog mutation, so it captures a fresh fingerprint; its refusal is
        // purely the temporal check, proving time-travel failures block independently of invalidation.
        if (isDraft("NDVI_DROUGHT", 3)) {
            long blockedGateId = gates.submit(new GovernanceDtos.GateSubmitRequest(
                    "NDVI_DROUGHT", 3, "demo time-travel gate", 0.15d, 0.20d, 0.50d,
                    List.of(DemoSeeder.CASE_PRIMARY), "demo-operator",
                    "definition effective only after the case cutoff"));
            result.put("timeTravelGate", gates.getGate(blockedGateId));
            result.put("timeTravelApprovalAttempt",
                    safeApprove(blockedGateId, "GATE_CHECKS_FAILED"));
        }

        result.put("allGates", gates.list());
        result.put("expectation", "the clean gate reaches APPROVED; the time-travel gate keeps "
                + "temporalPassed=false and cannot be approved; the invalidated gate is INVALIDATED and "
                + "every approval attempt is refused with GATE_INVALIDATED");
        return result;
    }

    /** Call gate approval and translate the expected refusal into a serialisable demo record. */
    private Map<String, Object> safeApprove(long gateId, String expectedErrorCode) {
        try {
            return Map.of("unexpected", gates.decide(gateId, true, "demo-operator", "should have failed"));
        } catch (com.agri.pit.common.ApiException e) {
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("refused", true);
            record.put("errorCode", e.getCode());
            record.put("expectedErrorCode", expectedErrorCode);
            record.put("matched", expectedErrorCode.equals(e.getCode()));
            record.put("message", e.getMessage());
            return record;
        }
    }

    /**
     * Targeted rollback demo. A four-shard batch backfills both cases while it is in flight:
     * <ul>
     *   <li>shard 1 (case 1, NDVI_DROUGHT) finishes before the rollback and keeps its result;</li>
     *   <li>shard 2 (case 2, NDVI_DROUGHT) is RUNNING with a queued run when the rollback lands,
     *       so its run is terminated and the shard becomes TERMINATED_BY_ROLLBACK;</li>
     *   <li>shard 3 (case 2, RAIN_ANOMALY) depends on a different definition version, is not
     *       touched by the NDVI rollback and still completes SUCCEEDED;</li>
     *   <li>shard 4 (case 2, NDVI_DROUGHT) is still QUEUED without a run, so it is quarantined
     *       before it can ever read the disabled version.</li>
     * </ul>
     * The batch ends PARTIALLY_TERMINATED; frozen snapshots are never modified.
     */
    @PostMapping("/rollback")
    public Map<String, Object> rollbackDemo() {
        String batchCode = "DEMO-ROLLBACK-" + Instant.now().toEpochMilli();
        long batchId = batches.createBatch(new GovernanceDtos.BatchCreateRequest(batchCode,
                List.of(new GovernanceDtos.BatchShardRequest(DemoSeeder.CASE_PRIMARY, "NDVI_DROUGHT"),
                        new GovernanceDtos.BatchShardRequest(DemoSeeder.CASE_SECONDARY, "NDVI_DROUGHT"),
                        new GovernanceDtos.BatchShardRequest(DemoSeeder.CASE_SECONDARY, "RAIN_ANOMALY"),
                        new GovernanceDtos.BatchShardRequest(DemoSeeder.CASE_SECONDARY, "NDVI_DROUGHT")),
                "demo-operator", "in-flight batch meeting a targeted, case-scoped rollback"));
        List<GovernanceDtos.ShardView> shards = batches.getBatch(batchId).shards();
        long finishedShard = shards.get(0).id();
        long runningNdviShard = shards.get(1).id();
        long runningRainShard = shards.get(2).id();
        long queuedNdviShard = shards.get(3).id();

        GovernanceDtos.ShardView finished = batches.runShard(finishedShard);
        // Two shards are dispatched but their runs are still queued: an in-flight backfill.
        GovernanceDtos.ShardView runningNdvi = batches.planShardRun(runningNdviShard);
        GovernanceDtos.ShardView runningRain = batches.planShardRun(runningRainShard);

        GovernanceDtos.RollbackView rollback = rollbacks.get(rollbacks.createRollback(
                new GovernanceDtos.RollbackRequest(
                        "NDVI_DROUGHT", 1, "CASE_LIST", List.of(DemoSeeder.CASE_SECONDARY),
                        "demo: stop NDVI_DROUGHT v1 reads for the secondary case after a suspected reversal",
                        "demo-operator")));

        // The rain shard reads a different definition version, so the rollback leaves it runnable.
        GovernanceDtos.ShardView rainAfter = batches.runShard(runningRainShard);
        GovernanceDtos.ShardView queuedAfter = batches.runShard(queuedNdviShard);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("batchCode", batchCode);
        result.put("finishedShardBeforeRollback", finished);
        result.put("runningNdviShardBeforeRollback", runningNdvi);
        result.put("runningRainShardBeforeRollback", runningRain);
        result.put("rollback", rollback);
        result.put("ndviShardAfterRollback", batches.getBatch(batchId).shards().stream()
                .filter(s -> s.id() == runningNdviShard).findFirst().orElseThrow());
        result.put("rainShardAfterRollback", rainAfter);
        result.put("queuedNdviShardAfterRollback", queuedAfter);
        result.put("batch", batches.getBatch(batchId));
        result.put("expectation", "the in-flight NDVI shard is TERMINATED_BY_ROLLBACK and its queued run is "
                + "TERMINATED; the still-queued NDVI shard is QUARANTINED; the RAIN_ANOMALY shard and the "
                + "already-finished shard keep SUCCEEDED results; the batch is PARTIALLY_TERMINATED and no "
                + "frozen snapshot changes");
        return result;
    }

    private boolean isDraft(String featureCode, int versionNo) {
        var feature = catalog.requireFeature(featureCode);
        return "DRAFT".equals(catalog.requireVersion(feature.id(), versionNo).status());
    }

    private int nextVersionNo(String featureCode) {
        return db.jdbc().queryForObject("""
                select coalesce(max(version_no), 0) + 1 from feature_definition_version fv
                  join feature_definition fd on fd.id = fv.feature_definition_id
                 where fd.code = ?
                """, Integer.class, featureCode);
    }
}

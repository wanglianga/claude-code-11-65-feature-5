package com.agri.pit.governance;

import com.agri.pit.cases.CaseService;
import com.agri.pit.cases.CaseService.CaseRow;
import com.agri.pit.common.ApiException;
import com.agri.pit.common.Db;
import com.agri.pit.common.Times;
import com.agri.pit.runs.RunExecutor;
import com.agri.pit.runs.RunService;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Sharded batch backfill orchestrator.
 * <p>
 * Each shard recomputes one case/feature pair and records the exact definition versions it
 * depends on in {@code depends_on_version_ids}. That explicit dependency set is what lets a
 * targeted rollback terminate only the shards reading the rolled-back version while shards
 * pinned to other definition versions keep their results.
 */
@Service
public class BatchBackfillService {

    private final Db db;
    private final CaseService cases;
    private final RunService runs;
    private final RunExecutor executor;
    private final RollbackService rollbacks;

    public BatchBackfillService(Db db, CaseService cases, RunService runs, RunExecutor executor,
                                RollbackService rollbacks) {
        this.db = db;
        this.cases = cases;
        this.runs = runs;
        this.executor = executor;
        this.rollbacks = rollbacks;
    }

    @Transactional
    public long createBatch(GovernanceDtos.BatchCreateRequest req) {
        if (req.batchCode() == null || req.batchCode().isBlank()) {
            throw ApiException.badRequest("BATCH_CODE_REQUIRED", "batchCode is required");
        }
        Integer exists = db.jdbc().queryForObject(
                "select count(*) from backfill_batch where batch_code = ?",
                Integer.class, req.batchCode().trim());
        if (exists != null && exists > 0) {
            throw ApiException.conflict("BATCH_EXISTS", "batch already exists: " + req.batchCode());
        }
        if (req.shards() == null || req.shards().isEmpty()) {
            throw ApiException.badRequest("SHARDS_REQUIRED", "a batch needs at least one shard");
        }
        long batchId = db.insert("""
                        insert into backfill_batch (batch_code, status, requested_by, note)
                        values (?, 'PLANNED', ?, ?)
                        """,
                req.batchCode().trim(),
                req.requestedBy() == null ? "batch-scheduler" : req.requestedBy(), req.note());
        int shardNo = 0;
        for (GovernanceDtos.BatchShardRequest shardReq : req.shards()) {
            shardNo++;
            CaseRow caseRow = cases.requireCase(shardReq.caseNo());
            long versionId = lockedVersionId(caseRow.id(), shardReq.featureCode());
            ArrayNode depends = db.mapper().createArrayNode();
            depends.add(versionId);
            db.insert("""
                            insert into backfill_shard
                                (batch_id, shard_no, case_id, feature_code, depends_on_version_ids, status)
                            values (?, ?, ?, ?, ?::jsonb, 'QUEUED')
                            """,
                    batchId, shardNo, caseRow.id(), shardReq.featureCode().trim().toUpperCase(),
                    db.jsonArg(depends));
        }
        return batchId;
    }

    /**
     * Enqueue a shard's feature run without executing it, so the shard sits RUNNING with a
     * QUEUED run. This is the state a real scheduler leaves shards in between dispatch ticks,
     * and it is what lets a rollback demonstrate terminating an in-progress shard.
     */
    @Transactional
    public GovernanceDtos.ShardView planShardRun(long shardId) {
        ShardRow shard = requireShard(shardId);
        if (!"QUEUED".equals(shard.status()) || shard.featureRunId() != null) {
            throw ApiException.conflict("SHARD_NOT_PLANNABLE",
                    "shard " + shardId + " is " + shard.status() + " and cannot be planned again");
        }
        if (hasDisabledDependency(shard)) {
            db.jdbc().update("""
                            update backfill_shard
                               set status = 'QUARANTINED',
                                   termination_reason = 'a dependency version is disabled by an active rollback',
                                   updated_at = now()
                             where id = ?
                            """, shardId);
            rollbacks.refreshBatchStatus(shard.batchId());
            return getBatch(shard.batchId()).shards().stream()
                    .filter(view -> view.id() == shardId).findFirst().orElseThrow();
        }
        long runId = runs.queueBatchBackfillShard(shard.caseId(), Instant.now(), null,
                "planned batch shard " + shard.shardNo() + " feature " + shard.featureCode());
        db.jdbc().update("""
                        update backfill_shard
                           set status = 'RUNNING', feature_run_id = ?, updated_at = now()
                         where id = ?
                        """, runId, shardId);
        rollbacks.refreshBatchStatus(shard.batchId());
        return getBatch(shard.batchId()).shards().stream()
                .filter(view -> view.id() == shardId).findFirst().orElseThrow();
    }

    /** Execute a single queued shard; used by the rollback demo to interleave a rollback mid-batch. */
    @Transactional
    public GovernanceDtos.ShardView runShard(long shardId) {
        ShardRow shard = requireShard(shardId);
        if ("QUEUED".equals(shard.status())) {
            if (hasDisabledDependency(shard)) {
                db.jdbc().update("""
                                update backfill_shard
                                   set status = 'QUARANTINED',
                                       termination_reason = 'a dependency version is disabled by an active rollback',
                                       updated_at = now()
                                 where id = ?
                                """, shardId);
            } else {
                runOneShard(shard);
            }
        } else if ("RUNNING".equals(shard.status()) && shard.featureRunId() != null) {
            executePlannedShard(shard);
        }
        rollbacks.refreshBatchStatus(shard.batchId());
        return getBatch(shard.batchId()).shards().stream()
                .filter(view -> view.id() == shardId).findFirst().orElseThrow();
    }

    /** Execute the run that {@link #planShardRun} already enqueued for a dispatched shard. */
    private void executePlannedShard(ShardRow shard) {
        try {
            String runStatus = db.jdbc().queryForObject(
                    "select status from feature_run where id = ?", String.class, shard.featureRunId());
            if ("TERMINATED".equals(runStatus)) {
                // A rollback stopped this run; the shard state is owned by the rollback reconciliation.
                return;
            }
            RunExecutor.ClaimResult result = executor.execute(shard.featureRunId());
            persistShardResult(shard, shard.featureRunId(), result);
        } catch (RuntimeException e) {
            ObjectNode error = db.mapper().createObjectNode();
            error.put("error", String.valueOf(e.getMessage()));
            db.jdbc().update("""
                            update backfill_shard
                               set status = 'FAILED', result = ?::jsonb,
                                   termination_reason = ?, updated_at = now()
                             where id = ?
                            """, db.jsonArg(error), truncate(e.getMessage()), shard.id());
        }
    }

    /** Execute every queued shard synchronously, honouring rollbacks discovered along the way. */
    @Transactional
    public GovernanceDtos.BatchView runBatch(long batchId) {
        requireBatch(batchId);
        db.jdbc().update("update backfill_batch set status = 'RUNNING', started_at = now() where id = ?",
                batchId);
        List<Long> shardIds = db.jdbc().query(
                "select id from backfill_shard where batch_id = ? order by shard_no",
                (rs, i) -> rs.getLong(1), batchId);
        for (long shardId : shardIds) {
            ShardRow shard = requireShard(shardId);
            if (!"QUEUED".equals(shard.status())) {
                // A rollback may have terminated or quarantined it while the batch was running.
                continue;
            }
            if (hasDisabledDependency(shard)) {
                db.jdbc().update("""
                                update backfill_shard
                                   set status = 'QUARANTINED',
                                       termination_reason = 'a dependency version is disabled by an active rollback',
                                       updated_at = now()
                                 where id = ?
                                """, shardId);
                continue;
            }
            runOneShard(shard);
        }
        rollbacks.refreshBatchStatus(batchId);
        return getBatch(batchId);
    }

    private void runOneShard(ShardRow shard) {
        try {
            Instant ceiling = Instant.now();
            long runId = runs.queueBatchBackfillShard(shard.caseId(), ceiling, null,
                    "batch shard " + shard.shardNo() + " feature " + shard.featureCode());
            db.jdbc().update("""
                            update backfill_shard
                               set status = 'RUNNING', feature_run_id = ?, updated_at = now()
                             where id = ? and status = 'QUEUED'
                            """, runId, shard.id());
            RunExecutor.ClaimResult result = executor.execute(runId);
            persistShardResult(shard, runId, result);
        } catch (RuntimeException e) {
            ObjectNode error = db.mapper().createObjectNode();
            error.put("error", String.valueOf(e.getMessage()));
            db.jdbc().update("""
                            update backfill_shard
                               set status = 'FAILED', result = ?::jsonb,
                                   termination_reason = ?, updated_at = now()
                             where id = ?
                            """, db.jsonArg(error), truncate(e.getMessage()), shard.id());
        }
    }

    private void persistShardResult(ShardRow shard, long runId, RunExecutor.ClaimResult result) {
        ObjectNode resultNode = db.mapper().createObjectNode();
        resultNode.put("runId", runId);
        resultNode.put("runStatus", result.status());
        resultNode.put("verdict", result.verdict());
        db.jdbc().query("""
                        select value, status from feature_run_feature
                         where run_id = ? and feature_code = ?
                        """, rs -> {
            if (rs.next()) {
                resultNode.put("featureStatus", rs.getString("status"));
                if (rs.getBigDecimal("value") != null) {
                    resultNode.put("value", rs.getBigDecimal("value").doubleValue());
                }
            }
        }, runId, shard.featureCode());
        String shardStatus = "SUCCEEDED".equals(result.status()) ? "SUCCEEDED" : "FAILED";
        db.jdbc().update("""
                        update backfill_shard
                           set status = ?, result = ?::jsonb, updated_at = now()
                         where id = ?
                        """, shardStatus, db.jsonArg(resultNode), shard.id());
    }

    private boolean hasDisabledDependency(ShardRow shard) {
        List<Long> depends = new ArrayList<>();
        db.readTree(shard.dependsOnJson()).forEach(node -> depends.add(node.asLong()));
        for (Long versionId : depends) {
            if (!rollbacks.isReadEnabled(versionId, shard.caseId())) {
                return true;
            }
        }
        return false;
    }

    private long lockedVersionId(long caseId, String featureCode) {
        List<Long> ids = db.jdbc().query("""
                        select l.feature_definition_version_id
                          from claim_case_feature_lock l
                          join feature_definition_version fv on fv.id = l.feature_definition_version_id
                          join feature_definition fd on fd.id = fv.feature_definition_id
                         where l.case_id = ? and fd.code = ?
                        """,
                (rs, i) -> rs.getLong(1), caseId, featureCode.trim().toUpperCase());
        if (ids.isEmpty()) {
            throw ApiException.unprocessable("NO_LOCKED_VERSION",
                    "case does not lock feature " + featureCode + "; cannot plan a shard for it");
        }
        return ids.get(0);
    }

    public List<GovernanceDtos.BatchView> list() {
        List<Long> ids = db.jdbc().query("select id from backfill_batch order by id desc",
                (rs, i) -> rs.getLong(1));
        List<GovernanceDtos.BatchView> views = new ArrayList<>();
        for (Long id : ids) {
            views.add(getBatch(id));
        }
        return views;
    }

    public GovernanceDtos.BatchView getBatch(long batchId) {
        BatchRow batch = requireBatch(batchId);
        List<GovernanceDtos.ShardView> shards = db.jdbc().query("""
                        select bs.*, c.case_no from backfill_shard bs
                          join claim_case c on c.id = bs.case_id
                         where bs.batch_id = ? order by bs.shard_no
                        """,
                (rs, i) -> new GovernanceDtos.ShardView(
                        rs.getLong("id"), rs.getInt("shard_no"), rs.getLong("case_id"),
                        rs.getString("case_no"), rs.getString("feature_code"),
                        readLongList(rs.getString("depends_on_version_ids")),
                        rs.getString("status"), (Long) rs.getObject("feature_run_id"),
                        rs.getString("termination_reason"), db.readTree(rs.getString("result")),
                        Times.format(Db.instant(rs, "created_at")),
                        Times.format(Db.instant(rs, "updated_at"))),
                batchId);
        return new GovernanceDtos.BatchView(batch.id(), batch.batchCode(), batch.status(),
                batch.requestedBy(), batch.note(), Times.format(batch.createdAt()),
                Times.format(batch.startedAt()), Times.format(batch.finishedAt()), shards);
    }

    private List<Long> readLongList(String json) {
        List<Long> values = new ArrayList<>();
        db.readTree(json).forEach(node -> values.add(node.asLong()));
        return values;
    }

    private BatchRow requireBatch(long batchId) {
        List<BatchRow> rows = db.jdbc().query("select * from backfill_batch where id = ?",
                (rs, i) -> new BatchRow(rs.getLong("id"), rs.getString("batch_code"),
                        rs.getString("status"), rs.getString("requested_by"), rs.getString("note"),
                        Db.instant(rs, "created_at"), Db.instant(rs, "started_at"),
                        Db.instant(rs, "finished_at")), batchId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("BATCH_NOT_FOUND", "backfill batch not found: " + batchId);
        }
        return rows.get(0);
    }

    private ShardRow requireShard(long shardId) {
        List<ShardRow> rows = db.jdbc().query("select * from backfill_shard where id = ?",
                (rs, i) -> new ShardRow(rs.getLong("id"), rs.getLong("batch_id"), rs.getInt("shard_no"),
                        rs.getLong("case_id"), rs.getString("feature_code"),
                        rs.getString("depends_on_version_ids"), rs.getString("status"),
                        (Long) rs.getObject("feature_run_id"), rs.getString("termination_reason")),
                shardId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("SHARD_NOT_FOUND", "backfill shard not found: " + shardId);
        }
        return rows.get(0);
    }

    private static String truncate(String text) {
        if (text == null) {
            return "unknown error";
        }
        return text.length() <= 2000 ? text : text.substring(0, 2000);
    }

    private record BatchRow(long id, String batchCode, String status, String requestedBy, String note,
                            Instant createdAt, Instant startedAt, Instant finishedAt) {
    }

    private record ShardRow(long id, long batchId, int shardNo, long caseId, String featureCode,
                            String dependsOnJson, String status, Long featureRunId,
                            String terminationReason) {
    }
}

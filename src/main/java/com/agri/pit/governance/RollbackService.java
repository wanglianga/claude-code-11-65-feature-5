package com.agri.pit.governance;

import com.agri.pit.cases.CaseService;
import com.agri.pit.catalog.CatalogService;
import com.agri.pit.common.ApiException;
import com.agri.pit.common.Db;
import com.agri.pit.common.Times;
import com.agri.pit.runs.RunService;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Targeted rollback controller.
 * <p>
 * A rollback disables reads of one definition version, either for every case or for an explicit
 * case list. Frozen snapshots are immutable and therefore untouched; the switch only affects
 * subsequent feature runs. When a rollback meets a running batch backfill, only the shards whose
 * dependency set contains the rolled-back version are terminated or quarantined; shards pinned to
 * other definition versions keep running and their results are preserved.
 */
@Service
public class RollbackService {

    private final Db db;
    private final CaseService cases;
    private final CatalogService catalog;
    private final RunService runs;

    public RollbackService(Db db, CaseService cases, CatalogService catalog, RunService runs) {
        this.db = db;
        this.cases = cases;
        this.catalog = catalog;
        this.runs = runs;
    }

    public record RollbackRow(long id, long featureDefinitionId, long versionId, String scopeType,
                              List<Long> caseIds, String status, String reason, String requestedBy,
                              int terminatedShards, int quarantinedShards, Instant createdAt,
                              Instant revokedAt, String revokeNote) {
    }

    @Transactional
    public long createRollback(GovernanceDtos.RollbackRequest req) {
        if (req.reason() == null || req.reason().isBlank()) {
            throw ApiException.badRequest("ROLLBACK_REASON_REQUIRED", "a rollback reason is required");
        }
        CatalogService.FeatureRow feature = catalog.requireFeature(req.featureCode());
        CatalogService.VersionRow version = catalog.requireVersion(feature.id(), req.versionNo());
        String scope = req.scopeType() == null ? "ALL_CASES" : req.scopeType().trim().toUpperCase();
        if (!scope.equals("ALL_CASES") && !scope.equals("CASE_LIST")) {
            throw ApiException.badRequest("INVALID_ROLLBACK_SCOPE",
                    "scopeType must be ALL_CASES or CASE_LIST");
        }
        List<Long> caseIds = new ArrayList<>();
        if (scope.equals("CASE_LIST")) {
            if (req.caseNos() == null || req.caseNos().isEmpty()) {
                throw ApiException.badRequest("CASE_SCOPE_REQUIRED",
                        "CASE_LIST rollbacks must name at least one case");
            }
            Set<String> unique = new LinkedHashSet<>(req.caseNos());
            for (String caseNo : unique) {
                caseIds.add(cases.requireCase(caseNo).id());
            }
        }
        ArrayNode scopeArray = db.mapper().createArrayNode();
        caseIds.forEach(scopeArray::add);
        long rollbackId = db.insert("""
                        insert into feature_rollback
                            (feature_definition_id, rolled_back_version_id, scope_type, case_scope,
                             status, reason, requested_by)
                        values (?, ?, ?, ?::jsonb, 'ACTIVE', ?, ?)
                        """,
                feature.id(), version.id(), scope, db.jsonArg(scopeArray),
                req.reason().trim(),
                req.requestedBy() == null || req.requestedBy().isBlank()
                        ? "rollback-controller" : req.requestedBy().trim());

        ReconcileOutcome outcome = reconcileShards(version.id(),
                "targeted rollback " + rollbackId + " disabled " + feature.code() + " v" + req.versionNo());
        db.jdbc().update("""
                        update feature_rollback
                           set terminated_shard_count = ?, quarantined_shard_count = ?
                         where id = ?
                        """, outcome.terminated(), outcome.quarantined(), rollbackId);
        return rollbackId;
    }

    /** True when a production feature run is allowed to read this definition version for the case. */
    public boolean isReadEnabled(long versionId, long caseId) {
        Integer blocked = db.jdbc().queryForObject("""
                        select count(*) from feature_rollback r
                         where r.rolled_back_version_id = ?
                           and r.status = 'ACTIVE'
                           and (
                                r.scope_type = 'ALL_CASES'
                             or (r.scope_type = 'CASE_LIST'
                                 and exists (select 1 from jsonb_array_elements_text(r.case_scope) s
                                              where s = ?::text))
                           )
                        """, Integer.class, versionId, String.valueOf(caseId));
        return blocked == null || blocked == 0;
    }

    @Transactional
    public void revoke(long rollbackId, String note) {
        RollbackRow row = requireRollback(rollbackId);
        if (!"ACTIVE".equals(row.status())) {
            throw ApiException.conflict("ROLLBACK_NOT_ACTIVE",
                    "rollback " + rollbackId + " is " + row.status());
        }
        db.jdbc().update("""
                        update feature_rollback set status = 'REVOKED', revoked_at = now(), revoke_note = ?
                         where id = ?
                        """, note, rollbackId);
    }

    public List<GovernanceDtos.RollbackView> list() {
        List<RollbackRow> rows = db.jdbc().query("""
                        select * from feature_rollback order by id desc
                        """, (rs, i) -> mapRow(rs));
        return rows.stream().map(this::toView).toList();
    }

    public GovernanceDtos.RollbackView get(long rollbackId) {
        return toView(requireRollback(rollbackId));
    }

    private RollbackRow requireRollback(long rollbackId) {
        List<RollbackRow> rows = db.jdbc().query("select * from feature_rollback where id = ?",
                (rs, i) -> mapRow(rs), rollbackId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("ROLLBACK_NOT_FOUND", "rollback not found: " + rollbackId);
        }
        return rows.get(0);
    }

    private RollbackRow mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        List<Long> caseIds = new ArrayList<>();
        String scopeJson = rs.getString("case_scope");
        if (scopeJson != null) {
            db.readTree(scopeJson).forEach(node -> caseIds.add(Long.parseLong(node.asText())));
        }
        return new RollbackRow(rs.getLong("id"), rs.getLong("feature_definition_id"),
                rs.getLong("rolled_back_version_id"), rs.getString("scope_type"), caseIds,
                rs.getString("status"), rs.getString("reason"), rs.getString("requested_by"),
                rs.getInt("terminated_shard_count"), rs.getInt("quarantined_shard_count"),
                Db.instant(rs, "created_at"), Db.instant(rs, "revoked_at"), rs.getString("revoke_note"));
    }

    private GovernanceDtos.RollbackView toView(RollbackRow row) {
        String featureCode = db.jdbc().queryForObject("""
                select fd.code from feature_definition fd
                  join feature_definition_version fv on fv.feature_definition_id = fd.id
                 where fv.id = ?
                """, String.class, row.versionId());
        Integer versionNo = db.jdbc().queryForObject(
                "select version_no from feature_definition_version where id = ?",
                Integer.class, row.versionId());
        List<String> caseNos = new ArrayList<>();
        for (Long caseId : row.caseIds()) {
            caseNos.add(db.jdbc().queryForObject(
                    "select case_no from claim_case where id = ?", String.class, caseId));
        }
        return new GovernanceDtos.RollbackView(row.id(), featureCode, versionNo, row.versionId(),
                row.scopeType(), caseNos, row.status(), row.reason(), row.requestedBy(),
                row.terminatedShards(), row.quarantinedShards(),
                Times.format(row.createdAt()), Times.format(row.revokedAt()), row.revokeNote());
    }

    /**
     * Stop every unfinished batch shard that depends on the rolled-back version. Shards already
     * terminal are left alone, and shards depending only on other versions are not touched.
     */
    @Transactional
    public ReconcileOutcome reconcileShards(long rolledBackVersionId, String reason) {
        List<Long> shardIds = db.jdbc().query("""
                        select id from backfill_shard
                         where status in ('QUEUED', 'RUNNING')
                           and depends_on_version_ids @> ?::jsonb
                        """,
                (rs, i) -> rs.getLong(1),
                db.toJson(db.mapper().createArrayNode().add(rolledBackVersionId)));
        int terminated = 0;
        int quarantined = 0;
        Set<Long> touchedBatches = new LinkedHashSet<>();
        for (long shardId : shardIds) {
            Long batchId = db.jdbc().queryForObject(
                    "select batch_id from backfill_shard where id = ?", Long.class, shardId);
            Long runId = db.jdbc().queryForObject(
                    "select feature_run_id from backfill_shard where id = ?", Long.class, shardId);
            String newStatus;
            if (runId != null && runs.terminateRun(runId, reason)) {
                newStatus = "TERMINATED_BY_ROLLBACK";
                terminated++;
            } else {
                // No run was ever claimed/enqueued for this shard; quarantine it before it can start.
                newStatus = "QUARANTINED";
                quarantined++;
            }
            db.jdbc().update("""
                            update backfill_shard
                               set status = ?, termination_reason = ?, updated_at = now()
                             where id = ? and status in ('QUEUED', 'RUNNING')
                            """, newStatus, reason, shardId);
            if (batchId != null) {
                touchedBatches.add(batchId);
            }
        }
        for (Long batchId : touchedBatches) {
            refreshBatchStatus(batchId);
        }
        return new ReconcileOutcome(terminated, quarantined);
    }

    /** Recompute a batch's aggregate status from its shards. */
    public void refreshBatchStatus(long batchId) {
        MapLike counts = db.jdbc().query("""
                        select
                          count(*) filter (where status = 'SUCCEEDED') as succeeded,
                          count(*) filter (where status in ('TERMINATED_BY_ROLLBACK', 'QUARANTINED')) as stopped,
                          count(*) filter (where status in ('QUEUED', 'RUNNING')) as active,
                          count(*) filter (where status = 'FAILED') as failed,
                          count(*) as total
                          from backfill_shard where batch_id = ?
                        """, rs -> {
            rs.next();
            return new MapLike(rs.getInt("succeeded"), rs.getInt("stopped"),
                    rs.getInt("active"), rs.getInt("failed"), rs.getInt("total"));
        }, batchId);
        String status;
        if (counts.stopped() == 0) {
            status = counts.active() > 0 ? "RUNNING" : "COMPLETED";
        } else if (counts.stopped() == counts.total()) {
            status = "TERMINATED";
        } else {
            status = "PARTIALLY_TERMINATED";
        }
        Instant finishedAt = "RUNNING".equals(status) ? null : Instant.now();
        db.jdbc().update("update backfill_batch set status = ?, finished_at = ? where id = ?",
                status, Db.timestamp(finishedAt), batchId);
    }

    private record MapLike(int succeeded, int stopped, int active, int failed, int total) {
    }

    public record ReconcileOutcome(int terminated, int quarantined) {
    }
}

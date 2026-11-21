package com.agri.pit.snapshots;

import com.agri.pit.cases.CaseService.CaseRow;
import com.agri.pit.common.ApiException;
import com.agri.pit.common.Db;
import com.agri.pit.common.Times;
import com.agri.pit.leakage.InputLedger;
import com.agri.pit.runs.FeatureEvaluator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Service
public class SnapshotService {

    private final Db db;

    public SnapshotService(Db db) {
        this.db = db;
    }

    /** Freeze an original scoring run into an immutable snapshot that claims reference. */
    @Transactional
    public long freeze(CaseRow caseRow, long runId, long boundaryVersionId,
                       List<FeatureEvaluator.FeatureResult> results,
                       List<InputLedger.Candidate> candidates) {
        ObjectNode values = db.mapper().createObjectNode();
        ArrayNode definitions = db.mapper().createArrayNode();
        Map<String, Object> canonical = new TreeMap<>();
        for (FeatureEvaluator.FeatureResult result : results) {
            ObjectNode value = db.mapper().createObjectNode();
            if (result.value() == null) {
                value.putNull("value");
            } else {
                value.put("value", result.value());
            }
            value.put("status", result.status());
            value.put("definitionVersion", result.definitionVersionNo());
            value.put("unit", result.unit());
            values.set(result.featureCode(), value);
            canonical.put(result.featureCode(), result.value());

            ObjectNode def = db.mapper().createObjectNode();
            def.put("featureCode", result.featureCode());
            def.put("versionId", result.definitionVersionId());
            def.put("versionNo", result.definitionVersionNo());
            def.put("formula", result.formula());
            def.put("unit", result.unit());
            definitions.add(def);
        }
        String inputsHash = hash(canonical, results);
        Long previous = db.jdbc().query("""
                        select id from scoring_snapshot where case_id = ? order by created_at desc limit 1
                        """, rs -> rs.next() ? rs.getLong(1) : null, caseRow.id());
        long snapshotId = db.insert("""
                        insert into scoring_snapshot
                            (case_id, run_id, boundary_version_id, scoring_cutoff, feature_values,
                             definition_versions, inputs_hash, supersedes_snapshot_id)
                        values (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)
                        """,
                caseRow.id(), runId, boundaryVersionId, Db.timestamp(caseRow.scoringCutoff()),
                db.jsonArg(values), db.jsonArg(definitions), inputsHash, previous);
        for (FeatureEvaluator.FeatureResult result : results) {
            ObjectNode evidence = db.mapper().createObjectNode();
            evidence.put("windowStart", Times.format(result.windowStart()));
            evidence.put("windowEnd", Times.format(result.windowEnd()));
            evidence.put("inputCount", result.inputCount());
            result.detail().forEach((k, v) -> evidence.set(k, db.mapper().valueToTree(v)));
            db.insert("""
                            insert into snapshot_feature
                                (snapshot_id, feature_code, definition_version_id, formula, value, unit,
                                 status, evidence)
                            values (?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                            """,
                    snapshotId, result.featureCode(), result.definitionVersionId(), result.formula(),
                    result.value() == null ? null : BigDecimal.valueOf(result.value()),
                    result.unit(), result.status(), db.jsonArg(evidence));
        }
        persistSnapshotInputs(snapshotId, candidates);
        return snapshotId;
    }

    private void persistSnapshotInputs(long snapshotId, List<InputLedger.Candidate> candidates) {
        // Snapshot inputs are derived from the run ledger for reproducibility checks.
        db.jdbc().update("""
                        insert into snapshot_input (snapshot_id, source_type, source_id, revision_no,
                                                   observed_at, ingested_at, eligibility, reason, detail)
                        select ?, source_type, source_id, revision_no, observed_at, ingested_at,
                               eligibility, reason, detail
                          from feature_run_input
                         where run_id = (select run_id from scoring_snapshot where id = ?)
                        """, snapshotId, snapshotId);
    }

    /**
     * Controlled backfill results are never snapshots: they become a review candidate holding
     * baseline-vs-candidate values so a human can accept or reject them.
     */
    @Transactional
    public long persistBackfillCandidate(long caseId, long backfillRunId, Long revisionEventId,
                                         List<FeatureEvaluator.FeatureResult> results) {
        Long snapshotId = db.jdbc().query("""
                        select id from scoring_snapshot where case_id = ? order by created_at desc limit 1
                        """, rs -> rs.next() ? rs.getLong(1) : null, caseId);
        ObjectNode candidateValues = db.mapper().createObjectNode();
        for (FeatureEvaluator.FeatureResult result : results) {
            if (result.value() == null) {
                candidateValues.putNull(result.featureCode());
            } else {
                candidateValues.put(result.featureCode(), result.value());
            }
        }
        ObjectNode baselineValues = db.mapper().createObjectNode();
        ObjectNode delta = db.mapper().createObjectNode();
        if (snapshotId != null) {
            JsonNode baseline = db.readTree(db.jdbc().queryForObject(
                    "select feature_values from scoring_snapshot where id = ?", String.class, snapshotId));
            // feature_values is keyed by feature code and holds {value, status, ...}.
            baseline.fields().forEachRemaining(entry -> {
                JsonNode valueNode = entry.getValue().path("value");
                baselineValues.set(entry.getKey(), valueNode);
                JsonNode candidateNode = candidateValues.get(entry.getKey());
                if (candidateNode != null && candidateNode.isNumber() && valueNode.isNumber()) {
                    delta.put(entry.getKey(), round(candidateNode.asDouble() - valueNode.asDouble()));
                }
            });
            candidateValues.fields().forEachRemaining(entry -> {
                if (!baselineValues.has(entry.getKey())) {
                    delta.put(entry.getKey(), "NEW");
                }
            });
        }
        Long analysisId = null;
        if (revisionEventId != null) {
            List<Long> analysisIds = db.jdbc().query("""
                            select id from impact_analysis where revision_event_id = ?
                             order by id desc limit 1
                            """, (rs, i) -> rs.getLong(1), revisionEventId);
            if (!analysisIds.isEmpty()) {
                analysisId = analysisIds.get(0);
            }
        }
        long candidateId = db.insert("""
                        insert into review_candidate
                            (analysis_id, case_id, backfill_run_id, snapshot_id, status, reason,
                             baseline_values, candidate_values, delta)
                        values (?, ?, ?, ?, 'PENDING_REVIEW', ?, ?::jsonb, ?::jsonb, ?::jsonb)
                        """,
                analysisId, caseId, backfillRunId, snapshotId,
                revisionEventId == null ? "controlled backfill from a data revision"
                        : "controlled backfill from revision event " + revisionEventId,
                db.jsonArg(baselineValues), db.jsonArg(candidateValues), db.jsonArg(delta));
        return candidateId;
    }

    private static double round(double value) {
        return Math.round(value * 1_000_000d) / 1_000_000d;
    }

    private String hash(Map<String, Object> canonical, List<FeatureEvaluator.FeatureResult> results) {
        StringBuilder sb = new StringBuilder();
        for (FeatureEvaluator.FeatureResult result : results) {
            sb.append(result.featureCode()).append('=')
                    .append(result.value() == null ? "null" : result.value()).append(';');
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public record SnapshotView(long id, long caseId, String caseNo, long runId, long boundaryVersionId,
                               int boundaryVersionNo, String scoringCutoff, String createdAt,
                               String inputsHash, Long supersedesSnapshotId, JsonNode featureValues,
                               JsonNode definitionVersions, List<SnapshotFeatureView> features) {
    }

    public record SnapshotFeatureView(String featureCode, int definitionVersionNo, String formula,
                                      BigDecimal value, String unit, String status, JsonNode evidence) {
    }

    public SnapshotView getSnapshot(long snapshotId) {
        List<SnapshotView> rows = db.jdbc().query("""
                        select s.id, s.case_id, c.case_no, s.run_id, s.boundary_version_id, b.version_no,
                               s.scoring_cutoff, s.created_at, s.inputs_hash, s.supersedes_snapshot_id,
                               s.feature_values, s.definition_versions
                          from scoring_snapshot s
                          join claim_case c on c.id = s.case_id
                          join parcel_boundary_version b on b.id = s.boundary_version_id
                         where s.id = ?
                        """,
                (rs, i) -> new SnapshotView(rs.getLong("id"), rs.getLong("case_id"), rs.getString("case_no"),
                        rs.getLong("run_id"), rs.getLong("boundary_version_id"), rs.getInt("version_no"),
                        Times.format(Db.instant(rs, "scoring_cutoff")),
                        Times.format(Db.instant(rs, "created_at")), rs.getString("inputs_hash"),
                        (Long) rs.getObject("supersedes_snapshot_id"),
                        db.readTree(rs.getString("feature_values")),
                        db.readTree(rs.getString("definition_versions")), List.of()),
                snapshotId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("SNAPSHOT_NOT_FOUND", "snapshot not found: " + snapshotId);
        }
        SnapshotView base = rows.get(0);
        List<SnapshotFeatureView> features = db.jdbc().query("""
                        select f.feature_code, fv.version_no, f.formula, f.value, f.unit, f.status, f.evidence
                          from snapshot_feature f
                          join feature_definition_version fv on fv.id = f.definition_version_id
                         where f.snapshot_id = ?
                         order by f.feature_code
                        """,
                (rs, i) -> new SnapshotFeatureView(rs.getString("feature_code"), rs.getInt("version_no"),
                        rs.getString("formula"), rs.getBigDecimal("value"), rs.getString("unit"),
                        rs.getString("status"), db.readTree(rs.getString("evidence"))),
                snapshotId);
        return new SnapshotView(base.id(), base.caseId(), base.caseNo(), base.runId(),
                base.boundaryVersionId(), base.boundaryVersionNo(), base.scoringCutoff(), base.createdAt(),
                base.inputsHash(), base.supersedesSnapshotId(), base.featureValues(),
                base.definitionVersions(), features);
    }

    public List<Map<String, Object>> listSnapshotsForCase(long caseId) {
        return db.jdbc().queryForList("""
                select id, run_id, boundary_version_id, scoring_cutoff, created_at, inputs_hash,
                       supersedes_snapshot_id
                  from scoring_snapshot where case_id = ? order by created_at desc
                """, caseId);
    }

    @Transactional
    public void recordReplayVerification(long snapshotId, long runId, boolean match, JsonNode diff) {
        db.insert("""
                        insert into replay_verification (snapshot_id, run_id, values_match, diff)
                        values (?, ?, ?, ?::jsonb)
                        """, snapshotId, runId, match, db.jsonArg(diff));
    }

    public record ReplayResult(long snapshotId, long runId, boolean match, Map<String, Object> differences) {
    }

    /**
     * Compare a replay run's computed features with the frozen snapshot. A mismatch means the
     * warehouse is no longer able to reproduce what was known at the cutoff (for example, a
     * missing record), which is a governance incident rather than a scoring change.
     */
    @Transactional
    public ReplayResult verifyReplay(long snapshotId, long runId) {
        SnapshotView snapshot = getSnapshot(snapshotId);
        Map<String, BigDecimal> replayed = new LinkedHashMap<>();
        db.jdbc().query("""
                        select feature_code, value from feature_run_feature where run_id = ?
                        """, rs -> {
            replayed.put(rs.getString("feature_code"), rs.getBigDecimal("value"));
        }, runId);
        Map<String, Object> differences = new LinkedHashMap<>();
        for (SnapshotFeatureView feature : snapshot.features()) {
            BigDecimal expected = feature.value();
            BigDecimal actual = replayed.get(feature.featureCode());
            if (expected == null && actual == null) {
                continue;
            }
            if (expected == null || actual == null || expected.compareTo(actual) != 0) {
                differences.put(feature.featureCode(), Map.of(
                        "snapshot", expected == null ? "null" : expected.toPlainString(),
                        "replay", actual == null ? "null" : actual.toPlainString()));
            }
        }
        boolean match = differences.isEmpty();
        recordReplayVerification(snapshotId, runId, match, db.mapper().valueToTree(differences));
        if (!match) {
            db.jdbc().update("""
                            update claim_case set status = 'UNDER_REVIEW'
                             where id = ? and status in ('OPEN','QUEUED','SCORED')
                            """, snapshot.caseId());
        }
        return new ReplayResult(snapshotId, runId, match, differences);
    }

    public List<Map<String, Object>> replayHistory(long caseId) {
        return db.jdbc().queryForList("""
                select v.id, v.snapshot_id, v.run_id, v.values_match, v.diff, v.created_at
                  from replay_verification v
                  join scoring_snapshot s on s.id = v.snapshot_id
                 where s.case_id = ?
                 order by v.id desc
                """, caseId);
    }
}

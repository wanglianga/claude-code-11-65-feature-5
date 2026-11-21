package com.agri.pit.impact;

import com.agri.pit.cases.CaseService;
import com.agri.pit.cases.CaseService.CaseRow;
import com.agri.pit.common.ApiException;
import com.agri.pit.common.Db;
import com.agri.pit.common.Times;
import com.agri.pit.runs.RunService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ReviewCandidateService {

    private final Db db;
    private final CaseService cases;
    private final RunService runs;

    public ReviewCandidateService(Db db, CaseService cases, RunService runs) {
        this.db = db;
        this.cases = cases;
        this.runs = runs;
    }

    public List<Map<String, Object>> list(String status) {
        String where = (status == null || status.isBlank()) ? "" : " where rc.status = ?";
        Object[] args = where.isEmpty() ? new Object[0] : new Object[]{status.toUpperCase()};
        return db.jdbc().query("""
                        select rc.id, rc.case_id, c.case_no, rc.analysis_id, rc.backfill_run_id, rc.snapshot_id,
                               rc.status, rc.reason,
                               rc.baseline_values::text as baseline_values,
                               rc.candidate_values::text as candidate_values,
                               rc.delta::text as delta,
                               rc.created_at, rc.decided_at, rc.decided_by, rc.decision_note
                          from review_candidate rc join claim_case c on c.id = rc.case_id
                        """ + where + " order by rc.id desc",
                (rs, i) -> candidateView(rs), args);
    }

    public Map<String, Object> get(long id) {
        List<Map<String, Object>> rows = db.jdbc().query("""
                        select rc.id, rc.case_id, c.case_no, rc.analysis_id, rc.backfill_run_id, rc.snapshot_id,
                               rc.status, rc.reason,
                               rc.baseline_values::text as baseline_values,
                               rc.candidate_values::text as candidate_values,
                               rc.delta::text as delta,
                               rc.created_at, rc.decided_at, rc.decided_by, rc.decision_note
                          from review_candidate rc join claim_case c on c.id = rc.case_id
                         where rc.id = ?
                        """, (rs, i) -> candidateView(rs), id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("CANDIDATE_NOT_FOUND", "review candidate not found: " + id);
        }
        Map<String, Object> result = rows.get(0);
        Long snapshotId = toLong(result.get("snapshot_id"));
        if (snapshotId != null) {
            result.put("frozenSnapshot", db.jdbc().query("""
                    select id, inputs_hash, scoring_cutoff, feature_values::text as feature_values
                      from scoring_snapshot where id = ?
                    """, (rs, i) -> {
                Map<String, Object> snap = new LinkedHashMap<>();
                snap.put("id", rs.getLong("id"));
                snap.put("inputsHash", rs.getString("inputs_hash"));
                snap.put("scoringCutoff", Times.format(Db.instant(rs, "scoring_cutoff")));
                snap.put("featureValues", db.readTree(rs.getString("feature_values")));
                return snap;
            }, snapshotId));
        }
        Long backfillRunId = toLong(result.get("backfill_run_id"));
        if (backfillRunId != null) {
            result.put("backfillFeatures", db.jdbc().queryForList("""
                    select feature_code, value, status from feature_run_feature where run_id = ?
                    order by feature_code
                    """, backfillRunId));
        }
        return result;
    }

    private Map<String, Object> candidateView(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", rs.getLong("id"));
        row.put("caseId", rs.getLong("case_id"));
        row.put("caseNo", rs.getString("case_no"));
        row.put("analysisId", rs.getObject("analysis_id"));
        row.put("backfillRunId", rs.getObject("backfill_run_id"));
        row.put("snapshotId", rs.getObject("snapshot_id"));
        row.put("status", rs.getString("status"));
        row.put("reason", rs.getString("reason"));
        row.put("baselineValues", db.readTree(rs.getString("baseline_values")));
        row.put("candidateValues", db.readTree(rs.getString("candidate_values")));
        row.put("delta", db.readTree(rs.getString("delta")));
        row.put("createdAt", Times.format(Db.instant(rs, "created_at")));
        row.put("decidedAt", Times.format(Db.instant(rs, "decided_at")));
        row.put("decidedBy", rs.getString("decided_by"));
        row.put("decisionNote", rs.getString("decision_note"));
        return row;
    }

    /**
     * Acknowledge or dismiss a candidate. This records a human decision; the frozen snapshot and
     * any settled payout are never rewritten by this operation.
     */
    @Transactional
    public Map<String, Object> decide(long id, String action, String decidedBy, String note) {
        Map<String, Object> candidate = get(id);
        String current = (String) candidate.get("status");
        if (!"PENDING_REVIEW".equals(current)) {
            throw ApiException.conflict("CANDIDATE_ALREADY_DECIDED",
                    "candidate " + id + " is already " + current);
        }
        String target = switch (action == null ? "" : action.toUpperCase()) {
            case "ACKNOWLEDGE" -> "ACKNOWLEDGED";
            case "DISMISS" -> "DISMISSED";
            default -> throw ApiException.badRequest("INVALID_ACTION",
                    "action must be ACKNOWLEDGE or DISMISS");
        };
        db.jdbc().update("""
                        update review_candidate set status = ?, decided_at = now(), decided_by = ?,
                               decision_note = ? where id = ?
                        """, target, decidedBy == null ? "reviewer" : decidedBy, note, id);
        Long caseId = toLong(candidate.get("case_id"));
        db.jdbc().update("""
                        update claim_case set status = 'UNDER_REVIEW'
                         where id = ? and status in ('OPEN','QUEUED','SCORED')
                        """, caseId);
        return get(id);
    }

    public long queueBoundaryVariant(String caseNo, long boundaryVersionId, String reason) {
        CaseRow caseRow = cases.requireCase(caseNo);
        List<Long> latestSnapshot = db.jdbc().query("""
                        select id from scoring_snapshot where case_id = ? order by created_at desc limit 1
                        """, (rs, i) -> rs.getLong(1), caseRow.id());
        long parentRunId = latestSnapshot.isEmpty() ? 0L : db.jdbc().queryForObject(
                "select run_id from scoring_snapshot where id = ?", Long.class, latestSnapshot.get(0));
        return runs.queueBoundaryVariant(caseRow.id(), boundaryVersionId,
                latestSnapshot.isEmpty() ? null : parentRunId,
                reason == null ? "boundary-variant isolation run" : reason);
    }

    private static Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(value.toString());
    }
}

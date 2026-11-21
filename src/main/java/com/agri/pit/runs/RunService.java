package com.agri.pit.runs;

import com.agri.pit.common.ApiException;
import com.agri.pit.common.Db;
import com.agri.pit.common.Times;
import com.agri.pit.cases.CaseService;
import com.agri.pit.cases.CaseService.CaseRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Service
public class RunService {

    private final Db db;
    private final CaseService cases;

    public RunService(Db db, CaseService cases) {
        this.db = db;
        this.cases = cases;
    }

    /** Queue the original evaluation of a newly opened case. */
    @Transactional
    public long queueOriginalRun(long caseId) {
        CaseRow row = requireCaseById(caseId);
        return enqueue(new RunCommand(row.id(), null, "ORIGINAL", "CASE_SCORING",
                "AS_OF_CUTOFF", row.scoringCutoff(), row.scoringCutoff(), row.hazardDate(),
                row.boundaryVersionId(), false, null, null, "system", "case intake scoring", null));
    }

    @Transactional
    public long queueRunForCase(String caseNo, String reason) {
        CaseRow row = cases.requireCase(caseNo);
        return enqueue(new RunCommand(row.id(), null, "ORIGINAL", "CASE_SCORING",
                "AS_OF_CUTOFF", row.scoringCutoff(), row.scoringCutoff(), row.hazardDate(),
                row.boundaryVersionId(), false, null, null, "api",
                reason == null ? "manual case scoring request" : reason, null));
    }

    /**
     * Queue a replay at the exact original cutoff. Used by the reproduction flow to prove
     * that a frozen snapshot can be regenerated from immutable inputs.
     */
    @Transactional
    public long queueReplay(long caseId, long parentRunId, String reason) {
        CaseRow row = requireCaseById(caseId);
        return enqueue(new RunCommand(row.id(), null, "REPLAY", "REPRODUCTION",
                "AS_OF_CUTOFF", row.scoringCutoff(), row.scoringCutoff(), row.hazardDate(),
                row.boundaryVersionId(), false, parentRunId, null, "replay", reason, null));
    }

    /**
     * Queue a controlled backfill with a deliberately raised availability ceiling. It may only
     * be produced by impact analysis and never writes a snapshot.
     */
    @Transactional
    public long queueBackfill(long caseId, Instant availabilityCeiling, Long revisionEventId,
                              long parentRunId, String reason) {
        CaseRow row = requireCaseById(caseId);
        if (!availabilityCeiling.isAfter(row.scoringCutoff())) {
            throw ApiException.badRequest("BACKFILL_CEILING_NOT_LATER",
                    "controlled backfill availability ceiling must be later than the case cutoff");
        }
        return enqueue(new RunCommand(row.id(), null, "CONTROLLED_BACKFILL", "IMPACT_ANALYSIS",
                "LATEST_AVAILABLE", row.scoringCutoff(), availabilityCeiling, row.hazardDate(),
                row.boundaryVersionId(), false, parentRunId, revisionEventId, "impact-analysis",
                reason, null));
    }

    /**
     * Queue a boundary-variant run: same case cutoff, but evaluated against an alternative
     * parcel boundary version. Used when two boundary versions overlap the same ground and the
     * claim must stay isolated on the locked version while the alternative is assessed separately.
     */
    @Transactional
    public long queueBoundaryVariant(long caseId, long boundaryVersionId, Long parentRunId, String reason) {
        CaseRow row = requireCaseById(caseId);
        if (boundaryVersionId == row.boundaryVersionId()) {
            throw ApiException.badRequest("BOUNDARY_VARIANT_SAME_VERSION",
                    "boundary variant must reference a different boundary version than the case lock");
        }
        return enqueue(new RunCommand(row.id(), null, "REPLAY", "BACKFILL_REQUEST",
                "AS_OF_CUTOFF", row.scoringCutoff(), row.scoringCutoff(), row.hazardDate(),
                boundaryVersionId, true, parentRunId, null, "boundary-variant", reason, null));
    }

    /**
     * Queue a shadow scoring run at the case's original cutoff. Shadow runs evaluate candidate
     * definition versions without writing a snapshot or touching the claim's production status.
     */
    @Transactional
    public long queueShadowRun(long caseId, Long parentRunId, Long shadowRunId, String reason) {
        CaseRow row = requireCaseById(caseId);
        return enqueue(new RunCommand(row.id(), null, "SHADOW", "SHADOW_SCORING",
                "AS_OF_CUTOFF", row.scoringCutoff(), row.scoringCutoff(), row.hazardDate(),
                row.boundaryVersionId(), false, parentRunId, null, "shadow-scorer", reason,
                shadowRunId));
    }

    /** Queue one shard of a batch backfill. The shard's definition dependencies live in backfill_shard. */
    @Transactional
    public long queueBatchBackfillShard(long caseId, Instant availabilityCeiling, Long parentRunId,
                                        String reason) {
        CaseRow row = requireCaseById(caseId);
        if (!availabilityCeiling.isAfter(row.scoringCutoff())) {
            throw ApiException.badRequest("BACKFILL_CEILING_NOT_LATER",
                    "batch backfill availability ceiling must be later than the case cutoff");
        }
        return enqueue(new RunCommand(row.id(), null, "CONTROLLED_BACKFILL", "BATCH_BACKFILL",
                "LATEST_AVAILABLE", row.scoringCutoff(), availabilityCeiling, row.hazardDate(),
                row.boundaryVersionId(), false, parentRunId, null, "batch-backfill", reason, null));
    }

    /**
     * Terminate a queued or running shard because a targeted rollback disabled one of its
     * definition dependencies. Already-finished runs are never rewritten.
     */
    @Transactional
    public boolean terminateRun(long runId, String reason) {
        int updated = db.jdbc().update("""
                        update feature_run
                           set status = 'TERMINATED', finished_at = now(),
                               error_message = ?, notes = coalesce(notes, '') || ?
                         where id = ? and status in ('QUEUED', 'RUNNING')
                        """,
                reason, " | terminated: " + reason, runId);
        return updated > 0;
    }

    private long enqueue(RunCommand cmd) {
        long id = db.insert("""
                        insert into feature_run
                            (case_id, mode, trigger_type, status, verdict, data_policy, cutoff_at,
                             availability_ceiling, hazard_date, boundary_version_id, boundary_variant,
                             parent_run_id, revision_event_id, reason, requested_by, shadow_run_id)
                        values (?, ?, ?, 'QUEUED', 'NOT_EVALUATED', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                cmd.caseId(), cmd.mode(), cmd.trigger(), cmd.dataPolicy(),
                Db.timestamp(cmd.cutoff()), Db.timestamp(cmd.ceiling()), cmd.hazardDate(),
                cmd.boundaryVersionId(), cmd.boundaryVariant(), cmd.parentRunId(), cmd.revisionEventId(),
                cmd.reason(), cmd.requestedBy(), cmd.shadowRunId());
        return id;
    }

    public RunDtos.QueueStats stats() {
        return db.jdbc().query("""
                        select
                          count(*) filter (where status = 'QUEUED') as queued,
                          count(*) filter (where status = 'RUNNING') as running,
                          count(*) filter (where status = 'SUCCEEDED') as succeeded,
                          count(*) filter (where status = 'LEAKAGE_BLOCKED') as blocked,
                          count(*) filter (where status = 'INSUFFICIENT_DATA') as insufficient,
                          count(*) filter (where status = 'FAILED') as failed
                          from feature_run
                        """,
                rs -> {
                    rs.next();
                    return new RunDtos.QueueStats(rs.getLong("queued"), rs.getLong("running"),
                            rs.getLong("succeeded"), rs.getLong("blocked"), rs.getLong("insufficient"),
                            rs.getLong("failed"));
                });
    }

    public List<RunDtos.RunView> listRuns(String status) {
        String sql = """
                select r.*, c.case_no, b.version_no as boundary_version_no
                  from feature_run r
                  left join claim_case c on c.id = r.case_id
                  left join parcel_boundary_version b on b.id = r.boundary_version_id
                """;
        if (status == null || status.isBlank()) {
            return db.jdbc().query(sql + " order by r.id desc", (rs, i) -> mapView(rs));
        }
        return db.jdbc().query(sql + " where r.status = ? order by r.id desc",
                (rs, i) -> mapView(rs), status.toUpperCase());
    }

    public List<RunDtos.RunView> traceCase(String caseNo) {
        CaseRow row = cases.requireCase(caseNo);
        return db.jdbc().query("""
                        select r.*, c.case_no, b.version_no as boundary_version_no
                          from feature_run r
                          join claim_case c on c.id = r.case_id
                          left join parcel_boundary_version b on b.id = r.boundary_version_id
                         where r.case_id = ?
                         order by r.id
                        """,
                (rs, i) -> mapView(rs), row.id());
    }

    public RunDtos.RunDetail getRun(long runId) {
        RunRow run = requireRun(runId);
        List<RunDtos.FeatureView> features = db.jdbc().query("""
                        select f.feature_code, f.definition_version_id, f.formula, f.value, f.unit,
                               f.status, f.input_count, f.window_start, f.window_end, fv.version_no
                          from feature_run_feature f
                          join feature_definition_version fv on fv.id = f.definition_version_id
                         where f.run_id = ?
                         order by f.feature_code
                        """,
                (rs, i) -> new RunDtos.FeatureView(rs.getString("feature_code"), rs.getInt("version_no"),
                        rs.getLong("definition_version_id"), rs.getString("formula"),
                        rs.getBigDecimal("value") == null ? null : rs.getBigDecimal("value").doubleValue(),
                        rs.getString("unit"), rs.getString("status"), rs.getInt("input_count"),
                        Times.format(Db.instant(rs, "window_start")),
                        Times.format(Db.instant(rs, "window_end"))),
                runId);
        List<RunDtos.InputView> inputs = db.jdbc().query("""
                        select id, source_type, source_id, revision_no, observed_at, ingested_at,
                               eligibility, reason, contributed_value, feature_codes
                          from feature_run_input where run_id = ? order by id
                        """,
                (rs, i) -> new RunDtos.InputView(rs.getLong("id"), rs.getString("source_type"),
                        rs.getLong("source_id"), (Integer) rs.getObject("revision_no"),
                        Times.format(Db.instant(rs, "observed_at")), Times.format(Db.instant(rs, "ingested_at")),
                        rs.getString("eligibility"), rs.getString("reason"),
                        rs.getBigDecimal("contributed_value") == null
                                ? null : rs.getBigDecimal("contributed_value").doubleValue(),
                        rs.getString("feature_codes")),
                runId);
        RunDtos.LeakageView leakage = leakage(runId);
        return new RunDtos.RunDetail(toView(run), features, inputs, leakage);
    }

    private RunDtos.LeakageView leakage(long runId) {
        List<Long> reportIds = db.jdbc().query(
                "select id from leakage_report where run_id = ?", (rs, i) -> rs.getLong(1), runId);
        if (reportIds.isEmpty()) {
            return null;
        }
        long reportId = reportIds.get(0);
        List<RunDtos.ViolationView> violations = db.jdbc().query("""
                        select code, severity, source_type, source_id, feature_code, message
                          from leakage_violation where report_id = ? order by id
                        """,
                (rs, i) -> new RunDtos.ViolationView(rs.getString("code"), rs.getString("severity"),
                        rs.getString("source_type"), (Long) rs.getObject("source_id"),
                        rs.getString("feature_code"), rs.getString("message")),
                reportId);
        return db.jdbc().query("""
                        select verdict, cutoff_at, availability_ceiling, input_count, eligible_count,
                               excluded_count, blocked_count, summary
                          from leakage_report where id = ?
                        """,
                rs -> {
                    rs.next();
                    return new RunDtos.LeakageView(rs.getString("verdict"),
                            Times.format(Db.instant(rs, "cutoff_at")),
                            Times.format(Db.instant(rs, "availability_ceiling")),
                            rs.getInt("input_count"), rs.getInt("eligible_count"),
                            rs.getInt("excluded_count"), rs.getInt("blocked_count"),
                            rs.getString("summary"), violations);
                }, reportId);
    }

    public RunRow requireRun(long runId) {
        List<RunRow> rows = db.jdbc().query("""
                        select r.*, c.case_no from feature_run r
                          left join claim_case c on c.id = r.case_id where r.id = ?
                        """,
                (rs, i) -> new RunRow(
                        rs.getLong("id"), (Long) rs.getObject("case_id"), rs.getString("case_no"),
                        rs.getString("mode"), rs.getString("trigger_type"), rs.getString("status"),
                        rs.getString("verdict"), rs.getString("data_policy"),
                        Db.instant(rs, "cutoff_at"), Db.instant(rs, "availability_ceiling"),
                        rs.getObject("hazard_date", LocalDate.class),
                        (Long) rs.getObject("boundary_version_id"), rs.getBoolean("boundary_variant"),
                        (Long) rs.getObject("parent_run_id"), (Long) rs.getObject("revision_event_id"),
                        rs.getString("reason"), rs.getString("requested_by"),
                        Db.instant(rs, "requested_at"), Db.instant(rs, "started_at"),
                        Db.instant(rs, "finished_at"), rs.getString("error_message"),
                        (Long) rs.getObject("result_snapshot_id"), (Long) rs.getObject("result_candidate_id"),
                        rs.getString("notes"), (Long) rs.getObject("shadow_run_id")),
                runId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("RUN_NOT_FOUND", "run not found: " + runId);
        }
        return rows.get(0);
    }

    private CaseRow requireCaseById(long caseId) {
        List<String> caseNos = db.jdbc().query("select case_no from claim_case where id = ?",
                (rs, i) -> rs.getString(1), caseId);
        if (caseNos.isEmpty()) {
            throw ApiException.notFound("CASE_NOT_FOUND", "case not found: " + caseId);
        }
        return cases.requireCase(caseNos.get(0));
    }

    private RunDtos.RunView mapView(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new RunDtos.RunView(rs.getLong("id"), (Long) rs.getObject("case_id"),
                rs.getString("case_no"), rs.getString("mode"), rs.getString("trigger_type"),
                rs.getString("status"), rs.getString("verdict"), rs.getString("data_policy"),
                Times.format(Db.instant(rs, "cutoff_at")),
                Times.format(Db.instant(rs, "availability_ceiling")),
                rs.getObject("hazard_date", LocalDate.class) == null
                        ? null : rs.getObject("hazard_date", LocalDate.class).toString(),
                (Long) rs.getObject("boundary_version_id"),
                (Integer) rs.getObject("boundary_version_no"), rs.getBoolean("boundary_variant"),
                (Long) rs.getObject("parent_run_id"), (Long) rs.getObject("revision_event_id"),
                rs.getString("requested_by"), Times.format(Db.instant(rs, "requested_at")),
                Times.format(Db.instant(rs, "started_at")), Times.format(Db.instant(rs, "finished_at")),
                rs.getString("reason"), rs.getString("error_message"),
                (Long) rs.getObject("result_snapshot_id"), (Long) rs.getObject("result_candidate_id"),
                rs.getString("notes"));
    }

    private RunDtos.RunView toView(RunRow r) {
        Integer boundaryVersionNo = r.boundaryVersionId() == null ? null : db.jdbc().queryForObject(
                "select version_no from parcel_boundary_version where id = ?", Integer.class, r.boundaryVersionId());
        return new RunDtos.RunView(r.id(), r.caseId(), r.caseNo(), r.mode(), r.triggerType(),
                r.status(), r.verdict(), r.dataPolicy(), Times.format(r.cutoffAt()),
                Times.format(r.availabilityCeiling()),
                r.hazardDate() == null ? null : r.hazardDate().toString(),
                r.boundaryVersionId(), boundaryVersionNo,
                r.boundaryVariant(), r.parentRunId(), r.revisionEventId(), r.requestedBy(),
                Times.format(r.requestedAt()), Times.format(r.startedAt()), Times.format(r.finishedAt()),
                r.reason(), r.error(), r.resultSnapshotId(), r.resultCandidateId(), r.notes());
    }

    private record RunCommand(long caseId, Long ignored, String mode, String trigger, String dataPolicy,
                               Instant cutoff, Instant ceiling, LocalDate hazardDate, long boundaryVersionId,
                               boolean boundaryVariant, Long parentRunId, Long revisionEventId,
                               String requestedBy, String reason, Long shadowRunId) {
    }

    public record RunRow(long id, Long caseId, String caseNo, String mode, String triggerType,
                         String status, String verdict, String dataPolicy, Instant cutoffAt,
                         Instant availabilityCeiling, LocalDate hazardDate, Long boundaryVersionId,
                         boolean boundaryVariant, Long parentRunId, Long revisionEventId,
                         String reason, String requestedBy, Instant requestedAt, Instant startedAt,
                         Instant finishedAt, String error, Long resultSnapshotId, Long resultCandidateId,
                         String notes, Long shadowRunId) {
    }
}

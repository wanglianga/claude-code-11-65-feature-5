package com.agri.pit.runs;

import com.agri.pit.cases.CaseService;
import com.agri.pit.cases.CaseService.CaseRow;
import com.agri.pit.catalog.CatalogService;
import com.agri.pit.catalog.CatalogService.ApprovedFeature;
import com.agri.pit.common.ApiException;
import com.agri.pit.common.Db;
import com.agri.pit.common.Times;
import com.agri.pit.governance.RollbackService;
import com.agri.pit.leakage.AsOfSelector;
import com.agri.pit.leakage.InputLedger;
import com.agri.pit.leakage.LeakageDetector;
import com.agri.pit.model.LeakageVerdict;
import com.agri.pit.model.RunMode;
import com.agri.pit.snapshots.SnapshotService;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.locationtech.jts.geom.Geometry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Executes a queued run. This is the "backfill orchestrator" as well as the original
 * scoring path: the only difference between them is the availability ceiling and the
 * run mode, both of which control which warehouse revisions may be read.
 */
@Service
public class RunExecutor {

    private static final Logger log = LoggerFactory.getLogger(RunExecutor.class);

    /** NDVI window is measured in days before the hazard date. */
    private static final int NDVI_LOOKBACK_DAYS = 30;
    /** Rainfall window is measured in days before the hazard date. */
    private static final int RAIN_LOOKBACK_DAYS = 60;

    private final Db db;
    private final CaseService cases;
    private final CatalogService catalog;
    private final AsOfSelector selector;
    private final LeakageDetector detector;
    private final SnapshotService snapshots;
    private final RunService runService;
    private final RollbackService rollbacks;

    public RunExecutor(Db db, CaseService cases, CatalogService catalog, AsOfSelector selector,
                       LeakageDetector detector, SnapshotService snapshots, RunService runService,
                       RollbackService rollbacks) {
        this.db = db;
        this.cases = cases;
        this.catalog = catalog;
        this.selector = selector;
        this.detector = detector;
        this.snapshots = snapshots;
        this.runService = runService;
        this.rollbacks = rollbacks;
    }

    public record ClaimResult(long runId, String status, String verdict, Long snapshotId) {
    }

    /** Synchronous execution used by the HTTP reproduction / backfill endpoints. */
    public ClaimResult execute(long runId) {
        return doExecute(runId, false);
    }

    /** Execution path for the queue worker, which has already flipped the run to RUNNING. */
    public ClaimResult executeClaimed(long runId) {
        return doExecute(runId, true);
    }

    @Transactional
    public ClaimResult doExecute(long runId, boolean alreadyClaimed) {
        RunService.RunRow run = lockRun(runId);
        if (alreadyClaimed) {
            if (!run.status().equals("RUNNING")) {
                return new ClaimResult(runId, run.status(), run.verdict(), run.resultSnapshotId());
            }
        } else {
            if (!run.status().equals("QUEUED")) {
                return new ClaimResult(runId, run.status(), run.verdict(), run.resultSnapshotId());
            }
            db.jdbc().update("update feature_run set status = 'RUNNING', started_at = now() where id = ?", runId);
        }
        if ("TERMINATED".equals(run.status())) {
            // A targeted rollback stopped this shard after it was claimed but before it executed.
            return new ClaimResult(runId, run.status(), run.verdict(), run.resultSnapshotId());
        }
        CaseRow caseRow = cases.requireCase(run.caseNo());
        long boundaryVersionId = run.boundaryVersionId() != null
                ? run.boundaryVersionId() : caseRow.boundaryVersionId();
        BoundaryRow boundary = loadBoundary(boundaryVersionId);
        Geometry geometry = selector.parseGeometry(boundary.geometry());
        RunMode mode = RunMode.valueOf(run.mode());
        Instant ceiling = run.availabilityCeiling();

        InputLedger.Builder builder = new InputLedger.Builder();

        Instant ndviStart = caseRow.hazardDate().minusDays(NDVI_LOOKBACK_DAYS).atStartOfDay(Times.UTC).toInstant();
        Instant ndviEnd = Times.endOfDayExclusive(caseRow.hazardDate());
        AsOfSelector.ScenePick scenes = selector.selectSatellite(builder, geometry,
                new AsOfSelector.Window(ndviStart, ndviEnd), ceiling, runId, caseRow.id(),
                caseRow.parcelId(), boundaryVersionId);

        Instant rainStart = Times.startOfDay(caseRow.hazardDate().minusDays(RAIN_LOOKBACK_DAYS));
        Instant rainEnd = Times.endOfDayExclusive(caseRow.hazardDate());
        AsOfSelector.WeatherPick weather = selector.selectWeather(builder, caseRow.parcelId(),
                new AsOfSelector.Window(rainStart, rainEnd), ceiling);

        AsOfSelector.DeclarationPick declaration = selector.selectDeclaration(builder, caseRow.parcelId(),
                ceiling, caseRow.cropCode());
        selector.recordBoundary(builder, boundaryVersionId, boundary.versionNo(),
                boundary.observedAt(), boundary.ingestedAt(), ceiling);

        Instant windowStart = ndviStart.isBefore(rainStart) ? ndviStart : rainStart;
        Instant windowEnd = ndviEnd.isAfter(rainEnd) ? ndviEnd : rainEnd;
        FeatureEvaluator.Context context = FeatureEvaluator.buildContext(scenes, weather, declaration,
                caseRow.hazardDate(), windowStart, windowEnd, boundary.areaHa());

        List<ApprovedFeature> features = resolveFeatures(caseRow, run, mode);
        List<FeatureEvaluator.FeatureResult> results = new ArrayList<>();
        boolean missingRequired = false;
        for (ApprovedFeature feature : features) {
            FeatureEvaluator.FeatureResult result = FeatureEvaluator.evaluate(feature.code(),
                    feature.versionId(), feature.versionNo(), feature.formula(), feature.unit(),
                    feature.required(), context);
            if (feature.required() && !"COMPUTED".equals(result.status())) {
                missingRequired = true;
            }
            results.add(result);
        }

        InputLedger.Result ledger = builder.build();
        LeakageDetector.Outcome detection = detector.evaluateAndPersist(runId, mode, run.cutoffAt(),
                ceiling, ledger, missingRequired);
        ledger = detection.ledger();

        persistFeatures(runId, results);
        persistInputs(runId, ledger.candidates(), features);

        Long snapshotId = null;
        Long candidateId = null;
        String status;
        if (detection.verdict() == LeakageVerdict.BLOCKED) {
            status = "LEAKAGE_BLOCKED";
        } else if (detection.hasMissingRequiredData()) {
            status = "INSUFFICIENT_DATA";
        } else {
            status = "SUCCEEDED";
        }

        if (status.equals("SUCCEEDED") && mode == RunMode.ORIGINAL) {
            snapshotId = snapshots.freeze(caseRow, runId, boundaryVersionId, results, ledger.candidates());
            db.jdbc().update("""
                            update claim_case set status = 'SCORED'
                             where id = ? and status in ('OPEN','QUEUED')
                            """, caseRow.id());
        } else if (status.equals("SUCCEEDED") && mode == RunMode.CONTROLLED_BACKFILL) {
            candidateId = snapshots.persistBackfillCandidate(caseRow.id(), runId, run.revisionEventId(),
                    results);
        }

        db.jdbc().update("""
                        update feature_run
                           set status = ?, verdict = ?, finished_at = now(), result_snapshot_id = ?,
                               result_candidate_id = ?, notes = ?
                         where id = ?
                        """,
                status, detection.verdict().name(), snapshotId, candidateId,
                buildNote(detection, snapshotId, candidateId), runId);
        if (status.equals("LEAKAGE_BLOCKED") && mode != RunMode.SHADOW) {
            db.jdbc().update("""
                            update claim_case set status = 'UNDER_REVIEW'
                             where id = ? and status in ('OPEN','QUEUED','SCORED')
                            """, caseRow.id());
        }
        log.info("run {} finished status={} verdict={} snapshot={} candidate={}", runId, status,
                detection.verdict(), snapshotId, candidateId);
        return new ClaimResult(runId, status, detection.verdict().name(), snapshotId);
    }

    /**
     * Resolve the definition versions a run must evaluate. Shadow runs swap in the candidate
     * versions recorded in {@code shadow_run_definition}; every other run reads the case's locked
     * versions except those disabled by an active targeted rollback.
     */
    private List<ApprovedFeature> resolveFeatures(CaseRow caseRow, RunService.RunRow run, RunMode mode) {
        if (mode == RunMode.SHADOW && run.shadowRunId() != null) {
            return shadowFeatures(caseRow.id(), run.shadowRunId());
        }
        List<ApprovedFeature> features = new ArrayList<>();
        for (ApprovedFeature feature : lockedFeatures(caseRow.id())) {
            if (!rollbacks.isReadEnabled(feature.versionId(), caseRow.id())) {
                log.info("run {} skips feature {} v{}: definition version read disabled by rollback",
                        run.id(), feature.code(), feature.versionNo());
                continue;
            }
            features.add(feature);
        }
        return features;
    }

    private List<ApprovedFeature> shadowFeatures(long caseId, long shadowRunId) {
        Map<String, Long> overrides = new HashMap<>();
        db.jdbc().query("""
                        select feature_code, definition_version_id
                          from shadow_run_definition where shadow_run_id = ?
                        """,
                (rs, i) -> overrides.put(rs.getString("feature_code"), rs.getLong("definition_version_id")),
                shadowRunId);
        List<ApprovedFeature> resolved = new ArrayList<>();
        for (ApprovedFeature feature : lockedFeatures(caseId)) {
            Long overrideVersionId = overrides.get(feature.code());
            if (overrideVersionId == null) {
                resolved.add(feature);
                continue;
            }
            CatalogService.VersionRow version = catalog.requireVersionById(overrideVersionId);
            resolved.add(new ApprovedFeature(feature.code(), feature.name(), feature.unit(),
                    feature.sourceTypes(), version.id(), version.versionNo(), version.formula(),
                    version.required(), version.availabilityLagSeconds(), version.asOfPolicy(),
                    version.lookbackDays()));
        }
        return resolved;
    }

    private String buildNote(LeakageDetector.Outcome detection, Long snapshotId, Long candidateId) {
        List<String> notes = new ArrayList<>();
        notes.add(detection.eligibleCount() + " eligible / " + detection.excludedCount() + " excluded input(s)");
        if (snapshotId != null) {
            notes.add("frozen snapshot " + snapshotId);
        }
        if (candidateId != null) {
            notes.add("review candidate " + candidateId + " created; no snapshot was written");
        }
        if (detection.verdict() == LeakageVerdict.BLOCKED) {
            notes.add("scoring blocked by leakage detector");
        }
        return String.join("; ", notes);
    }

    private RunService.RunRow lockRun(long runId) {
        List<Boolean> locked = db.jdbc().query(
                "select true from feature_run where id = ? for update", (rs, i) -> rs.getBoolean(1), runId);
        if (locked.isEmpty()) {
            throw ApiException.notFound("RUN_NOT_FOUND", "run not found: " + runId);
        }
        return runService.requireRun(runId);
    }

    private record BoundaryRow(long id, int versionNo, com.fasterxml.jackson.databind.JsonNode geometry,
                               double areaHa, Instant observedAt, Instant ingestedAt) {
    }

    private BoundaryRow loadBoundary(long boundaryVersionId) {
        List<BoundaryRow> rows = db.jdbc().query("""
                        select id, version_no, geometry_geojson, area_ha, observed_at, ingested_at
                          from parcel_boundary_version where id = ?
                        """,
                (rs, i) -> new BoundaryRow(rs.getLong("id"), rs.getInt("version_no"),
                        db.readTree(rs.getString("geometry_geojson")),
                        rs.getBigDecimal("area_ha").doubleValue(),
                        Db.instant(rs, "observed_at"), Db.instant(rs, "ingested_at")),
                boundaryVersionId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("BOUNDARY_NOT_FOUND", "boundary version not found: " + boundaryVersionId);
        }
        return rows.get(0);
    }

    /**
     * A case locks concrete definition versions at intake. Only those versions are evaluated;
     * approving a newer catalog version later cannot silently change a historical case.
     */
    private List<ApprovedFeature> lockedFeatures(long caseId) {
        List<ApprovedFeature> locked = db.jdbc().query("""
                        select fd.code, fd.name, fd.unit, fd.source_types,
                               fv.id as version_id, fv.version_no, fv.formula, fv.required,
                               fv.availability_lag_seconds, fv.as_of_policy, fv.lookback_days
                          from claim_case_feature_lock l
                          join feature_definition_version fv on fv.id = l.feature_definition_version_id
                          join feature_definition fd on fd.id = fv.feature_definition_id
                         where l.case_id = ?
                         order by fd.code
                        """,
                (rs, i) -> new ApprovedFeature(rs.getString("code"), rs.getString("name"),
                        rs.getString("unit"), parseSourceTypes(db.readTree(rs.getString("source_types"))),
                        rs.getLong("version_id"), rs.getInt("version_no"), rs.getString("formula"),
                        rs.getBoolean("required"), rs.getLong("availability_lag_seconds"),
                        rs.getString("as_of_policy"), rs.getInt("lookback_days")),
                caseId);
        if (!locked.isEmpty()) {
            return locked;
        }
        // Fallback for dynamically created cases without locks (kept for robustness).
        return catalog.approvedFeatures();
    }

    private static List<String> parseSourceTypes(com.fasterxml.jackson.databind.JsonNode node) {
        List<String> list = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(n -> list.add(n.asText()));
        }
        return list;
    }

    private void persistFeatures(long runId, List<FeatureEvaluator.FeatureResult> results) {
        db.jdbc().update("delete from feature_run_feature where run_id = ?", runId);
        for (FeatureEvaluator.FeatureResult result : results) {
            ObjectNode detail = db.mapper().createObjectNode();
            result.detail().forEach((k, v) -> detail.set(k, db.mapper().valueToTree(v)));
            db.insert("""
                            insert into feature_run_feature
                                (run_id, feature_code, definition_version_id, formula, value, unit,
                                 status, input_count, window_start, window_end, detail)
                            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                            """,
                    runId, result.featureCode(), result.definitionVersionId(), result.formula(),
                    result.value() == null ? null : BigDecimal.valueOf(result.value()),
                    result.unit(), result.status(), result.inputCount(),
                    Db.timestamp(result.windowStart()), Db.timestamp(result.windowEnd()),
                    db.jsonArg(detail));
        }
    }

    private void persistInputs(long runId, List<InputLedger.Candidate> candidates,
                               List<ApprovedFeature> features) {
        db.jdbc().update("delete from feature_run_input where run_id = ?", runId);
        for (InputLedger.Candidate candidate : candidates) {
            ObjectNode detail = candidate.detail() == null ? db.mapper().createObjectNode() : candidate.detail();
            detail.put("externalKey", candidate.externalKey());
            String featureCodes = features.stream()
                    .filter(f -> f.sourceTypes().contains(candidate.sourceType().name()))
                    .map(ApprovedFeature::code)
                    .reduce((a, b) -> a + "," + b)
                    .orElse("");
            db.insert("""
                            insert into feature_run_input
                                (run_id, feature_codes, source_type, source_table, source_id, revision_no,
                                 observed_at, ingested_at, eligibility, reason, contributed_value, detail)
                            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                            """,
                    runId, featureCodes.isEmpty() ? null : featureCodes, candidate.sourceType().name(),
                    candidate.sourceTable(), candidate.sourceId(), candidate.revisionNo(),
                    Db.timestamp(candidate.observedAt()), Db.timestamp(candidate.ingestedAt()),
                    candidate.verdict().name(), candidate.reason(),
                    candidate.contributedValue() == null ? null : BigDecimal.valueOf(candidate.contributedValue()),
                    db.jsonArg(detail));
        }
    }
}

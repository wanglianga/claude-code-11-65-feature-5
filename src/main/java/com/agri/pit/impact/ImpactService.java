package com.agri.pit.impact;

import com.agri.pit.common.ApiException;
import com.agri.pit.common.Db;
import com.agri.pit.common.Times;
import com.agri.pit.catalog.CatalogService;
import com.agri.pit.ingest.RevisionRecorder;
import com.agri.pit.model.SourceType;
import com.agri.pit.runs.RunExecutor;
import com.agri.pit.runs.RunService;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Given a warehouse revision (a late scene, a corrected weather observation, an adjusted
 * boundary or declaration), find the frozen claims it touches. Cases whose snapshot already
 * accounted for that revision are reported as no-impact; later revisions spawn a controlled
 * backfill run whose result becomes a pending review candidate, never an automatic payout change.
 */
@Service
public class ImpactService {

    private final Db db;
    private final RunService runs;
    private final RunExecutor executor;
    private final RevisionRecorder revisions;
    private final CatalogService catalog;
    private final com.agri.pit.common.GeoUtil geo;

    public ImpactService(Db db, RunService runs, RunExecutor executor, RevisionRecorder revisions,
                         CatalogService catalog, com.agri.pit.common.GeoUtil geo) {
        this.db = db;
        this.runs = runs;
        this.executor = executor;
        this.revisions = revisions;
        this.catalog = catalog;
        this.geo = geo;
    }

    public record CaseImpact(long caseId, String caseNo, String effect, String reason, Long snapshotId,
                             Instant cutoffAt, Long backfillRunId, Long candidateId) {
    }

    public record AnalysisResult(long analysisId, long revisionEventId, String sourceType,
                                 String externalKey, int revisionNo, Instant ingestedAt,
                                 List<CaseImpact> cases, Map<String, Object> summary) {
    }

    @Transactional
    public AnalysisResult analyze(long revisionEventId) {
        RevisionRow event = loadEvent(revisionEventId);
        List<Long> completed = db.jdbc().query("""
                        select id from impact_analysis where revision_event_id = ? and status = 'COMPLETED'
                         order by id desc limit 1
                        """, (rs, i) -> rs.getLong(1), revisionEventId);
        if (!completed.isEmpty()) {
            return loadCompleted(completed.get(0));
        }
        List<CandidateCase> affected = candidateCases(event);
        long analysisId = db.insert("""
                        insert into impact_analysis (revision_event_id, status, affected_cases, candidate_cases)
                        values (?, 'RUNNING', 0, 0)
                        """, revisionEventId);
        List<CaseImpact> impacts = new ArrayList<>();
        int candidates = 0;
        for (CandidateCase candidate : affected) {
            CaseImpact impact = evaluate(analysisId, event, candidate);
            if (impact != null) {
                impacts.add(impact);
                if ("RECOMPUTE_CANDIDATE".equals(impact.effect())) {
                    candidates++;
                }
                db.jdbc().update("""
                                insert into impact_case
                                    (analysis_id, case_id, snapshot_id, effect, reason, cutoff_at,
                                     backfill_run_id, candidate_id)
                                values (?, ?, ?, ?, ?, ?, ?, ?)
                                on conflict (analysis_id, case_id) do nothing
                                """,
                        analysisId, impact.caseId(), impact.snapshotId(), impact.effect(),
                        impact.reason(), Db.timestamp(impact.cutoffAt()), impact.backfillRunId(),
                        impact.candidateId());
            }
        }
        ObjectNode summary = db.mapper().createObjectNode();
        Map<String, Integer> byEffect = new LinkedHashMap<>();
        for (CaseImpact impact : impacts) {
            byEffect.merge(impact.effect(), 1, Integer::sum);
        }
        byEffect.forEach(summary::put);
        summary.put("note", "Affected means the revision overlapped the case's window; RECOMPUTE_CANDIDATE "
                + "means data was not visible at the case cutoff and a controlled backfill produced a "
                + "pending review candidate. Settled payouts are never modified automatically.");
        db.jdbc().update("""
                        update impact_analysis
                           set status = 'COMPLETED', affected_cases = ?, candidate_cases = ?, summary = ?::jsonb
                         where id = ?
                        """, impacts.size(), candidates, db.jsonArg(summary), analysisId);
        return new AnalysisResult(analysisId, event.id(), event.sourceType(), event.externalKey(),
                event.revisionNo(), event.ingestedAt(), impacts,
                db.mapper().convertValue(summary, Map.class));
    }

    private CaseImpact evaluate(long analysisId, RevisionRow event, CandidateCase candidate) {
        Long snapshotId = latestSnapshotId(candidate.caseId());
        if (snapshotId == null) {
            return null;
        }
        boolean visibleAtCutoff = !event.ingestedAt().isAfter(candidate.cutoffAt());
        boolean alreadyCaptured = revisionVisibleInSnapshotEvidence(snapshotId, event);
        if (visibleAtCutoff || alreadyCaptured) {
            String reason = alreadyCaptured
                    ? "snapshot already cites this revision; no re-scoring needed"
                    : "revision was ingested before the case cutoff and is part of the frozen snapshot";
            return new CaseImpact(candidate.caseId(), candidate.caseNo(), "ALREADY_VISIBLE_AT_CUTOFF",
                    reason, snapshotId, candidate.cutoffAt(), null, null);
        }
        if (!revisionTouchesCase(event, candidate)) {
            return new CaseImpact(candidate.caseId(), candidate.caseNo(), "NOT_RELEVANT",
                    "revision does not overlap the case's parcel geometry or feature window",
                    snapshotId, candidate.cutoffAt(), null, null);
        }
        long backfillRunId = runs.queueBackfill(candidate.caseId(), event.ingestedAt(), event.id(),
                snapshotId, "controlled backfill for revision event " + event.id()
                        + " (" + event.sourceType() + " rev " + event.revisionNo() + ")");
        RunExecutor.ClaimResult result = executor.execute(backfillRunId);
        Long candidateId = db.jdbc().query(
                "select result_candidate_id from feature_run where id = ?",
                rs -> rs.next() ? (Long) rs.getObject(1) : null, backfillRunId);
        String effect = candidateId != null ? "RECOMPUTE_CANDIDATE" : "NO_IMPACT";
        String reason = candidateId != null
                ? "revision was ingested after the case cutoff and overlaps the feature window; a controlled "
                + "backfill (" + result.status() + ") produced review candidate " + candidateId
                + " without touching the frozen snapshot or the settled payout"
                : "controlled backfill (" + result.status() + ") produced no review candidate; values unchanged "
                + "or the case could not be recomputed";
        return new CaseImpact(candidate.caseId(), candidate.caseNo(), effect, reason, snapshotId,
                candidate.cutoffAt(), backfillRunId, candidateId);
    }

    private boolean revisionVisibleInSnapshotEvidence(long snapshotId, RevisionRow event) {
        // Only an input the run actually used counts. A revision that was present in the ledger
        // because it was *excluded* is not evidence that the snapshot captured it.
        Integer count = db.jdbc().queryForObject("""
                        select count(*) from snapshot_input
                         where snapshot_id = ? and source_type = ? and source_id = ?
                           and eligibility in ('ELIGIBLE','ELIGIBLE_OLDER_REVISION_USED')
                        """, Integer.class, snapshotId, event.sourceType(), event.sourceId());
        return count != null && count > 0;
    }

    /**
     * Geometric and temporal relevance:
     * <ul>
     *   <li>satellite revision: scene footprint intersects the parcel's locked boundary and the
     *       acquisition falls in the case's 30-day NDVI window;</li>
     *   <li>weather revision: the case parcel is linked to the observation's station and the
     *       period overlaps the 60-day rainfall window;</li>
     *   <li>declaration revision: same parcel;</li>
     *   <li>boundary revision: the new geometry intersects the case boundary or the same parcel.</li>
     * </ul>
     */
    private boolean revisionTouchesCase(RevisionRow event, CandidateCase candidate) {
        Instant ndviStart = candidate.hazardDate().minusDays(30).atStartOfDay(Times.UTC).toInstant();
        Instant rainStart = candidate.hazardDate().minusDays(60).atStartOfDay(Times.UTC).toInstant();
        Instant windowEnd = candidate.hazardDate().plusDays(1).atStartOfDay(Times.UTC).toInstant();
        Instant start = event.observedAt() != null ? event.observedAt() : event.ingestedAt();
        Instant end = event.windowEnd() != null ? event.windowEnd() : start;
        SourceType type = SourceType.valueOf(event.sourceType());
        switch (type) {
            case SATELLITE_NDVI -> {
                if (!overlapped(start, end, ndviStart, windowEnd)) {
                    return false;
                }
                return sceneIntersectsParcel(event.sourceId(), candidate.parcelId());
            }
            case WEATHER_OBSERVATION -> {
                if (!overlapped(start, end, rainStart, windowEnd)) {
                    return false;
                }
                Long stationId = db.jdbc().query("""
                                select station_id from weather_observation where id = ?
                                """, rs -> rs.next() ? rs.getLong(1) : null, event.sourceId());
                if (stationId == null) {
                    return false;
                }
                Integer linked = db.jdbc().queryForObject("""
                                select count(*) from parcel_weather_link where parcel_id = ? and station_id = ?
                                """, Integer.class, candidate.parcelId(), stationId);
                return linked != null && linked > 0;
            }
            case PLANTING_DECLARATION -> {
                return candidate.parcelId() == declarationParcel(event.sourceId());
            }
            case PARCEL_BOUNDARY -> {
                if (event.parcelId() != null && event.parcelId() == candidate.parcelId()) {
                    return true;
                }
                return boundaryIntersectsCase(event.sourceId(), candidate.caseId());
            }
            default -> {
                return true;
            }
        }
    }

    private long declarationParcel(long declarationId) {
        Long parcelId = db.jdbc().query("select parcel_id from planting_declaration where id = ?",
                rs -> rs.next() ? rs.getLong(1) : null, declarationId);
        return parcelId == null ? -1 : parcelId;
    }

    private boolean sceneIntersectsParcel(long sceneId, long parcelId) {
        String sceneFootprint = db.jdbc().query("select footprint_geojson from satellite_scene where id = ?",
                rs -> rs.next() ? rs.getString(1) : null, sceneId);
        String parcelGeometry = db.jdbc().query("select geometry_geojson from parcel_boundary_version "
                        + "where parcel_id = ? order by version_no desc limit 1",
                rs -> rs.next() ? rs.getString(1) : null, parcelId);
        if (sceneFootprint == null || parcelGeometry == null) {
            return false;
        }
        return geo.intersects(geo.parse(db.readTree(sceneFootprint)), geo.parse(db.readTree(parcelGeometry)));
    }

    private boolean boundaryIntersectsCase(long boundaryVersionId, long caseId) {
        String newGeometry = db.jdbc().query("select geometry_geojson from parcel_boundary_version where id = ?",
                rs -> rs.next() ? rs.getString(1) : null, boundaryVersionId);
        String caseGeometry = db.jdbc().query("""
                        select b.geometry_geojson from claim_case c
                          join parcel_boundary_version b on b.id = c.boundary_version_id where c.id = ?
                        """, rs -> rs.next() ? rs.getString(1) : null, caseId);
        if (newGeometry == null || caseGeometry == null) {
            return false;
        }
        return geo.intersects(geo.parse(db.readTree(newGeometry)), geo.parse(db.readTree(caseGeometry)));
    }

    private static boolean overlapped(Instant start, Instant end, Instant windowStart, Instant windowEnd) {
        return !end.isBefore(windowStart) && !start.isAfter(windowEnd);
    }

    private Long latestSnapshotId(long caseId) {
        return db.jdbc().query("""
                        select id from scoring_snapshot where case_id = ? order by created_at desc limit 1
                        """, rs -> rs.next() ? rs.getLong(1) : null, caseId);
    }

    private record CandidateCase(long caseId, String caseNo, long parcelId, Instant cutoffAt,
                                 java.time.LocalDate hazardDate) {
    }

    /**
     * All scored cases whose parcel and feature windows could be touched by the revision.
     * Boundary revisions can affect cases with overlapping geometry beyond the same parcel.
     */
    private List<CandidateCase> candidateCases(RevisionRow event) {
        return db.jdbc().query("""
                        select c.id, c.case_no, c.parcel_id, c.scoring_cutoff, c.hazard_date
                          from claim_case c
                         where exists (select 1 from scoring_snapshot s where s.case_id = c.id)
                         order by c.id
                        """,
                (rs, i) -> new CandidateCase(rs.getLong("id"), rs.getString("case_no"),
                        rs.getLong("parcel_id"), Db.instant(rs, "scoring_cutoff"),
                        rs.getObject("hazard_date", java.time.LocalDate.class)));
    }

    private AnalysisResult loadCompleted(long analysisId) {
        RevisionRow event = loadEvent(db.jdbc().queryForObject(
                "select revision_event_id from impact_analysis where id = ?", Long.class, analysisId));
        List<CaseImpact> impacts = db.jdbc().query("""
                        select ic.case_id, c.case_no, ic.effect, ic.reason, ic.snapshot_id, ic.cutoff_at,
                               ic.backfill_run_id, ic.candidate_id
                          from impact_case ic join claim_case c on c.id = ic.case_id
                         where ic.analysis_id = ? order by ic.case_id
                        """,
                (rs, i) -> new CaseImpact(rs.getLong("case_id"), rs.getString("case_no"),
                        rs.getString("effect"), rs.getString("reason"),
                        (Long) rs.getObject("snapshot_id"), Db.instant(rs, "cutoff_at"),
                        (Long) rs.getObject("backfill_run_id"), (Long) rs.getObject("candidate_id")),
                analysisId);
        Map<String, Object> summary = db.jdbc().query("""
                        select summary from impact_analysis where id = ?
                        """, rs -> rs.next() ? db.mapper().convertValue(
                db.readTree(rs.getString("summary")), Map.class) : Map.of(), analysisId);
        return new AnalysisResult(analysisId, event.id(), event.sourceType(), event.externalKey(),
                event.revisionNo(), event.ingestedAt(), impacts, summary);
    }

    private RevisionRow loadEvent(long id) {        List<RevisionRow> rows = db.jdbc().query("""
                        select id, source_type, source_table, source_id, external_key, revision_no,
                               previous_id, parcel_id, observed_at, ingested_at, window_start, window_end,
                               description
                          from data_revision_event where id = ?
                        """,
                (rs, i) -> new RevisionRow(rs.getLong("id"), rs.getString("source_type"),
                        rs.getString("source_table"), rs.getLong("source_id"), rs.getString("external_key"),
                        rs.getInt("revision_no"), (Long) rs.getObject("previous_id"),
                        (Long) rs.getObject("parcel_id"), Db.instant(rs, "observed_at"),
                        Db.instant(rs, "ingested_at"), Db.instant(rs, "window_start"),
                        Db.instant(rs, "window_end"), rs.getString("description")),
                id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("REVISION_EVENT_NOT_FOUND", "revision event not found: " + id);
        }
        return rows.get(0);
    }

    private record RevisionRow(long id, String sourceType, String sourceTable, long sourceId,
                               String externalKey, int revisionNo, Long previousId, Long parcelId,
                               Instant observedAt, Instant ingestedAt, Instant windowStart,
                               Instant windowEnd, String description) {
    }

    // ----------------------------------------------------------- revision ingest

    /**
     * Ingest a new satellite revision and immediately run impact analysis. This is the primary
     * scenario for late-arriving imagery: the original claim must remain untouched while a review
     * candidate is generated.
     */
    @Transactional
    public AnalysisResult analyzeSceneRevision(long sceneRevisionId) {
        long eventId = db.jdbc().queryForObject("""
                        select id from data_revision_event
                         where source_type = 'SATELLITE_NDVI' and source_id = ?
                         order by id desc limit 1
                        """, Long.class, sceneRevisionId);
        return analyze(eventId);
    }

    public AnalysisResult analyzeWeatherRevision(long observationId) {
        long eventId = db.jdbc().queryForObject("""
                        select id from data_revision_event
                         where source_type = 'WEATHER_OBSERVATION' and source_id = ?
                         order by id desc limit 1
                        """, Long.class, observationId);
        return analyze(eventId);
    }

    public List<Map<String, Object>> listAnalyses() {
        return db.jdbc().query("""
                select a.id, a.revision_event_id, a.status, a.affected_cases, a.candidate_cases,
                       a.created_at, e.source_type, e.external_key, e.revision_no, e.ingested_at
                  from impact_analysis a join data_revision_event e on e.id = a.revision_event_id
                 order by a.id desc
                """, (rs, i) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rs.getLong("id"));
            row.put("revisionEventId", rs.getLong("revision_event_id"));
            row.put("status", rs.getString("status"));
            row.put("affectedCases", rs.getInt("affected_cases"));
            row.put("candidateCases", rs.getInt("candidate_cases"));
            row.put("createdAt", Times.format(Db.instant(rs, "created_at")));
            row.put("sourceType", rs.getString("source_type"));
            row.put("externalKey", rs.getString("external_key"));
            row.put("revisionNo", rs.getInt("revision_no"));
            row.put("ingestedAt", Times.format(Db.instant(rs, "ingested_at")));
            return row;
        });
    }

    public Map<String, Object> getAnalysis(long analysisId) {
        List<Map<String, Object>> rows = db.jdbc().query("""
                select a.id, a.revision_event_id, a.status, a.affected_cases, a.candidate_cases,
                       a.summary::text as summary, a.created_at, e.source_type, e.external_key,
                       e.revision_no, e.ingested_at, e.description
                  from impact_analysis a join data_revision_event e on e.id = a.revision_event_id
                 where a.id = ?
                """, (rs, i) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rs.getLong("id"));
            row.put("revisionEventId", rs.getLong("revision_event_id"));
            row.put("status", rs.getString("status"));
            row.put("affectedCases", rs.getInt("affected_cases"));
            row.put("candidateCases", rs.getInt("candidate_cases"));
            row.put("summary", db.readTree(rs.getString("summary")));
            row.put("createdAt", Times.format(Db.instant(rs, "created_at")));
            row.put("sourceType", rs.getString("source_type"));
            row.put("externalKey", rs.getString("external_key"));
            row.put("revisionNo", rs.getInt("revision_no"));
            row.put("ingestedAt", Times.format(Db.instant(rs, "ingested_at")));
            row.put("description", rs.getString("description"));
            return row;
        }, analysisId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("ANALYSIS_NOT_FOUND", "impact analysis not found: " + analysisId);
        }
        Map<String, Object> result = new LinkedHashMap<>(rows.get(0));
        result.put("cases", db.jdbc().query("""
                select ic.case_id, c.case_no, ic.snapshot_id, ic.effect, ic.reason, ic.cutoff_at,
                       ic.backfill_run_id, ic.candidate_id
                  from impact_case ic join claim_case c on c.id = ic.case_id
                 where ic.analysis_id = ? order by ic.case_id
                """, (rs, i) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("caseId", rs.getLong("case_id"));
            row.put("caseNo", rs.getString("case_no"));
            row.put("snapshotId", rs.getObject("snapshot_id"));
            row.put("effect", rs.getString("effect"));
            row.put("reason", rs.getString("reason"));
            row.put("cutoffAt", Times.format(Db.instant(rs, "cutoff_at")));
            row.put("backfillRunId", rs.getObject("backfill_run_id"));
            row.put("candidateId", rs.getObject("candidate_id"));
            return row;
        }, analysisId));
        return result;
    }
}

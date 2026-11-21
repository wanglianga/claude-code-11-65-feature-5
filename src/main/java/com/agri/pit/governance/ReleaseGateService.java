package com.agri.pit.governance;

import com.agri.pit.catalog.CatalogChangedEvent;
import com.agri.pit.catalog.CatalogService;
import com.agri.pit.common.ApiException;
import com.agri.pit.common.Db;
import com.agri.pit.common.Times;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Definition release gate.
 * <p>
 * A gate binds a candidate definition version to a shadow-scoring campaign over frozen snapshots
 * and to the exact catalog fingerprint captured at submission. Promotion is allowed only when the
 * temporal ordering rules pass and the reversal rate stays within the configured threshold. If the
 * catalog is modified while the gate is awaiting approval, the fingerprint drifts and the gate is
 * invalidated: the version the approval referenced can no longer be promoted.
 */
@Service
public class ReleaseGateService {

    private final Db db;
    private final CatalogService catalog;
    private final ShadowScorer shadowScorer;
    private final TemporalRuleStore ruleStore;
    private final GovernanceProperties properties;

    public ReleaseGateService(Db db, CatalogService catalog, ShadowScorer shadowScorer,
                              TemporalRuleStore ruleStore, GovernanceProperties properties) {
        this.db = db;
        this.catalog = catalog;
        this.shadowScorer = shadowScorer;
        this.ruleStore = ruleStore;
        this.properties = properties;
    }

    @Transactional
    public long submit(GovernanceDtos.GateSubmitRequest req) {
        CatalogService.FeatureRow feature = catalog.requireFeature(req.featureCode());
        CatalogService.VersionRow candidate = catalog.requireVersion(feature.id(), req.candidateVersionNo());
        if (!"DRAFT".equals(candidate.status())) {
            throw ApiException.conflict("CANDIDATE_NOT_DRAFT",
                    "only a DRAFT version can enter a release gate; v" + req.candidateVersionNo()
                            + " is " + candidate.status());
        }
        double absThreshold = req.scoreDiffThreshold() == null
                ? properties.getDefaultScoreDiffThreshold() : req.scoreDiffThreshold();
        double relativeThreshold = req.relativeDiffThreshold() == null
                ? properties.getDefaultRelativeDiffThreshold() : req.relativeDiffThreshold();
        double reversalLimit = req.reversalRateLimit() == null
                ? properties.getDefaultReversalRateLimit() : req.reversalRateLimit();
        String campaignCode = "GATE-" + feature.code() + "-v" + candidate.versionNo()
                + "-" + Instant.now().toEpochMilli();

        List<Long> shadowRunIds = shadowScorer.runCampaign(new GovernanceDtos.ShadowCampaignRequest(
                campaignCode, feature.code(), candidate.versionNo(), req.caseNos(),
                absThreshold, reversalLimit, req.releaseLabel(),
                "release gate shadow campaign", req.requestedBy()));

        GateCounts counts = aggregate(shadowRunIds);
        int totalComparisons = Math.max(1, counts.totalFeatures());
        double reversalRate = (double) counts.overThreshold() / totalComparisons;
        boolean temporalPassed = counts.blockingFindings() == 0 && counts.timeTravelFailures() == 0;
        boolean thresholdPassed = temporalPassed && reversalRate <= reversalLimit;

        ObjectNode thresholdConfig = db.mapper().createObjectNode();
        thresholdConfig.put("scoreDiffThreshold", absThreshold);
        thresholdConfig.put("relativeDiffThreshold", relativeThreshold);
        thresholdConfig.put("reversalRateLimit", reversalLimit);
        thresholdConfig.put("relativeDeltaFloor", properties.getRelativeDeltaFloor());

        ObjectNode checks = db.mapper().createObjectNode();
        checks.put("temporalPassed", temporalPassed);
        checks.put("thresholdPassed", thresholdPassed);
        checks.put("reversalRate", round(reversalRate));
        checks.put("overThresholdFeatures", counts.overThreshold());
        checks.put("businessDifferences", counts.businessDifferences());
        checks.put("timeTravelFailures", counts.timeTravelFailures());
        checks.put("missingDataDifferences", counts.missingDataDifferences());
        checks.put("shadowRunCount", shadowRunIds.size());

        ArrayNode runIds = db.mapper().createArrayNode();
        shadowRunIds.forEach(runIds::add);
        String fingerprint = catalog.catalogFingerprint();
        String checksum = db.jdbc().queryForObject(
                "select checksum from feature_definition_version where id = ?",
                String.class, candidate.id());
        long gateId = db.insert("""
                        insert into release_gate
                            (feature_definition_id, candidate_version_id, candidate_checksum, release_label,
                             status, catalog_fingerprint, threshold_config, shadow_run_ids, temporal_passed,
                             threshold_passed, blocking_finding_count, reversal_count,
                             business_difference_count, max_abs_delta, checks)
                        values (?, ?, ?, ?, 'PENDING_APPROVAL', ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?, ?, ?::jsonb)
                        """,
                feature.id(), candidate.id(), checksum == null ? "" : checksum,
                req.releaseLabel(), fingerprint, db.jsonArg(thresholdConfig), db.jsonArg(runIds),
                temporalPassed, thresholdPassed, counts.blockingFindings(), counts.overThreshold(),
                counts.businessDifferences(),
                counts.maxAbsDelta() == null ? null : BigDecimal.valueOf(counts.maxAbsDelta()),
                db.jsonArg(checks));

        persistChronologyFinding(gateId, feature, candidate);
        return gateId;
    }

    @Transactional
    public GovernanceDtos.GateView decide(long gateId, boolean approve, String decisionBy, String note) {
        GateRow gate = requireGate(gateId);
        if ("INVALIDATED".equals(gate.status())) {
            throw ApiException.conflict("GATE_INVALIDATED",
                    "the catalog changed while the gate was open; the referenced version approval is void: "
                            + gate.invalidateReason());
        }
        if (!"PENDING_APPROVAL".equals(gate.status())) {
            throw ApiException.conflict("GATE_NOT_OPEN",
                    "gate " + gateId + " is " + gate.status() + " and cannot be decided again");
        }
        String currentFingerprint = catalog.catalogFingerprint();
        if (!currentFingerprint.equals(gate.catalogFingerprint())) {
            invalidate(gateId, "catalog changed after gate submission; fingerprint "
                    + gate.catalogFingerprint().substring(0, 12) + " no longer matches "
                    + currentFingerprint.substring(0, 12));
            throw ApiException.conflict("GATE_INVALIDATED",
                    "the catalog changed while the gate was open; the referenced version approval is void");
        }
        String actor = decisionBy == null || decisionBy.isBlank() ? "release-manager" : decisionBy.trim();
        if (approve) {
            if (!gate.temporalPassed() || !gate.thresholdPassed()) {
                throw ApiException.unprocessable("GATE_CHECKS_FAILED",
                        "the candidate did not pass the temporal and threshold checks; reject it or fix the definition");
            }
            CatalogService.VersionRow candidate = catalog.requireVersionById(gate.candidateVersionId());
            catalog.approve(featureCode(gate.featureDefinitionId()), candidate.versionNo(), actor,
                    "approved via release gate " + gateId + (note == null ? "" : ": " + note));
            db.jdbc().update("""
                            update release_gate
                               set status = 'APPROVED', decision_by = ?, decided_at = now(), decision_note = ?
                             where id = ?
                            """, actor, note, gateId);
        } else {
            db.jdbc().update("""
                            update release_gate
                               set status = 'REJECTED', decision_by = ?, decided_at = now(), decision_note = ?
                             where id = ?
                            """, actor, note, gateId);
        }
        return getGate(gateId);
    }

    /** Catalog mutations while a gate is open invalidate every gate whose fingerprint drifted. */
    @EventListener
    @Transactional
    public void onCatalogChanged(CatalogChangedEvent event) {
        String fingerprint = catalog.catalogFingerprint();
        List<Long> openGateIds = db.jdbc().query(
                "select id from release_gate where status = 'PENDING_APPROVAL' order by id",
                (rs, i) -> rs.getLong(1));
        for (Long gateId : openGateIds) {
            GateRow gate = requireGate(gateId);
            if (!fingerprint.equals(gate.catalogFingerprint())) {
                invalidate(gateId, "catalog changed: " + event.featureCode() + " gained v"
                        + event.newVersionNo() + " while the gate was awaiting approval");
            }
        }
    }

    private void invalidate(long gateId, String reason) {
        db.jdbc().update("""
                        update release_gate
                           set status = 'INVALIDATED', invalidated_at = now(), invalidate_reason = ?
                         where id = ? and status = 'PENDING_APPROVAL'
                        """, reason, gateId);
    }

    private void persistChronologyFinding(long gateId, CatalogService.FeatureRow feature,
                                          CatalogService.VersionRow candidate) {
        List<Long> predecessorIds = db.jdbc().query("""
                        select id from feature_definition_version
                         where feature_definition_id = ? and version_no < ?
                         order by version_no desc limit 1
                        """,
                (rs, i) -> rs.getLong(1), feature.id(), candidate.versionNo());
        TemporalRuleSet.Finding finding;
        if (predecessorIds.isEmpty()) {
            finding = TemporalRuleSet.versionChronology(
                    new TemporalRuleSet.DefinitionClock(candidate.id(), candidate.versionNo(),
                            feature.code(), candidate.effectiveAt(), candidate.availabilityLagSeconds()),
                    null);
        } else {
            CatalogService.VersionRow predecessor = catalog.requireVersionById(predecessorIds.get(0));
            finding = TemporalRuleSet.versionChronology(
                    new TemporalRuleSet.DefinitionClock(candidate.id(), candidate.versionNo(),
                            feature.code(), candidate.effectiveAt(), candidate.availabilityLagSeconds()),
                    predecessor.effectiveAt());
        }
        ruleStore.persistFindings(null, gateId, List.of(finding));
    }

    private GateCounts aggregate(List<Long> shadowRunIds) {
        int blocking = 0;
        int overThreshold = 0;
        int business = 0;
        int timeTravel = 0;
        int missing = 0;
        int total = 0;
        Double maxAbsDelta = null;
        for (Long shadowRunId : shadowRunIds) {
            Integer blockingHere = db.jdbc().queryForObject(
                    "select blocking_finding_count from shadow_run where id = ?",
                    Integer.class, shadowRunId);
            blocking += blockingHere == null ? 0 : blockingHere;
            List<Map<String, Object>> rows = db.jdbc().queryForList("""
                    select classification, over_threshold, abs_delta
                      from shadow_run_feature where shadow_run_id = ?
                    """, shadowRunId);
            for (Map<String, Object> row : rows) {
                total++;
                if (Boolean.TRUE.equals(row.get("over_threshold"))) {
                    overThreshold++;
                }
                switch (String.valueOf(row.get("classification"))) {
                    case "BUSINESS_DIFFERENCE" -> business++;
                    case "TIME_TRAVEL_FAILURE" -> timeTravel++;
                    case "MISSING_DATA_DIFFERENCE" -> missing++;
                    default -> {
                    }
                }
                Object delta = row.get("abs_delta");
                if (delta instanceof BigDecimal decimal) {
                    maxAbsDelta = maxAbsDelta == null ? decimal.doubleValue()
                            : Math.max(maxAbsDelta, decimal.doubleValue());
                }
            }
        }
        return new GateCounts(blocking, overThreshold, business, timeTravel, missing, total, maxAbsDelta);
    }

    public List<GovernanceDtos.GateView> list() {
        List<Long> ids = db.jdbc().query("select id from release_gate order by id desc",
                (rs, i) -> rs.getLong(1));
        List<GovernanceDtos.GateView> views = new ArrayList<>();
        for (Long id : ids) {
            views.add(getGate(id));
        }
        return views;
    }

    public GovernanceDtos.GateView getGate(long gateId) {
        GateRow gate = requireGate(gateId);
        String featureCode = featureCode(gate.featureDefinitionId());
        Integer versionNo = db.jdbc().queryForObject(
                "select version_no from feature_definition_version where id = ?",
                Integer.class, gate.candidateVersionId());
        List<Long> shadowRunIds = new ArrayList<>();
        db.readTree(gate.shadowRunIdsJson()).forEach(node -> shadowRunIds.add(node.asLong()));
        List<GovernanceDtos.ShadowRunView> shadowRuns = shadowRunIds.stream()
                .map(id -> shadowScorer.getShadowRun(id)).toList();
        return new GovernanceDtos.GateView(gate.id(), featureCode, versionNo, gate.candidateVersionId(),
                gate.candidateChecksum(), gate.releaseLabel(), gate.status(), gate.catalogFingerprint(),
                db.readTree(gate.thresholdConfigJson()), shadowRunIds, gate.temporalPassed(),
                gate.thresholdPassed(), gate.blockingFindingCount(), gate.reversalCount(),
                gate.businessDifferenceCount(),
                gate.maxAbsDelta() == null ? null : gate.maxAbsDelta().doubleValue(),
                db.readTree(gate.checksJson()), gate.decisionBy(),
                Times.format(gate.decidedAt()), gate.decisionNote(), gate.invalidateReason(),
                Times.format(gate.createdAt()), Times.format(gate.invalidatedAt()),
                ruleStore.findingsForGate(gateId), shadowRuns);
    }

    private String featureCode(long featureDefinitionId) {
        return db.jdbc().queryForObject("select code from feature_definition where id = ?",
                String.class, featureDefinitionId);
    }

    private GateRow requireGate(long gateId) {
        List<GateRow> rows = db.jdbc().query("select * from release_gate where id = ?",
                (rs, i) -> new GateRow(
                        rs.getLong("id"), rs.getLong("feature_definition_id"),
                        rs.getLong("candidate_version_id"), rs.getString("candidate_checksum"),
                        rs.getString("release_label"), rs.getString("status"),
                        rs.getString("catalog_fingerprint"), rs.getString("threshold_config"),
                        rs.getString("shadow_run_ids"), rs.getBoolean("temporal_passed"),
                        rs.getBoolean("threshold_passed"), rs.getInt("blocking_finding_count"),
                        rs.getInt("reversal_count"), rs.getInt("business_difference_count"),
                        rs.getBigDecimal("max_abs_delta"), rs.getString("checks"),
                        rs.getString("decision_by"), Db.instant(rs, "decided_at"),
                        rs.getString("decision_note"), rs.getString("invalidate_reason"),
                        Db.instant(rs, "created_at"), Db.instant(rs, "invalidated_at")),
                gateId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("GATE_NOT_FOUND", "release gate not found: " + gateId);
        }
        return rows.get(0);
    }

    private static double round(double value) {
        return Math.round(value * 1_000_000d) / 1_000_000d;
    }

    private record GateCounts(int blockingFindings, int overThreshold, int businessDifferences,
                              int timeTravelFailures, int missingDataDifferences, int totalFeatures,
                              Double maxAbsDelta) {
    }

    private record GateRow(long id, long featureDefinitionId, long candidateVersionId,
                           String candidateChecksum, String releaseLabel, String status,
                           String catalogFingerprint, String thresholdConfigJson, String shadowRunIdsJson,
                           boolean temporalPassed, boolean thresholdPassed, int blockingFindingCount,
                           int reversalCount, int businessDifferenceCount, BigDecimal maxAbsDelta,
                           String checksJson, String decisionBy, Instant decidedAt, String decisionNote,
                           String invalidateReason, Instant createdAt, Instant invalidatedAt) {
    }
}

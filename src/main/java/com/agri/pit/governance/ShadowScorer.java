package com.agri.pit.governance;

import com.agri.pit.cases.CaseService;
import com.agri.pit.cases.CaseService.CaseRow;
import com.agri.pit.catalog.CatalogService;
import com.agri.pit.common.ApiException;
import com.agri.pit.common.Db;
import com.agri.pit.common.Times;
import com.agri.pit.leakage.InputLedger;
import com.agri.pit.model.InputVerdict;
import com.agri.pit.model.SourceType;
import com.agri.pit.runs.FeatureEvaluator;
import com.agri.pit.runs.RunExecutor;
import com.agri.pit.runs.RunService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shadow scorer.
 * <p>
 * Re-runs frozen claim cases at their original scoring cutoff with candidate definition versions
 * and diffs the candidate scores against the immutable production snapshots. Shadow runs execute
 * through the same point-in-time engine as production (mode {@code SHADOW}) but never write a
 * snapshot, never create a review candidate and never change the claim status, so production
 * claims are completely unaffected by the double computation.
 */
@Service
public class ShadowScorer {

    private final Db db;
    private final CaseService cases;
    private final CatalogService catalog;
    private final RunService runs;
    private final RunExecutor executor;
    private final TemporalRuleStore ruleStore;
    private final ReversalAttributor attributor;
    private final GovernanceProperties properties;

    public ShadowScorer(Db db, CaseService cases, CatalogService catalog, RunService runs,
                        RunExecutor executor, TemporalRuleStore ruleStore, ReversalAttributor attributor,
                        GovernanceProperties properties) {
        this.db = db;
        this.cases = cases;
        this.catalog = catalog;
        this.runs = runs;
        this.executor = executor;
        this.ruleStore = ruleStore;
        this.attributor = attributor;
        this.properties = properties;
    }

    public record BaselineFeature(String featureCode, long versionId, int versionNo, String formula,
                                  String unit, Double value, String status, JsonNode evidence) {
    }

    /** Create and synchronously execute one shadow run per frozen case snapshot. */
    @Transactional
    public List<Long> runCampaign(GovernanceDtos.ShadowCampaignRequest req) {
        if (req.featureCode() == null || req.featureCode().isBlank()) {
            throw ApiException.badRequest("FEATURE_CODE_REQUIRED", "featureCode is required");
        }
        CatalogService.FeatureRow feature = catalog.requireFeature(req.featureCode());
        CatalogService.VersionRow candidate = catalog.requireVersion(feature.id(), req.candidateVersionNo());
        if (!"DRAFT".equals(candidate.status())) {
            throw ApiException.conflict("CANDIDATE_NOT_DRAFT",
                    "shadow scoring requires a DRAFT candidate version; " + feature.code()
                            + " v" + req.candidateVersionNo() + " is " + candidate.status());
        }
        double threshold = req.scoreDiffThreshold() == null
                ? properties.getDefaultScoreDiffThreshold() : req.scoreDiffThreshold();
        double reversalLimit = req.reversalRateLimit() == null
                ? properties.getDefaultReversalRateLimit() : req.reversalRateLimit();
        List<Long> caseIds = resolveCaseIds(req.caseNos());
        String campaignCode = req.campaignCode() == null || req.campaignCode().isBlank()
                ? "SHADOW-" + feature.code() + "-v" + req.candidateVersionNo() + "-" + Instant.now().toEpochMilli()
                : req.campaignCode().trim();
        List<Long> shadowRunIds = new ArrayList<>();
        for (Long caseId : caseIds) {
            shadowRunIds.add(scoreOneCase(campaignCode, feature, candidate, caseId, threshold,
                    reversalLimit, req.releaseLabel(), req.reason(), req.requestedBy()));
        }
        return shadowRunIds;
    }

    private long scoreOneCase(String campaignCode, CatalogService.FeatureRow feature,
                              CatalogService.VersionRow candidate, long caseId, double threshold,
                              double reversalLimit, String releaseLabel, String reason, String requestedBy) {
        CaseRow caseRow = cases.requireCase(caseNo(caseId));
        Long snapshotId = latestSnapshotId(caseId);
        if (snapshotId == null) {
            throw ApiException.unprocessable("NO_FROZEN_SNAPSHOT",
                    "case " + caseRow.caseNo() + " has no frozen snapshot; shadow scoring needs a frozen baseline");
        }
        Integer locked = db.jdbc().queryForObject("""
                        select count(*)
                          from claim_case_feature_lock l
                          join feature_definition_version fv on fv.id = l.feature_definition_version_id
                          join feature_definition fd on fd.id = fv.feature_definition_id
                         where l.case_id = ? and fd.code = ?
                        """, Integer.class, caseId, feature.code());
        if (locked == null || locked == 0) {
            throw ApiException.unprocessable("FEATURE_NOT_LOCKED",
                    "case " + caseRow.caseNo() + " does not lock " + feature.code()
                            + "; shadow scoring can only compare definitions the frozen case actually used");
        }
        Long baselineRunId = db.jdbc().queryForObject(
                "select run_id from scoring_snapshot where id = ?", Long.class, snapshotId);
        long shadowRunId = db.insert("""
                        insert into shadow_run
                            (campaign_code, case_id, snapshot_id, baseline_run_id, release_label, status,
                             score_diff_threshold, reversal_rate_limit, cutoff_at, temporal_verdict,
                             requested_by, reason)
                        values (?, ?, ?, ?, ?, 'QUEUED', ?, ?, ?, 'NOT_EVALUATED', ?, ?)
                        """,
                campaignCode, caseId, snapshotId, baselineRunId, releaseLabel,
                BigDecimal.valueOf(threshold), BigDecimal.valueOf(reversalLimit),
                Db.timestamp(caseRow.scoringCutoff()),
                requestedBy == null || requestedBy.isBlank() ? "shadow-scorer" : requestedBy,
                reason == null ? "shadow comparison of " + feature.code() + " v" + candidate.versionNo()
                        : reason);
        db.insert("""
                        insert into shadow_run_definition (shadow_run_id, feature_code, definition_version_id)
                        values (?, ?, ?)
                        """, shadowRunId, feature.code(), candidate.id());

        long featureRunId = runs.queueShadowRun(caseId, baselineRunId, shadowRunId,
                "shadow run " + shadowRunId + " for " + feature.code() + " v" + candidate.versionNo());
        executor.execute(featureRunId);

        Map<String, FeatureEvaluator.FeatureResult> candidateResults = loadRunFeatures(featureRunId);
        Map<String, BaselineFeature> baseline = loadSnapshotBaseline(snapshotId);
        InputLedger.Result ledger = loadLedger(featureRunId);
        List<TemporalRuleSet.DefinitionClock> clocks = definitionClocks(caseId, feature.code(), candidate);

        TemporalRuleSet.Evaluation temporal = TemporalRuleSet.evaluate(caseRow.scoringCutoff(), ledger, clocks);
        ruleStore.persistFindings(shadowRunId, null, temporal.findings());

        int overThreshold = 0;
        int businessDifferences = 0;
        int reversalCount = 0;
        double maxAbsDelta = 0d;
        List<Map<String, Object>> featureSummaries = new ArrayList<>();
        for (Map.Entry<String, FeatureEvaluator.FeatureResult> entry : candidateResults.entrySet()) {
            String code = entry.getKey();
            FeatureEvaluator.FeatureResult candidateResult = entry.getValue();
            BaselineFeature base = baseline.get(code);
            FeatureEvaluator.FeatureResult baselineResult = toFeatureResult(base);
            ReversalAttributor.AttributionResult attribution = attributor.attribute(
                    baselineResult, candidateResult, threshold,
                    properties.getDefaultRelativeDiffThreshold(),
                    properties.getRelativeDeltaFloor(), temporal.chainComplete());
            if (attribution.overThreshold()) {
                overThreshold++;
            }
            if ("BUSINESS_DIFFERENCE".equals(attribution.classification())) {
                businessDifferences++;
            }
            if (attribution.reversed()) {
                reversalCount++;
            }
            if (attribution.absDelta() != null) {
                maxAbsDelta = Math.max(maxAbsDelta, attribution.absDelta());
            }
            persistShadowFeature(shadowRunId, base, candidateResult, attribution);
            featureSummaries.add(Map.of(
                    "featureCode", code,
                    "classification", attribution.classification(),
                    "absDelta", attribution.absDelta() == null ? 0d : attribution.absDelta()));
        }

        ObjectNode summary = db.mapper().createObjectNode();
        summary.put("featureRunId", featureRunId);
        summary.put("featuresCompared", candidateResults.size());
        summary.put("overThreshold", overThreshold);
        summary.put("businessDifferences", businessDifferences);
        summary.put("reversals", reversalCount);
        summary.put("maxAbsDelta", Math.round(maxAbsDelta * 1_000_000d) / 1_000_000d);
        summary.set("features", db.mapper().valueToTree(featureSummaries));

        db.jdbc().update("""
                        update shadow_run
                           set status = 'COMPLETED', temporal_verdict = ?, chain_complete = ?,
                               blocking_finding_count = ?, warning_finding_count = ?,
                               summary = ?::jsonb, completed_at = now()
                         where id = ?
                        """,
                temporal.verdict(), temporal.chainComplete(),
                temporal.blockingCount(), temporal.warningCount(), db.jsonArg(summary), shadowRunId);
        return shadowRunId;
    }

    private List<Long> resolveCaseIds(List<String> caseNos) {
        if (caseNos != null && !caseNos.isEmpty()) {
            List<Long> ids = new ArrayList<>();
            for (String caseNo : caseNos) {
                ids.add(cases.requireCase(caseNo).id());
            }
            return ids;
        }
        return db.jdbc().query("""
                        select distinct c.id from claim_case c
                          join scoring_snapshot s on s.case_id = c.id
                         order by c.id
                        """, (rs, i) -> rs.getLong(1));
    }

    private String caseNo(long caseId) {
        return db.jdbc().queryForObject("select case_no from claim_case where id = ?",
                String.class, caseId);
    }

    private Long latestSnapshotId(long caseId) {
        return db.jdbc().query("""
                        select id from scoring_snapshot where case_id = ? order by created_at desc limit 1
                        """, rs -> rs.next() ? rs.getLong(1) : null, caseId);
    }

    private Map<String, FeatureEvaluator.FeatureResult> loadRunFeatures(long runId) {
        Map<String, FeatureEvaluator.FeatureResult> results = new LinkedHashMap<>();
        db.jdbc().query("""
                        select feature_code, definition_version_id, formula, value, unit, status,
                               input_count, window_start, window_end, detail
                          from feature_run_feature where run_id = ? order by feature_code
                        """, rs -> {
            String code = rs.getString("feature_code");
            Integer versionNo = db.jdbc().queryForObject("""
                    select version_no from feature_definition_version where id = ?
                    """, Integer.class, rs.getLong("definition_version_id"));
            JsonNode detailNode = db.readTree(rs.getString("detail"));
            Map<String, Object> detail = db.mapper().convertValue(detailNode,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
            results.put(code, new FeatureEvaluator.FeatureResult(
                    code, rs.getLong("definition_version_id"), versionNo, rs.getString("formula"),
                    rs.getString("unit"),
                    rs.getBigDecimal("value") == null ? null : rs.getBigDecimal("value").doubleValue(),
                    rs.getString("status"), rs.getInt("input_count"),
                    Db.instant(rs, "window_start"), Db.instant(rs, "window_end"), detail));
        }, runId);
        return results;
    }

    private Map<String, BaselineFeature> loadSnapshotBaseline(long snapshotId) {
        Map<String, BaselineFeature> baseline = new LinkedHashMap<>();
        db.jdbc().query("""
                        select feature_code, definition_version_id, formula, value, unit, status, evidence
                          from snapshot_feature where snapshot_id = ? order by feature_code
                        """, rs -> {
            Integer versionNo = db.jdbc().queryForObject("""
                    select version_no from feature_definition_version where id = ?
                    """, Integer.class, rs.getLong("definition_version_id"));
            baseline.put(rs.getString("feature_code"), new BaselineFeature(
                    rs.getString("feature_code"), rs.getLong("definition_version_id"), versionNo,
                    rs.getString("formula"), rs.getString("unit"),
                    rs.getBigDecimal("value") == null ? null : rs.getBigDecimal("value").doubleValue(),
                    rs.getString("status"), db.readTree(rs.getString("evidence"))));
        }, snapshotId);
        return baseline;
    }

    private FeatureEvaluator.FeatureResult toFeatureResult(BaselineFeature base) {
        Map<String, Object> detail = db.mapper().convertValue(base.evidence(),
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                });
        return new FeatureEvaluator.FeatureResult(base.featureCode(), base.versionId(), base.versionNo(),
                base.formula(), base.unit(), base.value(), base.status(),
                base.evidence().path("inputCount").asInt(0), null, null, detail);
    }

    private InputLedger.Result loadLedger(long runId) {
        List<InputLedger.Candidate> candidates = new ArrayList<>();
        db.jdbc().query("""
                        select source_type, source_table, source_id, revision_no, observed_at, ingested_at,
                               eligibility, reason, contributed_value, detail
                          from feature_run_input where run_id = ? order by id
                        """, rs -> {
            ObjectNode detail = db.mapper().createObjectNode();
            String detailJson = rs.getString("detail");
            if (detailJson != null) {
                detail = (ObjectNode) db.readTree(detailJson);
            }
            candidates.add(new InputLedger.Candidate(
                    SourceType.valueOf(rs.getString("source_type")), rs.getString("source_table"),
                    rs.getLong("source_id"), (Integer) rs.getObject("revision_no"),
                    detail.path("externalKey").asText(null),
                    Db.instant(rs, "observed_at"), Db.instant(rs, "ingested_at"),
                    InputVerdict.valueOf(rs.getString("eligibility")), rs.getString("reason"),
                    rs.getBigDecimal("contributed_value") == null
                            ? null : rs.getBigDecimal("contributed_value").doubleValue(),
                    detail));
        }, runId);
        List<InputLedger.Violation> violations = new ArrayList<>();
        db.jdbc().query("""
                        select v.code, v.severity, v.source_type, v.source_id, v.feature_code, v.message
                          from leakage_violation v
                          join leakage_report r on r.id = v.report_id
                         where r.run_id = ? order by v.id
                        """, rs -> {
                            violations.add(new InputLedger.Violation(
                rs.getString("code"), rs.getString("severity"),
                rs.getString("source_type") == null ? null : SourceType.valueOf(rs.getString("source_type")),
                (Long) rs.getObject("source_id"), rs.getString("feature_code"), rs.getString("message")));
                        },
                runId);
        boolean hardBlocked = violations.stream().anyMatch(v -> "BLOCKING".equals(v.severity()));
        return new InputLedger.Result(candidates, violations, hardBlocked,
                hardBlocked ? "blocking leakage violation in shadow ledger" : null);
    }

    private List<TemporalRuleSet.DefinitionClock> definitionClocks(long caseId, String targetCode,
                                                                   CatalogService.VersionRow candidate) {
        List<TemporalRuleSet.DefinitionClock> clocks = new ArrayList<>();
        db.jdbc().query("""
                        select distinct fv.id, fv.version_no, fd.code, fv.effective_at,
                               fv.availability_lag_seconds
                          from claim_case_feature_lock l
                          join feature_definition_version fv on fv.id = l.feature_definition_version_id
                          join feature_definition fd on fd.id = fv.feature_definition_id
                         where l.case_id = ?
                         order by fd.code
                        """, rs -> {
            long versionId = rs.getLong("id");
            String code = rs.getString("code");
            if (code.equals(targetCode)) {
                clocks.add(new TemporalRuleSet.DefinitionClock(candidate.id(), candidate.versionNo(),
                        code, candidate.effectiveAt(), candidate.availabilityLagSeconds()));
            } else {
                clocks.add(new TemporalRuleSet.DefinitionClock(versionId, rs.getInt("version_no"),
                        code, Db.instant(rs, "effective_at"), rs.getLong("availability_lag_seconds")));
            }
        }, caseId);
        return clocks;
    }

    private void persistShadowFeature(long shadowRunId, BaselineFeature base,
                                      FeatureEvaluator.FeatureResult candidate,
                                      ReversalAttributor.AttributionResult attribution) {
        db.insert("""
                        insert into shadow_run_feature
                            (shadow_run_id, feature_code, baseline_version_id, baseline_version_no,
                             baseline_value, baseline_status, candidate_version_id, candidate_version_no,
                             candidate_value, candidate_status, abs_delta, relative_delta, reversed,
                             over_threshold, input_chain_complete, classification, attribution)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                        """,
                shadowRunId, candidate.featureCode(), base.versionId(), base.versionNo(),
                base.value() == null ? null : BigDecimal.valueOf(base.value()), base.status(),
                candidate.definitionVersionId(), candidate.definitionVersionNo(),
                candidate.value() == null ? null : BigDecimal.valueOf(candidate.value()),
                candidate.status(),
                attribution.absDelta() == null ? null : BigDecimal.valueOf(attribution.absDelta()),
                attribution.relativeDelta() == null ? null : BigDecimal.valueOf(attribution.relativeDelta()),
                attribution.reversed(), attribution.overThreshold(),
                attribution.inputChainComplete(), attribution.classification(),
                db.jsonArg(attribution.attribution()));
    }

    // -------------------------------------------------------------------- views

    public GovernanceDtos.ShadowRunView getShadowRun(long shadowRunId) {
        List<GovernanceDtos.ShadowRunView> rows = db.jdbc().query("""
                        select sr.*, c.case_no from shadow_run sr
                          join claim_case c on c.id = sr.case_id where sr.id = ?
                        """, (rs, i) -> new GovernanceDtos.ShadowRunView(
                rs.getLong("id"), rs.getString("campaign_code"), rs.getLong("case_id"),
                rs.getString("case_no"), rs.getLong("snapshot_id"), rs.getString("status"),
                rs.getBigDecimal("score_diff_threshold").doubleValue(),
                rs.getBigDecimal("reversal_rate_limit").doubleValue(),
                Times.format(Db.instant(rs, "cutoff_at")), rs.getString("temporal_verdict"),
                rs.getBoolean("chain_complete"), rs.getInt("blocking_finding_count"),
                rs.getInt("warning_finding_count"),
                db.readTree(rs.getString("summary")), List.of(), List.of()),
                shadowRunId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("SHADOW_RUN_NOT_FOUND", "shadow run not found: " + shadowRunId);
        }
        GovernanceDtos.ShadowRunView base = rows.get(0);
        List<GovernanceDtos.ShadowFeatureView> features = db.jdbc().query("""
                        select * from shadow_run_feature where shadow_run_id = ? order by feature_code
                        """,
                (rs, i) -> new GovernanceDtos.ShadowFeatureView(
                        rs.getString("feature_code"), (Integer) rs.getObject("baseline_version_no"),
                        rs.getBigDecimal("baseline_value") == null ? null
                                : rs.getBigDecimal("baseline_value").doubleValue(),
                        rs.getString("baseline_status"), rs.getInt("candidate_version_no"),
                        rs.getBigDecimal("candidate_value") == null ? null
                                : rs.getBigDecimal("candidate_value").doubleValue(),
                        rs.getString("candidate_status"),
                        rs.getBigDecimal("abs_delta") == null ? null
                                : rs.getBigDecimal("abs_delta").doubleValue(),
                        rs.getBigDecimal("relative_delta") == null ? null
                                : rs.getBigDecimal("relative_delta").doubleValue(),
                        rs.getBoolean("reversed"), rs.getBoolean("over_threshold"),
                        rs.getBoolean("input_chain_complete"), rs.getString("classification"),
                        db.readTree(rs.getString("attribution"))),
                shadowRunId);
        List<GovernanceDtos.TemporalFindingView> findings = ruleStore.findingsForShadow(shadowRunId);
        return new GovernanceDtos.ShadowRunView(base.id(), base.campaignCode(), base.caseId(),
                base.caseNo(), base.snapshotId(), base.status(), base.scoreDiffThreshold(),
                base.reversalRateLimit(), base.cutoffAt(), base.temporalVerdict(), base.chainComplete(),
                base.blockingFindings(), base.warningFindings(), base.summary(), features, findings);
    }

    public List<Map<String, Object>> listCampaigns(String campaignCode) {
        String sql = """
                select sr.id, sr.campaign_code, c.case_no, sr.status, sr.temporal_verdict,
                       sr.chain_complete, sr.blocking_finding_count, sr.warning_finding_count,
                       sr.score_diff_threshold, sr.created_at, sr.completed_at, sr.summary
                  from shadow_run sr join claim_case c on c.id = sr.case_id
                """;
        if (campaignCode == null || campaignCode.isBlank()) {
            return db.jdbc().query(sql + " order by sr.id desc", (rs, i) -> mapListItem(rs));
        }
        return db.jdbc().query(sql + " where sr.campaign_code = ? order by sr.id",
                (rs, i) -> mapListItem(rs), campaignCode.trim());
    }

    private Map<String, Object> mapListItem(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", rs.getLong("id"));
        row.put("campaignCode", rs.getString("campaign_code"));
        row.put("caseNo", rs.getString("case_no"));
        row.put("status", rs.getString("status"));
        row.put("temporalVerdict", rs.getString("temporal_verdict"));
        row.put("chainComplete", rs.getBoolean("chain_complete"));
        row.put("blockingFindingCount", rs.getInt("blocking_finding_count"));
        row.put("warningFindingCount", rs.getInt("warning_finding_count"));
        row.put("scoreDiffThreshold", rs.getBigDecimal("score_diff_threshold"));
        row.put("createdAt", Times.format(Db.instant(rs, "created_at")));
        row.put("completedAt", Times.format(Db.instant(rs, "completed_at")));
        row.put("summary", db.readTree(rs.getString("summary")));
        return row;
    }
}

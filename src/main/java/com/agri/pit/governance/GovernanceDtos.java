package com.agri.pit.governance;

import java.util.List;

/**
 * Request/response records for the shadow scoring, release gate and targeted rollback APIs.
 */
public final class GovernanceDtos {

    // ----------------------------------------------------------------- shadow

    public record ShadowCampaignRequest(
            String campaignCode,
            String featureCode,
            int candidateVersionNo,
            List<String> caseNos,
            Double scoreDiffThreshold,
            Double reversalRateLimit,
            String releaseLabel,
            String reason,
            String requestedBy) {
    }

    public record TemporalFindingView(
            long id,
            String ruleCode,
            String severity,
            String subjectType,
            String subjectRef,
            String expectedOrder,
            String actualOrder,
            String message,
            boolean passed) {
    }

    public record ShadowFeatureView(
            String featureCode,
            Integer baselineVersionNo,
            Double baselineValue,
            String baselineStatus,
            int candidateVersionNo,
            Double candidateValue,
            String candidateStatus,
            Double absDelta,
            Double relativeDelta,
            boolean reversed,
            boolean overThreshold,
            boolean inputChainComplete,
            String classification,
            Object attribution) {
    }

    public record ShadowRunView(
            long id,
            String campaignCode,
            long caseId,
            String caseNo,
            long snapshotId,
            String status,
            double scoreDiffThreshold,
            double reversalRateLimit,
            String cutoffAt,
            String temporalVerdict,
            boolean chainComplete,
            int blockingFindings,
            int warningFindings,
            Object summary,
            List<ShadowFeatureView> features,
            List<TemporalFindingView> findings) {
    }

    // ------------------------------------------------------------------- gate

    public record GateSubmitRequest(
            String featureCode,
            int candidateVersionNo,
            String releaseLabel,
            Double scoreDiffThreshold,
            Double relativeDiffThreshold,
            Double reversalRateLimit,
            List<String> caseNos,
            String requestedBy,
            String reason) {
    }

    public record GateDecisionRequest(String decisionBy, String note) {
    }

    public record GateView(
            long id,
            String featureCode,
            int candidateVersionNo,
            long candidateVersionId,
            String candidateChecksum,
            String releaseLabel,
            String status,
            String catalogFingerprint,
            Object thresholdConfig,
            List<Long> shadowRunIds,
            boolean temporalPassed,
            boolean thresholdPassed,
            int blockingFindingCount,
            int reversalCount,
            int businessDifferenceCount,
            Double maxAbsDelta,
            Object checks,
            String decisionBy,
            String decidedAt,
            String decisionNote,
            String invalidateReason,
            String createdAt,
            String invalidatedAt,
            List<TemporalFindingView> findings,
            List<ShadowRunView> shadowRuns) {
    }

    // --------------------------------------------------------------- rollback

    public record RollbackRequest(
            String featureCode,
            int versionNo,
            String scopeType,
            List<String> caseNos,
            String reason,
            String requestedBy) {
    }

    public record RollbackRevokeRequest(String note) {
    }

    public record RollbackView(
            long id,
            String featureCode,
            int versionNo,
            long versionId,
            String scopeType,
            List<String> caseScope,
            String status,
            String reason,
            String requestedBy,
            int terminatedShardCount,
            int quarantinedShardCount,
            String createdAt,
            String revokedAt,
            String revokeNote) {
    }

    // ------------------------------------------------------------ backfill batch

    public record BatchShardRequest(String caseNo, String featureCode) {
    }

    public record BatchCreateRequest(
            String batchCode,
            List<BatchShardRequest> shards,
            String requestedBy,
            String note) {
    }

    public record ShardView(
            long id,
            int shardNo,
            long caseId,
            String caseNo,
            String featureCode,
            List<Long> dependsOnVersionIds,
            String status,
            Long featureRunId,
            String terminationReason,
            Object result,
            String createdAt,
            String updatedAt) {
    }

    public record BatchView(
            long id,
            String batchCode,
            String status,
            String requestedBy,
            String note,
            String createdAt,
            String startedAt,
            String finishedAt,
            List<ShardView> shards) {
    }
}

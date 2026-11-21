package com.agri.pit.runs;

import java.util.List;

public final class RunDtos {

    public record RunView(
            long id,
            Long caseId,
            String caseNo,
            String mode,
            String triggerType,
            String status,
            String verdict,
            String dataPolicy,
            String cutoffAt,
            String availabilityCeiling,
            String hazardDate,
            Long boundaryVersionId,
            Integer boundaryVersionNo,
            boolean boundaryVariant,
            Long parentRunId,
            Long revisionEventId,
            String requestedBy,
            String requestedAt,
            String startedAt,
            String finishedAt,
            String reason,
            String error,
            Long snapshotId,
            Long candidateId,
            String notes) {
    }

    public record InputView(
            long id,
            String sourceType,
            Long sourceId,
            Integer revisionNo,
            String observedAt,
            String ingestedAt,
            String eligibility,
            String reason,
            Double contributedValue,
            String featureCodes) {
    }

    public record FeatureView(
            String featureCode,
            int definitionVersion,
            long definitionVersionId,
            String formula,
            Double value,
            String unit,
            String status,
            int inputCount,
            String windowStart,
            String windowEnd) {
    }

    public record ViolationView(String code, String severity, String sourceType, Long sourceId,
                                String featureCode, String message) {
    }

    public record LeakageView(String verdict, String cutoffAt, String availabilityCeiling,
                              int inputCount, int eligibleCount, int excludedCount, int blockedCount,
                              String summary, List<ViolationView> violations) {
    }

    public record RunDetail(RunView run, List<FeatureView> features, List<InputView> inputs,
                            LeakageView leakage) {
    }

    public record QueueStats(long queued, long running, long succeeded, long blocked, long insufficient,
                             long failed) {
    }
}

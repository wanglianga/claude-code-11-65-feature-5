package com.agri.pit.cases;

import java.time.Instant;
import java.time.LocalDate;

public final class CaseDtos {

    public record CreateCaseRequest(
            String caseNo,
            String policyNo,
            String parcelCode,
            String cropCode,
            LocalDate hazardDate,
            Instant scoringCutoff,
            Long boundaryVersionId,
            String note) {
    }

    public record CaseSummary(
            long id,
            String caseNo,
            String policyNo,
            String parcelCode,
            String cropCode,
            String hazardType,
            LocalDate hazardDate,
            String scoringCutoff,
            long boundaryVersionId,
            int boundaryVersionNo,
            String status,
            String latestSnapshotAt) {
    }

    public record CaseDetail(CaseSummary summary,
                             java.util.List<LockedFeatureView> lockedFeatures,
                             java.util.List<SnapshotSummary> snapshots,
                             java.util.List<RunSummary> runs) {
    }

    public record LockedFeatureView(String featureCode, int versionNo, String formula,
                                    boolean required, String unit) {
    }

    public record SnapshotSummary(long id, String createdAt, String inputsHash, String scoringCutoff,
                                  java.util.Map<String, Object> values) {
    }

    public record RunSummary(long id, String mode, String status, String verdict, String cutoffAt,
                             String availabilityCeiling, String boundaryVersion, Boolean boundaryVariant,
                             String requestedAt, String finishedAt, Long snapshotId, Long parentRunId,
                             String reason, String error) {
    }
}

package com.agri.pit.leakage;

import com.agri.pit.model.InputVerdict;
import com.agri.pit.model.SourceType;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.List;

public final class InputLedger {

    public record Candidate(
            SourceType sourceType,
            String sourceTable,
            long sourceId,
            Integer revisionNo,
            String externalKey,
            Instant observedAt,
            Instant ingestedAt,
            InputVerdict verdict,
            String reason,
            Double contributedValue,
            ObjectNode detail) {

        public Candidate withVerdict(InputVerdict newVerdict, String newReason) {
            return new Candidate(sourceType, sourceTable, sourceId, revisionNo, externalKey, observedAt,
                    ingestedAt, newVerdict, newReason, contributedValue, detail);
        }

        public Candidate withContribution(Double value) {
            return new Candidate(sourceType, sourceTable, sourceId, revisionNo, externalKey, observedAt,
                    ingestedAt, verdict, reason, value, detail);
        }
    }

    public record Violation(String code, String severity, SourceType sourceType, Long sourceId,
                            String featureCode, String message) {
    }

    public record Result(List<Candidate> candidates, List<Violation> violations, boolean hardBlocked,
                         String blockReason) {

        public boolean hasExclusions() {
            return candidates.stream().anyMatch(c -> switch (c.verdict()) {
                case EXCLUDED_LATE_AVAILABILITY, EXCLUDED_NOT_YET_INGESTED,
                     EXCLUDED_NO_SAMPLES_IN_PARCEL, EXCLUDED_PIXEL_ALLOCATED_OTHER_PARCEL -> true;
                default -> false;
            });
        }
    }

    public static final class Builder {
        private final java.util.List<Candidate> candidates = new java.util.ArrayList<>();
        private final java.util.List<Violation> violations = new java.util.ArrayList<>();
        private boolean hardBlocked;
        private String blockReason;

        public Builder add(Candidate candidate) {
            candidates.add(candidate);
            return this;
        }

        public Builder violation(Violation violation) {
            violations.add(violation);
            return this;
        }

        public Builder block(String reason) {
            this.hardBlocked = true;
            this.blockReason = reason;
            return this;
        }

        public List<Candidate> candidates() {
            return candidates;
        }

        public Result build() {
            return new Result(new java.util.ArrayList<>(candidates), new java.util.ArrayList<>(violations),
                    hardBlocked, blockReason);
        }
    }
}

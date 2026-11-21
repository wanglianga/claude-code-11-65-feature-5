package com.agri.pit.leakage;

import com.agri.pit.common.Db;
import com.agri.pit.model.InputVerdict;
import com.agri.pit.model.LeakageVerdict;
import com.agri.pit.model.RunMode;
import com.agri.pit.model.SourceType;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Verifies that every input which influenced a run was available at or before the run's
 * availability ceiling, and records a persisted leakage report for the case audit trail.
 */
@Component
public class LeakageDetector {

    private static final Set<InputVerdict> EXCLUSION_VERDICTS = Set.of(
            InputVerdict.EXCLUDED_LATE_AVAILABILITY,
            InputVerdict.EXCLUDED_NOT_YET_INGESTED,
            InputVerdict.EXCLUDED_NO_SAMPLES_IN_PARCEL,
            InputVerdict.EXCLUDED_PIXEL_ALLOCATED_OTHER_PARCEL);

    private final Db db;

    public LeakageDetector(Db db) {
        this.db = db;
    }

    public record Outcome(LeakageVerdict verdict, int inputCount, int eligibleCount, int excludedCount,
                          int blockedCount, boolean hasMissingRequiredData, InputLedger.Result ledger) {
    }

    public Outcome evaluateAndPersist(long runId, RunMode mode, Instant cutoff, Instant ceiling,
                                      InputLedger.Result ledger, boolean hasMissingRequiredData) {
        int eligible = 0;
        int excluded = 0;
        int blocked = 0;
        for (InputLedger.Candidate candidate : ledger.candidates()) {
            switch (candidate.verdict()) {
                case ELIGIBLE, ELIGIBLE_OLDER_REVISION_USED -> eligible++;
                default -> {
                    if (EXCLUSION_VERDICTS.contains(candidate.verdict())) {
                        excluded++;
                        if (candidate.verdict() == InputVerdict.EXCLUDED_LATE_AVAILABILITY) {
                            blocked++;
                        }
                    }
                }
            }
        }
        // A second, independent verification: nothing the ledger marks eligible may have an
        // ingest time after the ceiling. This catches selector regressions.
        for (InputLedger.Candidate candidate : ledger.candidates()) {
            if ((candidate.verdict() == InputVerdict.ELIGIBLE
                    || candidate.verdict() == InputVerdict.ELIGIBLE_OLDER_REVISION_USED)
                    && candidate.ingestedAt() != null && candidate.ingestedAt().isAfter(ceiling)) {
                ledger.violations().add(new InputLedger.Violation("ELIGIBLE_INPUT_AFTER_CEILING", "BLOCKING",
                        candidate.sourceType(), candidate.sourceId(), null,
                        "eligible input " + candidate.sourceTable() + "#" + candidate.sourceId()
                                + " was ingested " + candidate.ingestedAt() + " after the ceiling " + ceiling));
                ledger = new InputLedger.Result(ledger.candidates(), ledger.violations(), true,
                        "eligible input ingested after the ceiling");
            }
        }

        LeakageVerdict verdict;
        boolean hardBlocked = ledger.hardBlocked() || hasMissingRequiredData;
        if (mode == RunMode.CONTROLLED_BACKFILL) {
            verdict = LeakageVerdict.CANDIDATE_ONLY;
        } else if (hardBlocked) {
            verdict = LeakageVerdict.BLOCKED;
        } else if (excluded > 0) {
            verdict = LeakageVerdict.PASS_WITH_EXCLUSIONS;
        } else {
            verdict = LeakageVerdict.PASS;
        }

        String summary = summarize(mode, cutoff, ceiling, eligible, excluded, hasMissingRequiredData,
                ledger.blockReason());        long reportId = db.insert("""
                        insert into leakage_report
                            (run_id, verdict, cutoff_at, availability_ceiling, input_count, eligible_count,
                             excluded_count, blocked_count, summary)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                runId, verdict.name(), Db.timestamp(cutoff), Db.timestamp(ceiling),
                ledger.candidates().size(), eligible, excluded, blocked, summary);
        for (InputLedger.Violation violation : ledger.violations()) {
            db.insert("""
                            insert into leakage_violation
                                (report_id, code, severity, source_type, source_id, feature_code, message)
                            values (?, ?, ?, ?, ?, ?, ?)
                            """,
                    reportId, violation.code(), violation.severity(),
                    violation.sourceType() == null ? null : violation.sourceType().name(),
                    violation.sourceId(), violation.featureCode(), violation.message());
        }
        return new Outcome(verdict, ledger.candidates().size(), eligible, excluded, blocked,
                hasMissingRequiredData, ledger);
    }

    private String summarize(RunMode mode, Instant cutoff, Instant ceiling, int eligible, int excluded,
                             boolean missing, String blockReason) {
        String base = switch (mode) {
            case ORIGINAL -> "Original scoring verified as-of " + cutoff;
            case REPLAY -> "Replay verified as-of " + cutoff;
            case CONTROLLED_BACKFILL -> "Controlled backfill at ceiling " + ceiling
                    + "; result is a review candidate only";
            case SHADOW -> "Shadow scoring verified as-of " + cutoff
                    + "; no snapshot was written and the claim was not modified";
        };
        String tail = " (" + eligible + " eligible input(s)";
        if (excluded > 0) {
            tail += ", " + excluded + " excluded";
        }
        if (missing) {
            tail += ", required data missing";
        }
        if (blockReason != null) {
            tail += ", " + blockReason;
        }
        return base + tail + ")";
    }

    public static List<SourceType> sources() {
        return List.of(SourceType.values());
    }
}

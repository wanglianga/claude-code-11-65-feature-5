package com.agri.pit.governance;

import com.agri.pit.common.Times;
import com.agri.pit.leakage.InputLedger;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Temporal ordering rule set used by shadow scoring and the release gate.
 * <p>
 * The canonical ordering every production score must satisfy is:
 * <pre>
 *     observed_at &le; ingested_at (available_at) &le; scoring_cutoff
 * </pre>
 * plus a third clock for definitions:
 * <pre>
 *     definition.effective_at &le; scoring_cutoff
 * </pre>
 * A failed {@code BLOCKING} rule means the score difference is a time-travel failure, not a
 * legitimate business change, and the release gate must refuse the definition version.
 */
public final class TemporalRuleSet {

    private TemporalRuleSet() {
    }

    public record Finding(String ruleCode, String severity, String subjectType, String subjectRef,
                         String expectedOrder, String actualOrder, String message, boolean passed,
                         ObjectNode detail) {
    }

    public record DefinitionClock(long versionId, int versionNo, String featureCode,
                                  Instant effectiveAt, long availabilityLagSeconds) {
    }

    public record Evaluation(List<Finding> findings, String verdict, int blockingCount, int warningCount,
                             boolean chainComplete) {
    }

    /**
     * Evaluate the ordering rules over one shadow comparison.
     *
     * @param cutoff      the frozen case scoring cutoff
     * @param ledger      inputs selected for the candidate shadow evaluation
     * @param definitions candidate definition versions exercised by the shadow run
     */
    public static Evaluation evaluate(Instant cutoff, InputLedger.Result ledger,
                                      List<DefinitionClock> definitions) {
        List<Finding> findings = new ArrayList<>();
        for (InputLedger.Candidate candidate : ledger.candidates()) {
            String subject = candidate.sourceTable() + "#" + candidate.sourceId();
            if (candidate.observedAt() != null && candidate.ingestedAt() != null
                    && candidate.ingestedAt().isBefore(candidate.observedAt())) {
                findings.add(finding("OBSERVED_BEFORE_INGESTED", "BLOCKING", "INPUT", subject,
                        "observed_at <= ingested_at",
                        Times.format(candidate.observedAt()) + " > " + Times.format(candidate.ingestedAt()),
                        "input became available before it was observed; the two clocks are inconsistent",
                        false));
            }
            boolean eligible = switch (candidate.verdict()) {
                case ELIGIBLE, ELIGIBLE_OLDER_REVISION_USED -> true;
                default -> false;
            };
            if (eligible) {
                if (candidate.ingestedAt() != null && candidate.ingestedAt().isAfter(cutoff)) {
                    findings.add(finding("INGESTED_BEFORE_CUTOFF", "BLOCKING", "INPUT", subject,
                            "ingested_at <= scoring_cutoff (" + Times.format(cutoff) + ")",
                            "ingested_at = " + Times.format(candidate.ingestedAt()),
                            "an eligible input was not available at the cutoff; reading it is time travel",
                            false));
                }
                if (candidate.observedAt() != null && candidate.observedAt().isAfter(cutoff)) {
                    findings.add(finding("OBSERVED_BEFORE_CUTOFF", "BLOCKING", "INPUT", subject,
                            "observed_at <= scoring_cutoff (" + Times.format(cutoff) + ")",
                            "observed_at = " + Times.format(candidate.observedAt()),
                            "an eligible input observes a phenomenon from after the cutoff",
                            false));
                }
            }
        }
        for (DefinitionClock definition : definitions) {
            String subject = definition.featureCode() + "#v" + definition.versionNo();
            boolean passed = !definition.effectiveAt().isAfter(cutoff);
            findings.add(finding("DEFINITION_EFFECTIVE_BEFORE_CUTOFF",
                    passed ? "INFO" : "BLOCKING", "DEFINITION", subject,
                    "effective_at <= scoring_cutoff (" + Times.format(cutoff) + ")",
                    "effective_at = " + Times.format(definition.effectiveAt()),
                    passed ? "definition was already in force when the case was scored"
                            : "definition takes effect after the case cutoff; it cannot govern this score",
                    passed));
        }
        int blocking = (int) findings.stream().filter(f -> !f.passed() && "BLOCKING".equals(f.severity())).count();
        int warnings = (int) findings.stream().filter(f -> !f.passed() && "WARNING".equals(f.severity())).count();
        boolean chainComplete = blocking == 0 && !ledger.hardBlocked();
        String verdict = blocking > 0 || ledger.hardBlocked() ? "BLOCKED"
                : warnings > 0 ? "PASS_WITH_WARNINGS" : "PASS";
        return new Evaluation(findings, verdict, blocking, warnings, chainComplete);
    }

    /**
     * Catalog-level chronology check reused by the release gate: a newer version must not claim
     * an effective time earlier than the version it supersedes.
     */
    public static Finding versionChronology(DefinitionClock candidate, Instant previousEffectiveAt) {
        if (previousEffectiveAt == null) {
            return finding("DEFINITION_VERSION_CHRONOLOGY", "INFO", "DEFINITION",
                    candidate.featureCode() + "#v" + candidate.versionNo(),
                    "no predecessor", "first version",
                    "first version of the definition; nothing to order against", true);
        }
        boolean passed = !candidate.effectiveAt().isBefore(previousEffectiveAt);
        return finding("DEFINITION_VERSION_CHRONOLOGY", passed ? "INFO" : "WARNING", "DEFINITION",
                candidate.featureCode() + "#v" + candidate.versionNo(),
                "effective_at >= predecessor effective_at (" + Times.format(previousEffectiveAt) + ")",
                "effective_at = " + Times.format(candidate.effectiveAt()),
                passed ? "version chronology is consistent"
                        : "candidate effective time predates the version it supersedes",
                passed);
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Finding finding(String code, String severity, String subjectType, String subjectRef,
                                   String expectedOrder, String actualOrder, String message, boolean passed) {
        ObjectNode detail = MAPPER.createObjectNode();
        detail.put("ruleCode", code);
        return new Finding(code, severity, subjectType, subjectRef, expectedOrder, actualOrder,
                message, passed, detail);
    }
}

package com.agri.pit.governance;

import com.agri.pit.leakage.InputLedger;
import com.agri.pit.model.InputVerdict;
import com.agri.pit.model.SourceType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TemporalRuleSetTest {

    private static final Instant CUTOFF = Instant.parse("2024-06-22T00:00:00Z");
    private final ObjectMapper mapper = new ObjectMapper();

    private InputLedger.Candidate candidate(InputVerdict verdict, Instant observed, Instant ingested) {
        ObjectNode detail = mapper.createObjectNode();
        return new InputLedger.Candidate(SourceType.WEATHER_OBSERVATION, "weather_observation",
                1L, 1, "W1", observed, ingested, verdict, "test", null, detail);
    }

    private TemporalRuleSet.DefinitionClock definition(Instant effectiveAt) {
        return new TemporalRuleSet.DefinitionClock(10L, 2, "RAIN_ANOMALY", effectiveAt, 0L);
    }

    @Test
    void passesWhenObservedThenIngestedThenCutoffAndDefinitionInForce() {
        InputLedger.Result ledger = new InputLedger.Result(List.of(
                candidate(InputVerdict.ELIGIBLE,
                        Instant.parse("2024-06-10T00:00:00Z"),
                        Instant.parse("2024-06-12T00:00:00Z"))),
                List.of(), false, null);
        TemporalRuleSet.Evaluation evaluation = TemporalRuleSet.evaluate(CUTOFF, ledger,
                List.of(definition(Instant.parse("2024-01-01T00:00:00Z"))));
        assertThat(evaluation.verdict()).isEqualTo("PASS");
        assertThat(evaluation.chainComplete()).isTrue();
        assertThat(evaluation.blockingCount()).isZero();
    }

    @Test
    void blocksEligibleInputIngestedAfterCutoffAsTimeTravel() {
        InputLedger.Result ledger = new InputLedger.Result(List.of(
                candidate(InputVerdict.ELIGIBLE,
                        Instant.parse("2024-06-18T00:00:00Z"),
                        Instant.parse("2024-06-25T00:00:00Z"))),
                List.of(), false, null);
        TemporalRuleSet.Evaluation evaluation = TemporalRuleSet.evaluate(CUTOFF, ledger, List.of());
        assertThat(evaluation.verdict()).isEqualTo("BLOCKED");
        assertThat(evaluation.chainComplete()).isFalse();
        assertThat(evaluation.findings()).extracting(TemporalRuleSet.Finding::ruleCode)
                .contains("INGESTED_BEFORE_CUTOFF");
    }

    @Test
    void blocksDefinitionThatTakesEffectAfterTheCutoff() {
        InputLedger.Result ledger = new InputLedger.Result(List.of(), List.of(), false, null);
        TemporalRuleSet.Evaluation evaluation = TemporalRuleSet.evaluate(CUTOFF, ledger,
                List.of(definition(Instant.parse("2024-07-01T00:00:00Z"))));
        assertThat(evaluation.verdict()).isEqualTo("BLOCKED");
        assertThat(evaluation.findings()).extracting(TemporalRuleSet.Finding::ruleCode)
                .contains("DEFINITION_EFFECTIVE_BEFORE_CUTOFF");
    }

    @Test
    void flagsIngestedBeforeObservedClockInversion() {
        InputLedger.Result ledger = new InputLedger.Result(List.of(
                candidate(InputVerdict.ELIGIBLE,
                        Instant.parse("2024-06-20T00:00:00Z"),
                        Instant.parse("2024-06-01T00:00:00Z"))),
                List.of(), false, null);
        TemporalRuleSet.Evaluation evaluation = TemporalRuleSet.evaluate(CUTOFF, ledger, List.of());
        assertThat(evaluation.findings()).extracting(TemporalRuleSet.Finding::ruleCode)
                .contains("OBSERVED_BEFORE_INGESTED");
        assertThat(evaluation.verdict()).isEqualTo("BLOCKED");
    }
}

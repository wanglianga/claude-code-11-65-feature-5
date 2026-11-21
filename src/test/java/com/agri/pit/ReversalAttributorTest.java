package com.agri.pit.governance;

import com.agri.pit.runs.FeatureEvaluator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReversalAttributorTest {

    private final ReversalAttributor attributor = new ReversalAttributor(new ObjectMapper());

    private FeatureEvaluator.FeatureResult result(String code, int versionNo, Double value,
                                                   String status, Map<String, Object> detail) {
        return new FeatureEvaluator.FeatureResult(code, versionNo, versionNo,
                "formula-v" + versionNo, "index", value, status, 3, null, null, detail);
    }

    private Map<String, Object> detail(double ndviMean) {
        return Map.of("variables", Map.of("ndvi_mean", ndviMean, "ndvi_range", 0.1));
    }

    @Test
    void largeDeltaOnCompleteChainIsBusinessDifferenceForRuleOwner() {
        var baseline = result("NDVI_DROUGHT", 1, 0.50d, "COMPUTED", detail(0.45));
        var candidate = result("NDVI_DROUGHT", 2, 0.85d, "COMPUTED", detail(0.10));
        ReversalAttributor.AttributionResult attribution = attributor.attribute(
                baseline, candidate, 0.15d, 0.20d, 0.05d, true);
        assertThat(attribution.classification()).isEqualTo("BUSINESS_DIFFERENCE");
        assertThat(attribution.overThreshold()).isTrue();
        assertThat(attribution.attribution().path("reviewRouting").asText())
                .isEqualTo("RULE_OWNER_REVIEW");
        assertThat(attribution.attribution().path("dimensions").path("productVersion")
                .path("formulaChanged").asBoolean()).isTrue();
    }

    @Test
    void sameDeltaWithBrokenChainIsTimeTravelFailure() {
        var baseline = result("NDVI_DROUGHT", 1, 0.50d, "COMPUTED", detail(0.45));
        var candidate = result("NDVI_DROUGHT", 2, 0.85d, "COMPUTED", detail(0.10));
        ReversalAttributor.AttributionResult attribution = attributor.attribute(
                baseline, candidate, 0.15d, 0.20d, 0.05d, false);
        assertThat(attribution.classification()).isEqualTo("TIME_TRAVEL_FAILURE");
        assertThat(attribution.attribution().path("reviewRouting").asText())
                .isEqualTo("BLOCK_TIME_TRAVEL");
    }

    @Test
    void smallDeltaStaysWithinThreshold() {
        var baseline = result("RAIN_ANOMALY", 1, 10.0d, "COMPUTED",
                Map.of("variables", Map.of("rainfall_weighted", 5.0)));
        var candidate = result("RAIN_ANOMALY", 2, 10.05d, "COMPUTED",
                Map.of("variables", Map.of("rainfall_weighted", 4.99)));
        ReversalAttributor.AttributionResult attribution = attributor.attribute(
                baseline, candidate, 0.15d, 0.20d, 0.05d, true);
        assertThat(attribution.classification()).isEqualTo("WITHIN_THRESHOLD");
        assertThat(attribution.overThreshold()).isFalse();
    }

    @Test
    void nonComputableCandidateIsMissingDataDifference() {
        var baseline = result("CROP_EXPOSURE", 1, 5.6d, "COMPUTED",
                Map.of("variables", Map.of("declared_area_ha", 5.6)));
        var candidate = result("CROP_EXPOSURE", 2, null, "INSUFFICIENT_DATA",
                Map.of("missingVariables", java.util.List.of("irrigation_factor")));
        ReversalAttributor.AttributionResult attribution = attributor.attribute(
                baseline, candidate, 0.15d, 0.20d, 0.05d, true);
        assertThat(attribution.classification()).isEqualTo("MISSING_DATA_DIFFERENCE");
        assertThat(attribution.attribution().path("dimensions").path("missingPolicy")
                .path("candidateComputed").asBoolean()).isFalse();
    }

    @Test
    void oppositeSignsCountAsReversal() {
        var baseline = result("NDVI_DROUGHT", 1, -0.2d, "COMPUTED", detail(0.45));
        var candidate = result("NDVI_DROUGHT", 2, 0.3d, "COMPUTED", detail(0.10));
        ReversalAttributor.AttributionResult attribution = attributor.attribute(
                baseline, candidate, 0.15d, 0.20d, 0.05d, true);
        assertThat(attribution.reversed()).isTrue();
    }
}

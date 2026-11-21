package com.agri.pit.governance;

import com.agri.pit.runs.FeatureEvaluator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Decomposes a baseline-vs-candidate score reversal into the three dimensions an auditor needs:
 * <ol>
 *   <li><b>inputs</b> — which point-in-time aggregate variables moved and which warehouse feeds them;</li>
 *   <li><b>missing policy</b> — required flags, missing variables, NULL vs INSUFFICIENT_DATA handling;</li>
 *   <li><b>product version</b> — the definition versions and formulas on each side.</li>
 * </ol>
 * A reversal on a complete input chain is a {@code BUSINESS_DIFFERENCE} routed to the rule owner.
 * A reversal paired with a blocking temporal finding is a {@code TIME_TRAVEL_FAILURE}; the gate
 * blocks it instead of asking a human to accept a score the clocks do not support.
 */
@Component
public class ReversalAttributor {

    private final ObjectMapper mapper;

    public ReversalAttributor(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public record AttributionResult(String classification, boolean reversed, boolean overThreshold,
                                    Double absDelta, Double relativeDelta, boolean inputChainComplete,
                                    ObjectNode attribution) {
    }

    public AttributionResult attribute(FeatureEvaluator.FeatureResult baseline,
                                       FeatureEvaluator.FeatureResult candidate,
                                       double absThreshold, double relativeThreshold,
                                       double relativeFloor, boolean chainComplete) {
        boolean baselineComputed = "COMPUTED".equals(baseline.status());
        boolean candidateComputed = "COMPUTED".equals(candidate.status());
        Double absDelta = null;
        Double relativeDelta = null;
        boolean reversed = false;
        boolean overThreshold;

        String classification;
        if (!baselineComputed || !candidateComputed) {
            // The score difference is driven by the missing-data policy, not by numeric drift.
            classification = "MISSING_DATA_DIFFERENCE";
            overThreshold = true;
        } else {
            double delta = candidate.value() - baseline.value();
            absDelta = round(Math.abs(delta));
            double denominator = Math.max(Math.abs(baseline.value()), relativeFloor);
            relativeDelta = round(absDelta / denominator);
            reversed = baseline.value() * candidate.value() < 0d;
            overThreshold = absDelta > absThreshold || relativeDelta > relativeThreshold;
            if (!overThreshold) {
                classification = "WITHIN_THRESHOLD";
            } else if (!chainComplete) {
                classification = "TIME_TRAVEL_FAILURE";
            } else {
                classification = "BUSINESS_DIFFERENCE";
            }
        }
        if ("NOT_COMPUTABLE".equals(candidate.status()) || formulaError(candidate) || formulaError(baseline)) {
            classification = "NOT_COMPUTABLE";
        }
        ObjectNode attribution = buildAttribution(baseline, candidate, classification,
                baselineComputed, candidateComputed, absDelta, relativeDelta);
        return new AttributionResult(classification, reversed, overThreshold, absDelta, relativeDelta,
                chainComplete, attribution);
    }

    private boolean formulaError(FeatureEvaluator.FeatureResult result) {
        Object error = result.detail().get("error");
        return error != null;
    }

    private ObjectNode buildAttribution(FeatureEvaluator.FeatureResult baseline,
                                        FeatureEvaluator.FeatureResult candidate, String classification,
                                        boolean baselineComputed, boolean candidateComputed,
                                        Double absDelta, Double relativeDelta) {
        ObjectNode root = mapper.createObjectNode();
        root.put("classification", classification);
        root.put("baselineStatus", baseline.status());
        root.put("candidateStatus", candidate.status());
        if (absDelta != null) {
            root.put("absDelta", absDelta);
        }
        if (relativeDelta != null) {
            root.put("relativeDelta", relativeDelta);
        }

        ObjectNode dimensions = root.putObject("dimensions");
        dimensions.set("inputs", inputDimension(baseline, candidate));
        dimensions.set("missingPolicy", missingPolicyDimension(baseline, candidate,
                baselineComputed, candidateComputed));
        dimensions.set("productVersion", productVersionDimension(baseline, candidate));

        root.put("primaryDriver", primaryDriver(baseline, candidate, baselineComputed, candidateComputed));
        root.put("reviewRouting", switch (classification) {
            case "BUSINESS_DIFFERENCE" -> "RULE_OWNER_REVIEW";
            case "TIME_TRAVEL_FAILURE" -> "BLOCK_TIME_TRAVEL";
            case "MISSING_DATA_DIFFERENCE" -> "DATA_STEWARD_REVIEW";
            case "NOT_COMPUTABLE" -> "FORMULA_AUTHOR_FIX";
            default -> "NONE";
        });
        return root;
    }

    @SuppressWarnings("unchecked")
    private ArrayNode inputDimension(FeatureEvaluator.FeatureResult baseline,
                                     FeatureEvaluator.FeatureResult candidate) {
        Map<String, Double> baselineVars = variablesOf(baseline);
        Map<String, Double> candidateVars = variablesOf(candidate);
        Map<String, Double> deltas = new LinkedHashMap<>();
        for (String key : candidateVars.keySet()) {
            deltas.put(key, candidateVars.get(key) - baselineVars.getOrDefault(key, Double.NaN));
        }
        for (String key : baselineVars.keySet()) {
            deltas.putIfAbsent(key, baselineVars.get(key).isNaN() ? Double.NaN : -baselineVars.get(key));
        }
        ArrayNode inputs = mapper.createArrayNode();
        deltas.entrySet().stream()
                .sorted((a, b) -> Double.compare(Math.abs(nanToZero(b.getValue())), Math.abs(nanToZero(a.getValue()))))
                .forEach(entry -> {
                    ObjectNode node = inputs.addObject();
                    node.put("variable", entry.getKey());
                    node.put("likelySource", likelySource(entry.getKey()));
                    node.put("baseline", nanOrNull(baselineVars.get(entry.getKey())));
                    node.put("candidate", nanOrNull(candidateVars.get(entry.getKey())));
                    if (!Double.isNaN(entry.getValue())) {
                        node.put("delta", round(entry.getValue()));
                    }
                });
        return inputs;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Double> variablesOf(FeatureEvaluator.FeatureResult result) {
        Object variables = result.detail().get("variables");
        if (variables instanceof Map<?, ?> map) {
            Map<String, Double> typed = new LinkedHashMap<>();
            map.forEach((k, v) -> typed.put(String.valueOf(k), ((Number) v).doubleValue()));
            return typed;
        }
        return Map.of();
    }

    private ObjectNode missingPolicyDimension(FeatureEvaluator.FeatureResult baseline,
                                              FeatureEvaluator.FeatureResult candidate,
                                              boolean baselineComputed, boolean candidateComputed) {
        ObjectNode node = mapper.createObjectNode();
        node.put("strategy", "required features block scoring as INSUFFICIENT_DATA; optional ones resolve to NULL");
        node.put("baselineComputed", baselineComputed);
        node.put("candidateComputed", candidateComputed);
        node.set("baselineMissingVariables", missingVariables(baseline));
        node.set("candidateMissingVariables", missingVariables(candidate));
        node.put("baselineInputCount", baseline.inputCount());
        node.put("candidateInputCount", candidate.inputCount());
        return node;
    }

    private ArrayNode missingVariables(FeatureEvaluator.FeatureResult result) {
        ArrayNode array = mapper.createArrayNode();
        Object missing = result.detail().get("missingVariables");
        if (missing instanceof Iterable<?> iterable) {
            iterable.forEach(v -> array.add(String.valueOf(v)));
        }
        return array;
    }

    private ObjectNode productVersionDimension(FeatureEvaluator.FeatureResult baseline,
                                               FeatureEvaluator.FeatureResult candidate) {
        ObjectNode node = mapper.createObjectNode();
        node.put("featureCode", candidate.featureCode());
        node.put("baselineVersionNo", baseline.definitionVersionNo());
        node.put("candidateVersionNo", candidate.definitionVersionNo());
        node.put("baselineFormula", baseline.formula());
        node.put("candidateFormula", candidate.formula());
        node.put("formulaChanged", !baseline.formula().equals(candidate.formula()));
        return node;
    }

    private String primaryDriver(FeatureEvaluator.FeatureResult baseline,
                                 FeatureEvaluator.FeatureResult candidate,
                                 boolean baselineComputed, boolean candidateComputed) {
        if (!baselineComputed || !candidateComputed) {
            ArrayNode missing = missingVariables(candidate);
            if (!missing.isEmpty()) {
                return "missing:" + missing.get(0).asText();
            }
            return "missing-policy:" + baseline.status() + "->" + candidate.status();
        }
        Map<String, Double> baselineVars = variablesOf(baseline);
        Map<String, Double> candidateVars = variablesOf(candidate);
        String driver = null;
        double biggest = 0d;
        for (Map.Entry<String, Double> entry : candidateVars.entrySet()) {
            double before = nanToZero(baselineVars.get(entry.getKey()));
            double move = Math.abs(entry.getValue() - before);
            if (move > biggest) {
                biggest = move;
                driver = "variable:" + entry.getKey();
            }
        }
        if (driver == null && !baseline.formula().equals(candidate.formula())) {
            return "formula:v" + baseline.definitionVersionNo() + "->v" + candidate.definitionVersionNo();
        }
        return driver == null ? "none" : driver;
    }

    private static String likelySource(String variable) {
        if (variable.startsWith("ndvi") || variable.startsWith("scene")) {
            return "SATELLITE_NDVI";
        }
        if (variable.startsWith("rainfall")) {
            return "WEATHER_OBSERVATION";
        }
        if (variable.startsWith("declared_area") || variable.startsWith("days_since_planting")
                || variable.startsWith("planting_year")) {
            return "PLANTING_DECLARATION";
        }
        if (variable.startsWith("boundary")) {
            return "PARCEL_BOUNDARY";
        }
        return "CASE_CONTEXT";
    }

    private static double nanToZero(Double value) {
        return value == null || Double.isNaN(value) ? 0d : value;
    }

    private static Double nanOrNull(Double value) {
        return value == null || Double.isNaN(value) ? null : round(value);
    }

    private static double round(double value) {
        return Math.round(value * 1_000_000d) / 1_000_000d;
    }
}

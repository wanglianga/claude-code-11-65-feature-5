package com.agri.pit.runs;

import com.agri.pit.catalog.FormulaExpression;
import com.agri.pit.leakage.AsOfSelector;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns the point-in-time input selection into feature values by evaluating the approved
 * formula against aggregate variables. Variables are only populated from inputs the
 * as-of selector marked eligible, so a formula can never read a post-cutoff value.
 */
public final class FeatureEvaluator {

    public record Context(Map<String, Double> variables, int satelliteInputs, int weatherInputs,
                          int declarationInputs, Instant windowStart, Instant windowEnd) {
    }

    public record FeatureResult(String featureCode, long definitionVersionId, int definitionVersionNo,
                                String formula, String unit, Double value, String status,
                                int inputCount, Instant windowStart, Instant windowEnd,
                                Map<String, Object> detail) {
    }

    public static Context buildContext(AsOfSelector.ScenePick scenes, AsOfSelector.WeatherPick weather,
                                       AsOfSelector.DeclarationPick declaration,
                                       LocalDate hazardDate, Instant windowStart, Instant windowEnd,
                                       double boundaryAreaHa) {
        Map<String, Double> vars = new LinkedHashMap<>();
        List<AsOfSelector.SceneCellMatch> cells = scenes.cells();
        if (!cells.isEmpty()) {
            double sum = 0;
            double min = Double.POSITIVE_INFINITY;
            double max = Double.NEGATIVE_INFINITY;
            for (AsOfSelector.SceneCellMatch cell : cells) {
                sum += cell.ndvi();
                min = Math.min(min, cell.ndvi());
                max = Math.max(max, cell.ndvi());
            }
            double mean = sum / cells.size();
            vars.put("ndvi_mean", mean);
            vars.put("ndvi_min", min);
            vars.put("ndvi_max", max);
            vars.put("ndvi_samples", (double) cells.size());
            vars.put("ndvi_deficit", 1d - mean);
            vars.put("ndvi_range", max - min);
            vars.put("scene_count", (double) cells.stream().map(AsOfSelector.SceneCellMatch::sceneId)
                    .distinct().count());
        }
        List<AsOfSelector.WeatherValue> weatherValues = weather.values();
        if (!weatherValues.isEmpty()) {
            double totalWeight = weatherValues.stream().mapToDouble(AsOfSelector.WeatherValue::weight).sum();
            double weightedRain = weatherValues.stream()
                    .mapToDouble(v -> v.rainfallMm() * v.weight()).sum();
            double rainfallSum = weatherValues.stream().mapToDouble(AsOfSelector.WeatherValue::rainfallMm).sum();
            vars.put("rainfall_sum", rainfallSum);
            vars.put("rainfall_weighted", totalWeight == 0 ? rainfallSum : weightedRain / totalWeight);
            vars.put("rainfall_mean", rainfallSum / weatherValues.size());
            vars.put("rainfall_periods", (double) weatherValues.stream()
                    .map(AsOfSelector.WeatherValue::externalKey).distinct().count());
        }
        if (!declaration.missing() && declaration.value() != null) {
            var decl = declaration.value();
            vars.put("declared_area_ha", decl.declaredAreaHa());
            long days = ChronoUnit.DAYS.between(decl.plantingDate(), hazardDate);
            vars.put("days_since_planting", (double) days);
            vars.put("planting_year", (double) decl.plantingDate().getYear());
        }
        vars.put("boundary_area_ha", boundaryAreaHa);
        long windowDays = Math.max(1, Duration.between(windowStart, windowEnd).toDays());
        vars.put("window_days", (double) windowDays);
        vars.put("hazard_day_of_year", (double) hazardDate.getDayOfYear());
        return new Context(vars, cells.size(), weatherValues.size(),
                declaration.missing() ? 0 : 1, windowStart, windowEnd);
    }

    public static FeatureResult evaluate(String featureCode, long versionId, int versionNo, String formula,
                                         String unit, boolean required, Context context) {
        FormulaExpression expression;
        try {
            expression = FormulaExpression.compile(formula);
        } catch (IllegalArgumentException e) {
            return new FeatureResult(featureCode, versionId, versionNo, formula, unit, null,
                    "NULL", 0, context.windowStart(), context.windowEnd(),
                    Map.of("error", "formula compile failed: " + e.getMessage()));
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("variables", new LinkedHashMap<>(context.variables()));
        List<String> missing = expression.variables().stream()
                .filter(v -> !context.variables().containsKey(v)).toList();
        if (!missing.isEmpty()) {
            detail.put("missingVariables", missing);
            int inputs = context.satelliteInputs() + context.weatherInputs() + context.declarationInputs();
            return new FeatureResult(featureCode, versionId, versionNo, formula, unit, null,
                    required ? "INSUFFICIENT_DATA" : "NULL", inputs,
                    context.windowStart(), context.windowEnd(), detail);
        }
        double value;
        try {
            value = expression.evaluate(context.variables());
        } catch (RuntimeException e) {
            detail.put("error", e.getMessage());
            int inputs = context.satelliteInputs() + context.weatherInputs() + context.declarationInputs();
            return new FeatureResult(featureCode, versionId, versionNo, formula, unit, null,
                    required ? "INSUFFICIENT_DATA" : "NULL", inputs,
                    context.windowStart(), context.windowEnd(), detail);
        }
        int inputs = context.satelliteInputs() + context.weatherInputs() + context.declarationInputs();
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            detail.put("error", "formula produced a non-finite value");
            return new FeatureResult(featureCode, versionId, versionNo, formula, unit, null,
                    required ? "INSUFFICIENT_DATA" : "NULL", inputs,
                    context.windowStart(), context.windowEnd(), detail);
        }
        detail.put("inputBreakdown", Map.of(
                "satelliteCells", context.satelliteInputs(),
                "weatherObservations", context.weatherInputs(),
                "declarations", context.declarationInputs()));
        return new FeatureResult(featureCode, versionId, versionNo, formula, unit,
                BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP).doubleValue(),
                "COMPUTED", inputs, context.windowStart(), context.windowEnd(), detail);
    }
}

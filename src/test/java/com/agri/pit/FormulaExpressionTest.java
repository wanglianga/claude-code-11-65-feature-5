package com.agri.pit;

import com.agri.pit.catalog.FormulaExpression;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FormulaExpressionTest {

    @Test
    void evaluatesArithmeticWithPrecedence() {
        FormulaExpression expr = FormulaExpression.compile("1 + 2 * 3 - 4 / 2");
        assertThat(expr.evaluate(Map.of())).isEqualTo(5.0);
    }

    @Test
    void resolvesAggregateVariables() {
        FormulaExpression expr = FormulaExpression.compile("clamp(1 - ndvi_mean + 0.25 * ndvi_range, 0, 1)");
        double value = expr.evaluate(Map.of("ndvi_mean", 0.4, "ndvi_range", 0.2));
        assertThat(value).isEqualTo(0.65);
        assertThat(expr.variables()).containsExactlyInAnyOrder("ndvi_mean", "ndvi_range");
    }

    @Test
    void rejectsUnknownFunction() {
        assertThatThrownBy(() -> FormulaExpression.compile("system('rm -rf /')"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported function");
    }

    @Test
    void divisionByZeroYieldsNaN() {
        FormulaExpression expr = FormulaExpression.compile("rainfall_sum / rainfall_periods");
        assertThat(expr.evaluate(Map.of("rainfall_sum", 10.0, "rainfall_periods", 0.0))).isNaN();
    }
}

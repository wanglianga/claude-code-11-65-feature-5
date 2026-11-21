package com.agri.pit.governance;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Tunable thresholds for the shadow-first release workflow. Defaults are deliberately
 * conservative so a definition version that flips a material share of frozen cases cannot
 * reach production without an explicit review.
 */
@Component
@ConfigurationProperties(prefix = "app.governance")
public class GovernanceProperties {

    /** Default absolute per-feature score difference tolerated by a release gate. */
    private double defaultScoreDiffThreshold = 0.15d;

    /** Default relative difference (|delta| / max(|baseline|, floor)) tolerated by a gate. */
    private double defaultRelativeDiffThreshold = 0.20d;

    /** Share of shadow cases allowed to reverse beyond the absolute threshold. */
    private double defaultReversalRateLimit = 0.10d;

    /** Relative-delta denominator floor so near-zero baselines do not explode the ratio. */
    private double relativeDeltaFloor = 0.05d;

    public double getDefaultScoreDiffThreshold() {
        return defaultScoreDiffThreshold;
    }

    public void setDefaultScoreDiffThreshold(double defaultScoreDiffThreshold) {
        this.defaultScoreDiffThreshold = defaultScoreDiffThreshold;
    }

    public double getDefaultRelativeDiffThreshold() {
        return defaultRelativeDiffThreshold;
    }

    public void setDefaultRelativeDiffThreshold(double defaultRelativeDiffThreshold) {
        this.defaultRelativeDiffThreshold = defaultRelativeDiffThreshold;
    }

    public double getDefaultReversalRateLimit() {
        return defaultReversalRateLimit;
    }

    public void setDefaultReversalRateLimit(double defaultReversalRateLimit) {
        this.defaultReversalRateLimit = defaultReversalRateLimit;
    }

    public double getRelativeDeltaFloor() {
        return relativeDeltaFloor;
    }

    public void setRelativeDeltaFloor(double relativeDeltaFloor) {
        this.relativeDeltaFloor = relativeDeltaFloor;
    }
}

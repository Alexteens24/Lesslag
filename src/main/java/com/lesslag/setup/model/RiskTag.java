package com.lesslag.setup.model;

/** Risk level for a patch proposal. */
public enum RiskTag {
    LOW("Low risk — safe to apply"),
    MEDIUM("Medium risk — may affect gameplay"),
    HIGH("High risk — significant tradeoffs, review carefully");

    private final String description;

    RiskTag(String description) {
        this.description = description;
    }

    public String getDescription() { return description; }
}

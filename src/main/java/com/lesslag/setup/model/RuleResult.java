package com.lesslag.setup.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Output of a single rule evaluation.
 * Each rule explains why it fired, impact, tradeoffs, and manual steps.
 */
public class RuleResult {

    private final String ruleId;
    private final String ruleGroup;   // e.g. "consistency", "safety", "conflict", "fork-specific"
    private final Severity severity;
    private final double confidence;  // 0.0 – 1.0

    private final String why;
    private final String impact;
    private final String tradeoff;
    private final String manualSteps;       // null if auto-applicable
    private final String recommendationText;

    private final List<String> impactedKeys;

    private RuleResult(Builder b) {
        this.ruleId = b.ruleId;
        this.ruleGroup = b.ruleGroup;
        this.severity = b.severity;
        this.confidence = b.confidence;
        this.why = b.why;
        this.impact = b.impact;
        this.tradeoff = b.tradeoff;
        this.manualSteps = b.manualSteps;
        this.recommendationText = b.recommendationText;
        this.impactedKeys = b.impactedKeys;
    }

    public String getRuleId() { return ruleId; }
    public String getRuleGroup() { return ruleGroup; }
    public Severity getSeverity() { return severity; }
    public double getConfidence() { return confidence; }
    public String getWhy() { return why; }
    public String getImpact() { return impact; }
    public String getTradeoff() { return tradeoff; }
    public String getManualSteps() { return manualSteps; }
    public String getRecommendationText() { return recommendationText; }
    public List<String> getImpactedKeys() { return impactedKeys; }

    public static Builder builder(String ruleId) {
        return new Builder(ruleId);
    }

    public static class Builder {
        private final String ruleId;
        private String ruleGroup = "general";
        private Severity severity = Severity.INFO;
        private double confidence = 1.0;
        private String why = "";
        private String impact = "";
        private String tradeoff = "";
        private String manualSteps;
        private String recommendationText = "";
        private final List<String> impactedKeys = new ArrayList<>();

        Builder(String ruleId) { this.ruleId = ruleId; }

        public Builder group(String group) { this.ruleGroup = group; return this; }
        public Builder severity(Severity s) { this.severity = s; return this; }
        public Builder confidence(double c) { this.confidence = c; return this; }
        public Builder why(String w) { this.why = w; return this; }
        public Builder impact(String i) { this.impact = i; return this; }
        public Builder tradeoff(String t) { this.tradeoff = t; return this; }
        public Builder manualSteps(String m) { this.manualSteps = m; return this; }
        public Builder recommendation(String r) { this.recommendationText = r; return this; }
        public Builder impactedKey(String key) { this.impactedKeys.add(key); return this; }

        public RuleResult build() { return new RuleResult(this); }
    }
}

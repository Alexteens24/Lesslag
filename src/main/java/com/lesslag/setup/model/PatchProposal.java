package com.lesslag.setup.model;

/**
 * A single proposed configuration change.
 * Links back to the rule that generated it.
 */
public class PatchProposal {

    private final String targetFile;    // e.g. "config.yml", "spigot.yml", "config/paper-global.yml"
    private final String configKey;     // dot-path e.g. "modules.redstone.max-activations-per-chunk"
    private final String beforeValue;   // current value (string representation)
    private final String afterValue;    // recommended value
    private final RiskTag riskTag;
    private final ApplyScope applyScope;
    private final String ruleId;        // originating rule
    private final String rationale;     // brief explanation

    public PatchProposal(String targetFile, String configKey,
                          String beforeValue, String afterValue,
                          RiskTag riskTag, ApplyScope applyScope,
                          String ruleId, String rationale) {
        this.targetFile = targetFile;
        this.configKey = configKey;
        this.beforeValue = beforeValue;
        this.afterValue = afterValue;
        this.riskTag = riskTag;
        this.applyScope = applyScope;
        this.ruleId = ruleId;
        this.rationale = rationale;
    }

    public String getTargetFile() { return targetFile; }
    public String getConfigKey() { return configKey; }
    public String getBeforeValue() { return beforeValue; }
    public String getAfterValue() { return afterValue; }
    public RiskTag getRiskTag() { return riskTag; }
    public ApplyScope getApplyScope() { return applyScope; }
    public String getRuleId() { return ruleId; }
    public String getRationale() { return rationale; }

    /** Returns true if this patch can be auto-applied to LessLag config. */
    public boolean isAutoApplicable() {
        return applyScope == ApplyScope.LESSLAG_APPLY;
    }
}

package com.lesslag.setup.rules;

import com.lesslag.setup.detect.ConfigAdapter;
import com.lesslag.setup.detect.PlatformDetector;
import com.lesslag.setup.detect.PluginScanner;
import com.lesslag.setup.model.*;

import java.util.*;

/**
 * Rule engine that runs all registered rules through the priority pipeline:
 * critical safety → consistency → performance tuning → optional QoL.
 */
public class RuleEngine {

    /**
     * Semantic version of the rule set.
     * Increment the major when rules change in a breaking / semantically incompatible way.
     * The plugin sends this to the API so drift between the Java and TypeScript
     * implementations can be detected at runtime.
     */
    public static final String RULES_VERSION = "1.0.0";

    private final List<Rule> rules = new ArrayList<>();

    public RuleEngine() {
        // Register rules in priority order
        rules.add(new SafetyRules());
        rules.add(new ConsistencyRules());
        rules.add(new ConflictRules());
        rules.add(new ForkSpecificRules());
        rules.add(new PerformanceTuningRules());
    }

    /**
     * Run all rules against the detected environment.
     * Safe to call from async thread.
     *
     * @return results and proposals sorted by priority pipeline
     */
    public EvaluationResult evaluate(PlatformDetector platform,
                                      ConfigAdapter configs,
                                      PluginScanner plugins,
                                      HardwareAssessment hardware,
                                      GameProfile profile,
                                      HardwareTier tier,
                                      AggressivenessLevel level) {
        List<RuleResult> allResults = new ArrayList<>();
        List<PatchProposal> allProposals = new ArrayList<>();

        // Sort rules by priority (lower = first)
        List<Rule> sorted = new ArrayList<>(rules);
        sorted.sort(Comparator.comparingInt(Rule::getPriority));

        for (Rule rule : sorted) {
            try {
                rule.evaluate(platform, configs, plugins, hardware,
                              profile, tier, level, allResults, allProposals);
            } catch (Exception e) {
                allResults.add(RuleResult.builder("error-" + rule.getId())
                    .group("internal").severity(Severity.WARNING).confidence(1.0)
                    .why("Rule '" + rule.getId() + "' threw an exception: " + e.getMessage())
                    .impact("This rule's recommendations are unavailable")
                    .recommendation("Report this issue to the LessLag developer")
                    .build());
            }
        }

        // De-duplicate proposals by (targetFile, configKey)
        Map<String, PatchProposal> deduped = new LinkedHashMap<>();
        for (PatchProposal p : allProposals) {
            String key = p.getTargetFile() + ":" + p.getConfigKey();
            deduped.putIfAbsent(key, p); // first (highest priority rule) wins
        }

        return new EvaluationResult(allResults, new ArrayList<>(deduped.values()));
    }

    /** Immutable evaluation output. */
    public static class EvaluationResult {
        private final List<RuleResult> results;
        private final List<PatchProposal> proposals;

        public EvaluationResult(List<RuleResult> results, List<PatchProposal> proposals) {
            this.results = Collections.unmodifiableList(results);
            this.proposals = Collections.unmodifiableList(proposals);
        }

        public List<RuleResult> getResults() { return results; }
        public List<PatchProposal> getProposals() { return proposals; }

        public long countBySeverity(Severity severity) {
            return results.stream().filter(r -> r.getSeverity() == severity).count();
        }

        public long countAutoApplicable() {
            return proposals.stream().filter(PatchProposal::isAutoApplicable).count();
        }

        public long countRecommendOnly() {
            return proposals.stream().filter(p -> !p.isAutoApplicable()).count();
        }
    }
}

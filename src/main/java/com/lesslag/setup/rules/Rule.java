package com.lesslag.setup.rules;

import com.lesslag.setup.detect.ConfigAdapter;
import com.lesslag.setup.detect.PlatformDetector;
import com.lesslag.setup.detect.PluginScanner;
import com.lesslag.setup.model.*;

import java.util.List;

/**
 * A single evaluation rule that inspects environment state
 * and produces zero or more RuleResults + PatchProposals.
 */
public interface Rule {

    /** Unique identifier for this rule. */
    String getId();

    /** Group this rule belongs to (consistency, safety, conflict, fork-specific). */
    String getGroup();

    /** Priority within group. Lower = runs first. */
    default int getPriority() { return 100; }

    /**
     * Evaluate the rule against the current environment.
     * Must not block the main thread or do I/O.
     *
     * @param platform  detected server platform
     * @param configs   loaded config files
     * @param plugins   scanned plugin list
     * @param hardware  hardware assessment
     * @param profile   selected game profile
     * @param tier      selected hardware tier
     * @param level     aggressiveness level
     * @param results   add RuleResults here
     * @param proposals add PatchProposals here
     */
    void evaluate(PlatformDetector platform,
                  ConfigAdapter configs,
                  PluginScanner plugins,
                  HardwareAssessment hardware,
                  GameProfile profile,
                  HardwareTier tier,
                  AggressivenessLevel level,
                  List<RuleResult> results,
                  List<PatchProposal> proposals);
}

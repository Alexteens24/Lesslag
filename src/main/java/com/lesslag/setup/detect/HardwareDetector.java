package com.lesslag.setup.detect;

import com.lesslag.setup.model.HardwareAssessment;
import com.lesslag.setup.model.HardwareTier;

import java.lang.management.*;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Auto-detects hardware capabilities and scores into a HardwareTier.
 * Safe to run from any thread (uses only MXBean APIs).
 */
public class HardwareDetector {

    private static final Logger LOG = Logger.getLogger("LessLag-Setup");

    public HardwareAssessment assess() {
        HardwareAssessment hw = new HardwareAssessment();

        // ── CPU ────────────────────────────
        int procs = Runtime.getRuntime().availableProcessors();
        hw.setAvailableProcessors(procs);
        hw.setCpuModel(detectCpuModel());

        OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        hw.setSystemLoadAverage(os.getSystemLoadAverage());

        // ── Memory / Heap ────────────────────────────
        Runtime rt = Runtime.getRuntime();
        hw.setMaxHeapBytes(rt.maxMemory());
        hw.setAllocatedHeapBytes(rt.totalMemory());
        hw.setUsedHeapBytes(rt.totalMemory() - rt.freeMemory());

        // ── GC ────────────────────────────
        List<GarbageCollectorMXBean> gcs = ManagementFactory.getGarbageCollectorMXBeans();
        StringBuilder gcNames = new StringBuilder();
        long totalGcTime = 0;
        for (GarbageCollectorMXBean gc : gcs) {
            if (gcNames.length() > 0) gcNames.append(", ");
            gcNames.append(gc.getName());
            if (gc.getCollectionTime() > 0) totalGcTime += gc.getCollectionTime();
        }
        hw.setGcName(gcNames.toString());

        long uptimeMs = ManagementFactory.getRuntimeMXBean().getUptime();
        if (uptimeMs > 0) {
            hw.setGcOverheadPercent((totalGcTime * 100.0) / uptimeMs);
        }

        // ── JVM flags ────────────────────────────
        RuntimeMXBean runtimeBean = ManagementFactory.getRuntimeMXBean();
        hw.setJvmFlags(new ArrayList<>(runtimeBean.getInputArguments()));

        // ── Tier scoring ────────────────────────────
        scoreTier(hw);

        return hw;
    }

    private void scoreTier(HardwareAssessment hw) {
        int score = 0;
        double confidence = 0.0;
        List<String> reasons = hw.getReasons();
        int signals = 0;

        // CPU scoring
        int procs = hw.getAvailableProcessors();
        if (procs >= 8) {
            score += 3; reasons.add("8+ CPU threads detected"); signals++;
        } else if (procs >= 4) {
            score += 2; reasons.add("4-7 CPU threads detected"); signals++;
        } else {
            score += 1; reasons.add("1-3 CPU threads detected (limited)"); signals++;
        }

        // Memory scoring
        long maxMb = hw.getMaxHeapBytes() / (1024 * 1024);
        if (maxMb >= 12288) {      // 12 GB+
            score += 3; reasons.add("12GB+ heap allocated"); signals++;
        } else if (maxMb >= 6144) { // 6 GB+
            score += 2; reasons.add("6-12GB heap allocated"); signals++;
        } else if (maxMb >= 3072) { // 3 GB+
            score += 1; reasons.add("3-6GB heap allocated"); signals++;
        } else {
            reasons.add("<3GB heap (constrained)"); signals++;
        }

        // GC type bonus
        String gcName = hw.getGcName().toLowerCase();
        if (gcName.contains("zgc") || gcName.contains("shenandoah")) {
            score += 1; reasons.add("Modern low-pause GC detected (" + hw.getGcName() + ")");
            signals++;
        } else if (gcName.contains("g1")) {
            reasons.add("G1GC detected (standard)"); signals++;
        } else {
            reasons.add("Legacy GC detected (" + hw.getGcName() + ")");
        }

        // GC overhead penalty
        if (hw.getGcOverheadPercent() > 10) {
            score -= 1; reasons.add("High GC overhead: " + String.format("%.1f%%", hw.getGcOverheadPercent()));
            signals++;
        }

        // Aikars flags detection (indicates knowledgeable admin)
        boolean hasAikars = false;
        for (String flag : hw.getJvmFlags()) {
            if (flag.contains("G1NewSizePercent") || flag.contains("G1MixedGCLiveThresholdPercent")) {
                hasAikars = true;
                break;
            }
        }
        if (hasAikars) {
            reasons.add("Aikar's flags detected");
            signals++;
        }

        // Final tier assignment
        if (score >= 6) {
            hw.setDetectedTier(HardwareTier.HIGH);
        } else if (score >= 3) {
            hw.setDetectedTier(HardwareTier.MID);
        } else {
            hw.setDetectedTier(HardwareTier.LOW);
        }

        // Confidence: more signals = higher confidence
        confidence = Math.min(1.0, signals * 0.15 + 0.2);
        // High-thread + high-memory = strong signal
        if (procs >= 4 && maxMb >= 6144) confidence = Math.max(confidence, 0.8);
        if (procs >= 8 && maxMb >= 12288) confidence = Math.max(confidence, 0.95);
        // Very low resources = also confident
        if (procs <= 2 && maxMb < 3072) confidence = Math.max(confidence, 0.85);

        hw.setConfidenceScore(confidence);

        LOG.info("Hardware tier: " + hw.getDetectedTier() + " (confidence: "
            + String.format("%.0f%%", confidence * 100) + ", score: " + score + ")");
    }

    private String detectCpuModel() {
        // Try reading from /proc/cpuinfo on Linux
        try {
            java.io.File cpuinfo = new java.io.File("/proc/cpuinfo");
            if (cpuinfo.exists()) {
                try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(cpuinfo))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        if (line.startsWith("model name")) {
                            return line.substring(line.indexOf(':') + 1).trim();
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        // Fallback to arch
        return System.getProperty("os.arch", "unknown");
    }
}

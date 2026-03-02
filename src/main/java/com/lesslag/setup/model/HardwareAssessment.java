package com.lesslag.setup.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Hardware assessment with raw metrics, detected tier, and confidence.
 */
public class HardwareAssessment {

    // ── Raw metrics ────────────────────────────
    private int availableProcessors;
    private String cpuModel;
    private long maxHeapBytes;
    private long allocatedHeapBytes;
    private long usedHeapBytes;
    private String gcName;
    private List<String> jvmFlags = new ArrayList<>();
    private double systemLoadAverage;

    // ── Runtime performance ────────────────────────────
    private double averageMspt;
    private double gcOverheadPercent;

    // ── Scoring ────────────────────────────
    private HardwareTier detectedTier;
    private double confidenceScore;  // 0.0 – 1.0
    private final List<String> reasons = new ArrayList<>();

    // ── Getters / Setters ────────────────────────────

    public int getAvailableProcessors() { return availableProcessors; }
    public void setAvailableProcessors(int count) { this.availableProcessors = count; }

    public String getCpuModel() { return cpuModel; }
    public void setCpuModel(String model) { this.cpuModel = model; }

    public long getMaxHeapBytes() { return maxHeapBytes; }
    public void setMaxHeapBytes(long bytes) { this.maxHeapBytes = bytes; }

    public long getAllocatedHeapBytes() { return allocatedHeapBytes; }
    public void setAllocatedHeapBytes(long bytes) { this.allocatedHeapBytes = bytes; }

    public long getUsedHeapBytes() { return usedHeapBytes; }
    public void setUsedHeapBytes(long bytes) { this.usedHeapBytes = bytes; }

    public String getGcName() { return gcName; }
    public void setGcName(String name) { this.gcName = name; }

    public List<String> getJvmFlags() { return jvmFlags; }
    public void setJvmFlags(List<String> flags) { this.jvmFlags = flags; }

    public double getSystemLoadAverage() { return systemLoadAverage; }
    public void setSystemLoadAverage(double load) { this.systemLoadAverage = load; }

    public double getAverageMspt() { return averageMspt; }
    public void setAverageMspt(double mspt) { this.averageMspt = mspt; }

    public double getGcOverheadPercent() { return gcOverheadPercent; }
    public void setGcOverheadPercent(double percent) { this.gcOverheadPercent = percent; }

    public HardwareTier getDetectedTier() { return detectedTier; }
    public void setDetectedTier(HardwareTier tier) { this.detectedTier = tier; }

    public double getConfidenceScore() { return confidenceScore; }
    public void setConfidenceScore(double score) { this.confidenceScore = score; }

    public List<String> getReasons() { return reasons; }

    /** Human-readable heap string. */
    public String getMaxHeapFormatted() {
        return formatBytes(maxHeapBytes);
    }

    public String getUsedHeapFormatted() {
        return formatBytes(usedHeapBytes);
    }

    /**
     * Returns true if confidence is below threshold and
     * the wizard should ask the user to confirm the tier.
     */
    public boolean needsUserConfirmation() {
        return confidenceScore < 0.6;
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }
}

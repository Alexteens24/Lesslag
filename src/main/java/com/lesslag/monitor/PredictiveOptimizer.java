package com.lesslag.monitor;

import com.lesslag.LessLag;
import com.lesslag.action.ActionExecutor;
import com.lesslag.util.NotificationHelper;
import org.bukkit.Bukkit;

import java.util.LinkedList;
import java.util.List;

/**
 * Predictive Optimization Engine — Detects MSPT rising trends and
 * triggers preventive actions BEFORE TPS actually drops below 20.
 *
 * Enhancements over v1:
 * - Exponentially weighted linear regression (recent samples matter more)
 * - Rate-of-change spike detector (instant trigger if MSPT doubles in <3s)
 * - Trend data exposed for /lg predictive command
 */
public class PredictiveOptimizer {

    private final LessLag plugin;
    private final ActionExecutor actionExecutor;

    // MSPT sample buffer (each entry = avg MSPT over 1 second)
    private final LinkedList<Double> msptSamples = new LinkedList<>();

    // Config (cached)
    private int windowSize;
    private double slopeThreshold;
    private double msptBaseline;
    private int cooldownSeconds;
    private boolean notifyEnabled;

    // State
    private volatile boolean predictiveTriggered = false;
    private volatile long lastTriggerTime = 0;
    private volatile double lastSlope = 0;
    private volatile double lastAvgMSPT = 0;
    private volatile int triggerCount = 0;

    public PredictiveOptimizer(LessLag plugin, ActionExecutor actionExecutor) {
        this.plugin = plugin;
        this.actionExecutor = actionExecutor;
        loadConfig();
    }

    private void loadConfig() {
        windowSize = plugin.getConfig().getInt("automation.predictive-optimization.window-seconds", 10); // Hidden
        slopeThreshold = plugin.getConfig().getDouble("automation.predictive-optimization.slope-threshold", 3.0);
        msptBaseline = plugin.getConfig().getDouble("automation.predictive-optimization.mspt-baseline", 30.0); // Hidden
        cooldownSeconds = plugin.getConfig().getInt("automation.predictive-optimization.cooldown", 60);
        notifyEnabled = plugin.getConfig().getBoolean("automation.predictive-optimization.notify", true); // Hidden
    }

    /**
     * Called by TPSMonitor every second with the latest average MSPT.
     */
    public void feed(double currentMSPT) {
        if (!plugin.getConfig().getBoolean("automation.predictive-optimization.enabled", true))
            return;

        synchronized (msptSamples) {
            msptSamples.addLast(currentMSPT);
            while (msptSamples.size() > windowSize) {
                msptSamples.removeFirst();
            }

            // Need at least half the window filled to analyze
            if (msptSamples.size() < Math.max(3, windowSize / 2)) {
                return;
            }

            // Check for sudden spike first (rate-of-change detector)
            if (checkSuddenSpike(currentMSPT)) {
                return; // Already triggered
            }

            analyzeWeighted();
        }
    }

    /**
     * Sudden spike detection: if MSPT more than doubles in 3 seconds, trigger
     * immediately.
     * Catches explosive lag that linear regression would be slow to detect.
     */
    private boolean checkSuddenSpike(double currentMSPT) {
        if (msptSamples.size() < 4)
            return false;

        // Compare current to the sample from 3 seconds ago
        int lookback = Math.min(3, msptSamples.size() - 1);
        double pastMSPT = msptSamples.get(msptSamples.size() - 1 - lookback);

        if (pastMSPT > 0 && currentMSPT >= pastMSPT * 2.0 && currentMSPT >= msptBaseline) {
            long now = System.currentTimeMillis();
            if (now - lastTriggerTime < cooldownSeconds * 1000L)
                return false;

            lastTriggerTime = now;
            predictiveTriggered = true;
            triggerCount++;
            lastSlope = (currentMSPT - pastMSPT) / lookback;
            lastAvgMSPT = currentMSPT;

            plugin.getLogger().warning("[Predictive] MSPT spike detected! "
                    + String.format("%.1f", pastMSPT) + "ms → " + String.format("%.1f", currentMSPT)
                    + "ms in " + lookback + "s — Executing preventive actions");

            executeActions("[Spike]");
            return true;
        }
        return false;
    }

    /**
     * Analyze MSPT trend using exponentially weighted linear regression.
     * Recent samples get higher weight, making the detector more responsive
     * to current trends.
     */
    private void analyzeWeighted() {
        double slope = calculateWeightedSlope();
        lastSlope = slope;

        // Calculate weighted average (more weight on recent)
        double weightedSum = 0;
        double totalWeight = 0;
        int i = 0;
        for (double v : msptSamples) {
            double w = 1.0 + (double) i / msptSamples.size(); // 1.0 → 2.0
            weightedSum += v * w;
            totalWeight += w;
            i++;
        }
        double avg = weightedSum / totalWeight;
        lastAvgMSPT = avg;

        if (slope >= slopeThreshold && avg >= msptBaseline) {
            long now = System.currentTimeMillis();
            if (now - lastTriggerTime < cooldownSeconds * 1000L)
                return;

            lastTriggerTime = now;
            predictiveTriggered = true;
            triggerCount++;

            plugin.getLogger().warning("[Predictive] MSPT trend detected! Slope: "
                    + String.format("%.2f", slope) + " ms/sec, Avg MSPT: "
                    + String.format("%.1f", avg) + "ms — Executing preventive actions");

            executeActions("[Trend]");
        } else {
            predictiveTriggered = false;
        }
    }

    /**
     * Exponentially weighted linear regression.
     * Weight = e^(alpha * i) where alpha controls recency bias.
     * This gives ~3x more weight to the most recent sample vs the oldest.
     */
    private double calculateWeightedSlope() {
        int n = msptSamples.size();
        if (n < 2)
            return 0;

        double alpha = Math.log(3.0) / (n - 1); // Makes last sample ~3x first

        double sumWX = 0, sumWY = 0, sumW = 0;
        double sumWXY = 0, sumWXX = 0;
        int i = 0;
        for (double y : msptSamples) {
            double w = Math.exp(alpha * i);
            sumW += w;
            sumWX += w * i;
            sumWY += w * y;
            sumWXY += w * i * y;
            sumWXX += w * i * i;
            i++;
        }

        double denom = sumW * sumWXX - sumWX * sumWX;
        if (denom == 0)
            return 0;

        return (sumW * sumWXY - sumWX * sumWY) / denom;
    }

    private void executeActions(String triggerType) {
        // Assuming default action is "notify-admin" as per new config "action:
        // notify-admin".
        // But original code supported a list of actions.
        // I'll try to read `action` (single) and `actions` (list) for backward
        // compatibility or future proofing.
        List<String> actions = plugin.getConfig().getStringList("automation.predictive-optimization.actions");
        String singleAction = plugin.getConfig().getString("automation.predictive-optimization.action");

        if (singleAction != null && actions.isEmpty()) {
            actions.add(singleAction);
        }

        if (!actions.isEmpty()) {
            Bukkit.getScheduler().runTask(plugin, () -> actionExecutor.executeActions(actions));
        }

        if (notifyEnabled) {
            String msg = "&6⚡ Predictive " + triggerType + ": &eMSPT rising (&f"
                    + String.format("%.1f", lastAvgMSPT) + "ms&e, slope &f+"
                    + String.format("%.1f", lastSlope) + "&e/s) — Running preemptive cleanup";
            NotificationHelper.notifyAdminsAsync(msg);
        }
    }

    /**
     * Reset state (used on reload).
     */
    public void reset() {
        synchronized (msptSamples) {
            msptSamples.clear();
        }
        predictiveTriggered = false;
        lastSlope = 0;
        lastAvgMSPT = 0;
    }

    /**
     * Get a copy of the current MSPT sample window (for /lg predictive command).
     */
    public double[] getWindowSamples() {
        synchronized (msptSamples) {
            double[] copy = new double[msptSamples.size()];
            int i = 0;
            for (double v : msptSamples)
                copy[i++] = v;
            return copy;
        }
    }

    // ── Getters ──────────────────────────────────────

    public boolean isPredictiveTriggered() {
        return predictiveTriggered;
    }

    public double getLastSlope() {
        return lastSlope;
    }

    public double getLastAvgMSPT() {
        return lastAvgMSPT;
    }

    public int getTriggerCount() {
        return triggerCount;
    }

    public long getLastTriggerTime() {
        return lastTriggerTime;
    }
}

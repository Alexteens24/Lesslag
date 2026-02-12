package com.lesslag.action;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ThresholdConfigTest {

    @Test
    public void testGetSeverity() {
        // Create 3 thresholds
        ThresholdConfig t1 = createMockThreshold(20.0);
        ThresholdConfig t2 = createMockThreshold(15.0);
        ThresholdConfig t3 = createMockThreshold(10.0);

        List<ThresholdConfig> list = Arrays.asList(t1, t2, t3);

        assertEquals(0, t1.getSeverity(list), "Highest TPS (index 0) should have lowest severity (0)");
        assertEquals(1, t2.getSeverity(list), "Middle TPS (index 1) should have middle severity (1)");
        assertEquals(2, t3.getSeverity(list), "Lowest TPS (index 2) should have highest severity (2)");
    }

    @Test
    public void testGetColor() {
        ThresholdConfig t1 = createMockThreshold(20.0);
        ThresholdConfig t2 = createMockThreshold(15.0);
        ThresholdConfig t3 = createMockThreshold(10.0);

        List<ThresholdConfig> list = Arrays.asList(t1, t2, t3);

        // Severity 0, Max 2 -> Ratio 0.0 -> "Low" -> "&e"
        assertEquals("&e", t1.getColor(list));

        // Severity 1, Max 2 -> Ratio 0.5 -> "High" -> "&c"
        assertEquals("&c", t2.getColor(list));

        // Severity 2, Max 2 -> Ratio 1.0 -> "Most Severe" -> "&4&l"
        assertEquals("&4&l", t3.getColor(list));
    }

    private ThresholdConfig createMockThreshold(double tps) {
        return new ThresholdConfig("test", tps, true, 0, "msg", false, null, Collections.emptyList(), Collections.emptyList(), false, false, false, "sound", 1f, 1f);
    }
}

package com.lesslag.monitor;

import com.lesslag.LessLag;
import com.lesslag.monitor.LagSourceAnalyzer.LagSource;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

public class LagSourceAnalyzerTest {

    @Test
    public void testFormatCompactReport() {
        LessLag plugin = mock(LessLag.class);
        LagSourceAnalyzer analyzer = new LagSourceAnalyzer(plugin);

        LagSource s1 = new LagSource(LagSource.Type.ENTITY_OVERLOAD, "Source 1", 100);
        LagSource s2 = new LagSource(LagSource.Type.CHUNK_OVERLOAD, "Source 2", 200);
        LagSource s3 = new LagSource(LagSource.Type.PLUGIN_TASKS, "Source 3", 300);
        LagSource s4 = new LagSource(LagSource.Type.ENTITY_TYPE, "Source 4", 400);

        // Case 1: Empty
        List<String> reportEmpty = analyzer.formatCompactReport(Collections.emptyList());
        assertTrue(reportEmpty.isEmpty());

        // Case 2: < 3 items
        List<String> reportSmall = analyzer.formatCompactReport(Arrays.asList(s1, s2));
        assertEquals(2, reportSmall.size());
        assertTrue(reportSmall.get(0).contains("Source 1"));
        assertTrue(reportSmall.get(1).contains("Source 2"));

        // Case 3: > 3 items
        List<String> reportLarge = analyzer.formatCompactReport(Arrays.asList(s1, s2, s3, s4));
        assertEquals(4, reportLarge.size());
        assertTrue(reportLarge.get(0).contains("Source 1"));
        assertTrue(reportLarge.get(1).contains("Source 2"));
        assertTrue(reportLarge.get(2).contains("Source 3"));
        assertTrue(reportLarge.get(3).contains("... and 1 more issues"));
    }

    @Test
    public void testFormatReport() {
        LessLag plugin = mock(LessLag.class);
        LagSourceAnalyzer analyzer = new LagSourceAnalyzer(plugin);

        LagSource s1 = new LagSource(LagSource.Type.ENTITY_OVERLOAD, "Entity Overload", 100);
        LagSource s2 = new LagSource(LagSource.Type.CHUNK_OVERLOAD, "Chunk Overload", 200);
        LagSource s3 = new LagSource(LagSource.Type.PLUGIN_TASKS, "Plugin Tasks", 300);

        List<String> report = analyzer.formatReport(Arrays.asList(s1, s2, s3));

        // Verify sections
        assertTrue(report.stream().anyMatch(line -> line.contains("TOP ENTITIES")));
        assertTrue(report.stream().anyMatch(line -> line.contains("LOADED CHUNKS")));
        assertTrue(report.stream().anyMatch(line -> line.contains("PLUGIN TASKS")));

        // Verify content
        assertTrue(report.stream().anyMatch(line -> line.contains("Entity Overload")));
        assertTrue(report.stream().anyMatch(line -> line.contains("Chunk Overload")));
        assertTrue(report.stream().anyMatch(line -> line.contains("Plugin Tasks")));
    }
}

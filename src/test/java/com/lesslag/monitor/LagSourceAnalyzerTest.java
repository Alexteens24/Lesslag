package com.lesslag.monitor;

import com.lesslag.LessLag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LagSourceAnalyzerTest {

    @Mock
    private LessLag plugin;
    @Mock
    private org.bukkit.configuration.file.FileConfiguration config;

    private LagSourceAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(plugin.getConfig()).thenReturn(config);
        // Mock default config values
        when(config.getInt(anyString(), anyInt())).thenAnswer(inv -> inv.getArgument(1));
        when(config.getDouble(anyString(), anyDouble())).thenAnswer(inv -> inv.getArgument(1));

        analyzer = new LagSourceAnalyzer(plugin);
    }

    @Test
    void testFormatReport_Empty() {
        List<String> report = analyzer.formatReport(Collections.emptyList());
        assertFalse(report.isEmpty());
        assertTrue(report.get(0).contains("No significant lag sources"));
    }

    @Test
    void testFormatReport_Null() {
        List<String> report = analyzer.formatReport(null);
        assertFalse(report.isEmpty());
        assertTrue(report.get(0).contains("No significant lag sources"));
    }

    @Test
    void testFormatFullReport_Null() {
        List<String> report = analyzer.formatFullReport(null);
        assertTrue(report.isEmpty());
    }

    @Test
    void testFormatReport_WithData() {
        List<LagSourceAnalyzer.LagSource> sources = new ArrayList<>();
        sources.add(new LagSourceAnalyzer.LagSource(LagSourceAnalyzer.LagSource.Type.ENTITY_OVERLOAD, "Too many entities", 1000));
        sources.add(new LagSourceAnalyzer.LagSource(LagSourceAnalyzer.LagSource.Type.PLUGIN_TASKS, "Bad Plugin", 50));

        List<String> report = analyzer.formatReport(sources);
        assertFalse(report.isEmpty());
        boolean hasEntityHeader = report.stream().anyMatch(s -> s.contains("TOP ENTITIES"));
        boolean hasTaskHeader = report.stream().anyMatch(s -> s.contains("PLUGIN TASKS"));
        assertTrue(hasEntityHeader);
        assertTrue(hasTaskHeader);
    }

    @Test
    void testFormatFullReport_WithData() {
        Map<String, Integer> entityCounts = new HashMap<>();
        entityCounts.put("ZOMBIE", 100);

        LagSourceAnalyzer.WorldSnapshot ws = new LagSourceAnalyzer.WorldSnapshot(
            "world", 1000, 500, entityCounts, new HashMap<>()
        );

        LagSourceAnalyzer.TaskSnapshot ts = new LagSourceAnalyzer.TaskSnapshot("TestPlugin", 30);

        LagSourceAnalyzer.FullAnalysisResult result = new LagSourceAnalyzer.FullAnalysisResult(
            Collections.emptyList(),
            new LagSourceAnalyzer.WorldSnapshot[]{ws},
            new LagSourceAnalyzer.TaskSnapshot[]{ts},
            new HashMap<>(),
            0,
            System.currentTimeMillis()
        );

        List<String> report = analyzer.formatFullReport(result);
        assertFalse(report.isEmpty());
        assertTrue(report.stream().anyMatch(s -> s.contains("world")));
        assertTrue(report.stream().anyMatch(s -> s.contains("TestPlugin")));
    }
}

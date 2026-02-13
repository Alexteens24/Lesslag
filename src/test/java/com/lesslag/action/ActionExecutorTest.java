package com.lesslag.action;

import org.junit.jupiter.api.Test;

import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

class ActionExecutorTest {

    @Test
    void testActionConsistency() {
        // This accesses static fields, triggering static initializer
        assertEquals(new HashSet<>(ActionExecutor.ACTIONS_SORTED), ActionExecutor.AVAILABLE_ACTIONS);
    }
}

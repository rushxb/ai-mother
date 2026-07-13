package com.rush.rushaicodemother.service.devserver;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DevServerOutputHubTest {

    @Test
    void shouldBoundRecentLinesAndReturnNewestSubset() {
        DevServerOutputHub hub = new DevServerOutputHub(3, 20);

        hub.sink(1L).accept("line-1");
        hub.sink(1L).accept("line-2");
        hub.sink(1L).accept("line-3");
        hub.sink(1L).accept("line-4");

        assertEquals(List.of("line-2", "line-3", "line-4"), hub.recentLines(1L, 10));
        assertEquals(List.of("line-3", "line-4"), hub.recentLines(1L, 2));
    }

    @Test
    void collectorMustReceiveSameBoundedLineStoredInHistory() {
        DevServerOutputHub hub = new DevServerOutputHub(3, 16);
        DevServerErrorCollector collector = new DevServerErrorCollector();
        hub.registerCollector(1L, collector);

        hub.sink(1L).accept("SyntaxError: " + "x".repeat(100));

        String stored = hub.recentLines(1L, 1).getFirst();
        assertEquals(16, stored.length());
        assertEquals(List.of(stored), collector.getRawLines());
    }

    @Test
    void unregisterMustRemoveOnlyTheOwnedCollector() {
        DevServerOutputHub hub = new DevServerOutputHub(3, 100);
        DevServerErrorCollector firstCollector = new DevServerErrorCollector();
        DevServerErrorCollector secondCollector = new DevServerErrorCollector();
        hub.registerCollector(1L, firstCollector);
        hub.registerCollector(1L, secondCollector);

        hub.sink(1L).accept("first");
        hub.unregisterCollector(1L, firstCollector);
        hub.sink(1L).accept("second");

        assertEquals(List.of("first"), firstCollector.getRawLines());
        assertEquals(List.of("first", "second"), secondCollector.getRawLines());
    }

    @Test
    void prepareMustClearHistoryWithoutRemovingRegisteredCollector() {
        DevServerOutputHub hub = new DevServerOutputHub(3, 100);
        DevServerErrorCollector collector = new DevServerErrorCollector();
        hub.registerCollector(1L, collector);
        hub.sink(1L).accept("before");

        hub.prepare(1L);
        hub.sink(1L).accept("[vite] Internal server error: after");

        assertEquals(List.of("[vite] Internal server error: after"), hub.recentLines(1L, 10));
        assertTrue(collector.hasCriticalError());
        assertEquals(2, collector.getLineCount());
    }

    @Test
    void invalidAndBlankInputMustNotAllocateHistory() {
        DevServerOutputHub hub = new DevServerOutputHub(3, 20);

        hub.sink(null).accept("line");
        hub.sink(1L).accept(null);
        hub.sink(1L).accept("   ");

        assertTrue(hub.recentLines(1L, 10).isEmpty());
        assertTrue(hub.recentLines(null, 10).isEmpty());
        assertTrue(hub.recentLines(1L, 0).isEmpty());
    }
}

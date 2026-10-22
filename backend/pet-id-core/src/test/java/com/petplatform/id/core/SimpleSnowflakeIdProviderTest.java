package com.petplatform.id.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.hutool.core.lang.Snowflake;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class SimpleSnowflakeIdProviderTest {
    private static final SnowflakeProviderSettings SETTINGS = new SnowflakeProviderSettings(37);

    @Test
    void generatesPositiveIncreasingIdsWithConfiguredNode() {
        var provider = new SimpleSnowflakeIdProvider(SETTINGS);
        var decoder = new Snowflake(new Date(SnowflakeProviderSettings.EPOCH_MILLIS),
                SETTINGS.workerId(), SETTINGS.dataCenterId(), false, 0, 0);
        long before = System.currentTimeMillis();
        long previous = 0;
        for (int i = 0; i < 1_000; i++) {
            long id = provider.nextId();
            assertTrue(id > 0);
            assertTrue(id > previous);
            assertEquals(37, provider.nodeId());
            assertEquals(SETTINGS.workerId(), decoder.getWorkerId(id));
            assertEquals(SETTINGS.dataCenterId(), decoder.getDataCenterId(id));
            long generated = decoder.getGenerateDateTime(id);
            assertTrue(generated >= before - 1_000);
            assertTrue(generated <= System.currentTimeMillis() + 1_000);
            previous = id;
        }
    }

    @Test
    void concurrentCallsRemainUnique() throws Exception {
        var provider = new SimpleSnowflakeIdProvider(SETTINGS);
        int threads = 8;
        int perThread = 2_000;
        var ids = ConcurrentHashMap.<Long>newKeySet();
        try (var pool = Executors.newFixedThreadPool(threads)) {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                tasks.add(() -> {
                    for (int i = 0; i < perThread; i++) {
                        assertTrue(ids.add(provider.nextId()));
                    }
                    return null;
                });
            }
            for (var future : pool.invokeAll(tasks)) {
                future.get();
            }
        }
        assertEquals(threads * perThread, ids.size());
    }
}

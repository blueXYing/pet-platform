package com.petplatform.id.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Blocking actions are control-layer fixtures, not actual Hutool clock-freeze experiments. */
@Timeout(10)
class SingleFlightLaneTest {
    @Test void successPublishesModelAndResultTogetherAndRetainsSingleWorker() {
        try (var lane = new SingleFlightLane<>(0)) {
            long thread = lane.workerThreadId();
            for (int i = 1; i <= 20; i++) {
                int expected = i;
                int result = lane.invoke(context -> {
                    assertEquals(expected - 1, context.state());
                    return new SingleFlightLane.Proposal<>(expected, expected);
                });
                assertEquals(expected, result);
                assertEquals(expected, lane.state());
                assertEquals(thread, lane.workerThreadId());
            }
            assertFalse(lane.isClosed());
        }
    }

    @Test void timeoutReturnsWhileActionStillBlockedAndLateProposalCannotPublish() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var returned = new CountDownLatch(1);
        var actionCalls = new AtomicInteger();
        try (var lane = new SingleFlightLane<>(7); var callers = Executors.newFixedThreadPool(3)) {
            long originalThread = lane.workerThreadId();
            long started = System.nanoTime();
            var active = callers.submit(() -> lane.invoke(context -> {
                actionCalls.incrementAndGet(); entered.countDown();
                awaitUninterruptibly(release);
                returned.countDown();
                return new SingleFlightLane.Proposal<>(99, 99);
            }));
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            var other = callers.submit(() -> lane.invoke(context -> {
                actionCalls.incrementAndGet();
                return new SingleFlightLane.Proposal<>(100, 100);
            }));
            ExecutionException failure = assertThrows(ExecutionException.class, () -> active.get(2, TimeUnit.SECONDS));
            long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertInstanceOf(IllegalStateException.class, failure.getCause());
            assertTrue(millis >= 900 && millis < 1600, "1s budget with scheduling tolerance, observed=" + millis);
            assertThrows(ExecutionException.class, () -> other.get(1, TimeUnit.SECONDS));
            assertEquals(1, returned.getCount(), "returning failure must not imply stopping the action");
            assertTrue(lane.workerAlive());
            assertTrue(lane.isClosed());
            assertEquals(7, lane.state());
            for (int i = 0; i < 5; i++) assertThrows(IllegalStateException.class,
                    () -> lane.invoke(context -> new SingleFlightLane.Proposal<>(100, 100)));
            assertEquals(1, actionCalls.get(), "there must be no pending SDK-action queue");
            assertEquals(originalThread, lane.workerThreadId());
            release.countDown();
            assertTrue(returned.await(1, TimeUnit.SECONDS));
            awaitWorkerExit(lane);
            assertEquals(7, lane.state(), "late candidate cannot mutate published model");
            assertTrue(lane.isClosed());
        } finally { release.countDown(); }
    }

    @Test void closeDoesNotWaitForActionMonitorAndDoesNotPublishItsLaterResult() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        Object pausedMonitor = new Object();
        try (var lane = new SingleFlightLane<>(3); var callers = Executors.newSingleThreadExecutor()) {
            var pending = callers.submit(() -> lane.invoke(context -> {
                synchronized (pausedMonitor) {
                    entered.countDown(); awaitUninterruptibly(release);
                    return new SingleFlightLane.Proposal<>(8, 8);
                }
            }));
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            long started = System.nanoTime();
            lane.close("HOST_CLOSE");
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 200);
            assertThrows(ExecutionException.class, () -> pending.get(1, TimeUnit.SECONDS));
            assertTrue(lane.workerAlive(), "the blocked action has not been interrupted or magically stopped");
            assertEquals(3, lane.state());
            release.countDown();
            awaitWorkerExit(lane);
            assertEquals(3, lane.state());
        } finally { release.countDown(); }
    }

    @Test void admittedFailureIsTerminalAndNoReplacementThreadIsCreated() throws Exception {
        try (var lane = new SingleFlightLane<>(1)) {
            long originalThread = lane.workerThreadId();
            assertThrows(IllegalStateException.class, () -> lane.invoke(context -> { throw new AssertionError("test action failure"); }));
            assertTrue(lane.isClosed());
            assertEquals(1, lane.state());
            assertThrows(IllegalStateException.class, () -> lane.invoke(context -> new SingleFlightLane.Proposal<>(2, 2)));
            awaitWorkerExit(lane);
            assertEquals(originalThread, lane.workerThreadId());
        }
    }

    @Test void interruptionBeforeAdmissionOnlyFailsThatCaller() {
        try (var lane = new SingleFlightLane<>(1)) {
            Thread.currentThread().interrupt();
            try {
                assertThrows(IllegalStateException.class, () -> lane.invoke(context -> new SingleFlightLane.Proposal<>(2, 2)));
            } finally { Thread.interrupted(); }
            assertFalse(lane.isClosed());
            assertEquals(2, (int) lane.invoke(context -> new SingleFlightLane.Proposal<>(2, 2)));
        }
    }

    @Test void racingCloseAndCandidateAlwaysExposeOneConsistentOutcome() throws Exception {
        try (var callers = Executors.newFixedThreadPool(2)) {
            for (int round = 0; round < 30; round++) {
                var entered = new CountDownLatch(1);
                var race = new CountDownLatch(1);
                try (var lane = new SingleFlightLane<>(0)) {
                    var result = callers.submit(() -> lane.invoke(context -> {
                        entered.countDown();
                        awaitUninterruptibly(race);
                        return new SingleFlightLane.Proposal<>(1, 1);
                    }));
                    assertTrue(entered.await(1, TimeUnit.SECONDS));
                    var closer = callers.submit(() -> { awaitUninterruptibly(race); lane.close(); });
                    race.countDown();
                    closer.get(1, TimeUnit.SECONDS);
                    try {
                        assertEquals(1, (int) result.get(1, TimeUnit.SECONDS));
                        assertEquals(1, lane.state(), "a won success must retain the matching published state");
                    } catch (ExecutionException failure) {
                        assertInstanceOf(IllegalStateException.class, failure.getCause());
                        assertEquals(0, lane.state(), "a failed outcome cannot publish the candidate");
                    }
                    assertTrue(lane.isClosed());
                    assertThrows(IllegalStateException.class, () -> lane.invoke(context -> new SingleFlightLane.Proposal<>(2, 2)));
                } finally { race.countDown(); }
            }
        }
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        for (;;) {
            try { latch.await(); break; }
            catch (InterruptedException failure) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private static void awaitWorkerExit(SingleFlightLane<?> lane) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (lane.workerAlive() && System.nanoTime() < until) Thread.sleep(2);
        assertFalse(lane.workerAlive(), "released fixture action should let the existing worker exit");
    }
}

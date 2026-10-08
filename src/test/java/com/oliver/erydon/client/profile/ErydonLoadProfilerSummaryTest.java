package com.oliver.erydon.client.profile;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class ErydonLoadProfilerSummaryTest {
    private static final long QUIET_PERIOD = 10L;

    @Test
    void fullObservedModelBurstQueuesOneSummaryAndRetainsEveryCount() {
        FakeScheduler scheduler = new FakeScheduler();
        AtomicInteger events = new AtomicInteger();
        List<Integer> summaries = new ArrayList<>();
        var debouncer = debouncer(scheduler, () -> summaries.add(events.get()));

        // The previous runtime report contained this many creation/cache-hit events.
        for (int i = 0; i < 626_434; i++) {
            events.incrementAndGet();
            debouncer.request();
        }

        assertEquals(1, scheduler.scheduledCount);
        assertEquals(1, scheduler.pendingCount());
        scheduler.advanceTo(QUIET_PERIOD);
        assertEquals(List.of(626_434), summaries);
        assertEquals(0, scheduler.pendingCount());
    }

    @Test
    void continuedWorkDefersSummaryUntilTheLastEventsQuietPeriod() {
        FakeScheduler scheduler = new FakeScheduler();
        List<Long> summaries = new ArrayList<>();
        var debouncer = debouncer(scheduler, () -> summaries.add(scheduler.now));

        debouncer.request();
        scheduler.advanceTo(7L);
        debouncer.request();
        scheduler.advanceTo(10L);
        assertEquals(List.of(), summaries);
        assertEquals(2, scheduler.scheduledCount);
        assertEquals(1, scheduler.pendingCount());
        scheduler.advanceTo(16L);
        assertEquals(List.of(), summaries);
        scheduler.advanceTo(17L);
        assertEquals(List.of(17L), summaries);
        assertEquals(0, scheduler.pendingCount());
    }

    @Test
    void reloadResetCancelsOldSummaryWithoutReportingClearedCounters() {
        FakeScheduler scheduler = new FakeScheduler();
        AtomicInteger events = new AtomicInteger(12);
        List<Integer> summaries = new ArrayList<>();
        var debouncer = debouncer(scheduler, () -> summaries.add(events.get()));

        debouncer.request();
        Runnable alreadyDequeuedOldTask = scheduler.tasks.peek().action;
        scheduler.advanceTo(3L);
        debouncer.reset(() -> events.set(0));
        alreadyDequeuedOldTask.run();
        scheduler.advanceTo(30L);

        assertEquals(0, events.get());
        assertEquals(List.of(), summaries);
        assertEquals(0, scheduler.pendingCount());
    }

    @Test
    void staleOldReloadTaskCannotConsumeOrPrematurelyReportNewReload() {
        FakeScheduler scheduler = new FakeScheduler();
        AtomicInteger events = new AtomicInteger(12);
        List<Integer> summaries = new ArrayList<>();
        var debouncer = debouncer(scheduler, () -> summaries.add(events.get()));

        debouncer.request();
        Runnable alreadyDequeuedOldTask = scheduler.tasks.peek().action;
        scheduler.advanceTo(7L);
        debouncer.reset(() -> events.set(0));
        events.addAndGet(5);
        debouncer.request();
        alreadyDequeuedOldTask.run();
        scheduler.advanceTo(10L);

        assertEquals(List.of(), summaries);
        assertEquals(1, scheduler.pendingCount());
        scheduler.advanceTo(17L);
        assertEquals(List.of(5), summaries);
        assertEquals(0, scheduler.pendingCount());
    }

    @Test
    void eventArrivingDuringSummaryStillGetsAFinalSummary() {
        FakeScheduler scheduler = new FakeScheduler();
        AtomicInteger events = new AtomicInteger(1);
        List<Integer> summaries = new ArrayList<>();
        ErydonLoadProfiler.ModelSummaryDebouncer[] reference = new ErydonLoadProfiler.ModelSummaryDebouncer[1];
        reference[0] = debouncer(scheduler, () -> {
            summaries.add(events.get());
            if (events.compareAndSet(1, 2)) {
                reference[0].request();
            }
        });

        reference[0].request();
        scheduler.advanceTo(10L);
        assertEquals(List.of(1), summaries);
        assertEquals(1, scheduler.pendingCount());
        scheduler.advanceTo(20L);
        assertEquals(List.of(1, 2), summaries);
        assertEquals(0, scheduler.pendingCount());
    }

    private static ErydonLoadProfiler.ModelSummaryDebouncer debouncer(FakeScheduler scheduler, Runnable summary) {
        return new ErydonLoadProfiler.ModelSummaryDebouncer(QUIET_PERIOD, () -> scheduler.now, scheduler::schedule, summary);
    }

    private static final class FakeScheduler {
        private final PriorityQueue<Task> tasks = new PriorityQueue<>(Comparator.comparingLong(task -> task.deadline));
        private long now;
        private int scheduledCount;

        private ErydonLoadProfiler.ModelSummaryDebouncer.Cancellation schedule(Runnable action, long delayNanos) {
            Task task = new Task(now + delayNanos, action);
            tasks.add(task);
            scheduledCount++;
            return () -> task.cancelled = true;
        }

        private long pendingCount() {
            return tasks.stream().filter(task -> !task.cancelled).count();
        }

        private void advanceTo(long target) {
            while (!tasks.isEmpty() && tasks.peek().deadline <= target) {
                Task task = tasks.remove();
                now = task.deadline;
                if (!task.cancelled) {
                    task.action.run();
                }
            }
            now = target;
        }
    }

    private static final class Task {
        private final long deadline;
        private final Runnable action;
        private boolean cancelled;

        private Task(long deadline, Runnable action) {
            this.deadline = deadline;
            this.action = action;
        }
    }
}

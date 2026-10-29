/*
 * SmartCharge - CO3408 Advanced Software Modelling
 * Name: <STUDENT NAME>
 * Student ID: <STUDENT ID>
 */
package smartcharge.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import smartcharge.model.ChargingRequest;
import smartcharge.model.Priority;

/**
 * Pending-request queue and dispatch policy, implemented without library priority queues.
 *
 * Policy (assumptions A-03..A-06):
 * 1. Order by effective priority, then arrival sequence (FIFO within a priority).
 * 2. Aging: effective priority = base level - one level per agingStep waited, never above 1.
 * 3. Backfill: if the front request cannot get a bay and its full power, start the first later
 *    request that can.
 * 4. Starvation guard: once the front request was first overtaken by a backfill at least
 *    starvationGuard ago, stop backfilling until it starts, so freed power accumulates for it.
 *    The clock is kept per request: it starts at the request's first overtake (not at the first
 *    failed check, which would depend on how often idle threads poll and so differ between pool
 *    sizes) and is not reset if aging temporarily moves another request to the front.
 *
 * Lock order: scheduler lock -> StationResources power lock. Nothing acquires them the other way.
 */
public final class PriorityScheduler {

    /** Attempts to reserve a bay and power for a session. Must not block. */
    @FunctionalInterface
    public interface Allocator {
        boolean tryAllocate(ChargingSession session);

        /** Whether the session would fit right now, without reserving anything. */
        default boolean wouldFit(ChargingSession session) {
            return false;
        }
    }

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition workAvailable = lock.newCondition();
    private final List<ArrayDeque<ChargingSession>> lanes = new ArrayList<>();
    private final long agingStepNanos;
    private final long starvationGuardNanos;
    private final long recheckNanos;

    private long nextSequence;
    private int pending;
    private boolean closed;
    private boolean draining;
    private long guardActivations;

    public PriorityScheduler(long agingStepNanos, long starvationGuardNanos) {
        if (agingStepNanos <= 0 || starvationGuardNanos <= 0) {
            throw new IllegalArgumentException("aging step and starvation guard must be positive");
        }
        for (int i = 0; i < Priority.values().length; i++) {
            lanes.add(new ArrayDeque<>());
        }
        this.agingStepNanos = agingStepNanos;
        this.starvationGuardNanos = starvationGuardNanos;
        long quarter = Math.min(agingStepNanos, starvationGuardNanos) / 4;
        this.recheckNanos = Math.max(TimeUnit.MILLISECONDS.toNanos(1), Math.min(quarter, TimeUnit.MILLISECONDS.toNanos(100)));
    }

    public ChargingSession enqueue(ChargingRequest request) {
        lock.lock();
        try {
            return enqueue(request, System.nanoTime());
        } finally {
            lock.unlock();
        }
    }

    /** Creates the session under the lock so that sequence order equals queue order (FIFO). */
    public ChargingSession enqueue(ChargingRequest request, long nowNanos) {
        lock.lock();
        try {
            if (closed) {
                throw new IllegalStateException("scheduler is closed; request " + request.requestId() + " not accepted");
            }
            ChargingSession session = new ChargingSession(request, ++nextSequence, nowNanos);
            lane(session).addLast(session);
            pending++;
            workAvailable.signal();
            return session;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Blocks until a session can start, then returns it already marked CHARGING with its
     * resources allocated. Returns null once the scheduler is closed and has nothing to hand out.
     */
    public ChargingSession awaitNext(Allocator allocator) throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (true) {
                if (closed && (!draining || pending == 0)) {
                    workAvailable.signalAll();
                    return null;
                }
                long now = System.nanoTime();
                ChargingSession next = selectNext(now, allocator);
                if (next != null) {
                    next.markCharging(now, Thread.currentThread().getName());
                    if (pending > 0) {
                        workAvailable.signal();
                    } else if (closed) {
                        workAvailable.signalAll();
                    }
                    return next;
                }
                workAvailable.awaitNanos(recheckNanos);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * One dispatch decision at time {@code nowNanos}. Removes and returns the session whose
     * resources were allocated, or returns null. Public so the policy can be tested with a
     * controlled clock and allocator.
     */
    public ChargingSession selectNext(long nowNanos, Allocator allocator) {
        lock.lock();
        try {
            MergedOrder order = new MergedOrder(nowNanos);
            ChargingSession front = null;
            while (order.hasNext()) {
                ChargingSession candidate = order.next();
                if (allocator.tryAllocate(candidate)) {
                    lane(candidate).remove(candidate);
                    pending--;
                    if (front != null) {
                        if (front.overtakenSinceNanos < 0) {
                            front.overtakenSinceNanos = nowNanos;
                        }
                        Log.info("BACKFILL %s starts ahead of blocked %s", candidate, front);
                    }
                    return candidate;
                }
                if (front == null) {
                    front = candidate;
                    if (front.overtakenSinceNanos >= 0 && nowNanos - front.overtakenSinceNanos >= starvationGuardNanos) {
                        if (!front.guardReported && anyLaterWouldFit(order, allocator)) {
                            front.guardReported = true;
                            guardActivations++;
                            Log.info("STARVATION GUARD %s overtaken for too long; backfill paused until it starts", front);
                        }
                        return null;
                    }
                }
            }
            return null;
        } finally {
            lock.unlock();
        }
    }

    /** Removes a still-queued session and marks it CANCELLED. False if it already left the queue. */
    public boolean cancelQueued(ChargingSession session, long nowNanos) {
        lock.lock();
        try {
            if (!session.cancelIfQueued(nowNanos)) {
                return false;
            }
            if (lane(session).remove(session)) {
                pending--;
            }
            workAvailable.signal();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** Wakes one waiting worker after resources were released. */
    public void capacityReleased() {
        lock.lock();
        try {
            workAvailable.signal();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Stops accepting requests. With drain=true queued sessions are still handed out; with
     * drain=false they are removed and returned (still QUEUED) for the caller to cancel.
     */
    public List<ChargingSession> close(boolean drain) {
        lock.lock();
        try {
            closed = true;
            draining = drain;
            List<ChargingSession> dropped = new ArrayList<>();
            if (!drain) {
                for (ArrayDeque<ChargingSession> lane : lanes) {
                    dropped.addAll(lane);
                    lane.clear();
                }
                pending = 0;
            }
            workAvailable.signalAll();
            return dropped;
        } finally {
            lock.unlock();
        }
    }

    public int[] pendingByPriority() {
        lock.lock();
        try {
            int[] counts = new int[lanes.size()];
            for (int i = 0; i < lanes.size(); i++) {
                counts[i] = lanes.get(i).size();
            }
            return counts;
        } finally {
            lock.unlock();
        }
    }

    public long guardActivations() {
        lock.lock();
        try {
            return guardActivations;
        } finally {
            lock.unlock();
        }
    }

    /** Counts a guard activation only when it actually stops a request that could have started. */
    private static boolean anyLaterWouldFit(MergedOrder order, Allocator allocator) {
        while (order.hasNext()) {
            if (allocator.wouldFit(order.next())) {
                return true;
            }
        }
        return false;
    }

    int effectivePriority(ChargingSession s, long nowNanos) {
        long waited = Math.max(0, nowNanos - s.arrivalNanos());
        long boosts = waited / agingStepNanos;
        return (int) Math.max(1, s.priority().level() - Math.min(boosts, Priority.values().length));
    }

    private ArrayDeque<ChargingSession> lane(ChargingSession s) {
        return lanes.get(s.priority().level() - 1);
    }

    /**
     * Lazy k-way merge over the per-priority FIFO lanes. Each lane is already sorted by
     * (effective priority, sequence): an earlier arrival in the same lane has waited at least as
     * long, so its effective priority is never worse. Merging the lane heads therefore yields
     * the global dispatch order without sorting.
     */
    private final class MergedOrder {
        private final long now;
        private final List<Iterator<ChargingSession>> iterators = new ArrayList<>();
        private final ChargingSession[] heads;

        MergedOrder(long now) {
            this.now = now;
            this.heads = new ChargingSession[lanes.size()];
            for (int i = 0; i < lanes.size(); i++) {
                Iterator<ChargingSession> it = lanes.get(i).iterator();
                iterators.add(it);
                heads[i] = it.hasNext() ? it.next() : null;
            }
        }

        boolean hasNext() {
            for (ChargingSession h : heads) {
                if (h != null) {
                    return true;
                }
            }
            return false;
        }

        ChargingSession next() {
            int best = -1;
            for (int i = 0; i < heads.length; i++) {
                if (heads[i] != null && (best < 0 || before(heads[i], heads[best]))) {
                    best = i;
                }
            }
            ChargingSession chosen = heads[best];
            Iterator<ChargingSession> it = iterators.get(best);
            heads[best] = it.hasNext() ? it.next() : null;
            return chosen;
        }

        private boolean before(ChargingSession a, ChargingSession b) {
            int pa = effectivePriority(a, now);
            int pb = effectivePriority(b, now);
            return pa != pb ? pa < pb : a.sequence() < b.sequence();
        }
    }
}

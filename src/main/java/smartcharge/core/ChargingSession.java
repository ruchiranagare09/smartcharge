/*
 * SmartCharge - CO3408 Advanced Software Modelling
 * Name: <STUDENT NAME>
 * Student ID: <STUDENT ID>
 */
package smartcharge.core;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import smartcharge.model.ChargingRequest;
import smartcharge.model.Priority;
import smartcharge.model.SessionState;

/**
 * Lifecycle of one accepted request. Every state transition is synchronized on the session, so
 * cancellation, completion and billing are applied in one total order and an illegal transition
 * fails loudly instead of corrupting state.
 *
 * Source: cancellation uses a CountDownLatch timed await, which returns early once the latch is
 * counted down (Java 17 CountDownLatch API):
 * https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/concurrent/CountDownLatch.html
 */
public final class ChargingSession {

    private final ChargingRequest request;
    private final Priority priority;
    private final long sequence;
    private final long arrivalNanos;
    private final CountDownLatch cancelSignal = new CountDownLatch(1);

    private SessionState state = SessionState.QUEUED;
    private boolean cancelRequested;
    private long startNanos = -1;
    private long endNanos = -1;
    private long startEpochMillis = -1;
    private long endEpochMillis = -1;
    private String workerName;

    /** Starvation-guard bookkeeping, owned by PriorityScheduler and guarded by its lock. */
    long overtakenSinceNanos = -1;
    boolean guardReported;

    public ChargingSession(ChargingRequest request, long sequence, long arrivalNanos) {
        this.request = request;
        this.priority = request.priorityLevel();
        this.sequence = sequence;
        this.arrivalNanos = arrivalNanos;
    }

    public synchronized void markCharging(long nowNanos, String worker) {
        require(SessionState.QUEUED, "start charging");
        state = SessionState.CHARGING;
        startNanos = nowNanos;
        startEpochMillis = System.currentTimeMillis();
        workerName = worker;
    }

    /** QUEUED -> CANCELLED. Returns false if the session already left the queue. */
    public synchronized boolean cancelIfQueued(long nowNanos) {
        if (state != SessionState.QUEUED) {
            return false;
        }
        state = SessionState.CANCELLED;
        endNanos = nowNanos;
        endEpochMillis = System.currentTimeMillis();
        return true;
    }

    /** Asks a CHARGING session to stop early. The worker applies the cancellation. */
    public synchronized boolean requestCancel() {
        if (state != SessionState.CHARGING) {
            return false;
        }
        cancelRequested = true;
        cancelSignal.countDown();
        return true;
    }

    /** Blocks for the charging duration. Returns true if cancellation was requested first. */
    public boolean awaitCancellation(long timeoutNanos) throws InterruptedException {
        return cancelSignal.await(timeoutNanos, TimeUnit.NANOSECONDS);
    }

    /** CHARGING -> COMPLETED, or CANCELLED when a cancel request won the race. */
    public synchronized SessionState finishCharging(long nowNanos) {
        require(SessionState.CHARGING, "finish charging");
        endNanos = nowNanos;
        endEpochMillis = System.currentTimeMillis();
        state = cancelRequested ? SessionState.CANCELLED : SessionState.COMPLETED;
        return state;
    }

    /** CHARGING -> CANCELLED when the worker thread is interrupted. */
    public synchronized boolean abortCharging(long nowNanos) {
        if (state != SessionState.CHARGING) {
            return false;
        }
        endNanos = nowNanos;
        endEpochMillis = System.currentTimeMillis();
        state = SessionState.CANCELLED;
        return true;
    }

    public synchronized void markBilled() {
        require(SessionState.COMPLETED, "mark billed");
        state = SessionState.BILLED;
    }

    public synchronized void markBillingFailed() {
        require(SessionState.COMPLETED, "mark billing failed");
        state = SessionState.BILLING_FAILED;
    }

    private void require(SessionState expected, String action) {
        if (state != expected) {
            throw new IllegalStateException(String.format("cannot %s for %s in state %s (expected %s)",
                    action, request.requestId(), state, expected));
        }
    }

    public ChargingRequest request() {
        return request;
    }

    public Priority priority() {
        return priority;
    }

    public long sequence() {
        return sequence;
    }

    public long arrivalNanos() {
        return arrivalNanos;
    }

    public synchronized SessionState state() {
        return state;
    }

    public synchronized long startNanos() {
        return startNanos;
    }

    public synchronized long endNanos() {
        return endNanos;
    }

    public synchronized long startEpochMillis() {
        return startEpochMillis;
    }

    public synchronized long endEpochMillis() {
        return endEpochMillis;
    }

    public synchronized String workerName() {
        return workerName;
    }

    /** Time from arrival to start of charging, or -1 if the session never started. */
    public synchronized long waitNanos() {
        return startNanos < 0 ? -1 : startNanos - arrivalNanos;
    }

    @Override
    public String toString() {
        return request.requestId() + "(P" + priority.level() + "," + request.rate() + "," + request.energyKwh() + "kWh)";
    }
}

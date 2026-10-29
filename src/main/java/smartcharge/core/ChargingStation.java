/*
 * SmartCharge - CO3408 Advanced Software Modelling
 * Name: <STUDENT NAME>
 * Student ID: <STUDENT ID>
 */
package smartcharge.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import smartcharge.billing.BillingAdapter;
import smartcharge.model.ChargingRequest;
import smartcharge.model.SessionState;

/**
 * Station controller: request intake, worker threads, session execution, cancellation and
 * shutdown. Workers pull the next startable session from the {@link PriorityScheduler}; a session
 * is handed out only once its bay and power are already reserved, so no worker ever holds a
 * partial allocation.
 *
 * Source: shutdown follows the two-phase pattern (shutdown, awaitTermination, then shutdownNow)
 * described in the Java 17 ExecutorService API:
 * https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/concurrent/ExecutorService.html
 */
public final class ChargingStation {

    public enum ShutdownMode { GRACEFUL, IMMEDIATE }

    private enum Lifecycle { NEW, RUNNING, SHUTTING_DOWN, TERMINATED }

    /**
     * Execution configuration. workers == 0 means thread-per-request: a new thread is created for
     * every accepted request and runs exactly one session.
     */
    public record Config(int workers, TimeScale timeScale, double agingStepSimMinutes, double starvationGuardSimMinutes) {

        public static final double DEFAULT_AGING_STEP_MINUTES = 30.0;
        public static final double DEFAULT_STARVATION_GUARD_MINUTES = 30.0;

        public static Config pool(int workers) {
            if (workers < 1) {
                throw new IllegalArgumentException("worker pool needs at least one worker");
            }
            return new Config(workers, TimeScale.DEFAULT, DEFAULT_AGING_STEP_MINUTES, DEFAULT_STARVATION_GUARD_MINUTES);
        }

        public static Config perRequestThreads() {
            return new Config(0, TimeScale.DEFAULT, DEFAULT_AGING_STEP_MINUTES, DEFAULT_STARVATION_GUARD_MINUTES);
        }

        public Config withTimeScale(TimeScale scale) {
            return new Config(workers, scale, agingStepSimMinutes, starvationGuardSimMinutes);
        }

        public Config withAgingStep(double simMinutes) {
            return new Config(workers, timeScale, simMinutes, starvationGuardSimMinutes);
        }

        public Config withStarvationGuard(double simMinutes) {
            return new Config(workers, timeScale, agingStepSimMinutes, simMinutes);
        }

        public boolean isThreadPerRequest() {
            return workers == 0;
        }

        public String label() {
            return isThreadPerRequest() ? "thread-per-request" : "pool-" + workers;
        }
    }

    /** Station status for monitoring output. */
    public record Status(int[] pendingByPriority, StationResources.Snapshot resources, Map<SessionState, Integer> sessionsByState) {
        public String describe() {
            return String.format("queued[P1=%d P2=%d P3=%d] activeBays=%d/%d reserved=%d/%d kW states=%s",
                    pendingByPriority[0], pendingByPriority[1], pendingByPriority[2],
                    resources.activeBays(), resources.totalBays(), resources.reservedKw(), resources.powerCapacityKw(),
                    sessionsByState);
        }
    }

    private final Config config;
    private final StationResources resources;
    private final BillingAdapter billing;
    private final PriorityScheduler scheduler;
    private final RequestValidator validator = new RequestValidator();
    private final Set<String> seenRequestIds = ConcurrentHashMap.newKeySet();
    private final Map<String, ChargingSession> sessions = new ConcurrentHashMap<>();
    private final PriorityScheduler.Allocator allocator;
    private final List<Thread> requestThreads = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger requestThreadCounter = new AtomicInteger();
    private final AtomicReference<Lifecycle> lifecycle = new AtomicReference<>(Lifecycle.NEW);
    private volatile ExecutorService workerPool;

    public ChargingStation(Config config, StationResources resources, BillingAdapter billing) {
        this.config = config;
        this.resources = resources;
        this.billing = billing;
        this.scheduler = new PriorityScheduler(
                config.timeScale().simMinutesToNanos(config.agingStepSimMinutes()),
                config.timeScale().simMinutesToNanos(config.starvationGuardSimMinutes()));
        this.allocator = new PriorityScheduler.Allocator() {
            @Override
            public boolean tryAllocate(ChargingSession session) {
                return resources.tryAllocate(session.request().rate().kw());
            }

            @Override
            public boolean wouldFit(ChargingSession session) {
                return resources.wouldFit(session.request().rate().kw());
            }
        };
    }

    public void start() {
        if (lifecycle.get() != Lifecycle.NEW) {
            throw new IllegalStateException("station already started");
        }
        if (!config.isThreadPerRequest()) {
            workerPool = Executors.newFixedThreadPool(config.workers(), namedThreads("worker-"));
        }
        lifecycle.set(Lifecycle.RUNNING);
        if (workerPool != null) {
            for (int i = 0; i < config.workers(); i++) {
                workerPool.execute(this::workerLoop);
            }
        }
        StationResources.Snapshot s = resources.snapshot();
        Log.info("Station started: mode=%s bays=%d power=%d kW aging=%.0f min guard=%.0f min scale=%.3f ms/sim-min",
                config.label(), s.totalBays(), s.powerCapacityKw(), config.agingStepSimMinutes(),
                config.starvationGuardSimMinutes(), config.timeScale().realMillisPerSimMinute());
    }

    /** Validates and queues a request. Throws ValidationException for invalid or duplicate input. */
    public ChargingSession submit(ChargingRequest request) throws ValidationException {
        try {
            validator.validate(request);
        } catch (ValidationException e) {
            Log.info("REJECTED %s", e.getMessage());
            throw e;
        }
        if (lifecycle.get() != Lifecycle.RUNNING) {
            throw new IllegalStateException("station is not accepting requests (" + lifecycle.get() + ")");
        }
        if (!seenRequestIds.add(request.requestId())) {
            Log.info("REJECTED duplicate request id %s", request.requestId());
            throw new ValidationException("duplicate request id: " + request.requestId());
        }
        Thread oneShot = null;
        if (config.isThreadPerRequest()) {
            oneShot = new Thread(this::runOneSession, "req-thread-" + requestThreadCounter.incrementAndGet());
            requestThreads.add(oneShot);
        }
        ChargingSession session;
        try {
            session = scheduler.enqueue(request);
        } catch (IllegalStateException e) {
            if (oneShot != null) {
                requestThreads.remove(oneShot);
            }
            throw e;
        }
        sessions.put(request.requestId(), session);
        Log.info("QUEUED %s account=%s seq=%d", session, request.accountId(), session.sequence());
        if (oneShot != null) {
            oneShot.start();
        }
        return session;
    }

    /** Cancels a queued or charging session. Returns false if it is unknown or already finished. */
    public boolean cancel(String requestId) {
        ChargingSession session = sessions.get(requestId);
        if (session == null) {
            return false;
        }
        if (scheduler.cancelQueued(session, System.nanoTime())) {
            Log.info("CANCELLED %s while queued", session);
            return true;
        }
        boolean requested = session.requestCancel();
        if (requested) {
            Log.info("CANCEL requested for charging session %s", session);
        }
        return requested;
    }

    /**
     * GRACEFUL: stop intake, finish every queued and active session. IMMEDIATE: stop intake,
     * cancel queued sessions, interrupt active sessions. Both wait for all worker threads to end
     * and then verify every bay and kW has been returned.
     */
    public boolean shutdown(ShutdownMode mode, long timeout, TimeUnit unit) throws InterruptedException {
        if (!lifecycle.compareAndSet(Lifecycle.RUNNING, Lifecycle.SHUTTING_DOWN)) {
            throw new IllegalStateException("station is not running");
        }
        Log.info("SHUTDOWN requested (%s)", mode);
        long now = System.nanoTime();
        for (ChargingSession s : scheduler.close(mode == ShutdownMode.GRACEFUL)) {
            if (s.cancelIfQueued(now)) {
                Log.info("CANCELLED %s (queued at shutdown)", s);
            }
        }
        if (mode == ShutdownMode.IMMEDIATE) {
            for (ChargingSession s : sessions.values()) {
                if (s.requestCancel()) {
                    Log.info("CANCEL requested for %s (active at shutdown)", s);
                }
            }
        }
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        boolean terminated = awaitThreads(deadline);
        if (!terminated) {
            Log.info("SHUTDOWN timeout reached; interrupting remaining threads");
            forceStop();
            terminated = awaitThreads(System.nanoTime() + TimeUnit.SECONDS.toNanos(5));
        }
        lifecycle.set(Lifecycle.TERMINATED);
        StationResources.Snapshot snap = resources.snapshot();
        Log.info("SHUTDOWN complete: threadsTerminated=%s reservedKw=%d activeBays=%d",
                terminated, snap.reservedKw(), snap.activeBays());
        if (terminated) {
            assert snap.reservedKw() == 0 && snap.activeBays() == 0 : "resources leaked after shutdown: " + snap;
        }
        return terminated;
    }

    public boolean isTerminated() {
        if (lifecycle.get() != Lifecycle.TERMINATED) {
            return false;
        }
        if (workerPool != null) {
            return workerPool.isTerminated();
        }
        synchronized (requestThreads) {
            return requestThreads.stream().noneMatch(Thread::isAlive);
        }
    }

    public Status status() {
        Map<SessionState, Integer> counts = new EnumMap<>(SessionState.class);
        for (ChargingSession s : sessions.values()) {
            counts.merge(s.state(), 1, Integer::sum);
        }
        return new Status(scheduler.pendingByPriority(), resources.snapshot(), counts);
    }

    public List<ChargingSession> sessions() {
        return new ArrayList<>(sessions.values());
    }

    public ChargingSession session(String requestId) {
        return sessions.get(requestId);
    }

    public StationResources resources() {
        return resources;
    }

    public PriorityScheduler scheduler() {
        return scheduler;
    }

    public Config config() {
        return config;
    }

    private void workerLoop() {
        try {
            ChargingSession session;
            while ((session = scheduler.awaitNext(allocator)) != null) {
                runSession(session);
                if (Thread.currentThread().isInterrupted()) {
                    break;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void runOneSession() {
        try {
            ChargingSession session = scheduler.awaitNext(allocator);
            if (session != null) {
                runSession(session);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Runs one session that already holds its bay and power. Resources are released in finally,
     * so completion, cancellation, billing failure, interruption and unexpected exceptions all
     * return them exactly once.
     */
    private void runSession(ChargingSession session) {
        int kw = session.request().rate().kw();
        Log.info("START %s bay+%d kW waited=%.1f sim-min %s", session, kw,
                config.timeScale().nanosToSimMinutes(session.waitNanos()), resourcesLine());
        try {
            long durationNanos = config.timeScale().simMinutesToNanos(session.request().durationSimMinutes());
            session.awaitCancellation(durationNanos);
            SessionState outcome = session.finishCharging(System.nanoTime());
            if (outcome == SessionState.COMPLETED) {
                Log.info("COMPLETED %s after %.1f sim-min", session,
                        config.timeScale().nanosToSimMinutes(session.endNanos() - session.startNanos()));
                billing.bill(session);
            } else {
                Log.info("CANCELLED %s during charging (not billed)", session);
            }
        } catch (InterruptedException e) {
            if (session.abortCharging(System.nanoTime())) {
                Log.info("CANCELLED %s: worker interrupted (not billed)", session);
            }
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            Log.info("ERROR in session %s: %s", session, e);
            if (!session.abortCharging(System.nanoTime()) && session.state() == SessionState.COMPLETED) {
                session.markBillingFailed();
            }
        } finally {
            resources.release(kw);
            scheduler.capacityReleased();
            Log.info("RELEASED %s bay-%d kW %s", session.request().requestId(), kw, resourcesLine());
        }
    }

    private String resourcesLine() {
        StationResources.Snapshot s = resources.snapshot();
        return "[bays " + s.activeBays() + "/" + s.totalBays() + ", " + s.reservedKw() + "/" + s.powerCapacityKw() + " kW]";
    }

    private boolean awaitThreads(long deadlineNanos) throws InterruptedException {
        if (workerPool != null) {
            workerPool.shutdown();
            return workerPool.awaitTermination(Math.max(0, deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS);
        }
        List<Thread> threads;
        synchronized (requestThreads) {
            threads = new ArrayList<>(requestThreads);
        }
        for (Thread t : threads) {
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining > 0) {
                TimeUnit.NANOSECONDS.timedJoin(t, remaining);
            }
        }
        return threads.stream().noneMatch(Thread::isAlive);
    }

    private void forceStop() {
        if (workerPool != null) {
            workerPool.shutdownNow();
            return;
        }
        synchronized (requestThreads) {
            requestThreads.forEach(Thread::interrupt);
        }
    }

    private static ThreadFactory namedThreads(String prefix) {
        AtomicInteger n = new AtomicInteger();
        return r -> new Thread(r, prefix + n.incrementAndGet());
    }
}

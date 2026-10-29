/*
 * SmartCharge - CO3408 Advanced Software Modelling
 * Name: <STUDENT NAME>
 * Student ID: <STUDENT ID>
 */
package smartcharge.demo;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import smartcharge.core.StationResources;

/**
 * Stand-alone deadlock demonstration. NOT used by the station: run it explicitly.
 *
 * Deadlock-prone variant: a "naive allocator" where fast sessions lock the power ledger and then
 * block for a bay, while standard sessions hold a bay and then block for the power ledger.
 * Latches force the fatal interleaving every time, so the deadlock is reproducible.
 *
 * Corrected variant: the same threads and the same interleaving, but allocation goes through
 * {@link StationResources#tryAllocate}, which never blocks while holding a resource.
 *
 * Usage: DeadlockDemo [deadlock | fixed | both]
 *
 * Sources: deadlock conditions (mutual exclusion, hold-and-wait, no pre-emption, circular wait)
 * from Coffman, Elphick and Shoshani (1971), https://doi.org/10.1145/356586.356588 . The JVM's
 * findDeadlockedThreads() only detects cycles on object monitors and ownable synchronizers
 * (Java 17 ThreadMXBean API), which is why a timeout watchdog is used to detect a cycle that
 * involves a Semaphore:
 * https://docs.oracle.com/en/java/javase/17/docs/api/java.management/java/lang/management/ThreadMXBean.html
 */
public final class DeadlockDemo {

    private static final int STANDARD_THREADS = 4;
    private static final long DETECTION_TIMEOUT_MS = 2000;

    /** Outcome of one variant: whether all threads finished, plus the evidence lines. */
    public record Result(boolean allFinished, List<String> evidence) {
    }

    private DeadlockDemo() {
    }

    public static void main(String[] args) throws InterruptedException {
        String mode = args.length > 0 ? args[0] : "both";
        if (mode.equals("deadlock") || mode.equals("both")) {
            Result r = runDeadlockProne(DETECTION_TIMEOUT_MS);
            r.evidence().forEach(DeadlockDemo::print);
            print("RESULT deadlock-prone variant: " + (r.allFinished() ? "completed (no deadlock)" : "DEADLOCKED (threads never finished)"));
        }
        if (mode.equals("fixed") || mode.equals("both")) {
            Result r = runCorrected(DETECTION_TIMEOUT_MS);
            r.evidence().forEach(DeadlockDemo::print);
            print("RESULT corrected variant: " + (r.allFinished() ? "all sessions completed, no deadlock" : "DID NOT COMPLETE"));
        }
    }

    /** Runs the deadlock-prone allocator. Threads are daemons so a deadlock cannot hang the JVM. */
    public static Result runDeadlockProne(long timeoutMs) throws InterruptedException {
        List<String> evidence = new ArrayList<>();
        Semaphore bays = new Semaphore(StationResources.DEFAULT_BAYS, true);
        ReentrantLock powerLedger = new ReentrantLock();
        CountDownLatch allBaysHeld = new CountDownLatch(STANDARD_THREADS);
        CountDownLatch ledgerHeld = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(STANDARD_THREADS + 1);
        List<Thread> threads = new ArrayList<>();

        evidence.add("=== DEADLOCK-PRONE VARIANT (naive allocator, inconsistent acquisition order) ===");
        for (int i = 1; i <= STANDARD_THREADS; i++) {
            threads.add(daemon("std-" + i, () -> {
                try {
                    bays.acquire();
                    log(evidence, "holds 1 bay, now waiting for the power ledger lock");
                    allBaysHeld.countDown();
                    ledgerHeld.await();
                    powerLedger.lock();
                    try {
                        log(evidence, "reserved 7 kW");
                    } finally {
                        powerLedger.unlock();
                    }
                    bays.release();
                    finished.countDown();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        threads.add(daemon("fast-1", () -> {
            try {
                powerLedger.lock();
                try {
                    log(evidence, "holds the power ledger lock, now waiting for a bay");
                    ledgerHeld.countDown();
                    allBaysHeld.await();
                    bays.acquire();
                    log(evidence, "reserved 22 kW");
                    bays.release();
                } finally {
                    powerLedger.unlock();
                }
                finished.countDown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        threads.forEach(Thread::start);

        boolean allFinished = finished.await(timeoutMs, TimeUnit.MILLISECONDS);
        if (!allFinished) {
            evidence.add(String.format(Locale.ROOT, "DETECTED: no progress within %d ms timeout; %d of %d threads still blocked",
                    timeoutMs, finished.getCount(), STANDARD_THREADS + 1));
            evidence.addAll(threadDump(threads));
            evidence.add("Resource-allocation graph (holds: resource -> thread, requests: thread -> resource):");
            evidence.add("  bay permits x4 -> std-1, std-2, std-3, std-4      (all permits held)");
            evidence.add("  power ledger   -> fast-1                          (lock held)");
            evidence.add("  std-1..std-4   -> power ledger                    (blocked in lock())");
            evidence.add("  fast-1         -> bay permit                      (blocked in acquire())");
            evidence.add("Wait-for graph: std-k -> fast-1 (ledger owner) and fast-1 -> {std-1..std-4} (permit holders): CYCLE");
            evidence.add("Coffman conditions: mutual exclusion (ledger lock, bay permits) | hold-and-wait (each thread holds one,");
            evidence.add("  waits for the other) | no pre-emption (neither can be taken away) | circular wait (cycle above) => deadlock");
            long[] jvmDeadlocked = ManagementFactory.getThreadMXBean().findDeadlockedThreads();
            evidence.add("ThreadMXBean.findDeadlockedThreads() = " + (jvmDeadlocked == null ? "null" : jvmDeadlocked.length + " threads")
                    + " (a Semaphore has no owner thread, so the JVM detector cannot see this cycle; the timeout watchdog does)");
        }
        return new Result(allFinished, evidence);
    }

    /**
     * Same thread roles and forced interleaving, using the corrected allocator: a refused
     * allocation holds nothing, so the fast session simply retries after a bay is released.
     */
    public static Result runCorrected(long timeoutMs) throws InterruptedException {
        List<String> evidence = new ArrayList<>();
        StationResources resources = new StationResources();
        CountDownLatch allBaysHeld = new CountDownLatch(STANDARD_THREADS);
        CountDownLatch fastRefused = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(STANDARD_THREADS + 1);
        List<Thread> threads = new ArrayList<>();

        evidence.add("=== CORRECTED VARIANT (atomic, non-blocking allocation, fixed order: power lock -> bay tryAcquire) ===");
        for (int i = 1; i <= STANDARD_THREADS; i++) {
            threads.add(daemon("std-" + i, () -> {
                try {
                    if (!resources.tryAllocate(7)) {
                        log(evidence, "unexpected refusal");
                        return;
                    }
                    log(evidence, "allocated bay + 7 kW atomically (holds both, waits for nothing)");
                    allBaysHeld.countDown();
                    fastRefused.await();
                    TimeUnit.MILLISECONDS.sleep(50);
                    resources.release(7);
                    log(evidence, "charging done, released bay + 7 kW");
                    finished.countDown();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        threads.add(daemon("fast-1", () -> {
            try {
                allBaysHeld.await();
                int attempts = 0;
                while (!resources.tryAllocate(22)) {
                    attempts++;
                    if (attempts == 1) {
                        log(evidence, "allocation refused (no free bay) - holding NOTHING, will retry");
                        fastRefused.countDown();
                    }
                    TimeUnit.MILLISECONDS.sleep(5);
                }
                log(evidence, "allocated bay + 22 kW after " + attempts + " refused attempt(s)");
                resources.release(22);
                finished.countDown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        threads.forEach(Thread::start);

        boolean allFinished = finished.await(timeoutMs, TimeUnit.MILLISECONDS);
        StationResources.Snapshot snap = resources.snapshot();
        evidence.add(String.format(Locale.ROOT, "Finished=%s, final reserved=%d kW, active bays=%d, peak reserved=%d kW, peak bays=%d",
                allFinished, snap.reservedKw(), snap.activeBays(), snap.peakReservedKw(), snap.peakActiveBays()));
        evidence.add("Why no deadlock: tryAcquire() never blocks while the power lock is held, and a refused request");
        evidence.add("  keeps nothing, so hold-and-wait and circular wait cannot occur.");
        return new Result(allFinished, evidence);
    }

    private static List<String> threadDump(List<Thread> threads) {
        ThreadMXBean mx = ManagementFactory.getThreadMXBean();
        List<String> lines = new ArrayList<>();
        lines.add("Thread dump of demo threads:");
        for (Thread t : threads) {
            ThreadInfo info = mx.getThreadInfo(new long[]{t.getId()}, true, true)[0];
            if (info == null) {
                continue;
            }
            lines.add(String.format(Locale.ROOT, "  \"%s\" state=%s waitingOn=%s owner=%s", info.getThreadName(),
                    info.getThreadState(), info.getLockName(), info.getLockOwnerName()));
            StackTraceElement[] stack = info.getStackTrace();
            for (StackTraceElement frame : stack) {
                if (frame.getClassName().startsWith("smartcharge")) {
                    lines.add("      at " + frame);
                    break;
                }
            }
        }
        return lines;
    }

    private static Thread daemon(String name, Runnable body) {
        Thread t = new Thread(body, name);
        t.setDaemon(true);
        return t;
    }

    private static void log(List<String> evidence, String message) {
        synchronized (evidence) {
            evidence.add("[" + Thread.currentThread().getName() + "] " + message);
        }
    }

    private static void print(String line) {
        System.out.println(line);
    }
}

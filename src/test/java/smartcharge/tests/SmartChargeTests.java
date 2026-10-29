package smartcharge.tests;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import smartcharge.billing.BillingAdapter;
import smartcharge.billing.LegacyBillingService;
import smartcharge.core.ChargingSession;
import smartcharge.core.ChargingStation;
import smartcharge.core.ChargingStation.Config;
import smartcharge.core.ChargingStation.ShutdownMode;
import smartcharge.core.PriorityScheduler;
import smartcharge.core.StationResources;
import smartcharge.core.TimeScale;
import smartcharge.core.ValidationException;
import smartcharge.demo.DeadlockDemo;
import smartcharge.model.ChargingRate;
import smartcharge.model.ChargingRequest;
import smartcharge.model.SessionState;
import smartcharge.tests.TestRunner.Case;
import smartcharge.tests.TestRunner.Outcome;
import smartcharge.workload.WorkloadGenerator;
import smartcharge.workload.WorkloadItem;

/**
 * Consolidated test cases. Default time scale: 1 simulated minute = 1 ms, so a 7 kWh standard
 * session or a 22 kWh fast session lasts 60 simulated minutes = 60 ms.
 */
final class SmartChargeTests {

    private static final double DISABLED = 1e9;

    private SmartChargeTests() {
    }

    static List<Case> all() {
        return List.of(
                new Case("T01", "NORMAL", "Standard-rate session completes and is billed once",
                        "pool-1; R1 P2 STANDARD 7 kWh (60 sim-min)",
                        "BILLED; 1 legacy entry; account total 7.0 kWh; charged ~60 sim-min; resources returned",
                        SmartChargeTests::standardSession),
                new Case("T02", "NORMAL", "Fast-rate session completes and is billed once",
                        "pool-1; R1 P1 FAST 22 kWh (60 sim-min)",
                        "BILLED; 1 entry; peak reserved 22 kW; resources returned",
                        SmartChargeTests::fastSession),
                new Case("T03", "NORMAL", "Multiple simultaneous valid requests",
                        "pool-4; 8 threads released by one latch each submit STANDARD 7 kWh",
                        "all 8 BILLED; peak bays 4/4; peak power 28 <= 60 kW; 8 entries; billing reconciled",
                        SmartChargeTests::simultaneousRequests),
                new Case("T04", "NORMAL", "Priority ordering with FIFO tie-break among equal priorities",
                        "pool-1, aging off; blocker charging; then queue V1(P3) S1(P2) S2(P2) F1(P1) V2(P3)",
                        "start order BLK, F1, S1, S2, V1, V2",
                        SmartChargeTests::priorityAndFifo),
                new Case("T05", "EDGE", "Invalid and duplicate requests are rejected at intake",
                        "zero, negative, NaN, infinite, >100 kWh; priority 0 and 4; null rate; blank account; null id; duplicate id",
                        "every invalid request throws ValidationException and creates no session",
                        SmartChargeTests::validation),
                new Case("T06", "EDGE", "No free bay: fifth request waits for a released bay",
                        "pool-16; 5 x STANDARD 7 kWh submitted together (35 kW fits, 5 bays do not)",
                        "peak bays 4; 5th session starts only after an earlier session ends",
                        SmartChargeTests::noFreeBay),
                new Case("T07", "EDGE", "Power exhausted while bays are free",
                        "pool-16; 3 x FAST 22 kWh submitted together (66 kW > 60 kW)",
                        "peak reserved 44 kW with 2 bays idle; 3rd starts only after one ends",
                        SmartChargeTests::powerExhausted),
                new Case("T08", "EDGE", "Cancellation before charging",
                        "pool-1; blocker charging; R2 queued then cancelled",
                        "R2 CANCELLED, never started, no billing entry; blocker billed",
                        SmartChargeTests::cancelBeforeCharging),
                new Case("T09", "EDGE", "Cancellation during charging",
                        "pool-1; R1 STANDARD 60 kWh (~514 sim-min) charging, cancelled after ~20 ms",
                        "CANCELLED promptly (< 100 ms), no billing entry, bay and 7 kW released",
                        SmartChargeTests::cancelDuringCharging),
                new Case("T10", "EDGE", "Immediate shutdown while sessions are active",
                        "pool-4; 6 x STANDARD 60 kWh; 4 charging + 2 queued; shutdown(IMMEDIATE)",
                        "all 6 CANCELLED, 0 entries, 0 kW / 0 bays reserved, worker pool terminated",
                        SmartChargeTests::immediateShutdown),
                new Case("T11", "EDGE", "Graceful shutdown while sessions are active",
                        "pool-4; 6 x STANDARD 7 kWh; shutdown(GRACEFUL) right after submission",
                        "all 6 BILLED before shutdown returns; resources returned; pool terminated",
                        SmartChargeTests::gracefulShutdown),
                new Case("T12", "EDGE", "Concurrent billing through the adapter",
                        "16 threads x 500 completed sessions over 10 accounts + every thread bills one shared session",
                        "8001 entries; 15 duplicates ignored; every account total exact",
                        SmartChargeTests::concurrentBilling),
                new Case("T13", "OBSERVATION", "Same concurrent load straight into the legacy service (no adapter)",
                        "16 threads x 500 recordSession calls directly on LegacyBillingService, 5 trials",
                        "shows why the adapter is needed (lost entries / totals or exceptions are possible)",
                        SmartChargeTests::unsafeLegacyObservation),
                new Case("T14", "EDGE", "Resources released after a billing exception",
                        "pool-2; legacy throws for ACC-FAIL; submit failing + normal session",
                        "failing session BILLING_FAILED, normal BILLED, 1 entry, 0 kW / 0 bays reserved",
                        SmartChargeTests::releaseAfterException),
                new Case("T15", "EDGE", "Duplicate billing attempt is ignored",
                        "one COMPLETED session passed to BillingAdapter.bill twice",
                        "first BILLED, second DUPLICATE_IGNORED, 1 entry",
                        SmartChargeTests::duplicateBilling),
                new Case("T16", "EDGE", "Aging lifts a waiting visitor above a later fleet request",
                        "scheduler aging step 30; V(P3) arrives t=0, F(P1) arrives t=5",
                        "at t=20 F is chosen; at t=65 V (effective P1, earlier arrival) is chosen",
                        SmartChargeTests::aging),
                new Case("T17", "EDGE", "Starvation guard bounds backfill past a blocked fast request",
                        "guard 30; F(P1 fast) cannot fit, S1..S3 (P2 standard) can; selections at t=10,39,40, then F fits",
                        "S1, S2 backfill; at t=40 nothing starts (guard); then F before S3",
                        SmartChargeTests::starvationGuard),
                new Case("T18", "EDGE", "Burst of the seeded workload with thread-per-request",
                        "200 seeded requests submitted at once; thread-per-request; scale 0.05 ms/sim-min",
                        "200 BILLED; peak <= 4 bays and <= 60 kW; reconciled; all threads ended",
                        SmartChargeTests::burstThreadPerRequest),
                new Case("T19", "EDGE", "Deadlock-prone allocator deadlocks (separate demo)",
                        "DeadlockDemo.runDeadlockProne: 4 std hold bays wait for ledger; fast holds ledger waits for bay",
                        "watchdog detects no progress within 1000 ms; dump shows the wait cycle",
                        SmartChargeTests::deadlockProne),
                new Case("T20", "EDGE", "Corrected allocator under the same interleaving",
                        "DeadlockDemo.runCorrected: same roles and latches, allocation via tryAllocate",
                        "all 5 threads finish; 0 kW reserved at end",
                        SmartChargeTests::deadlockCorrected));
    }

    private record Fixture(LegacyBillingService legacy, BillingAdapter billing, ChargingStation station) {
    }

    private static Fixture start(Config config) {
        return start(config, new LegacyBillingService());
    }

    private static Fixture start(Config config, LegacyBillingService legacy) {
        BillingAdapter billing = new BillingAdapter(legacy);
        ChargingStation station = new ChargingStation(config, new StationResources(), billing);
        station.start();
        return new Fixture(legacy, billing, station);
    }

    private static ChargingRequest req(String id, int priority, double kwh, ChargingRate rate) {
        return new ChargingRequest(id, "ACC-" + id, priority, kwh, rate);
    }

    private static void awaitState(ChargingSession s, SessionState expected, long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (s.state() != expected) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError(s + " did not reach " + expected + " within " + timeoutMs + " ms (is " + s.state() + ")");
            }
            TimeUnit.MILLISECONDS.sleep(1);
        }
    }

    private static boolean released(StationResources.Snapshot s) {
        return s.reservedKw() == 0 && s.activeBays() == 0;
    }

    private static double ms(long nanos) {
        return nanos / 1e6;
    }

    private static Outcome standardSession() throws Exception {
        Fixture f = start(Config.pool(1));
        ChargingSession s = f.station().submit(req("R1", 2, 7.0, ChargingRate.STANDARD));
        f.station().shutdown(ShutdownMode.GRACEFUL, 5, TimeUnit.SECONDS);
        double charged = TimeScale.DEFAULT.nanosToSimMinutes(s.endNanos() - s.startNanos());
        StationResources.Snapshot snap = f.station().resources().snapshot();
        boolean pass = s.state() == SessionState.BILLED && f.billing().entryCount() == 1
                && f.billing().totalFor("ACC-R1") == 7.0 && charged >= 59 && charged < 120 && released(snap);
        return Outcome.of(String.format(Locale.ROOT, "state=%s entries=%d total=%.1f charged=%.1f sim-min reserved=%d bays=%d",
                s.state(), f.billing().entryCount(), f.billing().totalFor("ACC-R1"), charged, snap.reservedKw(), snap.activeBays()), pass);
    }

    private static Outcome fastSession() throws Exception {
        Fixture f = start(Config.pool(1));
        ChargingSession s = f.station().submit(req("R1", 1, 22.0, ChargingRate.FAST));
        f.station().shutdown(ShutdownMode.GRACEFUL, 5, TimeUnit.SECONDS);
        double charged = TimeScale.DEFAULT.nanosToSimMinutes(s.endNanos() - s.startNanos());
        StationResources.Snapshot snap = f.station().resources().snapshot();
        boolean pass = s.state() == SessionState.BILLED && f.billing().entryCount() == 1 && snap.peakReservedKw() == 22
                && charged >= 59 && charged < 120 && released(snap);
        return Outcome.of(String.format(Locale.ROOT, "state=%s entries=%d peakReserved=%d kW charged=%.1f sim-min released=%s",
                s.state(), f.billing().entryCount(), snap.peakReservedKw(), charged, released(snap)), pass);
    }

    private static Outcome simultaneousRequests() throws Exception {
        Fixture f = start(Config.pool(4));
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> submitters = new ArrayList<>();
        AtomicInteger rejected = new AtomicInteger();
        for (int i = 1; i <= 8; i++) {
            String id = "R" + i;
            Thread t = new Thread(() -> {
                try {
                    go.await();
                    f.station().submit(req(id, 2, 7.0, ChargingRate.STANDARD));
                } catch (ValidationException | InterruptedException e) {
                    rejected.incrementAndGet();
                }
            }, "submitter-" + i);
            submitters.add(t);
            t.start();
        }
        go.countDown();
        for (Thread t : submitters) {
            t.join();
        }
        f.station().shutdown(ShutdownMode.GRACEFUL, 5, TimeUnit.SECONDS);
        long billed = f.station().sessions().stream().filter(s -> s.state() == SessionState.BILLED).count();
        StationResources.Snapshot snap = f.station().resources().snapshot();
        BillingAdapter.Reconciliation rec = f.billing().reconcile(f.station().sessions());
        boolean pass = rejected.get() == 0 && billed == 8 && snap.peakActiveBays() == 4 && snap.peakReservedKw() <= 60
                && f.billing().entryCount() == 8 && rec.ok() && released(snap);
        return Outcome.of(String.format(Locale.ROOT, "billed=%d peakBays=%d peakPower=%d kW entries=%d reconciled=%s released=%s",
                billed, snap.peakActiveBays(), snap.peakReservedKw(), f.billing().entryCount(), rec.ok(), released(snap)), pass,
                "BILLING " + rec.describe());
    }

    private static Outcome priorityAndFifo() throws Exception {
        Fixture f = start(Config.pool(1).withAgingStep(DISABLED).withStarvationGuard(DISABLED));
        ChargingSession blocker = f.station().submit(req("BLK", 3, 7.0, ChargingRate.STANDARD));
        awaitState(blocker, SessionState.CHARGING, 1000);
        f.station().submit(req("V1", 3, 3.5, ChargingRate.STANDARD));
        f.station().submit(req("S1", 2, 3.5, ChargingRate.STANDARD));
        f.station().submit(req("S2", 2, 3.5, ChargingRate.STANDARD));
        f.station().submit(req("F1", 1, 3.5, ChargingRate.STANDARD));
        f.station().submit(req("V2", 3, 3.5, ChargingRate.STANDARD));
        f.station().shutdown(ShutdownMode.GRACEFUL, 5, TimeUnit.SECONDS);
        List<String> order = f.station().sessions().stream()
                .sorted(Comparator.comparingLong(ChargingSession::startNanos))
                .map(s -> s.request().requestId()).collect(Collectors.toList());
        List<String> expected = List.of("BLK", "F1", "S1", "S2", "V1", "V2");
        return Outcome.of("start order " + order, order.equals(expected));
    }

    private static Outcome validation() throws Exception {
        Fixture f = start(Config.pool(1));
        f.station().submit(req("OK1", 2, 7.0, ChargingRate.STANDARD));
        List<ChargingRequest> invalid = List.of(
                req("Z1", 2, 0.0, ChargingRate.STANDARD),
                req("N1", 2, -5.0, ChargingRate.STANDARD),
                req("NAN", 2, Double.NaN, ChargingRate.STANDARD),
                req("INF", 2, Double.POSITIVE_INFINITY, ChargingRate.FAST),
                req("BIG", 2, 150.0, ChargingRate.FAST),
                req("P0", 0, 7.0, ChargingRate.STANDARD),
                req("P4", 4, 7.0, ChargingRate.STANDARD),
                req("NR", 2, 7.0, null),
                new ChargingRequest("BA", " ", 2, 7.0, ChargingRate.STANDARD),
                new ChargingRequest(null, "ACC-X", 2, 7.0, ChargingRate.STANDARD),
                req("OK1", 2, 7.0, ChargingRate.STANDARD));
        List<String> messages = new ArrayList<>();
        int rejected = 0;
        for (ChargingRequest r : invalid) {
            try {
                f.station().submit(r);
                messages.add("ACCEPTED (unexpected): " + r);
            } catch (ValidationException e) {
                rejected++;
                messages.add("rejected: " + e.getMessage());
            }
        }
        int sessions = f.station().sessions().size();
        f.station().shutdown(ShutdownMode.GRACEFUL, 5, TimeUnit.SECONDS);
        boolean pass = rejected == invalid.size() && sessions == 1;
        return new Outcome(String.format(Locale.ROOT, "%d/%d rejected; sessions created=%d (only OK1)", rejected, invalid.size(), sessions),
                pass, messages);
    }

    private static Outcome noFreeBay() throws Exception {
        Fixture f = start(Config.pool(16));
        for (int i = 1; i <= 5; i++) {
            f.station().submit(req("R" + i, 2, 7.0, ChargingRate.STANDARD));
        }
        f.station().shutdown(ShutdownMode.GRACEFUL, 5, TimeUnit.SECONDS);
        List<ChargingSession> byStart = f.station().sessions().stream()
                .sorted(Comparator.comparingLong(ChargingSession::startNanos)).toList();
        ChargingSession fifth = byStart.get(4);
        long firstEnd = byStart.subList(0, 4).stream().mapToLong(ChargingSession::endNanos).min().orElseThrow();
        StationResources.Snapshot snap = f.station().resources().snapshot();
        long origin = byStart.get(0).startNanos();
        boolean pass = snap.peakActiveBays() == 4 && fifth.startNanos() >= firstEnd && released(snap)
                && byStart.stream().allMatch(s -> s.state() == SessionState.BILLED);
        return Outcome.of(String.format(Locale.ROOT, "peakBays=%d; 5th (%s) started at +%.1f ms, first release at +%.1f ms; all billed=%s",
                snap.peakActiveBays(), fifth.request().requestId(), ms(fifth.startNanos() - origin), ms(firstEnd - origin),
                byStart.stream().allMatch(s -> s.state() == SessionState.BILLED)), pass);
    }

    private static Outcome powerExhausted() throws Exception {
        Fixture f = start(Config.pool(16));
        for (int i = 1; i <= 3; i++) {
            f.station().submit(req("F" + i, 1, 22.0, ChargingRate.FAST));
        }
        f.station().shutdown(ShutdownMode.GRACEFUL, 5, TimeUnit.SECONDS);
        List<ChargingSession> byStart = f.station().sessions().stream()
                .sorted(Comparator.comparingLong(ChargingSession::startNanos)).toList();
        ChargingSession third = byStart.get(2);
        long firstEnd = Math.min(byStart.get(0).endNanos(), byStart.get(1).endNanos());
        StationResources.Snapshot snap = f.station().resources().snapshot();
        long origin = byStart.get(0).startNanos();
        boolean pass = snap.peakReservedKw() == 44 && snap.peakActiveBays() == 2 && third.startNanos() >= firstEnd && released(snap);
        return Outcome.of(String.format(Locale.ROOT, "peakReserved=%d kW peakBays=%d (2 bays idle); 3rd started +%.1f ms, first release +%.1f ms",
                snap.peakReservedKw(), snap.peakActiveBays(), ms(third.startNanos() - origin), ms(firstEnd - origin)), pass);
    }

    private static Outcome cancelBeforeCharging() throws Exception {
        Fixture f = start(Config.pool(1));
        ChargingSession blocker = f.station().submit(req("BLK", 2, 7.0, ChargingRate.STANDARD));
        awaitState(blocker, SessionState.CHARGING, 1000);
        ChargingSession r2 = f.station().submit(req("R2", 1, 7.0, ChargingRate.STANDARD));
        boolean cancelled = f.station().cancel("R2");
        f.station().shutdown(ShutdownMode.GRACEFUL, 5, TimeUnit.SECONDS);
        BillingAdapter.Reconciliation rec = f.billing().reconcile(f.station().sessions());
        boolean pass = cancelled && r2.state() == SessionState.CANCELLED && r2.startNanos() < 0
                && f.billing().totalFor("ACC-R2") == 0.0 && f.billing().entryCount() == 1
                && blocker.state() == SessionState.BILLED && rec.ok() && !rec.cancelledBilled();
        return Outcome.of(String.format(Locale.ROOT, "cancel()=%s R2=%s started=%s R2 billed kWh=%.1f entries=%d blocker=%s",
                cancelled, r2.state(), r2.startNanos() >= 0, f.billing().totalFor("ACC-R2"), f.billing().entryCount(), blocker.state()),
                pass, "BILLING " + rec.describe());
    }

    private static Outcome cancelDuringCharging() throws Exception {
        Fixture f = start(Config.pool(1));
        ChargingSession s = f.station().submit(req("R1", 2, 60.0, ChargingRate.STANDARD));
        awaitState(s, SessionState.CHARGING, 1000);
        TimeUnit.MILLISECONDS.sleep(20);
        long t0 = System.nanoTime();
        boolean requested = f.station().cancel("R1");
        awaitState(s, SessionState.CANCELLED, 1000);
        double reactionMs = ms(System.nanoTime() - t0);
        f.station().shutdown(ShutdownMode.GRACEFUL, 5, TimeUnit.SECONDS);
        StationResources.Snapshot snap = f.station().resources().snapshot();
        boolean pass = requested && s.state() == SessionState.CANCELLED && reactionMs < 100
                && f.billing().entryCount() == 0 && released(snap);
        return Outcome.of(String.format(Locale.ROOT, "cancel()=%s state=%s reaction=%.1f ms entries=%d reserved=%d bays=%d",
                requested, s.state(), reactionMs, f.billing().entryCount(), snap.reservedKw(), snap.activeBays()), pass);
    }

    private static Outcome immediateShutdown() throws Exception {
        Fixture f = start(Config.pool(4));
        List<ChargingSession> sessions = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            sessions.add(f.station().submit(req("R" + i, 2, 60.0, ChargingRate.STANDARD)));
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (sessions.stream().filter(s -> s.state() == SessionState.CHARGING).count() < 4 && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(1);
        }
        long charging = sessions.stream().filter(s -> s.state() == SessionState.CHARGING).count();
        long t0 = System.nanoTime();
        boolean terminated = f.station().shutdown(ShutdownMode.IMMEDIATE, 5, TimeUnit.SECONDS);
        double shutdownMs = ms(System.nanoTime() - t0);
        StationResources.Snapshot snap = f.station().resources().snapshot();
        long cancelled = sessions.stream().filter(s -> s.state() == SessionState.CANCELLED).count();
        boolean pass = charging == 4 && terminated && f.station().isTerminated() && cancelled == 6
                && f.billing().entryCount() == 0 && released(snap) && shutdownMs < 1000;
        return Outcome.of(String.format(Locale.ROOT,
                "charging at shutdown=%d; terminated=%s in %.1f ms; cancelled=%d/6; entries=%d; reserved=%d kW; bays=%d",
                charging, f.station().isTerminated(), shutdownMs, cancelled, f.billing().entryCount(), snap.reservedKw(), snap.activeBays()), pass);
    }

    private static Outcome gracefulShutdown() throws Exception {
        Fixture f = start(Config.pool(4));
        for (int i = 1; i <= 6; i++) {
            f.station().submit(req("R" + i, 2, 7.0, ChargingRate.STANDARD));
        }
        boolean terminated = f.station().shutdown(ShutdownMode.GRACEFUL, 5, TimeUnit.SECONDS);
        long billed = f.station().sessions().stream().filter(s -> s.state() == SessionState.BILLED).count();
        StationResources.Snapshot snap = f.station().resources().snapshot();
        boolean pass = terminated && f.station().isTerminated() && billed == 6 && f.billing().entryCount() == 6 && released(snap);
        return Outcome.of(String.format(Locale.ROOT, "terminated=%s billed=%d/6 entries=%d reserved=%d bays=%d",
                f.station().isTerminated(), billed, f.billing().entryCount(), snap.reservedKw(), snap.activeBays()), pass);
    }

    private static ChargingSession completedSession(String id, String account, double kwh, long seq) {
        ChargingSession s = new ChargingSession(new ChargingRequest(id, account, 2, kwh, ChargingRate.STANDARD), seq, System.nanoTime());
        s.markCharging(System.nanoTime(), "test");
        s.finishCharging(System.nanoTime());
        return s;
    }

    private static Outcome concurrentBilling() throws Exception {
        smartcharge.core.Log.setEnabled(false);
        try {
            BillingAdapter billing = new BillingAdapter(new LegacyBillingService());
            int threads = 16;
            int perThread = 500;
            ChargingSession shared = completedSession("SHARED", "ACC-SHARED", 1.0, 0);
            Map<String, Double> expected = new HashMap<>();
            List<List<ChargingSession>> work = new ArrayList<>();
            long seq = 1;
            for (int t = 0; t < threads; t++) {
                List<ChargingSession> batch = new ArrayList<>();
                for (int i = 0; i < perThread; i++) {
                    String account = "ACC-" + (i % 10);
                    double kwh = 1.0 + (i % 7);
                    batch.add(completedSession("T" + t + "-" + i, account, kwh, seq++));
                    expected.merge(account, kwh, Double::sum);
                }
                work.add(batch);
            }
            expected.put("ACC-SHARED", 1.0);
            CountDownLatch go = new CountDownLatch(1);
            List<Thread> workers = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                List<ChargingSession> batch = work.get(t);
                Thread th = new Thread(() -> {
                    try {
                        go.await();
                    } catch (InterruptedException e) {
                        return;
                    }
                    for (ChargingSession s : batch) {
                        billing.bill(s);
                    }
                    billing.bill(shared);
                }, "biller-" + t);
                workers.add(th);
                th.start();
            }
            go.countDown();
            for (Thread th : workers) {
                th.join();
            }
            boolean totalsOk = expected.entrySet().stream().allMatch(e -> Math.abs(billing.totalFor(e.getKey()) - e.getValue()) < 1e-6);
            int expectedEntries = threads * perThread + 1;
            boolean pass = billing.entryCount() == expectedEntries && billing.duplicatesIgnored() == threads - 1 && totalsOk;
            return Outcome.of(String.format(Locale.ROOT, "entries=%d (expected %d) duplicatesIgnored=%d (expected %d) totalsExact=%s",
                    billing.entryCount(), expectedEntries, billing.duplicatesIgnored(), threads - 1, totalsOk), pass);
        } finally {
            smartcharge.core.Log.setEnabled(true);
        }
    }

    private static Outcome unsafeLegacyObservation() throws Exception {
        List<String> trials = new ArrayList<>();
        int corruptedTrials = 0;
        for (int trial = 1; trial <= 5; trial++) {
            LegacyBillingService legacy = new LegacyBillingService();
            int threads = 16;
            int perThread = 500;
            AtomicInteger exceptions = new AtomicInteger();
            CountDownLatch go = new CountDownLatch(1);
            List<Thread> workers = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                Thread th = new Thread(() -> {
                    try {
                        go.await();
                    } catch (InterruptedException e) {
                        return;
                    }
                    for (int i = 0; i < perThread; i++) {
                        try {
                            legacy.recordSession("ACC-" + (i % 10), 1.0, 0, 1);
                        } catch (RuntimeException e) {
                            exceptions.incrementAndGet();
                        }
                    }
                }, "unsafe-" + t);
                workers.add(th);
                th.start();
            }
            go.countDown();
            for (Thread th : workers) {
                th.join();
            }
            double total = 0;
            for (int a = 0; a < 10; a++) {
                total += legacy.getTotal("ACC-" + a);
            }
            int expected = threads * perThread;
            boolean corrupted = legacy.getEntryCount() != expected || Math.abs(total - expected) > 1e-6 || exceptions.get() > 0;
            if (corrupted) {
                corruptedTrials++;
            }
            trials.add(String.format(Locale.ROOT, "trial %d: entries=%d/%d summedTotals=%.0f/%d exceptions=%d corrupted=%s",
                    trial, legacy.getEntryCount(), expected, total, expected, exceptions.get(), corrupted));
        }
        return new Outcome(corruptedTrials + "/5 trials showed lost entries, lost total updates or exceptions", true, trials);
    }

    private static Outcome releaseAfterException() throws Exception {
        LegacyBillingService failing = new LegacyBillingService() {
            @Override
            public void recordSession(String accountId, double kWh, long startMillis, long endMillis) {
                if (accountId.equals("ACC-FAIL")) {
                    throw new IllegalStateException("simulated legacy billing outage");
                }
                super.recordSession(accountId, kWh, startMillis, endMillis);
            }
        };
        Fixture f = start(Config.pool(2), failing);
        ChargingSession bad = f.station().submit(new ChargingRequest("RF", "ACC-FAIL", 2, 7.0, ChargingRate.STANDARD));
        ChargingSession good = f.station().submit(req("RG", 2, 7.0, ChargingRate.STANDARD));
        f.station().shutdown(ShutdownMode.GRACEFUL, 5, TimeUnit.SECONDS);
        StationResources.Snapshot snap = f.station().resources().snapshot();
        BillingAdapter.Reconciliation rec = f.billing().reconcile(f.station().sessions());
        boolean pass = bad.state() == SessionState.BILLING_FAILED && good.state() == SessionState.BILLED
                && f.billing().entryCount() == 1 && released(snap) && rec.ok();
        return Outcome.of(String.format(Locale.ROOT, "RF=%s RG=%s entries=%d reserved=%d kW bays=%d reconciled=%s",
                bad.state(), good.state(), f.billing().entryCount(), snap.reservedKw(), snap.activeBays(), rec.ok()), pass,
                "BILLING " + rec.describe());
    }

    private static Outcome duplicateBilling() {
        BillingAdapter billing = new BillingAdapter(new LegacyBillingService());
        ChargingSession s = completedSession("R1", "ACC-R1", 7.0, 1);
        BillingAdapter.Result first = billing.bill(s);
        BillingAdapter.Result second = billing.bill(s);
        boolean pass = first == BillingAdapter.Result.BILLED && second == BillingAdapter.Result.DUPLICATE_IGNORED
                && billing.entryCount() == 1 && billing.totalFor("ACC-R1") == 7.0;
        return Outcome.of(String.format(Locale.ROOT, "first=%s second=%s entries=%d total=%.1f",
                first, second, billing.entryCount(), billing.totalFor("ACC-R1")), pass);
    }

    private static Outcome aging() {
        PriorityScheduler.Allocator all = s -> true;
        PriorityScheduler early = new PriorityScheduler(30, Long.MAX_VALUE / 4);
        early.enqueue(req("V", 3, 7.0, ChargingRate.STANDARD), 0);
        early.enqueue(req("F", 1, 7.0, ChargingRate.STANDARD), 5);
        String atT20 = early.selectNext(20, all).request().requestId();
        PriorityScheduler late = new PriorityScheduler(30, Long.MAX_VALUE / 4);
        late.enqueue(req("V", 3, 7.0, ChargingRate.STANDARD), 0);
        late.enqueue(req("F", 1, 7.0, ChargingRate.STANDARD), 5);
        String atT65 = late.selectNext(65, all).request().requestId();
        return Outcome.of("selected at t=20: " + atT20 + "; selected at t=65: " + atT65, atT20.equals("F") && atT65.equals("V"));
    }

    private static Outcome starvationGuard() {
        AtomicBoolean fastFits = new AtomicBoolean(false);
        PriorityScheduler.Allocator allocator = s -> s.request().rate() == ChargingRate.STANDARD || fastFits.get();
        PriorityScheduler scheduler = new PriorityScheduler(Long.MAX_VALUE / 4, 30);
        scheduler.enqueue(req("F", 1, 22.0, ChargingRate.FAST), 0);
        scheduler.enqueue(req("S1", 2, 7.0, ChargingRate.STANDARD), 1);
        scheduler.enqueue(req("S2", 2, 7.0, ChargingRate.STANDARD), 2);
        List<String> picks = new ArrayList<>();
        picks.add(name(scheduler.selectNext(10, allocator)));
        picks.add(name(scheduler.selectNext(39, allocator)));
        scheduler.enqueue(req("S3", 2, 7.0, ChargingRate.STANDARD), 39);
        picks.add(name(scheduler.selectNext(40, allocator)));
        fastFits.set(true);
        picks.add(name(scheduler.selectNext(41, allocator)));
        picks.add(name(scheduler.selectNext(42, allocator)));
        List<String> expected = List.of("S1", "S2", "none", "F", "S3");
        return Outcome.of("selections at t=10,39,40,41,42: " + picks, picks.equals(expected));
    }

    private static String name(ChargingSession s) {
        return s == null ? "none" : s.request().requestId();
    }

    private static Outcome burstThreadPerRequest() throws Exception {
        smartcharge.core.Log.setEnabled(false);
        try {
            TimeScale fast = new TimeScale(0.05);
            Fixture f = start(Config.perRequestThreads().withTimeScale(fast));
            List<WorkloadItem> workload = WorkloadGenerator.generate(200, WorkloadGenerator.DEFAULT_SEED);
            for (WorkloadItem item : workload) {
                f.station().submit(item.request());
            }
            boolean terminated = f.station().shutdown(ShutdownMode.GRACEFUL, 60, TimeUnit.SECONDS);
            long billed = f.station().sessions().stream().filter(s -> s.state() == SessionState.BILLED).count();
            StationResources.Snapshot snap = f.station().resources().snapshot();
            BillingAdapter.Reconciliation rec = f.billing().reconcile(f.station().sessions());
            boolean pass = terminated && f.station().isTerminated() && billed == 200 && snap.peakActiveBays() <= 4
                    && snap.peakReservedKw() <= 60 && rec.ok() && released(snap);
            return Outcome.of(String.format(Locale.ROOT, "billed=%d peakBays=%d peakPower=%d kW reconciled=%s threadsEnded=%s released=%s",
                    billed, snap.peakActiveBays(), snap.peakReservedKw(), rec.ok(), f.station().isTerminated(), released(snap)), pass,
                    "BILLING " + rec.describe());
        } finally {
            smartcharge.core.Log.setEnabled(true);
        }
    }

    private static Outcome deadlockProne() throws Exception {
        DeadlockDemo.Result r = DeadlockDemo.runDeadlockProne(1000);
        return new Outcome(r.allFinished() ? "threads finished (no deadlock reproduced)" : "deadlock detected by watchdog; threads blocked",
                !r.allFinished(), r.evidence());
    }

    private static Outcome deadlockCorrected() throws Exception {
        DeadlockDemo.Result r = DeadlockDemo.runCorrected(2000);
        return new Outcome(r.allFinished() ? "all threads finished; no deadlock" : "did not finish", r.allFinished(), r.evidence());
    }
}

package smartcharge.app;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.ToDoubleFunction;
import smartcharge.billing.BillingAdapter;
import smartcharge.billing.LegacyBillingService;
import smartcharge.core.ChargingStation;
import smartcharge.core.Log;
import smartcharge.core.StationResources;
import smartcharge.core.TimeScale;
import smartcharge.workload.WorkloadDriver;
import smartcharge.workload.WorkloadGenerator;
import smartcharge.workload.WorkloadItem;

/**
 * Worker-pool experiment. Every configuration replays the identical seeded workload (verified by
 * fingerprint) at the same time scale. Runs are interleaved round-robin (run 1 of every config,
 * then run 2, ...) so slow drift on the host affects all configurations alike. One short warm-up
 * run is executed first and discarded to reduce JIT and class-loading bias.
 *
 * Usage: BenchmarkRunner [--runs N] [--requests N] [--seed N] [--scale MS_PER_SIM_MIN] [--out DIR]
 */
public final class BenchmarkRunner {

    private record Row(RunMetrics metrics, int peakThreads, String fingerprint) {
    }

    private BenchmarkRunner() {
    }

    public static void main(String[] args) throws Exception {
        Thread.currentThread().setName("bench");
        int runs = 3;
        int requests = WorkloadGenerator.DEFAULT_COUNT;
        long seed = WorkloadGenerator.DEFAULT_SEED;
        double scaleMs = TimeScale.DEFAULT.realMillisPerSimMinute();
        Path out = Path.of("results");
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--runs" -> runs = Integer.parseInt(args[++i]);
                case "--requests" -> requests = Integer.parseInt(args[++i]);
                case "--seed" -> seed = Long.parseLong(args[++i]);
                case "--scale" -> scaleMs = Double.parseDouble(args[++i]);
                case "--out" -> out = Path.of(args[++i]);
                default -> throw new IllegalArgumentException("unknown option: " + args[i]);
            }
        }
        if (runs < 1 || requests < 1) {
            throw new IllegalArgumentException("runs and requests must be positive");
        }
        Files.createDirectories(out);
        TimeScale scale = new TimeScale(scaleMs);
        List<WorkloadItem> workload = WorkloadGenerator.generate(requests, seed);
        String fingerprint = WorkloadGenerator.fingerprint(workload);
        List<ChargingStation.Config> configs = List.of(
                ChargingStation.Config.pool(1), ChargingStation.Config.pool(4),
                ChargingStation.Config.pool(16), ChargingStation.Config.perRequestThreads());

        System.out.printf(Locale.ROOT, "Benchmark: %d requests, seed=%d, fingerprint=%s, scale=%.3f ms/sim-min, runs=%d, java=%s, cpus=%d, assertions=%s%n",
                requests, seed, fingerprint, scaleMs, runs, System.getProperty("java.version"),
                Runtime.getRuntime().availableProcessors(), Main.assertionsEnabled());

        Log.setEnabled(false);
        runOnce(ChargingStation.Config.pool(4).withTimeScale(scale), 0, WorkloadGenerator.generate(40, seed), scale);
        System.out.println("Warm-up run (pool-4, 40 requests) done and discarded");

        List<Row> rows = new ArrayList<>();
        boolean violation = false;
        for (int run = 1; run <= runs; run++) {
            for (ChargingStation.Config base : configs) {
                ChargingStation.Config config = base.withTimeScale(scale);
                List<WorkloadItem> replay = WorkloadGenerator.generate(requests, seed);
                String fp = WorkloadGenerator.fingerprint(replay);
                Row row = runOnce(config, run, replay, scale);
                row = new Row(row.metrics(), row.peakThreads(), fp);
                rows.add(row);
                RunMetrics m = row.metrics();
                boolean ok = m.constraintsOk() && m.reconciled() && m.completed() == requests && fp.equals(fingerprint);
                violation |= !ok;
                System.out.printf(Locale.ROOT, "run %d %-18s completion=%8.1f sim-min (%6.0f ms) avgWait=%7.1f maxWait=%7.1f peakPower=%2d kW peakBays=%d completed=%d peakThreads=%d %s%n",
                        run, m.config(), m.completionSimMin(), m.completionRealMs(), m.avgWaitSimMin(), m.maxWaitSimMin(),
                        m.peakReservedKw(), m.peakActiveBays(), m.completed(), row.peakThreads(), ok ? "OK" : "VIOLATION");
            }
        }
        Log.setEnabled(true);

        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        StringBuilder csv = new StringBuilder(RunMetrics.CSV_HEADER).append(",peak_live_threads,workload_fingerprint\n");
        rows.forEach(r -> csv.append(r.metrics().toCsv()).append(',').append(r.peakThreads()).append(',').append(r.fingerprint()).append('\n'));
        Path csvPath = out.resolve("benchmark-runs-" + stamp + ".csv");
        Files.writeString(csvPath, csv.toString());
        String summary = summary(rows, configs, requests, seed, fingerprint, scaleMs, runs);
        Path summaryPath = out.resolve("benchmark-summary-" + stamp + ".md");
        Files.writeString(summaryPath, summary);
        System.out.println();
        System.out.println(summary);
        System.out.println("Raw runs: " + csvPath + "\nSummary:  " + summaryPath);
        if (violation) {
            System.out.println("CONSTRAINT OR RECONCILIATION VIOLATION DETECTED");
            System.exit(1);
        }
    }

    private static Row runOnce(ChargingStation.Config config, int run, List<WorkloadItem> workload, TimeScale scale)
            throws InterruptedException {
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        threads.resetPeakThreadCount();
        BillingAdapter billing = new BillingAdapter(new LegacyBillingService());
        ChargingStation station = new ChargingStation(config, new StationResources(), billing);
        station.start();
        long origin = WorkloadDriver.replay(station, workload, scale);
        if (!station.shutdown(ChargingStation.ShutdownMode.GRACEFUL, 30, TimeUnit.MINUTES)) {
            throw new IllegalStateException(config.label() + " run " + run + " did not terminate");
        }
        RunMetrics m = RunMetrics.collect(config.label(), run, station.sessions(), origin, station.resources().snapshot(),
                billing.reconcile(station.sessions()), scale, station.scheduler().guardActivations());
        return new Row(m, threads.getPeakThreadCount(), "");
    }

    private static String summary(List<Row> rows, List<ChargingStation.Config> configs, int requests, long seed,
                                  String fingerprint, double scaleMs, int runs) {
        Map<String, List<Row>> byConfig = new LinkedHashMap<>();
        configs.forEach(c -> byConfig.put(c.label(), new ArrayList<>()));
        rows.forEach(r -> byConfig.get(r.metrics().config()).add(r));
        StringBuilder sb = new StringBuilder();
        sb.append("# Benchmark summary (median of ").append(runs).append(" runs per configuration)\n\n");
        sb.append(String.format(Locale.ROOT, "Workload: %d requests, seed %d, fingerprint `%s`; time scale %.3f ms per simulated minute; "
                        + "Java %s; %d logical CPUs; generated %s.%n%n", requests, seed, fingerprint, scaleMs,
                System.getProperty("java.version"), Runtime.getRuntime().availableProcessors(),
                LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)));
        sb.append("| Configuration | Total completion (sim-h) | Total completion (real s) | Avg wait (sim-min) | Max wait (sim-min) "
                + "| Peak reserved (kW) | Peak bays | Completed | Avg wait P1 / P2 / P3 (sim-min) | Peak live threads "
                + "| Guard activations | Avg session overrun (sim-min) |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (Map.Entry<String, List<Row>> e : byConfig.entrySet()) {
            List<RunMetrics> ms = e.getValue().stream().map(Row::metrics).toList();
            sb.append(String.format(Locale.ROOT, "| %s | %.1f | %.2f | %.1f | %.1f | %.0f (max %d) | %.0f | %.0f | %.0f / %.0f / %.0f | %.0f | %.0f | %.3f |%n",
                    e.getKey(), median(ms, m -> m.completionSimMin() / 60.0), median(ms, m -> m.completionRealMs() / 1000.0),
                    median(ms, RunMetrics::avgWaitSimMin), median(ms, RunMetrics::maxWaitSimMin),
                    median(ms, RunMetrics::peakReservedKw), ms.stream().mapToInt(RunMetrics::peakReservedKw).max().orElse(0),
                    median(ms, RunMetrics::peakActiveBays), median(ms, RunMetrics::completed),
                    median(ms, RunMetrics::avgWaitP1), median(ms, RunMetrics::avgWaitP2), median(ms, RunMetrics::avgWaitP3),
                    medianInt(e.getValue().stream().mapToInt(Row::peakThreads).toArray()),
                    median(ms, RunMetrics::guardActivations), median(ms, RunMetrics::avgOverrunSimMin)));
        }
        sb.append("\nPer-run spread (min - max):\n\n");
        for (Map.Entry<String, List<Row>> e : byConfig.entrySet()) {
            List<RunMetrics> ms = e.getValue().stream().map(Row::metrics).toList();
            sb.append(String.format(Locale.ROOT, "- %s: completion %.1f - %.1f sim-h, avg wait %.1f - %.1f sim-min, max wait %.1f - %.1f sim-min%n",
                    e.getKey(), min(ms, m -> m.completionSimMin() / 60.0), max(ms, m -> m.completionSimMin() / 60.0),
                    min(ms, RunMetrics::avgWaitSimMin), max(ms, RunMetrics::avgWaitSimMin),
                    min(ms, RunMetrics::maxWaitSimMin), max(ms, RunMetrics::maxWaitSimMin)));
        }
        boolean allOk = rows.stream().allMatch(r -> r.metrics().constraintsOk() && r.metrics().reconciled()
                && r.metrics().completed() == requests && r.fingerprint().equals(fingerprint));
        sb.append("\nConstraint check across all ").append(rows.size()).append(" runs (peak bays <= 4, peak power <= 60 kW, ")
                .append("all requests billed, billing reconciled, identical workload fingerprint): ")
                .append(allOk ? "**PASSED**" : "**FAILED**").append('\n');
        return sb.toString();
    }

    private static double median(List<RunMetrics> ms, ToDoubleFunction<RunMetrics> f) {
        double[] v = ms.stream().mapToDouble(f).sorted().toArray();
        int n = v.length;
        return n % 2 == 1 ? v[n / 2] : (v[n / 2 - 1] + v[n / 2]) / 2.0;
    }

    private static double medianInt(int[] values) {
        int[] v = Arrays.stream(values).sorted().toArray();
        int n = v.length;
        return n % 2 == 1 ? v[n / 2] : (v[n / 2 - 1] + v[n / 2]) / 2.0;
    }

    private static double min(List<RunMetrics> ms, ToDoubleFunction<RunMetrics> f) {
        return ms.stream().mapToDouble(f).min().orElse(0);
    }

    private static double max(List<RunMetrics> ms, ToDoubleFunction<RunMetrics> f) {
        return ms.stream().mapToDouble(f).max().orElse(0);
    }
}

package smartcharge.app;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
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
 * Normal execution: replays the seeded workload through one station configuration with full
 * logging and periodic status lines, then shuts down gracefully and reconciles billing.
 *
 * Usage: Main [--workers N | --thread-per-request] [--requests N] [--seed N] [--scale MS_PER_SIM_MIN]
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        Thread.currentThread().setName("main");
        Options opts = Options.parse(args);
        TimeScale scale = new TimeScale(opts.scale);
        ChargingStation.Config config = (opts.workers == 0 ? ChargingStation.Config.perRequestThreads()
                : ChargingStation.Config.pool(opts.workers)).withTimeScale(scale);

        List<WorkloadItem> workload = WorkloadGenerator.generate(opts.requests, opts.seed);
        Log.info("Workload: %d requests, seed=%d, fingerprint=%s, assertions=%s",
                workload.size(), opts.seed, WorkloadGenerator.fingerprint(workload), assertionsEnabled());

        BillingAdapter billing = new BillingAdapter(new LegacyBillingService());
        ChargingStation station = new ChargingStation(config, new StationResources(), billing);
        station.start();

        ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "monitor");
            t.setDaemon(true);
            return t;
        });
        long statusPeriodNanos = scale.simMinutesToNanos(240);
        monitor.scheduleAtFixedRate(() -> Log.info("STATUS %s", station.status().describe()),
                statusPeriodNanos, statusPeriodNanos, TimeUnit.NANOSECONDS);

        long origin = WorkloadDriver.replay(station, workload, scale);
        Log.info("All %d requests submitted; draining queue", workload.size());
        boolean terminated = station.shutdown(ChargingStation.ShutdownMode.GRACEFUL, 10, TimeUnit.MINUTES);
        monitor.shutdownNow();

        BillingAdapter.Reconciliation rec = billing.reconcile(station.sessions());
        RunMetrics metrics = RunMetrics.collect(config.label(), 1, station.sessions(), origin,
                station.resources().snapshot(), rec, scale, station.scheduler().guardActivations());
        Log.info("FINAL STATUS %s", station.status().describe());
        Log.info("BILLING %s", rec.describe());
        Log.info("SUMMARY%n%s", metrics.describe());
        if (!terminated || !rec.ok() || !metrics.constraintsOk()) {
            System.exit(1);
        }
    }

    static boolean assertionsEnabled() {
        boolean enabled = false;
        assert enabled = true;
        return enabled;
    }

    private static final class Options {
        int workers = 4;
        int requests = WorkloadGenerator.DEFAULT_COUNT;
        long seed = WorkloadGenerator.DEFAULT_SEED;
        double scale = TimeScale.DEFAULT.realMillisPerSimMinute();

        static Options parse(String[] args) {
            Options o = new Options();
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--workers" -> o.workers = Integer.parseInt(args[++i]);
                    case "--thread-per-request" -> o.workers = 0;
                    case "--requests" -> o.requests = Integer.parseInt(args[++i]);
                    case "--seed" -> o.seed = Long.parseLong(args[++i]);
                    case "--scale" -> o.scale = Double.parseDouble(args[++i]);
                    default -> throw new IllegalArgumentException("unknown option: " + args[i]);
                }
            }
            return o;
        }
    }
}

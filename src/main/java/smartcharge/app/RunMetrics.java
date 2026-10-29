/*
 * SmartCharge - CO3408 Advanced Software Modelling
 * Name: Ruchira Nagare
 * Student ID: G21289292
 */
package smartcharge.app;

import java.util.Collection;
import java.util.Locale;
import smartcharge.billing.BillingAdapter;
import smartcharge.core.ChargingSession;
import smartcharge.core.StationResources;
import smartcharge.core.TimeScale;
import smartcharge.model.SessionState;

/**
 * Metrics for one run. Definitions (used identically for every configuration):
 * - waiting time: session start - request arrival (submission), over sessions that started;
 * - total completion time: run origin (first arrival due) -> last session end;
 * - peak reserved power: high-water mark of reserved kW, tracked under the power lock.
 */
public record RunMetrics(String config, int run, int requests, int completed, int cancelled, int billingFailed,
                         double completionSimMin, double completionRealMs, double avgWaitSimMin, double maxWaitSimMin,
                         double avgWaitP1, double avgWaitP2, double avgWaitP3, int peakReservedKw, int peakActiveBays,
                         int powerCapacityKw, int totalBays, boolean reconciled, long guardActivations,
                         double avgOverrunSimMin) {

    public static final String CSV_HEADER = "config,run,requests,completed,cancelled,billing_failed,"
            + "completion_sim_min,completion_real_ms,avg_wait_sim_min,max_wait_sim_min,"
            + "avg_wait_p1,avg_wait_p2,avg_wait_p3,peak_reserved_kw,peak_active_bays,reconciled,guard_activations,constraints_ok,"
            + "avg_overrun_sim_min";

    public static RunMetrics collect(String config, int run, Collection<ChargingSession> sessions, long originNanos,
                                     StationResources.Snapshot resources, BillingAdapter.Reconciliation reconciliation,
                                     TimeScale scale, long guardActivations) {
        int completed = 0;
        int cancelled = 0;
        int failed = 0;
        long lastEnd = originNanos;
        long waitSum = 0;
        long waitMax = 0;
        int started = 0;
        long[] prioritySum = new long[3];
        int[] priorityCount = new int[3];
        double overrunSum = 0;
        for (ChargingSession s : sessions) {
            SessionState st = s.state();
            if (st == SessionState.BILLED) {
                completed++;
            } else if (st == SessionState.CANCELLED) {
                cancelled++;
            } else if (st == SessionState.BILLING_FAILED) {
                failed++;
            }
            if (st == SessionState.BILLED || st == SessionState.BILLING_FAILED) {
                overrunSum += scale.nanosToSimMinutes(s.endNanos() - s.startNanos()) - s.request().durationSimMinutes();
            }
            lastEnd = Math.max(lastEnd, s.endNanos());
            long wait = s.waitNanos();
            if (wait >= 0) {
                started++;
                waitSum += wait;
                waitMax = Math.max(waitMax, wait);
                int p = s.priority().level() - 1;
                prioritySum[p] += wait;
                priorityCount[p]++;
            }
        }
        long elapsed = lastEnd - originNanos;
        return new RunMetrics(config, run, sessions.size(), completed, cancelled, failed,
                scale.nanosToSimMinutes(elapsed), elapsed / 1e6,
                started == 0 ? 0 : scale.nanosToSimMinutes(waitSum / started), scale.nanosToSimMinutes(waitMax),
                avg(prioritySum[0], priorityCount[0], scale), avg(prioritySum[1], priorityCount[1], scale),
                avg(prioritySum[2], priorityCount[2], scale),
                resources.peakReservedKw(), resources.peakActiveBays(), resources.powerCapacityKw(), resources.totalBays(),
                reconciliation.ok(), guardActivations, completed + failed == 0 ? 0 : overrunSum / (completed + failed));
    }

    private static double avg(long sum, int count, TimeScale scale) {
        return count == 0 ? 0 : scale.nanosToSimMinutes(sum / count);
    }

    public boolean constraintsOk() {
        return peakReservedKw <= powerCapacityKw && peakActiveBays <= totalBays;
    }

    public String toCsv() {
        return String.format(Locale.ROOT, "%s,%d,%d,%d,%d,%d,%.1f,%.1f,%.1f,%.1f,%.1f,%.1f,%.1f,%d,%d,%s,%d,%s,%.3f",
                config, run, requests, completed, cancelled, billingFailed, completionSimMin, completionRealMs,
                avgWaitSimMin, maxWaitSimMin, avgWaitP1, avgWaitP2, avgWaitP3, peakReservedKw, peakActiveBays,
                reconciled, guardActivations, constraintsOk(), avgOverrunSimMin);
    }

    public String describe() {
        return String.format(Locale.ROOT,
                "config=%s run=%d requests=%d completed=%d cancelled=%d billingFailed=%d%n"
                        + "  total completion time = %.1f sim-min (%.2f sim-h, %.0f real ms)%n"
                        + "  waiting time avg = %.1f sim-min, max = %.1f sim-min (avg by priority P1=%.1f P2=%.1f P3=%.1f)%n"
                        + "  peak reserved power = %d/%d kW, peak active bays = %d/%d, constraints ok = %s%n"
                        + "  billing reconciled = %s, starvation-guard activations = %d, avg session overrun = %.2f sim-min",
                config, run, requests, completed, cancelled, billingFailed, completionSimMin, completionSimMin / 60.0,
                completionRealMs, avgWaitSimMin, maxWaitSimMin, avgWaitP1, avgWaitP2, avgWaitP3, peakReservedKw,
                powerCapacityKw, peakActiveBays, totalBays, constraintsOk(), reconciled, guardActivations, avgOverrunSimMin);
    }
}

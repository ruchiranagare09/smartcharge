/*
 * SmartCharge - CO3408 Advanced Software Modelling
 * Name: Ruchira Nagare
 * Student ID: G21289292
 */
package smartcharge.workload;

import java.util.List;
import java.util.concurrent.TimeUnit;
import smartcharge.core.ChargingStation;
import smartcharge.core.Log;
import smartcharge.core.TimeScale;
import smartcharge.core.ValidationException;

/**
 * Replays a workload against a station on a dedicated "intake" thread, submitting each request
 * at its arrival offset. Deadlines are absolute, so sleep jitter does not accumulate.
 */
public final class WorkloadDriver {

    private WorkloadDriver() {
    }

    /** Returns the System.nanoTime() at which the first request was due. */
    public static long replay(ChargingStation station, List<WorkloadItem> items, TimeScale scale) throws InterruptedException {
        long[] origin = new long[1];
        Throwable[] failure = new Throwable[1];
        Thread intake = new Thread(() -> {
            origin[0] = System.nanoTime();
            try {
                for (WorkloadItem item : items) {
                    long due = origin[0] + scale.simMinutesToNanos(item.arrivalSimMinutes());
                    long wait;
                    while ((wait = due - System.nanoTime()) > 0) {
                        TimeUnit.NANOSECONDS.sleep(wait);
                    }
                    try {
                        station.submit(item.request());
                    } catch (ValidationException e) {
                        Log.info("intake skipped invalid request: %s", e.getMessage());
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                failure[0] = e;
            }
        }, "intake");
        intake.start();
        intake.join();
        if (failure[0] != null) {
            throw new IllegalStateException("intake failed", failure[0]);
        }
        return origin[0];
    }
}

/*
 * SmartCharge - CO3408 Advanced Software Modelling
 * Name: Ruchira Nagare
 * Student ID: G21289292
 */
package smartcharge.workload;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import smartcharge.model.ChargingRate;
import smartcharge.model.ChargingRequest;

/**
 * Deterministic workload: the same (count, seed) always yields the same requests and arrival
 * times, so every benchmark configuration runs the identical workload (assumption A-09).
 */
public final class WorkloadGenerator {

    public static final long DEFAULT_SEED = 42L;
    public static final int DEFAULT_COUNT = 200;
    public static final double MEAN_INTER_ARRIVAL_SIM_MINUTES = 35.0;
    public static final int ACCOUNTS = 40;

    private WorkloadGenerator() {
    }

    public static List<WorkloadItem> generate(int count, long seed) {
        if (count < 1) {
            throw new IllegalArgumentException("count must be positive");
        }
        Random rnd = new Random(seed);
        List<WorkloadItem> items = new ArrayList<>(count);
        double arrival = 0.0;
        for (int i = 1; i <= count; i++) {
            arrival += -Math.log(1.0 - rnd.nextDouble()) * MEAN_INTER_ARRIVAL_SIM_MINUTES;
            double roll = rnd.nextDouble();
            int priority = roll < 0.20 ? 1 : roll < 0.50 ? 2 : 3;
            ChargingRate rate = rnd.nextDouble() < 0.35 ? ChargingRate.FAST : ChargingRate.STANDARD;
            double kwh = Math.round((5.0 + rnd.nextDouble() * 35.0) * 2.0) / 2.0;
            String account = String.format(Locale.ROOT, "ACC-%02d", 1 + rnd.nextInt(ACCOUNTS));
            String id = String.format(Locale.ROOT, "R%04d", i);
            items.add(new WorkloadItem(Math.round(arrival * 10.0) / 10.0, new ChargingRequest(id, account, priority, kwh, rate)));
        }
        return items;
    }

    /** SHA-256 over a canonical text form; identical fingerprints prove identical workloads. */
    public static String fingerprint(List<WorkloadItem> items) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (WorkloadItem item : items) {
                ChargingRequest r = item.request();
                String line = String.format(Locale.ROOT, "%.1f|%s|%s|%d|%.1f|%s%n", item.arrivalSimMinutes(),
                        r.requestId(), r.accountId(), r.priority(), r.energyKwh(), r.rate());
                md.update(line.getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(md.digest()).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

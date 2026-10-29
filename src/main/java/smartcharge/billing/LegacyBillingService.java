/*
 * SmartCharge - CO3408 Advanced Software Modelling
 * Name: Ruchira Nagare
 * Student ID: G21289292
 */
package smartcharge.billing;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Stand-in for the brownfield billing component. Treated as immutable and NOT thread-safe:
 * it uses plain unsynchronised collections, exactly like a single-threaded legacy service.
 * Callers must serialise access themselves (see {@link BillingAdapter}). Do not modify.
 */
public class LegacyBillingService {

    private final List<String> entries = new ArrayList<>();
    private final Map<String, Double> totals = new HashMap<>();

    public void recordSession(String accountId, double kWh, long startMillis, long endMillis) {
        entries.add(accountId + "|" + kWh + "|" + startMillis + "|" + endMillis);
        Double previous = totals.get(accountId);
        totals.put(accountId, previous == null ? kWh : previous + kWh);
    }

    public double getTotal(String accountId) {
        Double total = totals.get(accountId);
        return total == null ? 0.0 : total;
    }

    public int getEntryCount() {
        return entries.size();
    }
}

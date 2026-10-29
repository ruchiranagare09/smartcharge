/*
 * SmartCharge - CO3408 Advanced Software Modelling
 * Name: <STUDENT NAME>
 * Student ID: <STUDENT ID>
 */
package smartcharge.billing;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import smartcharge.core.ChargingSession;
import smartcharge.core.Log;
import smartcharge.model.SessionState;

/**
 * Serialises every call into the non-thread-safe {@link LegacyBillingService} behind one lock
 * and enforces exactly-once billing per request ID. The lock is never held while acquiring any
 * other station lock, so it cannot take part in a lock-ordering cycle.
 */
public final class BillingAdapter {

    public enum Result { BILLED, DUPLICATE_IGNORED, FAILED }

    /** Outcome of reconciling billing records against session outcomes. */
    public record Reconciliation(boolean ok, long billedSessions, int legacyEntries, long cancelledSessions,
                                 boolean cancelledBilled, boolean totalsMatch, int duplicatesIgnored, int failures) {
        public String describe() {
            return String.format("billedSessions=%d legacyEntries=%d cancelled=%d cancelledBilled=%s totalsMatch=%s "
                            + "duplicatesIgnored=%d billingFailures=%d => %s",
                    billedSessions, legacyEntries, cancelledSessions, cancelledBilled, totalsMatch,
                    duplicatesIgnored, failures, ok ? "RECONCILED" : "MISMATCH");
        }
    }

    private final LegacyBillingService legacy;
    private final ReentrantLock lock = new ReentrantLock();
    private final Set<String> billedRequestIds = new HashSet<>();
    private final List<String> failedRequestIds = new ArrayList<>();
    private int duplicatesIgnored;

    public BillingAdapter(LegacyBillingService legacy) {
        this.legacy = legacy;
    }

    /**
     * Pre: session is COMPLETED (or was already billed). Post: exactly one legacy entry exists for
     * the request and the session is BILLED, or the session is BILLING_FAILED and no entry exists.
     */
    public Result bill(ChargingSession session) {
        String requestId = session.request().requestId();
        lock.lock();
        try {
            if (billedRequestIds.contains(requestId)) {
                duplicatesIgnored++;
                Log.info("BILLING duplicate ignored for %s", requestId);
                return Result.DUPLICATE_IGNORED;
            }
            if (session.state() != SessionState.COMPLETED) {
                throw new IllegalStateException("only COMPLETED sessions can be billed: " + requestId + " is " + session.state());
            }
            int before = legacy.getEntryCount();
            try {
                legacy.recordSession(session.request().accountId(), session.request().energyKwh(),
                        session.startEpochMillis(), session.endEpochMillis());
            } catch (RuntimeException e) {
                failedRequestIds.add(requestId);
                session.markBillingFailed();
                Log.info("BILLING FAILED for %s: %s", requestId, e.getMessage());
                return Result.FAILED;
            }
            billedRequestIds.add(requestId);
            session.markBilled();
            assert legacy.getEntryCount() == before + 1 : "legacy entry count must grow by exactly one";
            Log.info("BILLED %s account=%s %.1f kWh", requestId, session.request().accountId(), session.request().energyKwh());
            return Result.BILLED;
        } finally {
            lock.unlock();
        }
    }

    public int entryCount() {
        lock.lock();
        try {
            return legacy.getEntryCount();
        } finally {
            lock.unlock();
        }
    }

    public double totalFor(String accountId) {
        lock.lock();
        try {
            return legacy.getTotal(accountId);
        } finally {
            lock.unlock();
        }
    }

    public int duplicatesIgnored() {
        lock.lock();
        try {
            return duplicatesIgnored;
        } finally {
            lock.unlock();
        }
    }

    /** Cross-checks legacy records against the final state of every session. */
    public Reconciliation reconcile(Collection<ChargingSession> sessions) {
        lock.lock();
        try {
            long billed = 0;
            long cancelled = 0;
            boolean cancelledBilled = false;
            Map<String, Double> expectedTotals = new HashMap<>();
            for (ChargingSession s : sessions) {
                SessionState st = s.state();
                if (st == SessionState.BILLED) {
                    billed++;
                    expectedTotals.merge(s.request().accountId(), s.request().energyKwh(), Double::sum);
                } else if (st == SessionState.CANCELLED) {
                    cancelled++;
                    cancelledBilled |= billedRequestIds.contains(s.request().requestId());
                }
            }
            boolean totalsMatch = true;
            for (Map.Entry<String, Double> e : expectedTotals.entrySet()) {
                totalsMatch &= Math.abs(legacy.getTotal(e.getKey()) - e.getValue()) < 1e-6;
            }
            int entries = legacy.getEntryCount();
            boolean ok = billed == entries && billed == billedRequestIds.size() && !cancelledBilled && totalsMatch;
            return new Reconciliation(ok, billed, entries, cancelled, cancelledBilled, totalsMatch,
                    duplicatesIgnored, failedRequestIds.size());
        } finally {
            lock.unlock();
        }
    }
}

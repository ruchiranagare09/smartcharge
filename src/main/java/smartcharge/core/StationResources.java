/*
 * SmartCharge - CO3408 Advanced Software Modelling
 * Name: <STUDENT NAME>
 * Student ID: <STUDENT ID>
 */
package smartcharge.core;

import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Owns the two shared physical resources: charging bays (a counting semaphore) and the power
 * budget (an integer guarded by a mutex).
 *
 * Deadlock prevention: allocation takes the power lock and then calls the NON-blocking
 * {@code bays.tryAcquire()} inside it, so no thread ever waits for one resource while holding the
 * other (no hold-and-wait), and the acquisition order is fixed (no circular wait). Either both
 * the bay and the full power reservation are granted, or nothing is.
 */
public final class StationResources {

    public static final int DEFAULT_BAYS = 4;
    public static final int DEFAULT_POWER_KW = 60;

    /** Point-in-time view of resource usage, including high-water marks. */
    public record Snapshot(int reservedKw, int activeBays, int freeBays, int peakReservedKw, int peakActiveBays,
                           int totalBays, int powerCapacityKw) {
    }

    private final int totalBays;
    private final int powerCapacityKw;
    private final Semaphore bays;
    private final ReentrantLock powerLock = new ReentrantLock();

    private int reservedKw;
    private int peakReservedKw;
    private int peakActiveBays;

    public StationResources() {
        this(DEFAULT_BAYS, DEFAULT_POWER_KW);
    }

    public StationResources(int totalBays, int powerCapacityKw) {
        if (totalBays < 1 || powerCapacityKw < 1) {
            throw new IllegalArgumentException("bays and power capacity must be positive");
        }
        this.totalBays = totalBays;
        this.powerCapacityKw = powerCapacityKw;
        this.bays = new Semaphore(totalBays, true);
    }

    /**
     * Pre: kw > 0. Post: returns true and holds one bay plus kw of reservation, or returns false
     * and holds nothing. Invariant: 0 <= reservedKw <= capacity and 0 <= activeBays <= totalBays.
     */
    public boolean tryAllocate(int kw) {
        if (kw <= 0) {
            throw new IllegalArgumentException("power reservation must be positive: " + kw);
        }
        powerLock.lock();
        try {
            if (reservedKw + kw > powerCapacityKw) {
                return false;
            }
            if (!bays.tryAcquire()) {
                return false;
            }
            reservedKw += kw;
            peakReservedKw = Math.max(peakReservedKw, reservedKw);
            peakActiveBays = Math.max(peakActiveBays, activeBaysLocked());
            assert invariantHolds() : "resource invariant violated after allocate: " + describeLocked();
            return true;
        } finally {
            powerLock.unlock();
        }
    }

    /** Pre: a matching allocation of kw is held. Post: one bay and kw are returned to the pool. */
    public void release(int kw) {
        powerLock.lock();
        try {
            if (kw <= 0 || kw > reservedKw || activeBaysLocked() == 0) {
                throw new IllegalStateException("release(" + kw + ") without a matching allocation: " + describeLocked());
            }
            reservedKw -= kw;
            bays.release();
            assert invariantHolds() : "resource invariant violated after release: " + describeLocked();
        } finally {
            powerLock.unlock();
        }
    }

    /** True if a bay is free and kw more would stay within capacity. Reserves nothing. */
    public boolean wouldFit(int kw) {
        powerLock.lock();
        try {
            return reservedKw + kw <= powerCapacityKw && bays.availablePermits() > 0;
        } finally {
            powerLock.unlock();
        }
    }

    public Snapshot snapshot() {
        powerLock.lock();
        try {
            int active = activeBaysLocked();
            return new Snapshot(reservedKw, active, totalBays - active, peakReservedKw, peakActiveBays,
                    totalBays, powerCapacityKw);
        } finally {
            powerLock.unlock();
        }
    }

    private int activeBaysLocked() {
        return totalBays - bays.availablePermits();
    }

    private boolean invariantHolds() {
        int active = activeBaysLocked();
        return reservedKw >= 0 && reservedKw <= powerCapacityKw && active >= 0 && active <= totalBays;
    }

    private String describeLocked() {
        return "reservedKw=" + reservedKw + "/" + powerCapacityKw + " activeBays=" + activeBaysLocked() + "/" + totalBays;
    }
}

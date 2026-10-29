/*
 * SmartCharge - CO3408 Advanced Software Modelling
 * Name: Ruchira Nagare
 * Student ID: G21289292
 */
package smartcharge.model;

/** Charging rate category. A session reserves exactly this much power for its whole duration. */
public enum ChargingRate {
    STANDARD(7),
    FAST(22);

    private final int kw;

    ChargingRate(int kw) {
        this.kw = kw;
    }

    public int kw() {
        return kw;
    }
}

/*
 * SmartCharge - CO3408 Advanced Software Modelling
 * Name: <STUDENT NAME>
 * Student ID: <STUDENT ID>
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

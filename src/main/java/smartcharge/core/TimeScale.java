/*
 * SmartCharge - CO3408 Advanced Software Modelling
 * Name: <STUDENT NAME>
 * Student ID: <STUDENT ID>
 */
package smartcharge.core;

/**
 * Maps simulated minutes to real time. Default (assumption A-02): 1 simulated minute = 1 real
 * millisecond, so 1 simulated hour = 60 ms. Every benchmark configuration uses the same scale.
 */
public final class TimeScale {

    public static final TimeScale DEFAULT = new TimeScale(1.0);

    private final double realMillisPerSimMinute;

    public TimeScale(double realMillisPerSimMinute) {
        if (!(realMillisPerSimMinute > 0) || Double.isInfinite(realMillisPerSimMinute)) {
            throw new IllegalArgumentException("realMillisPerSimMinute must be a positive number");
        }
        this.realMillisPerSimMinute = realMillisPerSimMinute;
    }

    public double realMillisPerSimMinute() {
        return realMillisPerSimMinute;
    }

    public long simMinutesToNanos(double simMinutes) {
        return Math.round(simMinutes * realMillisPerSimMinute * 1_000_000.0);
    }

    public double nanosToSimMinutes(long nanos) {
        return nanos / (realMillisPerSimMinute * 1_000_000.0);
    }
}

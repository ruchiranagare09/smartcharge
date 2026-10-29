/*
 * SmartCharge - CO3408 Advanced Software Modelling
 * Name: Ruchira Nagare
 * Student ID: G21289292
 */
package smartcharge.model;

/**
 * An immutable charging request as received from a driver. Fields hold raw input values;
 * {@link smartcharge.core.RequestValidator} decides whether the request is acceptable.
 */
public record ChargingRequest(String requestId, String accountId, int priority, double energyKwh, ChargingRate rate) {

    public Priority priorityLevel() {
        return Priority.fromLevel(priority);
    }

    /** Session duration in simulated minutes: energy (kWh) / rate (kW) * 60. */
    public double durationSimMinutes() {
        return energyKwh / rate.kw() * 60.0;
    }
}

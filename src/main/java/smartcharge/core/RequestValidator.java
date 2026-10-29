/*
 * SmartCharge - CO3408 Advanced Software Modelling
 * Name: Ruchira Nagare
 * Student ID: G21289292
 */
package smartcharge.core;

import smartcharge.model.ChargingRequest;

/**
 * Stateless field validation for incoming requests (FR-02). Duplicate request IDs need station
 * state, so {@link ChargingStation#submit} checks those.
 */
public final class RequestValidator {

    /** Assumption A-08: no single request may ask for more than a large EV battery holds. */
    public static final double MAX_ENERGY_KWH = 100.0;

    public void validate(ChargingRequest r) throws ValidationException {
        if (r == null) {
            throw new ValidationException("request must not be null");
        }
        requireText(r.requestId(), "requestId");
        requireText(r.accountId(), "accountId");
        if (r.priority() < 1 || r.priority() > 3) {
            throw new ValidationException("priority must be 1, 2 or 3 but was " + r.priority() + " (" + r.requestId() + ")");
        }
        if (r.rate() == null) {
            throw new ValidationException("charging rate is required (" + r.requestId() + ")");
        }
        double e = r.energyKwh();
        if (Double.isNaN(e) || Double.isInfinite(e) || e <= 0) {
            throw new ValidationException("energyKwh must be a positive finite number but was " + e + " (" + r.requestId() + ")");
        }
        if (e > MAX_ENERGY_KWH) {
            throw new ValidationException("energyKwh must not exceed " + MAX_ENERGY_KWH + " but was " + e + " (" + r.requestId() + ")");
        }
    }

    private static void requireText(String value, String field) throws ValidationException {
        if (value == null || value.isBlank()) {
            throw new ValidationException(field + " must not be blank");
        }
    }
}

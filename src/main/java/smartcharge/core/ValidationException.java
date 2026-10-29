/*
 * SmartCharge - CO3408 Advanced Software Modelling
 * Name: Ruchira Nagare
 * Student ID: G21289292
 */
package smartcharge.core;

/** Thrown when a charging request is rejected at intake. */
public final class ValidationException extends Exception {
    private static final long serialVersionUID = 1L;

    public ValidationException(String message) {
        super(message);
    }
}

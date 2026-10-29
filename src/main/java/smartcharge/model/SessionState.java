/*
 * SmartCharge - CO3408 Advanced Software Modelling
 * Name: Ruchira Nagare
 * Student ID: G21289292
 */
package smartcharge.model;

/**
 * Session lifecycle. Legal transitions:
 * QUEUED -> CHARGING | CANCELLED; CHARGING -> COMPLETED | CANCELLED;
 * COMPLETED -> BILLED | BILLING_FAILED. BILLED, BILLING_FAILED and CANCELLED are terminal.
 */
public enum SessionState {
    QUEUED,
    CHARGING,
    COMPLETED,
    BILLED,
    BILLING_FAILED,
    CANCELLED;

    public boolean isTerminal() {
        return this == BILLED || this == BILLING_FAILED || this == CANCELLED;
    }
}

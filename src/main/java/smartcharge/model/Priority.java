/*
 * SmartCharge - CO3408 Advanced Software Modelling
 * Name: Ruchira Nagare
 * Student ID: G21289292
 */
package smartcharge.model;

/** Request priority. A lower level is served first. */
public enum Priority {
    FLEET_EMERGENCY(1),
    STAFF(2),
    VISITOR(3);

    private final int level;

    Priority(int level) {
        this.level = level;
    }

    public int level() {
        return level;
    }

    public static Priority fromLevel(int level) {
        for (Priority p : values()) {
            if (p.level == level) {
                return p;
            }
        }
        throw new IllegalArgumentException("invalid priority level: " + level);
    }
}

package smartcharge.core;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Minimal line logger. Every line carries the wall-clock time and the calling thread's name. */
public final class Log {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static volatile boolean enabled = true;

    private Log() {
    }

    public static void setEnabled(boolean on) {
        enabled = on;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void info(String format, Object... args) {
        if (!enabled) {
            return;
        }
        String line = LocalTime.now().format(TIME) + " [" + Thread.currentThread().getName() + "] "
                + String.format(Locale.ROOT, format, args);
        System.out.println(line);
    }
}

package android.util;

/** JVM tests exercise DataStore/sync code without calling Android's stub logger. */
public final class Log {
    private Log() {}
    public static int d(String tag, String message) { return 0; }
    public static int d(String tag, String message, Throwable error) { return 0; }
    public static int i(String tag, String message) { return 0; }
    public static int i(String tag, String message, Throwable error) { return 0; }
    public static int w(String tag, String message) { return 0; }
    public static int w(String tag, String message, Throwable error) { return 0; }
    public static int w(String tag, Throwable error) { return 0; }
    public static int e(String tag, String message) { return 0; }
    public static int e(String tag, String message, Throwable error) { return 0; }
    public static int v(String tag, String message) { return 0; }
    public static boolean isLoggable(String tag, int level) { return false; }
}

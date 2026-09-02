package org.newdawn.slick.util;

/** Minimal replacement for Slick's logging, routing to logcat. */
public final class Log {
    private static final String TAG = "XFTL";

    private Log() {
    }

    public static void info(String message) {
        android.util.Log.i(TAG, message);
    }

    public static void info(String message, Throwable e) {
        android.util.Log.i(TAG, message, e);
    }

    public static void info(Throwable e) {
        android.util.Log.i(TAG, "", e);
    }

    public static void warn(String message) {
        android.util.Log.w(TAG, message);
    }

    public static void warn(String message, Throwable e) {
        android.util.Log.w(TAG, message, e);
    }

    public static void warn(Throwable e) {
        android.util.Log.w(TAG, "", e);
    }

    public static void error(String message) {
        android.util.Log.e(TAG, message);
    }

    public static void error(String message, Throwable e) {
        android.util.Log.e(TAG, message, e);
    }

    public static void error(Throwable e) {
        android.util.Log.e(TAG, "", e);
    }

    public static void debug(String message) {
        android.util.Log.d(TAG, message);
    }

    public static void debug(String message, Throwable e) {
        android.util.Log.d(TAG, message, e);
    }

    public static void debug(Throwable e) {
        android.util.Log.d(TAG, "", e);
    }
}

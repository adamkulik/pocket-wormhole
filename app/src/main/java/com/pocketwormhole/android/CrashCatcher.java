package com.pocketwormhole.android;

import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;

/**
 * Default uncaught-exception handler that writes the stack trace (plus all
 * live thread stacks) to the app's internal and external files directories
 * before the process dies, so crashes can be diagnosed afterwards via
 *
 *   adb pull /sdcard/Android/data/com.pocketwormhole.android/files/
 *
 * even when no device was tethered at the time of the crash.
 */
public final class CrashCatcher implements Thread.UncaughtExceptionHandler {
    private static final String TAG = "XFTL";
    private static final long MIN_INTERVAL_MS = 2000;

    private final Thread.UncaughtExceptionHandler previous;
    private final Context app;
    private long lastWriteMs;

    private CrashCatcher(Context app) {
        this.previous = Thread.getDefaultUncaughtExceptionHandler();
        this.app = app;
    }

    public static void install(Context app) {
        Thread.setDefaultUncaughtExceptionHandler(new CrashCatcher(app));
    }

    @Override
    public void uncaughtException(Thread thread, Throwable throwable) {
        long now = System.currentTimeMillis();
        if (now - lastWriteMs > MIN_INTERVAL_MS) {
            lastWriteMs = now;
            try {
                write(thread, throwable);
            } catch (Throwable ignored) {
                // Nowhere left to report to
            }
        }
        if (previous != null) {
            previous.uncaughtException(thread, throwable);
        }
    }

    private void write(Thread thread, Throwable throwable) throws Exception {
        String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date());
        String body = buildReport(thread, throwable, ts);
        String name = "crash-" + System.currentTimeMillis() + ".txt";

        File external = app.getExternalFilesDir(null);
        File[] dirs = {external, app.getFilesDir()};
        for (File dir : dirs) {
            if (dir == null) continue;
            try (FileWriter fw = new FileWriter(new File(dir, name))) {
                fw.write(body);
            }
        }
        android.util.Log.e(TAG, "Crash report written: " + name);
    }

    private String buildReport(Thread thread, Throwable throwable, String ts) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);

        pw.println("FTL-Android crash report");
        pw.println("time     : " + ts);
        pw.println("device   : " + Build.MANUFACTURER + " " + Build.MODEL
                + " (Android " + Build.VERSION.RELEASE + ", SDK " + Build.VERSION.SDK_INT + ")");
        pw.println("thread   : " + thread.getName() + " (id " + thread.getId() + ")");
        pw.println();

        pw.println("---- exception ----");
        pw.println(throwable.toString());
        pw.println();

        pw.println("---- stack of crashing thread ----");
        appendTrace(pw, throwable);

        // Include causes
        Throwable cause = throwable.getCause();
        int depth = 0;
        while (cause != null && depth < 8) {
            pw.println();
            pw.println("---- cause " + depth + " ----");
            appendTrace(pw, cause);
            cause = cause.getCause();
            depth++;
        }

        pw.println();
        pw.println("---- all threads ----");
        for (Map.Entry<Thread, StackTraceElement[]> entry :
                Thread.getAllStackTraces().entrySet()) {
            Thread t = entry.getKey();
            pw.println();
            pw.println("thread: " + t.getName() + " (id " + t.getId()
                    + ", state " + t.getState() + ")");
            StackTraceElement[] frames = entry.getValue();
            int limit = Math.min(frames.length, t == thread ? frames.length : 25);
            for (int i = 0; i < limit; i++) {
                pw.println("    at " + frames[i]);
            }
        }

        return sw.toString();
    }

    private void appendTrace(PrintWriter pw, Throwable t) {
        StackTraceElement[] frames = t.getStackTrace();
        pw.println(t.toString());
        for (StackTraceElement frame : frames) {
            pw.println("    at " + frame);
        }
    }
}

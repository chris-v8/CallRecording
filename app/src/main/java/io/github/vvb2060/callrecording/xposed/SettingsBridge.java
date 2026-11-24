package io.github.vvb2060.callrecording.xposed;

import android.app.Application;
import android.net.Uri;
import android.os.Bundle;

import java.lang.reflect.Method;

/** Reads settings from this module without exposing a world-readable prefs file. */
final class SettingsBridge {
    private static final Uri URI =
            Uri.parse("content://io.github.vvb2060.callrecording.settings");

    private SettingsBridge() {}

    static boolean allCallsEnabled() {
        Bundle options = read();
        return options != null && options.getBoolean("auto_record_all", false);
    }

    static boolean diagnosticsEnabled() {
        Bundle options = read();
        return options != null && options.getBoolean("diagnostic_logging", false);
    }

    static boolean multiPickEnabled() {
        Bundle options = read();
        return options != null && options.getBoolean("multi_pick", true);
    }

    /**
     * Changes picked in the module app: "pending_numbers" (contact Data URIs to
     * add) and "pending_removals" (stored numbers to remove). The provider
     * clears them once taken.
     */
    static Bundle takePending() {
        try {
            Application application = currentApplication();
            if (application == null) return null;
            return application.getContentResolver().call(URI, "take_pending", null, null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    static Application application() {
        return currentApplication();
    }

    /** Copy of Google Phone's Selected numbers list for the module app's picker. */
    static void reportSelectedNumbers(java.util.ArrayList<String> numbers) {
        try {
            Application application = currentApplication();
            if (application == null) return;
            Bundle extras = new Bundle();
            extras.putStringArrayList("selected_snapshot", numbers);
            application.getContentResolver().call(URI, "selected_snapshot", null, extras);
        } catch (Throwable ignored) {
            // The picker then just can't mark existing numbers.
        }
    }

    static void report(String code) {
        try {
            Application application = currentApplication();
            if (application != null) {
                application.getContentResolver().call(URI, "report", code, null);
            }
        } catch (Throwable ignored) {
            // Never allow diagnostics to interfere with calls.
        }
    }

    private static Bundle read() {
        try {
            Application application = currentApplication();
            if (application == null) return null;
            return application.getContentResolver().call(URI, "read", null, null);
        } catch (Throwable ignored) {
            return null;
        }
    }
    private static Application currentApplication() {
        // The original Xposed API v82 artifact does not expose AndroidAppHelper
        // at compile time; find it through reflection when LSPosed provides it.
        try {
            Class<?> helper = Class.forName("de.robv.android.xposed.AndroidAppHelper");
            Object app = helper.getMethod("currentApplication").invoke(null);
            if (app instanceof Application) return (Application) app;
        } catch (Throwable ignored) {
        }
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method method = activityThread.getDeclaredMethod("currentApplication");
            method.setAccessible(true);
            Object app = method.invoke(null);
            if (app instanceof Application) return (Application) app;
        } catch (Throwable ignored) {
        }
        return null;
    }
}

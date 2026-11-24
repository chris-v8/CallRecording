package io.github.vvb2060.callrecording.settings;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;

import java.util.ArrayList;
import java.util.Collections;

/**
 * Cross-process settings bridge. Calls are restricted to the module UID and
 * com.google.android.dialer UID. No call numbers or audio are stored; numbers
 * picked for Google Phone's Selected numbers list are queued as contact Data
 * URIs and handed over once.
 */
public final class SettingsProvider extends ContentProvider {
    public static final String PREFS = "module_settings";
    public static final String AUTO = "auto_record_all";
    public static final String DEBUG = "diagnostic_logging";
    public static final String LAST_STATUS = "last_status";
    public static final String LAST_CHECK = "last_check";
    public static final String LAST_HIT = "last_hit";
    public static final String MULTI_PICK = "multi_pick";
    /** Contact Data URIs (not numbers) waiting for Google Phone's Selected numbers screen. */
    public static final String PENDING = "pending_numbers";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Context context = getContext();
        if (context == null) return null;
        verifyCaller(context);
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if ("read".equals(method)) {
            Bundle reply = new Bundle();
            reply.putBoolean(AUTO, prefs.getBoolean(AUTO, true));
            reply.putBoolean(DEBUG, prefs.getBoolean(DEBUG, false));
            reply.putBoolean(MULTI_PICK, prefs.getBoolean(MULTI_PICK, true));
            return reply;
        }
        if ("take_pending".equals(method)) {
            Bundle reply = new Bundle();
            reply.putStringArrayList(PENDING, new ArrayList<>(
                    prefs.getStringSet(PENDING, Collections.emptySet())));
            prefs.edit().remove(PENDING).apply();
            return reply;
        }
        if ("report".equals(method)) {
            if (arg == null) return Bundle.EMPTY;
            switch (arg) {
                case "installed":
                case "not_found":
                case "factory_missing":
                case "error":
                case "hit":
                    break;
                default:
                    return Bundle.EMPTY;
            }
            SharedPreferences.Editor edit = prefs.edit();
            if ("hit".equals(arg)) {
                edit.putLong(LAST_HIT, System.currentTimeMillis());
            } else {
                edit.putString(LAST_STATUS, arg);
                edit.putLong(LAST_CHECK, System.currentTimeMillis());
            }
            edit.apply();
            return Bundle.EMPTY;
        }
        throw new IllegalArgumentException("Unsupported provider operation");
    }

    private static void verifyCaller(Context context) {
        int uid = Binder.getCallingUid();
        if (uid == context.getApplicationInfo().uid) return;
        String[] packages = context.getPackageManager().getPackagesForUid(uid);
        if (packages != null) {
            for (String pkg : packages) {
                if ("com.google.android.dialer".equals(pkg)) return;
            }
        }
        throw new SecurityException("Only Google Phone can access recording settings");
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection,
                      String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }
}

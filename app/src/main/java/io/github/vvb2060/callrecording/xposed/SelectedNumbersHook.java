package io.github.vvb2060.callrecording.xposed;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;

import java.io.File;
import java.lang.reflect.Member;
import java.nio.file.Files;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Multi-select for Google Phone's "Selected numbers" (always record) list.
 *
 * Google Phone adds one number per pick: its "Add" button launches the system
 * picker for a single phone number (ACTION_PICK, phone_v2) and its fragment's
 * onActivityResult reads data1 from the returned Data row and appends it to the
 * settings DataStore. Each append is an updateData() transform that skips
 * duplicates, so several appends in a row are safe.
 *
 * This hook never touches that storage. It
 *   1. sends the Add button to this module's multi-select picker,
 *   2. replays Google Phone's own onActivityResult once per picked number, and
 *   3. applies numbers queued in the module app when the screen is opened.
 *
 * Verified on 88.0.472595733 (dic.Z, cym.onClick) and 247.0.1025958192
 * (lbb.ag, jmz.onClick). Anything unrecognised leaves the screen stock.
 */
final class SelectedNumbersHook {
    private static final String TAG = "CallRecording/Selected";
    private static final String RESULT_MARKER = "uri received from system contact picker";
    private static final String CLICK_MARKER = "invoke the system contact picker";
    /** ContactsContract.CommonDataKinds.Phone.CONTENT_TYPE */
    private static final String PHONE_PICK_TYPE = "vnd.android.cursor.dir/phone_v2";
    private static final String MODULE = "io.github.vvb2060.callrecording";
    private static final String PICKER = MODULE + ".settings.NumberPickerActivity";
    static final String EXTRA_URIS = MODULE + ".extra.URIS";
    /** Numbers already on the list, so the picker can show them as added. */
    static final String EXTRA_EXISTING = MODULE + ".extra.EXISTING_NUMBERS";
    /** Google Phone's DataStore file for call recording settings (88 and 247). */
    private static final String SETTINGS_FILE = "CallRecordingSettingsData.pb";
    /** The request code Google Phone uses for this picker; its handler ignores any other. */
    private static final int PICK_REQUEST = 9;

    /** True only while Google Phone handles a click on the "Add" button. */
    private static final ThreadLocal<Boolean> IN_ADD_CLICK = new ThreadLocal<>();

    private SelectedNumbersHook() {}

    static void install(DexHelper dex) {
        Method result = findUnique(dex, RESULT_MARKER, (short) 3,
                int.class, int.class, Intent.class);
        if (result == null) {
            XposedBridge.log(TAG + ": result handler not found, multi-select disabled");
            return;
        }
        hookResult(result);
        XposedBridge.log(TAG + ": result handler hooked: " + result);

        Method onViewCreated = viewCreatedOf(result.getDeclaringClass());
        if (onViewCreated != null) {
            hookPendingQueue(onViewCreated, result);
        } else {
            XposedBridge.log(TAG + ": no onViewCreated, numbers queued in the app are not applied");
        }

        Method click = findUnique(dex, CLICK_MARKER, (short) 1, View.class);
        if (click != null) {
            hookAddClick(click);
            hookPickerLaunch();
        } else {
            XposedBridge.log(TAG + ": Add button not found, it keeps the system picker");
        }
    }

    /** Google Phone's result handler with a multi-pick result: replay it per number. */
    private static void hookResult(Method result) {
        XposedBridge.hookMethod(result, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Intent data = (Intent) param.args[2];
                if (data == null) return;
                ArrayList<Uri> uris;
                try {
                    uris = data.getParcelableArrayListExtra(EXTRA_URIS);
                } catch (Throwable error) {
                    return;
                }
                if (uris == null || uris.size() < 2) return; // single pick: stock path
                int added = replay(result, param.thisObject, (Integer) param.args[0],
                        (Integer) param.args[1], uris);
                param.setResult(null);
                XposedBridge.log(TAG + ": multi-select passed " + added + "/" + uris.size()
                        + " numbers to Google Phone");
            }
        });
    }

    /** Numbers picked from the module app wait until this screen is shown. */
    private static void hookPendingQueue(Method onViewCreated, Method result) {
        XposedBridge.hookMethod(onViewCreated, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object fragment = param.thisObject;
                View view = (View) param.args[0];
                if (view == null) return;
                // Run after the fragment has finished starting.
                view.post(() -> {
                    reportSnapshot(view.getContext());
                    List<String> pending = SettingsBridge.takePendingNumbers();
                    if (pending == null || pending.isEmpty()) return;
                    List<Uri> uris = new ArrayList<>();
                    for (String value : pending) uris.add(Uri.parse(value));
                    int added = replay(result, fragment, PICK_REQUEST, Activity.RESULT_OK, uris);
                    XposedBridge.log(TAG + ": applied " + added + "/" + uris.size()
                            + " numbers queued in the module app");
                    // The adds are saved asynchronously; refresh the module's copy afterwards.
                    view.postDelayed(() -> reportSnapshot(view.getContext()), 3000);
                });
            }
        });
    }

    private static int replay(Method result, Object fragment, int requestCode,
                              int resultCode, List<Uri> uris) {
        int added = 0;
        for (Uri uri : uris) {
            try {
                XposedBridge.invokeOriginalMethod(result, fragment,
                        new Object[]{requestCode, resultCode, new Intent().setData(uri)});
                added++;
            } catch (Throwable error) {
                XposedBridge.log(TAG + ": Google Phone rejected one number: " + error);
            }
        }
        return added;
    }

    private static void hookAddClick(Method click) {
        XposedBridge.hookMethod(click, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                IN_ADD_CLICK.set(Boolean.TRUE);
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                IN_ADD_CLICK.remove();
            }
        });
    }

    /** During an Add click only, open the module's multi-select picker instead. */
    private static void hookPickerLaunch() {
        Method start;
        try {
            start = Activity.class.getDeclaredMethod("startActivityForResult",
                    Intent.class, int.class, Bundle.class);
        } catch (NoSuchMethodException error) {
            XposedBridge.log(TAG + ": startActivityForResult not found: " + error);
            return;
        }
        XposedBridge.hookMethod(start, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!Boolean.TRUE.equals(IN_ADD_CLICK.get())) return;
                Intent intent = (Intent) param.args[0];
                if (intent == null || intent.getComponent() != null) return;
                if (!Intent.ACTION_PICK.equals(intent.getAction())) return;
                if (!PHONE_PICK_TYPE.equals(intent.getType())) return;
                if (!SettingsBridge.multiPickEnabled()) return;
                Activity activity = (Activity) param.thisObject;
                Intent redirected = new Intent(intent).setClassName(MODULE, PICKER);
                if (activity.getPackageManager().resolveActivity(redirected, 0) == null) return;
                ArrayList<String> existing = currentNumbers(activity);
                if (existing != null) {
                    redirected.putStringArrayListExtra(EXTRA_EXISTING, existing);
                    SettingsBridge.reportSelectedNumbers(existing);
                }
                param.args[0] = redirected;
            }
        });
        XposedBridge.log(TAG + ": Add button opens the multi-select picker");
    }

    /**
     * Numbers on Google Phone's Selected numbers list, read (never written)
     * from its DataStore file. Null if the file can't be read.
     */
    private static ArrayList<String> currentNumbers(Context context) {
        try {
            File file = new File(context.getFilesDir(), SETTINGS_FILE);
            if (!file.isFile()) return new ArrayList<>();
            return new ArrayList<>(ProtoNumbers.collect(Files.readAllBytes(file.toPath())));
        } catch (Throwable error) {
            XposedBridge.log(TAG + ": cannot read the current list: " + error);
            return null;
        }
    }

    /** Lets the module app mark numbers that are already on the list. */
    private static void reportSnapshot(Context context) {
        ArrayList<String> numbers = currentNumbers(context);
        if (numbers != null) SettingsBridge.reportSelectedNumbers(numbers);
    }

    private static Method viewCreatedOf(Class<?> fragment) {
        Method found = null;
        for (Method method : fragment.getDeclaredMethods()) {
            Class<?>[] types = method.getParameterTypes();
            if (method.getReturnType() != void.class || types.length != 2) continue;
            if (types[0] != View.class || types[1] != Bundle.class) continue;
            if (found != null) return null;
            found = method;
        }
        return found;
    }

    private static Method findUnique(DexHelper dex, String marker, short count,
                                     Class<?>... parameters) {
        Set<Method> valid = new LinkedHashSet<>();
        for (long index : dex.findMethodUsingString(marker, false, -1, count, null, -1,
                null, null, null, false)) {
            Member member = dex.decodeMethodIndex(index);
            if (!(member instanceof Method)) continue;
            Method method = (Method) member;
            if (method.getReturnType() != void.class) continue;
            if (!java.util.Arrays.equals(method.getParameterTypes(), parameters)) continue;
            valid.add(method);
        }
        return valid.size() == 1 ? valid.iterator().next() : null;
    }
}

package io.github.vvb2060.callrecording.xposed;

import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Google Phone's native automatic-recording eligibility hook.
 *
 * Discovers the producer by its diagnostic string, verified signature and
 * called Future factory. Never uses a hard-coded obfuscated class name and
 * never clicks a recording button or modifies the user's contacts.
 *
 * This is an experimental policy hook: an upstream gate may still prevent
 * Google Phone from starting its built-in recorder.
 */
public final class AutoRecordingHook {
    private static final String TAG = "CallRecording/AutoAll";
    /**
     * Producers whose completed Future&lt;Boolean&gt; decides automatic recording.
     * 2xx releases use the first; 88.x has the other two (the legacy in-call
     * UI goes through Crosby). Every unique match is hooked.
     */
    private static final String[] POLICY_MARKERS = {
            "produceShouldStartAutomaticRecordingForContact",
            "produceShouldStartAutomaticRecording",
            "produceIsCrosbyEnabledForCall",
    };
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();
    private static final AtomicBoolean FIRST_HIT = new AtomicBoolean();

    private AutoRecordingHook() {}

    public static void install(DexHelper dex) {
        if (!INSTALLED.compareAndSet(false, true)) return;
        int hooked = 0;
        boolean factoryMissing = false;
        for (String marker : POLICY_MARKERS) {
            try {
                Method policy = discoverPolicy(dex, marker);
                if (policy == null) continue;
                Method factory = discoverCompletedFutureFactory(dex, policy);
                if (factory == null) {
                    factoryMissing = true;
                    XposedBridge.log(TAG + ": " + marker + ": no unique compatible immediate Future factory");
                    continue;
                }
                hookPolicy(policy, factory);
                hooked++;
                XposedBridge.log(TAG + ": Policy hooked: " + policy);
            } catch (Throwable error) {
                XposedBridge.log(TAG + ": " + marker + ": discovery failed safely: " + error);
            }
        }
        if (hooked > 0) {
            status("installed", hooked + " policy method(s) hooked");
        } else if (factoryMissing) {
            status("factory_missing", "No unique compatible immediate Future factory");
        } else {
            status("not_found", "No unique supported policy method");
        }
    }

    private static void hookPolicy(Method policy, Method factory) {
        XposedBridge.hookMethod(policy, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!SettingsBridge.allCallsEnabled()) return;
                try {
                    Object immediateTrue = factory.invoke(null, Boolean.TRUE);
                    if (!policy.getReturnType().isInstance(immediateTrue)) return;
                    param.setResult(immediateTrue);
                    RecordingTimingHook.markAutoPolicy();
                    if (FIRST_HIT.compareAndSet(false, true)) {
                        SettingsBridge.report("hit");
                        XposedBridge.log(TAG + ": first eligibility override");
                    }
                } catch (Throwable error) {
                    // Any failure leaves Google Phone's original decision intact.
                    if (SettingsBridge.diagnosticsEnabled()) {
                        XposedBridge.log(TAG + ": cannot create completed Future: " + error);
                    }
                }
            }
        });
    }

    private static Method discoverPolicy(DexHelper dex, String marker) {
        long[] matches = dex.findMethodUsingString(
                marker, false, -1, (short) 1, null, -1,
                null, null, null, false);
        Set<Method> valid = new LinkedHashSet<>();
        for (long index : matches) {
            Member member = dex.decodeMethodIndex(index);
            if (!(member instanceof Method)) continue;
            Method method = (Method) member;
            if (method.getParameterCount() != 1
                    || method.getParameterTypes()[0] != Object.class) continue;
            if (!Future.class.isAssignableFrom(method.getReturnType())) continue;
            valid.add(method);
        }
        return valid.size() == 1 ? valid.iterator().next() : null;
    }

    /** Finds the static immediate-Future factory that {@code policy} itself calls. */
    static Method discoverCompletedFutureFactory(DexHelper dex, Method policy) {
        long policyIndex = dex.encodeMethodIndex(policy);
        // DexHelper.findMethodInvoking returns methods invoked BY policy.
        long[] callees = dex.findMethodInvoking(policyIndex, -1, (short) 1,
                null, -1, null, null, null, false);
        Set<Method> factories = new LinkedHashSet<>();
        for (long index : callees) {
            Member member = dex.decodeMethodIndex(index);
            if (!(member instanceof Method)) continue;
            Method method = (Method) member;
            if (!Modifier.isStatic(method.getModifiers())
                    || method.getParameterCount() != 1
                    || method.getParameterTypes()[0] != Object.class) continue;
            if (!policy.getReturnType().isAssignableFrom(method.getReturnType())) continue;
            factories.add(method);
        }
        if (factories.size() != 1) return null;
        Method factory = factories.iterator().next();
        try {
            factory.setAccessible(true);
            Object sample = factory.invoke(null, Boolean.TRUE);
            if (!policy.getReturnType().isInstance(sample)
                    || !(sample instanceof Future<?>)) return null;
            Future<?> future = (Future<?>) sample;
            if (!future.isDone() || !Boolean.TRUE.equals(future.get())) return null;
            return factory;
        } catch (Throwable error) {
            XposedBridge.log(TAG + ": rejected factory: " + error);
            return null;
        }
    }

    private static void status(String code, String message) {
        XposedBridge.log(TAG + ": " + message);
        SettingsBridge.report(code);
    }
}

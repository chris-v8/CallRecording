package io.github.vvb2060.callrecording.xposed;

import android.content.Context;
import android.os.Build;
import android.provider.Settings;

import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Restores Google Phone's "return to call" bubble on Android 12+.
 *
 * Newer releases (verified on 247.0.1025958192) keep the bubble but refuse it
 * unconditionally in produceIsBubbleFeatureEnabledForCall:
 *
 *   args = [Context, Boolean inhibit, DialerBubbleActionController state]
 *   if (SDK_INT >= 31)               -> false  "Not showing bubble in Android S+."
 *   else if (state.dismissed.get())  -> false
 *   else if (!canDrawOverlays(ctx))  -> false
 *   else                             -> !inhibit
 *
 * The hook re-evaluates only the remaining checks. Older releases gate the
 * bubble on a flag instead and log a different message, so they are never
 * matched. Any unexpected shape leaves Google Phone's decision unchanged.
 */
final class BubbleHook {
    private static final String TAG = "CallRecording/Bubble";
    private static final String PRODUCER_MARKER = "produceIsBubbleFeatureEnabledForCall";
    private static final String SDK_GATE_MARKER = "Not showing bubble in Android S+.";
    private static volatile boolean firstHit = true;

    private BubbleHook() {}

    static void install(DexHelper dex) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            XposedBridge.log(TAG + ": below Android 12, not needed");
            return;
        }
        Method producer = discoverProducer(dex);
        if (producer == null) {
            XposedBridge.log(TAG + ": no Android 12 bubble gate in this Google Phone version");
            return;
        }
        Method factory = AutoRecordingHook.discoverCompletedFutureFactory(dex, producer);
        if (factory == null) {
            XposedBridge.log(TAG + ": no unique immediate Future factory, leaving bubble gate intact");
            return;
        }
        XposedBridge.hookMethod(producer, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    Boolean show = evaluate(param.args[0]);
                    if (show == null) return;
                    Object result = factory.invoke(null, show);
                    if (!producer.getReturnType().isInstance(result)) return;
                    param.setResult(result);
                    if (firstHit) {
                        firstHit = false;
                        XposedBridge.log(TAG + ": Android 12 gate bypassed, show=" + show);
                    }
                } catch (Throwable error) {
                    XposedBridge.log(TAG + ": evaluation failed, using original: " + error);
                }
            }
        });
        XposedBridge.log(TAG + ": bubble gate hooked: " + producer);
    }

    /** The original decision without the SDK check, or null if the inputs look unfamiliar. */
    private static Boolean evaluate(Object arg) throws IllegalAccessException {
        if (!(arg instanceof List)) return null;
        List<?> inputs = (List<?>) arg;
        if (inputs.size() < 3) return null;
        if (!(inputs.get(0) instanceof Context)) return null;
        if (!(inputs.get(1) instanceof Boolean)) return null;
        Object state = inputs.get(2);
        if (state == null) return null;
        Field dismissed = uniqueAtomicBoolean(state.getClass());
        if (dismissed == null) return null;

        Context context = (Context) inputs.get(0);
        boolean inhibit = (Boolean) inputs.get(1);
        if (((AtomicBoolean) dismissed.get(state)).get()) return false;
        if (!Settings.canDrawOverlays(context)) return false;
        return !inhibit;
    }

    private static Field uniqueAtomicBoolean(Class<?> type) {
        Field found = null;
        for (Field field : type.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            if (field.getType() != AtomicBoolean.class) continue;
            if (found != null) return null;
            found = field;
        }
        if (found != null) found.setAccessible(true);
        return found;
    }

    private static Method discoverProducer(DexHelper dex) {
        Set<Member> gated = new LinkedHashSet<>();
        for (long index : dex.findMethodUsingString(SDK_GATE_MARKER, false, -1, (short) 1,
                null, -1, null, null, null, false)) {
            Member member = dex.decodeMethodIndex(index);
            if (member != null) gated.add(member);
        }
        Set<Method> valid = new LinkedHashSet<>();
        for (long index : dex.findMethodUsingString(PRODUCER_MARKER, false, -1, (short) 1,
                null, -1, null, null, null, false)) {
            Member member = dex.decodeMethodIndex(index);
            if (!(member instanceof Method) || !gated.contains(member)) continue;
            Method method = (Method) member;
            if (method.getParameterTypes()[0] != Object.class) continue;
            if (!Future.class.isAssignableFrom(method.getReturnType())) continue;
            valid.add(method);
        }
        return valid.size() == 1 ? valid.iterator().next() : null;
    }
}

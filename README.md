# CallRecording (chris-v8 fork)

An LSPosed module that unlocks **Google Phone's built-in call recording** on phones and countries where Google switches it off, plus a few extras:

- record calls in countries Google doesn't support (tested in Poland)
- keep working on phones **without a text-to-speech engine**
- optionally **record every call automatically**, contacts included
- bring back the **floating "return to call" bubble** on Android 12+ in newer Google Phone versions

This fork is based on [vvb2060/CallRecording](https://github.com/vvb2060/CallRecording) v1.3. See [AUTO_RECORDING.md](AUTO_RECORDING.md) and [RECORDING_START_TIMING.md](RECORDING_START_TIMING.md) for the technical notes on the auto-record and timing features.

> **Consent.** Recording a phone call without telling the other person is illegal in many places. The module can silence Google's "this call is being recorded" announcement when no voice engine is available. Telling the other side is your responsibility.

---

## Contents

1. [Tested setup](#1-tested-setup)
2. [Words you'll see in this guide](#2-words-youll-see-in-this-guide)
3. [How the module works, in one picture](#3-how-the-module-works-in-one-picture)
4. [Problems found and how they were fixed](#4-problems-found-and-how-they-were-fixed)
   - [4.1 "Couldn't start recording due to a system problem"](#41-couldnt-start-recording-due-to-a-system-problem)
   - [4.2 Auto-record settings randomly switched off ("incompatible region")](#42-auto-record-settings-randomly-switched-off-incompatible-region)
   - [4.3 Supporting a newer Google Phone (v247)](#43-supporting-a-newer-google-phone-v247)
   - [4.4 The "return to call" bubble disappeared on Android 12+](#44-the-return-to-call-bubble-disappeared-on-android-12)
   - [4.5 "Record all calls" did nothing on v88](#45-record-all-calls-did-nothing-on-v88)
   - [4.6 Adding several "Selected numbers" at once](#46-adding-several-selected-numbers-at-once)
5. [Tour of the code](#5-tour-of-the-code)
6. [Building, installing and testing](#6-building-installing-and-testing)
7. [Reading the logs](#7-reading-the-logs)
8. [Switching Google Phone versions safely](#8-switching-google-phone-versions-safely)
9. [Where to learn more](#9-where-to-learn-more)

---

## 1. Tested setup

| | |
|---|---|
| Phone | Oplus/ColorOS device (model `RE54ABL1`), rooted with Magisk |
| Android | 12 |
| Hook framework | LSPosed (Vector), module scope: `com.google.android.dialer` |
| Google Phone | **88.0.472595733** (installed as a system app) and **247.0.1025958192** (as an update) |
| Country / SIM | Poland (PL) |
| Text-to-speech engine | **None installed** |

Status per Google Phone version:

| Feature | v88 | v247 |
|---|---|---|
| Manual recording | ✅ | ✅ |
| Auto-record unknown / selected numbers (Google's settings) | ✅ | ✅ |
| Settings no longer switch themselves off | ✅ tested | ✅ (same protection) |
| "Record all calls" (module setting) | ✅ tested | ✅ tested |
| Return-to-call bubble on Android 12 | ✅ (built in) | ✅ restored by the module |
| Multi-select for "Selected numbers" | ⏳ waiting for test | ⏳ waiting for test |

Other versions (e.g. 100, 120, …) are untested. The module is written to *skip* anything it can't recognise rather than break, so on an unknown version the worst case should be "that feature doesn't work", not a crash. Check the logs (section 7) to see which parts found their targets.

---

## 2. Words you'll see in this guide

| Word | Meaning |
|---|---|
| **Google Phone / dialer** | The Phone app, package name `com.google.android.dialer`. |
| **Root / Magisk** | Full administrator access to the phone. Needed for LSPosed. |
| **LSPosed** | A framework that lets small add-ons (*modules*) change how other apps behave, **without** modifying the apps themselves. |
| **Module** | This project. LSPosed loads it into Google Phone every time Google Phone starts. |
| **Scope** | The list of apps LSPosed loads a module into. Ours: only Google Phone. |
| **Method** | A named piece of code inside an app that does one job, e.g. "can this call be recorded?". Like a function or recipe. |
| **Hook** | A detour the module installs on a method. When Google Phone calls that method, our code runs instead of (or before/after) Google's. |
| **Return value** | The answer a method gives back, e.g. `true`/`false`. Many of our hooks simply change the answer. |
| **APK** | An Android app package (`.apk` file). |
| **DEX** | The compiled code inside an APK. |
| **Obfuscation** | Google scrambles the names in its code (a method that checks recording becomes `Lkwj;->a()`), and the scrambled names change in every version. So we can't hook methods by name. |
| **DexHelper** | A small C++ tool included in this module that searches Google Phone's DEX for a method by the **text messages it contains**, which don't get scrambled. |
| **Decompiler (jadx)** | A program that turns DEX back into readable (if ugly) Java code, so we can see what a method does. |
| **Future** | A "promise of an answer later". Google Phone often answers questions with a Future instead of an immediate `true`/`false`. |
| **TTS (text-to-speech)** | The voice engine that reads text aloud. Google Phone uses it for the "this call is being recorded" announcement. |
| **WorkManager job / worker** | A background task Android runs on a schedule, even if you never open the app. |
| **logcat** | Android's live system log, readable over USB with `adb logcat`. |
| **ADB** | Android Debug Bridge: the command-line tool that talks to the phone over USB. |

---

## 3. How the module works, in one picture

```
Google Phone starts
        │
        ▼
LSPosed loads the module  ──►  Init.handleLoadPackage()        (app/src/main/java/.../xposed/Init.java)
        │
        ├─ 1. RIGHT NOW, before any Google Phone code runs  (installEligibilityHooks)
        │      • open DexHelper (search tool)
        │      • canRecordCall()            → always "yes"
        │      • geofence check             → always "inside allowed area"
        │      • isCallRecordingCountry()   → always "yes"
        │      • TTS locale                 → English (US)
        │      • TTS hooks                  → silent fallback when no voice engine
        │
        └─ 2. In the background, a moment later  (installDeferredHooks)
               • "record all calls" policy (if enabled in the module's app)
               • recording-start timing diagnostics
               • Android 12 bubble gate
               • close DexHelper
```

All the *finding* is done by **text markers**. Example: Google Phone's "can I record?" method writes the word `canRecordCall` into its own log, so DexHelper looks for "the method that contains the text `canRecordCall`, takes no inputs and answers true/false". That description stays the same across versions, even though the scrambled name doesn't.

---

## 4. Problems found and how they were fixed

### 4.1 "Couldn't start recording due to a system problem"

**Symptom.** Pressing record during a call showed *"Couldn't start recording due to a system problem"*. Nothing was recorded.

**What the logs showed.** Every press produced this in `logcat`:

```
DialerLogcatLoggingBindings: Impression: CALL_RECORDING_BUTTON_START
TextToSpeech: synthesizeToFile failed: not bound to TTS engine
CallRecording: synthesizeToFile: TTS failed and silent fallback is disabled; result=-1
LegacyCallRecordingImpl: recording state: IDLE
```

So the recorder never even started. The failure was one step earlier:

1. Before recording, Google Phone plays an announcement ("this call is being recorded").
2. Poland isn't on Google's list of countries with a built-in recorded announcement (`country PL not supported by builtInAudio`), so it falls back to generating the sentence with **text-to-speech**.
3. The phone had **no TTS engine at all**: Google's speech engine was present in the system but uninstalled for the user (`pm list packages -u` shows `com.google.android.tts`), and `query-services` for `TTS_SERVICE` returned *No services found*.
4. The module build running at that moment (an experimental 1.6 APK) had its silent fallback **switched off**, so the TTS failure was passed straight on and Google Phone gave up.

One more trap: after reinstalling the module, **Google Phone was still running with the old code loaded** in memory. A module change only takes effect after Google Phone's process restarts (force-stop it).

**The fix.** The code in this repository already handles a missing TTS engine. It was added upstream as "Support devices without TTS". With the right build installed and Google Phone restarted, recording works. Here's how that code works.

`Init.java`, method `hookSynthesizeToFile()`, shortened:

```java
// Google Phone asks Android: "turn this text into a sound file"
Method synthesizeToFile = TextToSpeech.class.getDeclaredMethod("synthesizeToFile", ...);

XposedBridge.hookMethod(synthesizeToFile, new XC_MethodHook() {

    // runs BEFORE Android's own method
    protected void beforeHookedMethod(MethodHookParam param) {
        param.args[0] = "";          // replace the sentence with empty text
    }

    // runs AFTER Android's own method
    protected void afterHookedMethod(MethodHookParam param) {
        if (result is not SUCCESS) {                 // TTS failed (e.g. no engine)
            write a tiny, valid, silent WAV file     // to the file Google Phone asked for
            param.setResult(TextToSpeech.SUCCESS);   // pretend it worked
            call listener.onDone(utteranceId);       // tell Google Phone "the sound is ready"
        }
    }
});
```

The last step matters: Google Phone doesn't just check the answer, it **waits for an "I'm done" callback** (`onDone`). Without it, recording would still never start.

Two more small hooks back this up:
- `hookDispatchOnInit()`: when the TTS engine reports "I failed to start", the module changes that to "success", so Google Phone keeps going.
- `hookIsLanguageAvailable()`: answers "yes, this language is available" so Google Phone doesn't stop early.

**Lesson.** Read the log *around* the error. The button press, the TTS failure and the "still IDLE" line together tell the whole story.

---

### 4.2 Auto-record settings randomly switched off ("incompatible region")

**Symptom.** With *Record unknown numbers* and *Record selected numbers* turned on, every so often both switched themselves **off**, with a notice about an unsupported region. There was no pattern.

**What we found.** Google Phone contains a **background job** named `recording_geofence_check_worker`. Android runs it about **once a day**, even when you never open the dialer. In the decompiled code it does this:

```
if (!(canRecordCall() && withinGeofence())) {
    show notification "auto_recording_geofence"
    switch off "always record" settings
}
```

Both checks are hooked by the module, so normally the job sees "allowed" and does nothing. So why did it sometimes switch the settings off?

**Timing.** In both the original module and this fork, the hooks were installed on a **background thread** after a search that takes 70–250 ms:

```java
// BEFORE the fix (simplified)
public void handleLoadPackage(...) {
    new Thread(() -> installHooks(classLoader)).start();   // hooks arrive "a bit later"
}
```

If Android started Google Phone *just to run that background job*, the job could ask "can I record?" **before the hooks existed**. It then got Google's real answer (*"Call recording is disabled by the call recording flag"*) and turned the settings off. Whether the job or the hooks got there first changed from one start to the next, which is why it looked random. This kind of bug is called a **race condition**.

**The fix.** Install the important hooks *immediately*, before `handleLoadPackage` returns. Google Phone's own code can't run until then, so it can never see an unhooked answer. Only the slow, non-essential steps stay in the background.

`Init.java`, after the fix:

```java
public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
    if (!"com.google.android.dialer".equals(lpparam.packageName)) return;   // only Google Phone
    ...
    var dex = installEligibilityHooks(lpparam.classLoader);                  // ① now, on the main thread
    new Thread(() -> installDeferredHooks(dex), "CallRecording-Init").start(); // ② the rest, in background
}
```

- `installEligibilityHooks` opens DexHelper and hooks `canRecordCall`, the geofence check, the locale and `isCallRecordingCountry`, plus the quick TTS hooks.
- `installDeferredHooks` receives the **same, already-open** DexHelper, runs the slow searches (record-all policy, timing diagnostics, bubble), then closes it.
- Every step goes through `runHookStep(...)`, which catches any error. One broken hook can never crash Google Phone or stop the other hooks.

**Cost.** Google Phone's process starts about **55–105 ms** later (once Android has optimised a new dialer version, the first start can take longer). That only happens when the process starts from scratch, not every time you open the app.

**How it was tested.**
1. Killed Google Phone completely.
2. Looked up the job's ID in Google Phone's WorkManager database (`no_backup/androidx.work.workdb`). It was ID **20**.
3. Forced that one job to run: `adb shell cmd jobscheduler run -f com.google.android.dialer 20`
4. Android started Google Phone just for the job (`am_proc_start … service SystemJobService`). The log showed `eligibility-complete durationMs=54` *before* any dialer recording code. No notification appeared, and **both settings stayed on**.

---

### 4.3 Supporting a newer Google Phone (v247)

The newest Google Phone APK was scanned for every text marker the module uses:

| Marker | v88 | v247 |
|---|---|---|
| `canRecordCall` | ✅ | ✅ |
| `withinCrosbyGeoFence` | ✅ | ❌ **renamed** to `withinCallRecordingGeoFence` |
| `getSupportedLocaleFromCountryCode` | ⚠️ different shape (see below) | ✅ |
| `isCallRecordingCountry` | ✅ | ✅ |
| auto-record policy | different name (see 4.5) | ✅ `produceShouldStartAutomaticRecordingForContact` |

**Fix.** The geofence hook now tries **both** names and hooks whichever exists (`Init.java`):

```java
private static final String[] GEOFENCE_MARKERS = {
        "withinCrosbyGeoFence", "withinCallRecordingGeoFence"};

for (String marker : GEOFENCE_MARKERS) {
    find a method that contains `marker`, takes no inputs, returns true/false
    if found → hook it to always return true
}
if nothing was found → log a warning (and carry on)
```

On v247 the daily geofence job (4.2) only calls `canRecordCall()`, which is hooked, so the settings stay protected there too.

**Known, harmless:** on v88 the log says `getSupportedLocaleFromCountryCode method not found`. The hook looks for the shape the method has in newer versions (returns a `Locale`, 2 inputs); in v88 it returns an `Optional<Locale>` with 3 inputs. The result is that v88 uses TTS (→ silent fallback) instead of its built-in English announcement. Recording works either way.

---

### 4.4 The "return to call" bubble disappeared on Android 12+

**Symptom.** In v88, leaving a call shows a floating bubble to jump back in. Newer Google Phone versions show nothing on Android 12; the only way back is the notification. That's why the phone was kept on v88.

**What we found.** The bubble code is **still inside v247**, buttons and all (speaker, mute, hang up, back to call). One method decides whether to show it. Decompiled with **jadx** (class `pix`, method `b`), it reads:

```java
// produceIsBubbleFeatureEnabledForCall      inputs: [Context, Boolean inhibit, state]
if (Build.VERSION.SDK_INT >= 31) {                  // 31 = Android 12
    log("Not showing bubble in Android S+.");       // ← v247 always says no on Android 12+
} else if (state.dismissed.get()) {                  // you swiped the bubble away this call
    ...
} else if (!Settings.canDrawOverlays(context)) {     // no "display over other apps" permission
    ...
} else if (!inhibit) {
    show = true;
}
return immediateFuture(show);
```

In v88 the same check exists, but only blocks when a server flag (`enable_android_s_notifications`) is on. The flag was off on this phone, so v88 kept the bubble. In v247 the block is **unconditional**, so no setting or flag can undo it.

Other version checks in the bubble code (`BubbleImpl`) turned out to be *API choices*, not blocks. For example, on Android 12+ it creates its window with `createWindowContext(TYPE_APPLICATION_OVERLAY)`. Google kept the bubble's drawing code working on Android 12 and only switched the feature off at that one decision point.

**The fix:** `BubbleHook.java`. The hook performs Google's same decision **minus the Android-version line**:

```java
private static Boolean evaluate(Object arg) {
    if (arg is not a list of at least 3 things)       return null;   // unfamiliar → don't touch
    Context context = inputs.get(0);
    boolean inhibit = inputs.get(1);
    Object state    = inputs.get(2);
    Field dismissed = the one AtomicBoolean field in `state`;       // "swiped away" flag
    if (no such single field)                          return null;   // unfamiliar → don't touch

    if (dismissed is true)                    return false;   // keep Google's "swiped away" rule
    if (!Settings.canDrawOverlays(context))   return false;   // keep the permission rule
    return !inhibit;                                          // keep Google's inhibit rule
}
```

Safety rules built in:
- It only activates when the method contains **both** `produceIsBubbleFeatureEnabledForCall` **and** the exact sentence `Not showing bubble in Android S+.`. v88 has a different sentence, so the hook stays off there and v88's bubble behaves exactly as before.
- It does nothing below Android 12.
- If anything looks unfamiliar, it returns `null`, which means "let Google's original code decide".
- The answer is wrapped using **the same "finished Future" helper Google's method itself uses**, found automatically (`AutoRecordingHook.discoverCompletedFutureFactory`).

**Result** (from the LSPosed log on v247):

```
CallRecording/Bubble: bubble gate hooked: public final ajii pix.b(java.lang.Object)
CallRecording/Bubble: Android 12 gate bypassed, show=true
```

**If the bubble ever doesn't appear on another version**, look for `can't show/update bubble, bubble feature not present` in logcat. That's a *second* switch, the server flags `G__enable_return_to_call_bubble` / `G__enable_return_to_call_bubble_v2`, which would need GAppsMod or another hook.

---

### 4.5 "Record all calls" did nothing on v88

**Symptom.** The module's own setting *Automatically record all calls* worked on v247 but had no effect on v88. The log said `AutoAll: No unique supported policy method` every time.

**What we found.** The hook looked for one exact name, `produceShouldStartAutomaticRecordingForContact`, which is what the decision method is called in v244/v247. In v88 the same decision exists **twice**, under different names:

| Method (v88) | Part of Google Phone |
|---|---|
| `produceShouldStartAutomaticRecording` (`Ldew;->b`) | the newer recording service |
| `produceIsCrosbyEnabledForCall` (`Lkup;->b`) | the older in-call screen ("Crosby" is Google's code name for automatic recording). On this phone, v88's log showed this one in use. |

Both read the same "unknown numbers / contacts" settings, check the geofence, and answer through the same "finished Future" helper.

**The fix:** `AutoRecordingHook.java` now tries a list of names and hooks every one that matches exactly:

```java
private static final String[] POLICY_MARKERS = {
        "produceShouldStartAutomaticRecordingForContact",   // 2xx (e.g. v247)
        "produceShouldStartAutomaticRecording",             // 88.x
        "produceIsCrosbyEnabledForCall",                    // 88.x, older in-call screen
};
```

When a call starts and *Automatically record all calls* is on, the hook answers "yes, record" for every matched method. When the setting is off, it does nothing and Google's own rules apply.

Status: **tested on v88 and v247.** The LSPosed log shows two `Policy hooked:` lines on v88 (`dew.b`, `kup.b`) and one on v247 (`kzj.b`), followed by `first eligibility override` during a call.

> **Heads-up:** this setting is **on by default** in the module's app. With it on, *every* call is recorded, contacts included, regardless of Google's "unknown / selected numbers" settings. Switch it off in the **Call Recording** app if you only want Google's selective recording, then force-stop Google Phone.

---

### 4.6 Adding several "Selected numbers" at once

**Wish.** Google Phone's *Call recording → Selected numbers → Add* lets you pick **one** contact at a time. Adding a dozen people means a dozen round trips.

**What we found** (v88 classes `cym`, `dic`, `dbt`, `csf`; same markers in v247):

```
Add button  → Intent(ACTION_PICK, type "vnd.android.cursor.dir/phone_v2")   // "pick ONE phone number"
            → startActivityForResult(…, 9)
Result      → dic.Z(9, RESULT_OK, data)        // log: "uri received from system contact picker"
            → read column data1 (the number) from data.getData()
            → normalise the number (+48…)
            → settings store.updateData( add number if not already there )
```

The last step matters for safety: each pick hands the settings store a small "add this one number" change. The store applies changes **one after another** and skips duplicates, so adding many numbers quickly can't overwrite each other. Nothing in the module writes Google Phone's settings file itself.

**The feature.** The Call Recording app gets a **multi-select list** of your phone numbers (`NumberPickerActivity`: search, checkboxes, "Add (N)"). There are two ways in:

1. **From Google Phone:** *Selected numbers → Add* opens the module's list instead of the one-contact picker. When you tap *Add (N)*, the module calls Google Phone's **own** result handler once per number, exactly as if you'd picked them one by one.
2. **From the Call Recording app:** *Select numbers to always record* → pick → the numbers are queued and Phone's settings open. When you open *Call recording → Selected numbers*, Google Phone adds the queued numbers through the same handler.

`SelectedNumbersHook.java` (inside Google Phone), simplified:

```java
// 1. Only while the "Add" button's click is being handled…
hookAddClick(click):  IN_ADD_CLICK = true … false

// …redirect "pick one phone number" to the module's list (other screens keep the system picker)
Activity.startActivityForResult(intent, …):
    if (IN_ADD_CLICK && PICK && type == phone_v2 && setting on && module list installed)
        intent → NumberPickerActivity

// 2. Several numbers came back → run Google Phone's own handler once per number
resultHandler(requestCode, resultCode, data):
    if (data has the module's list of ≥ 2 numbers)
        for each uri: original handler(requestCode, resultCode, Intent(data = uri))

// 3. Queue from the module app → applied when the Selected numbers screen appears
onViewCreated(...) → later: for each queued uri: original handler(9, RESULT_OK, Intent(data = uri))
```

Safety rules:
- With one contact ticked, the result looks exactly like the system picker's, and Google Phone's code handles it unchanged.
- If the module's list isn't installed or the setting *Multi-select in Phone's Add button* is off, the Add button opens the normal picker.
- The list only answers Google Phone and the Call Recording app. The queue stores contact *links*, not numbers, and is emptied once handed over.
- Turning on Google Phone's *Selected numbers* switch is still up to you; the module only adds numbers.

The Call Recording app now asks for the **Contacts** permission, to show the list. Contacts never leave the phone.

---

## 5. Tour of the code

All the hooking code lives in `app/src/main/java/io/github/vvb2060/callrecording/`:

```
xposed/
  Init.java                 Entry point. Decides what to hook and when (section 3).
                            Also: canRecordCall, geofence, locale, country, TTS hooks.
  DexHelper.java            Java front-end for the C++ search tool (app/src/main/jni/).
  AutoRecordingHook.java    "Record all calls" (4.5).
  BubbleHook.java           Android 12 bubble gate (4.4).
  SelectedNumbersHook.java  Multi-select for Google Phone's "Selected numbers" (4.6).
  RecordingTimingHook.java  Diagnostics only: logs when a call connects / recording starts.
  SettingsBridge.java       Lets hooks inside Google Phone read the module's settings.
settings/
  SettingsActivity.java     The "Call Recording" app screen (switches + hook status).
  SettingsProvider.java     Stores those settings; only Google Phone and the module may read them.
  NumberPickerActivity.java Multi-select list of phone numbers (4.6).
assets/xposed_init          Tells LSPosed which class to start: ...xposed.Init
```

### Anatomy of a hook

Here's the simplest one, `hookCanRecordCall` in `Init.java`, line by line:

```java
var canRecordCall = Arrays.stream(
        dex.findMethodUsingString("canRecordCall",   // 1. find methods containing this text…
                false,          //    exact text, not just the beginning
                -1,             //    any return type…
                (short) 0,      // 2. …that take 0 inputs…
                "Z",            // 3. …and answer true/false ("Z" = boolean in DEX)
                -1, null, null, null,
                true))          //    stop at the first match
        .mapToObj(dex::decodeMethodIndex)       // turn the search result into a real Method
        .filter(Objects::nonNull)
        .findFirst();

if (canRecordCall.isPresent()) {
    XposedBridge.hookMethod(canRecordCall.get(),   // 4. install the detour…
        new XC_MethodReplacement() {               //    …that REPLACES Google's method
            protected Object replaceHookedMethod(MethodHookParam param) {
                return true;                       // 5. always answer "yes, you may record"
            }
        });
} else {
    Log.e(TAG, "canRecordCall method not found");  // 6. not found → log it, don't crash
}
```

Two kinds of hooks appear in this project:

| Kind | Used when | Example |
|---|---|---|
| `XC_MethodReplacement` | Google's answer should be ignored completely | `canRecordCall` → always `true` |
| `XC_MethodHook` with `beforeHookedMethod` / `afterHookedMethod` | Look at (or change) the inputs before, or the answer after, Google's code runs | TTS: change text before, fix the result after; bubble: decide before, only when inputs look right |

### The "fail closed" habit

Every new hook in this fork follows the same pattern:
1. Find the target by **text + shape** (number and types of inputs, return type).
2. Require **exactly one** match. Zero or several matches mean "this version is different", so skip.
3. Check the inputs at runtime. If they look unfamiliar, **leave Google's behaviour alone**.
4. Log what happened, so the LSPosed log tells you which parts are active.

That's what makes it reasonably safe to try new Google Phone versions: an unrecognised version loses a feature instead of crashing.

---

## 6. Building, installing and testing

### Build with GitHub Actions (no tools needed on your PC)

1. Push your changes to a branch on GitHub (a test branch keeps `main` untouched):
   ```bat
   git switch -c test/my-change
   git add <changed files>
   git commit -m "describe the change"
   git push -u origin test/my-change
   ```
2. On GitHub → **Actions**:
   - the **Build** workflow runs automatically on every push. Download **ubuntu-latest-artifact**; the APK is in `apk/debug/`.
   - or open **Debug APK** → **Run workflow** → choose your branch → download **CallRecording-debug-apk**.

`main` builds automatically with *Debug APK* whenever files under `app/` change.

### Install and activate

1. Install the APK on the phone (`adb install -r app-debug.apk`, or open it on the phone).
2. In **LSPosed**, make sure the module is enabled and **Google Phone** is ticked in its scope.
3. **Force-stop Google Phone**, otherwise it keeps running the old code:
   ```bat
   adb shell am force-stop com.google.android.dialer
   ```
4. Open the **Call Recording** app to check *Hook status* and the *Automatically record all calls* switch.

### Quick test checklist

- [ ] LSPosed log shows `eligibility-complete` before `worker-start`
- [ ] Manual record button works and a recording is saved
- [ ] Unknown number is auto-recorded (if enabled in Google Phone settings)
- [ ] Saved contact is auto-recorded **only** if *record all calls* is on
- [ ] Bubble appears after leaving the call screen (Android 12+)
- [ ] After a day or two, Google Phone's auto-record settings are still on

---

## 7. Reading the logs

**LSPosed module log**: the most useful one. On the phone in the LSPosed app → Logs, or over USB:

```bat
adb shell su -c "cat /data/adb/lspd/log/modules_*.log" | findstr CallRecording
```

| Line | Meaning |
|---|---|
| `Init: eligibility-complete durationMs=…` | Critical hooks installed; the number is the startup delay |
| `Init: <step> success` | That step didn't crash. It does **not** always mean the target was found; check logcat for "not found" |
| `AutoAll: Policy hooked: …` | Record-all found a decision method (one line per method) |
| `AutoAll: No unique supported policy method` | Record-all can't work on this Google Phone version |
| `AutoAll: first eligibility override` | Record-all actually told Google Phone to record a call |
| `Bubble: bubble gate hooked` / `gate bypassed, show=true` | Bubble fix active / used |
| `Bubble: no Android 12 bubble gate in this Google Phone version` | Normal on v88 (it has its own bubble) |
| `Timing: telecom-connected` / `telecom-disconnected` | A call started / ended |

**logcat**: Android's live log, including Google Phone's own messages and the module's "not found" warnings:

```bat
adb logcat -d -s CallRecording:*
```
```bat
adb logcat -d | findstr /i "CallRecording LegacyCallRecordingImpl TextToSpeech bubble"
```

Newer Google Phone versions (e.g. v247) write far less to logcat than v88, so the LSPosed log is often the better source there.

---

## 8. Switching Google Phone versions safely

Updating Google Phone (e.g. v88 → v247) and going back can leave its data in a newer format that the older version can't read.

**Before updating, back up its data** (needs root; this also contains your recordings and recording settings):

```bat
adb shell am force-stop com.google.android.dialer
adb shell "su -c 'cd / && tar -czpf /data/local/tmp/dialer-backup.tgz data/data/com.google.android.dialer data/user_de/0/com.google.android.dialer && chmod 644 /data/local/tmp/dialer-backup.tgz'"
adb pull /data/local/tmp/dialer-backup.tgz C:\dev\dialer-backup.tgz
adb shell "su -c 'rm /data/local/tmp/dialer-backup.tgz'"
```

**Going back to the built-in version:** Settings → Apps → Phone → ⋮ → **Uninstall updates**, or:

```bat
adb shell pm uninstall-system-updates com.google.android.dialer
```

Afterwards: copy any recordings made on the newer version off the phone first, restore the backup from the version you're returning to, and force-stop Google Phone.

**The module itself doesn't need changing** when you switch versions. It detects what's there each time Google Phone starts.

Your call history and contacts are stored by Android, not by Google Phone, so they're not affected.

---

## 9. Where to learn more

- **Java basics**: the module is written in Java. Any beginner Java course covers methods, `if`, classes and fields, which is enough to follow this code.
- **Xposed API**: the original [Xposed development tutorial](https://github.com/rovo89/XposedBridge/wiki/Development-tutorial) explains `IXposedHookLoadPackage`, `handleLoadPackage` and `XC_MethodHook`.
- **LSPosed**: [github.com/LSPosed/LSPosed](https://github.com/LSPosed/LSPosed) (framework, scopes, logs).
- **jadx**: [github.com/skylot/jadx](https://github.com/skylot/jadx), the decompiler used to read Google Phone's code. Use `--single-class <name>` to decompile one class at a time.
- **ADB**: [developer.android.com/tools/adb](https://developer.android.com/tools/adb).
- **Git**: [git-scm.com/book](https://git-scm.com/book), chapters 1–3 cover everything used here (commit, branch, push, remotes).

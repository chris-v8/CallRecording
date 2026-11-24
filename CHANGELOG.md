# Changelog

All notable changes to this fork of [vvb2060/CallRecording](https://github.com/vvb2060/CallRecording). Versions match `versionName` in `app/build.gradle.kts`. Details, background and code walkthroughs are in the [README](README.md).

## 1.5 (2026-11-24)

Tested on Google Phone **88.0.472595733** and **247.0.1025958192**, Android 12.

### New

- **Multi-select for Google Phone's "Selected numbers" list** (always record).
  - Google Phone's *Settings → Call recording → Selected numbers → Add* now opens a multi-select list of your phone numbers, with search, instead of the one-contact picker.
  - Numbers already on the list start ticked. Untick to remove, tick to add, then **Save (+added / −removed)**.
  - Changes go through Google Phone's own add and remove code, so the list is saved exactly as if you'd edited it by hand.
  - New entry in the Call Recording app: **Select numbers to always record**. Changes made there are queued and applied the next time Google Phone's *Selected numbers* screen opens.
  - New switch: **Multi-select in Phone's Add button** (on by default) to turn the redirect off.
  - The Call Recording app now asks for the **Contacts** permission (read only, stays on the phone).
- **Return-to-call bubble on Android 12+ in newer Google Phone versions.** v247 hides the bubble on Android 12 and later; the module skips only that version check and keeps Google's other rules (dismissed for this call, overlay permission, inhibit).
- **"Record all calls" on Google Phone 88.** The setting now also finds v88's two automatic-recording decisions (`produceShouldStartAutomaticRecording`, `produceIsCrosbyEnabledForCall`). Previously it only worked on v2xx.
- **Google Phone v247 support:** the geofence check is found under its new name, `withinCallRecordingGeoFence`, as well as the old `withinCrosbyGeoFence`.

### Fixed

- **"Always record" settings randomly switched off** ("incompatible region"). Google Phone's daily `recording_geofence_check_worker` could run before the module's hooks were installed and switch the settings off. The eligibility hooks (`canRecordCall`, geofence, country, locale, TTS) are now installed before any Google Phone code runs. This adds about 55–105 ms when Google Phone's process starts from scratch.

### Changed

- The GitHub Actions *Debug APK* workflow builds on pushes to `main` (was `master`).
- The *Build* workflow no longer builds on macOS (the runner wasn't picking up jobs).

### Docs

- New [README](README.md) for non-programmers: tested setup, glossary, the problems found and how they were fixed, a tour of the code, building and testing, reading the logs, switching Google Phone versions safely, and known limitations.
- [AUTO_RECORDING.md](AUTO_RECORDING.md) lists the method names used on v88 and v247.

### Known limitations

See [README section 9](README.md#9-known-limitations). The main one: the Call Recording app's checkmarks come from a copy of Google Phone's list, which only exists after Google Phone's *Selected numbers* screen has been opened once.

## 1.4-auto

Fork of vvb2060/CallRecording v1.3.

- Native "Automatically record all calls" policy hook and a settings app (switches, hook status).
- Recording-start timing diagnostics ([RECORDING_START_TIMING.md](RECORDING_START_TIMING.md)).
- Each hook installed independently, so one failure doesn't stop the others.
- *Debug APK* GitHub Actions workflow.

## 1.3 and earlier

See [vvb2060/CallRecording](https://github.com/vvb2060/CallRecording).

# ShiftX

Android training app with a Hammerhead Karoo extension.

## Installing on your phone

Open this link on the phone and tap through the download — it always serves the newest release:

**https://github.com/iamchriswest88-dot/Shiftx/releases/latest/download/shiftx.apk**

The first time, Android will ask you to allow "install unknown apps" for your browser. After that
every update installs straight over the last one and keeps your data — workouts, exercise logs,
segment matches and cached leaderboards all survive.

No adb, no debugger mode.

## Installing on the Karoo

There is no separate Karoo build. `ShiftExtension` is a service inside this same APK, declared in
the same manifest against `io.hammerhead.karooext.KAROO_EXTENSION`, so one release covers the phone
app and the four Karoo data types (`pr-delta`, `segment-distance`, `segment-page`, `race-view`).

**Karoo 3** — no cable needed. Long-press the release link above on your phone, share it to the
Hammerhead Companion app, and confirm the Install prompt that appears on the Karoo. Hammerhead has
only verified GitHub URLs for this, and the link above is one.

**Karoo 2** — Companion App sideloading is Karoo 3 only. Enable Developer Options (Settings → About
→ tap Build Number repeatedly) and USB debugging, then:

```bash
adb install -r shiftx.apk
```

`-r` reinstalls in place. That works now that builds share a signing key; before, it failed with
`INSTALL_FAILED_UPDATE_INCOMPATIBLE` unless you uninstalled first.

## Cutting a release

Either works, and both produce that same link.

**From the phone or any browser** — Actions tab → *Build ShiftX APK* → *Run workflow*, type the
version (`1.9`), Run. It builds, tags, and publishes.

**From a terminal**

```bash
git tag v1.9 && git push origin v1.9
```

`versionName` comes from the tag, `versionCode` from the Actions run number, so it always climbs.

## Strength module

The **Strength** tab runs a set-by-set session with a timer and tells you what to do before you
start. Plans come from the last six weeks of logged sets: clean sets move the load up one owned
increment in code, and only the judgement calls (a stall, a heavy cycling week, a two-week gap, a
session landing on a netball day) go to Claude. Whatever comes back is checked against the
equipment list and exercise library; anything that fails repeats last session unchanged.

The runner keeps its place on disk on every step, so a refresh, a locked screen or a killed app
lands back on the same set with the same countdown. Finished sessions are written to the local
database and, if intervals.icu is configured, pushed there as a WeightTraining entry.

Set the **Anthropic API Key** in Settings to enable the judgement step. Without it the
deterministic plan is used and the reasons it would have asked are listed on the card.

## Gemini API key

Set it once in the app's Settings screen. It is deliberately not baked into the APK — this repo is
public and anything compiled in is trivially extracted from a published release.

`local.properties` still works for local development builds:

```properties
GEMINI_API_KEY=your-key-here
```

## Signing

`app/shiftx.jks` is checked in on purpose. It is not protecting anything — it exists so every build
shares one signing identity and updates install over each other instead of failing with
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`. If this app is ever distributed to other people, replace it
with a real key held in repository secrets.

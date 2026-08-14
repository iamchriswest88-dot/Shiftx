# ShiftX

Android training app with a Hammerhead Karoo extension.

## Installing on your phone

Open this link on the phone and tap through the download — it always serves the newest release:

**https://github.com/iamchriswest88-dot/Shiftx/releases/latest/download/shiftx.apk**

The first time, Android will ask you to allow "install unknown apps" for your browser. After that
every update installs straight over the last one and keeps your data — workouts, exercise logs,
segment matches and cached leaderboards all survive.

No adb, no debugger mode.

## Cutting a release

Either works, and both produce that same link.

**From the phone or any browser** — Actions tab → *Build ShiftX APK* → *Run workflow*, type the
version (`1.9`), Run. It builds, tags, and publishes.

**From a terminal**

```bash
git tag v1.9 && git push origin v1.9
```

`versionName` comes from the tag, `versionCode` from the Actions run number, so it always climbs.

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

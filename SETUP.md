# Brosco — setup steps (phone-only, no computer needed)

This builds the APK in the cloud via GitHub Actions, so you never need Android Studio or a computer.

## 1. Create a GitHub account and repo
1. Sign up at github.com (works fine in your phone browser).
2. Create a new repository named `brosco` (public or private).

## 2. Upload every file into the exact folder path shown
Use "Add file → Upload files" in the repo, and for each one, type the full path (including folders) into the filename box before uploading:

| File | Upload path |
|---|---|
| `AndroidManifest.xml` | `app/src/main/AndroidManifest.xml` |
| `MainActivity.kt` | `app/src/main/java/com/brosco/assistant/MainActivity.kt` |
| `ClaudeApiClient.kt` | `app/src/main/java/com/brosco/assistant/ClaudeApiClient.kt` |
| `activity_main.xml` | `app/src/main/res/layout/activity_main.xml` |
| `build.gradle.kts` (app-level) | `app/build.gradle.kts` |
| `build.gradle.kts` (root-level) | `build.gradle.kts` |
| `settings.gradle.kts` | `settings.gradle.kts` |
| `gradle.properties` | `gradle.properties` |
| `build.yml` | `.github/workflows/build.yml` |

Two files share the name `build.gradle.kts` — make sure one lands at the repo root and the other inside `app/`, don't overwrite one with the other.

## 3. Add your Anthropic API key as a GitHub Secret (not a plain file)
1. Get a key from console.anthropic.com — this is a separate paid API account, billed on real usage, tracked on your API dashboard (not your claude.ai subscription).
2. In your repo: Settings → Secrets and variables → Actions → New repository secret.
3. Name: `ANTHROPIC_API_KEY`, Value: your real key. Save.

## 4. Trigger the build
1. Go to the **Actions** tab in your repo → you should see "Build Brosco APK".
2. If it hasn't run automatically, tap "Run workflow" (it's set to `workflow_dispatch` so you can trigger it manually too).
3. Wait a couple minutes for the build to finish (green checkmark = success).
4. Open the completed run → scroll to **Artifacts** → download `brosco-debug-apk`. It downloads as a `.zip` — unzip it (most phone file managers can do this) to get `app-debug.apk`.

## 5. Install it on your phone
1. Open the downloaded `app-debug.apk` file directly from your file manager.
2. Android will ask to allow installs from this source the first time — allow it.
3. Install, open, grant the permissions it asks for (mic, call, contacts, SMS).

## 4. Using it
- Tap the mic button, say a command:
  - **"Call Mom"** → dials Mom directly from your contacts, no API call, no cost.
  - **"Text John saying running late"** → sends an SMS, no API call.
  - **"Open Spotify"** → launches the app, no API call.
  - **"What's the latest on the Ukraine ceasefire talks"** → this one falls through to Claude, uses web search, costs a bit of API credit.
  - **"What's 15% of 340"** → falls through to Claude Haiku (cheap), no search needed.

## 5. Extending it
- Add more apps to the `appPackages` map in `MainActivity.kt` (find any package name via `adb shell pm list packages`).
- Add more instant, zero-cost commands (alarms, timers, flashlight) the same way as `call`/`text`/`open` before it falls through to the API.
- If you want a wake word ("Hey Brosco") instead of tapping a button, that needs an on-device model like Picovoice Porcupine — happy to wire that in next if you want it.

## Honest limitations
- **iOS is not supported** by this build — Apple doesn't allow apps to place calls or read contacts this freely. This is Android-only.
- It can't "connect to every app" — only apps that expose an Android Intent (calling, texting, opening, sharing). Most mainstream apps do.
- It's not always listening in the background yet — you tap to talk. Background listening needs extra battery/privacy tradeoffs worth deciding on deliberately.

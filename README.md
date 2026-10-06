# vibe-collector

Android app that catches the code your AI chatbot just wrote for you, works out what to
call the files, drops them into a project folder, and lets you browse everything later.

No cloud account or API key is required. Captures stay on-device unless you export them or explicitly use the optional AI structure endpoint.

## How it works

Chatbots cannot be read directly on modern Android — their conversations live in private
app sandboxes. So vibe-collector watches the **clipboard** instead, from inside an
Accessibility service, which is the one sanctioned way for an app to observe copies:

1. You ask a chatbot for code and tap **Copy**.
2. `ChatAccessibilityService` sees the clipboard change and hands it to `CaptureRules`.
3. `CodeBlockParser` pulls the fenced blocks apart and works out a filename for each one
   (`main.py`, `src/app/user.service.ts`, a `File:` heading, a `# path/to/x.js` comment
   header, a `// filename: x.js` line, and so on).
4. Each capture gets its own notification with **Save** and **Discard** actions. Guessed
   filenames always wait in the Inbox for review—even when **Yes to all** is enabled.
5. Saved files land in a project folder. Empty folders are created for you.

A **project structure** can be generated first — paste an ASCII tree, a bullet list, an
indented list, a markdown link list, or just a comma-separated path list, and every folder
and file gets created up front. Optionally an AI can draft the structure from a plain
English description, but that is strictly optional and off by default.

A floating bubble can sit over the chat app so you can jump back and forth without
leaving the conversation.

If you do not want to enable Accessibility, use Android's **Share** action (or
**Process text**) to send chatbot text to vibe-collector, or open **Inbox → Paste or
import code**. Monitoring waits for a new copy event and does not import the clipboard
contents already present when the service starts.

## Requirements

- Android 8.0 (API 26) or newer.
- Sideloaded APK. This app is not on the Play Store: Google Play restricts clipboard
  background access and overlays, so it is distributed as a build you install yourself.

## Setup

1. Install `app-debug.apk` (or the release APK from CI).
2. Open the app and grant the prompted permissions.
3. Optional: enable the accessibility service: **Settings → Accessibility →
   vibe-collector** for automatic clipboard capture. Share and paste/import work without it.
4. Enable notifications (Android 13+ asks separately).
5. Optional: enable the floating bubble and allow **Display over other apps**.

Both steps 3 and 5 open the relevant system settings directly from the in-app settings
screen, so you do not have to hunt for them.

## Where files are stored

Projects live in the app's own external files directory:

```
Android/data/com.vibecollector/files/Projects/<project>/...
```

That needs no storage permission on any supported Android version. Because the folder is
app-scoped, uninstalling vibe-collector deletes it — use **Export to Downloads** and choose
the Downloads folder in the save dialog to keep a copy.

Every path coming out of the clipboard or an AI response is sanitised: absolute paths,
drive letters, `..` traversal, and backslash traversal are all rejected before anything
touches the disk.

## Settings

| Setting | What it does |
| --- | --- |
| Capture | Master switch for automatic clipboard capture |
| Copy collect | Enable or disable capture of new copies |
| Pause capture | Pause automatic capture for an hour, or resume it |
| Notifications | Show save/discard prompts. Off means captures queue in **Inbox** |
| Yes to all | Save immediately only when filenames were clearly supplied |
| Files already exist | Ask, overwrite, or keep both copies |
| Chat apps only | Ignore copies made outside recognised chat apps |
| Privacy exclusions | Exclude selected assistant apps from capture |
| Hide code previews | Hide filenames and details in capture notifications |
| Minimum length | Skip short snippets like one-liners |
| Default project | Where captures go when you have not picked a project |
| Floating bubble | Show the overlay bubble and set its position |
| AI key / model / base | Optional, for generating a structure from a description |

The AI key is stored in the app's private preferences. It is not encrypted, and it is
only ever sent to the base URL you configure.

## Building

Requires JDK 17 and an Android SDK with API 34.

```bash
./gradlew testDebugUnitTest     # parser regression suite
./gradlew assembleDebug         # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease       # app/build/outputs/apk/release/app-release-unsigned.apk
```

The release APK is unsigned. Add a `signingConfig` in `app/build.gradle.kts` with your
own keystore to produce an installable release build.

Room, KSP and WorkManager are declared as dependencies for upcoming features; the
current storage layer uses plain files plus DataStore. The Gradle build could not be run
in the environment where this change was prepared (Google's Maven repository was
unreachable), so the CI workflow is the first real build of this configuration.

## CI

`.github/workflows/build-apk.yml` runs the unit tests, builds both APKs, and uploads them
as artifacts on every push and pull request.

## Tests

`app/src/test/java/com/vibecollector/parse/ParserTest.kt` holds 91 assertions covering the
two pure parsers: filename detection from info strings, header comments, prose labels,
multi-file splitting, language sniffing, path sanitisation, traversal rejection,
duplicate handling, plus every accepted project-structure format. Both parsers are pure
Kotlin, so they are testable without an emulator.

## Project layout

```
app/src/main/java/com/vibecollector/
  parse/       CodeBlockParser, TreeParser      - pure, fully tested
  data/        Models, VibeSettings              - DataStore settings
  storage/     ProjectStore                      - filesystem + ZIP export
  capture/     CaptureRules, CaptureCoordinator, ChatAccessibilityService
  notify/      CaptureNotifier, CaptureActionReceiver
  overlay/     BubbleService
  ai/          AiStructureClient                 - optional DeepSeek-compatible call
  ui/          VibeViewModel, screens, components, theme
```

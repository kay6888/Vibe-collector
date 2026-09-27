# vibe-collector

Android app that catches the code your AI chatbot just wrote for you, works out what to
call the files, drops them into a project folder, and lets you browse everything later.

No cloud account, no API key required, nothing leaves the device unless you export it.

## How it works

Chatbots cannot be read directly on modern Android — their conversations live in private
app sandboxes. So vibe-collector watches the **clipboard** instead, from inside an
Accessibility service, which is the one sanctioned way for an app to observe copies:

1. You ask a chatbot for code and tap **Copy**.
2. `ChatAccessibilityService` sees the clipboard change and hands it to `CaptureRules`.
3. `CodeBlockParser` pulls the fenced blocks apart and works out a filename for each one
   (`main.py`, `src/app/user.service.ts`, a `File:` heading, a `# path/to/x.js` comment
   header, a `// filename: x.js` line, and so on).
4. If the filename is only a guess, you get a notification: **Save**, **Discard**, or
   **Save all**.
5. Saved files land in a project folder. Empty folders are created for you.

A **project structure** can be generated first — paste an ASCII tree, a bullet list, an
indented list, a markdown link list, or just a comma-separated path list, and every folder
and file gets created up front. Optionally an AI can draft the structure from a plain
English description, but that is strictly optional and off by default.

A floating bubble can sit over the chat app so you can jump back and forth without
leaving the conversation.

## Requirements

- Android 8.0 (API 26) or newer.
- Sideloaded APK. This app is not on the Play Store: Google Play restricts clipboard
  background access and overlays, so it is distributed as a build you install yourself.

## Setup

1. Install `app-debug.apk` (or the release APK from CI).
2. Open the app and grant the prompted permissions.
3. Enable the accessibility service: **Settings → Accessibility → vibe-collector**.
   This is required for clipboard capture and cannot be granted from inside the app.
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
app-scoped, uninstalling vibe-collector deletes it — use **Export ZIP** (which writes to
your Downloads folder via the media store) to keep a copy.

Every path coming out of the clipboard or an AI response is sanitised: absolute paths,
drive letters, `..` traversal, and backslash traversal are all rejected before anything
touches the disk.

## Settings

| Setting | What it does |
| --- | --- |
| Capture | Master switch for watching the clipboard |
| Collect on copy | Capture automatically instead of only showing the notification |
| Notifications | Show the save/discard prompt. Off means captures queue in **Inbox** |
| Yes to all | Save everything without asking (use with care) |
| Chat apps only | Ignore copies made outside recognised chat apps |
| Minimum length | Skip short snippets like one-liners |
| Default project | Where captures go when you have not picked a project |
| Floating bubble | Show the overlay bubble and set its position |
| AI key / model / base | Optional, for generating a structure from a description |

The AI key is stored in the app's private preferences. It is not encrypted, and it is
only ever sent to the base URL you configure.

## Building

Requires JDK 17 and an Android SDK with API 35.

```bash
./gradlew testDebugUnitTest     # parser regression suite
./gradlew assembleDebug         # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease       # app/build/outputs/apk/release/app-release-unsigned.apk
```

The release APK is unsigned. Add a `signingConfig` in `app/build.gradle.kts` with your
own keystore to produce an installable release build.

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

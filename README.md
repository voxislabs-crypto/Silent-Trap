# Silent Trap

Silent Trap is an Android app that keeps the microphone open and saves a short clip only when it hears speech. A whisper is a first-class case: the detector is fed a boosted copy of the audio, and the saved file is amplified again so the whisper is listenable.

The microphone use is visible. A foreground-service notification stays up the whole time listening is on. There is no hidden or notification-free mode.

This is not legal advice. Nebraska is a one-party consent state: you may record a conversation you are part of. Recording a private conversation you are not a party to can be illegal. You are responsible for the law where you use the phone.

## What it saves

Each clip is 16 kHz mono WAV:

`2026-11-09_19-22-15_segment.wav`

The file contains:

- the pre-roll (default 3 seconds before speech)
- the speech
- the post-roll (default 3 seconds after speech)

If speech starts again during the post-roll, it stays in the same clip. A clip is split around 8 minutes so memory stays bounded. The split overlaps by the pre-roll, so nothing is dropped.

## Whisper path

Phone microphones record a whisper very quietly, often around −50 to −35 dBFS. Silero's normal threshold is a speech probability of 0.5, and a near-silent waveform often never reaches it.

Silent Trap does three things about that:

1. **Detection boost.** Each 32 ms frame is copied and gained until it is near −20 dBFS, capped by the sensitivity slider (about 28× at the default threshold of 0.25). Digital silence below about −62 dBFS is not boosted. Silero then runs on that copy in its sensitive `NORMAL` mode. At the default threshold, an energy detector can also open a clip when the level rises above the room floor, so a whisper Silero still misses is kept.
2. **Save gain.** The stored audio is multiplied by the gain slider (default 12×) and soft-clipped, so peaks round off instead of crunching.
3. **Whisper makeup.** If the whole clip is still quiet, its peak is lifted toward about −6 dBFS, with the extra gain capped at 8×.

Hardware noise suppression is turned off. It treats whispers as noise. The app prefers the raw `UNPROCESSED` microphone when the phone allows it, and falls back to the normal mic.

A 120 Hz high-pass runs before the save gain so rumble is not amplified. Software noise reduction is not included in this build. Clips still receive the high-pass filter, saved gain, and whisper makeup.

The home screen shows the live input level and the detector boost on the current frame. Each clip shows the input peak and the saved peak, so you can see the amplification.

If the room is noisy and clips open too often, raise the VAD threshold toward 0.45 or 0.60. If a whisper is missed, lower it toward 0.15, raise the save gain, and move the phone closer. Watch the meter: if it never moves off −90 dBFS, the mic is blocked or permission was denied.

## Run it

Open the `silent trap` folder in Android Studio (Quail / 2026.1 or newer is fine; the project uses Android Gradle Plugin 8.13.2). Let Studio install SDK 36 and create `local.properties`. Then run the `app` configuration on a device.

The app asks for the microphone, notifications, and a foreground microphone service. Internet is used only for Drive upload.

Minimum SDK is 26.

## Battery

Listening uses a foreground service and a partial wake lock so the capture thread keeps running with the screen off. Doze can still interfere on some phones.

On the settings screen, open battery settings and set Silent Trap to **Unrestricted** (Settings → Apps → Silent Trap → Battery → Unrestricted).

## Google Drive

Uploads go to a folder named **Silent Trap Segments** in the signed-in account. The app requests only `https://www.googleapis.com/auth/drive.file`, so it can create and revisit its own files and cannot browse the rest of Drive.

Failed uploads are retried by WorkManager when a network is available. The default sends each clip to Drive and then deletes the phone copy (keep local clips is 0). The WAV is written on the phone first so a failed upload can be retried, and it is removed only after Drive accepts it. Pending and failed uploads stay on the phone. Sign in is required; until then, clips stay on the phone and upload after you sign in. Raise the slider if you want recent clips available to play in the app. With auto-upload off, the same slider caps files on the phone, and 0 deletes them without uploading.

### OAuth client

1. Open [Google Cloud Console](https://console.cloud.google.com/) and create a project.
2. Enable the **Google Drive API**.
3. Configure the OAuth consent screen. Add the scope `https://www.googleapis.com/auth/drive.file` (Drive file). While the app is in testing, add your Google account as a test user.
4. Create an **Android** OAuth client ID.
   - Package name: `app.silenttrap`
   - SHA-1: the certificate that signs the build you run. For a debug build, Android Studio's **Gradle → app → Tasks → android → signingReport** prints the debug SHA-1. You can also run `./gradlew signingReport`.
5. Install the app and use **Sign in** on the settings screen. Play services matches the package name and SHA-1 to that client. No client id is pasted into the app.

A release keystore needs its own SHA-1 and its own Android OAuth client (or the same client with both fingerprints, if you add both).

If sign-in succeeds but upload returns 403, the Drive API is not enabled, the test user is missing, or the SHA-1 does not match the installed build.

## Project map

- `audio` — ring buffer, segment assembly, high-pass, whisper gain, WAV
- `vad` — Silero through `com.github.gkonovalov.android-vad:silero`
- `service` — microphone foreground service
- `drive` — sign-in, folder create, multipart upload, WorkManager retry
- `ui` — start/stop, meter, clip list, settings

`app/src/test` checks the buffer, whisper gain, assembler, filter, and WAV header. Those tests do not need a device:

```
./gradlew :app:testDebugUnitTest
```

## Not included

On-device transcription (a small Whisper model) is left out on purpose. The model is large and would compete with capture. The clip list is the place to add it later.

Release minification is off so ONNX Runtime and Play services are not stripped before the release build is tried on a phone.

# Aurix.studio

**Local-first Android music player and music-creation companion.**

Repository: [anandkaithwas/resonance](https://github.com/anandkaithwas/resonance)  
Android namespace/application ID: `dev.resonance.app` (kept stable to preserve installed-app upgrade compatibility).

## Current capabilities

- Local music discovery through MediaStore (audio permission is requested only when the user chooses to browse the local library).
- Background playback and media-session integration using AndroidX Media3.
- Direct HTTPS audio/radio URL playback where the stream is compatible with Media3.
- Three amplitude visualisation styles. The current visualiser is an RMS amplitude display, not an FFT frequency spectrum.
- AI DJ controls currently provide a single-player fade transition, shuffle and optional text-to-speech announcements. True overlapping dual-deck crossfade is not implemented yet.
- Song Studio can call a configured HTTPS endpoint that returns WAV audio. Without an endpoint it creates a short procedural demo tone; this is not AI music generation.

## Product roadmap additions

Three new product modules have been specified in [the Karaoke, AI Remix and DJ Rooms feature brief](docs/FEATURES_KARAOKE_REMIX_DJ_ROOMS.md):

- **Aurix Karaoke:** timed lyrics, sing-along mode, optional vocal recording and singing feedback, plus a future instrumental/vocal-separation workflow.
- **Aurix AI Remix:** create alternate versions of the currently playing track (slow, lo-fi, remix, acoustic, EDM, chill, instrumental, karaoke and custom prompts), with quick playback effects separated from actual AI-generated audio transformations.
- **Aurix DJ Rooms:** private rooms with short join codes, host-controlled playback and synchronised listening for invited participants.

These are product specifications, not shipped features. Karaoke recording, AI audio transformation and multi-user rooms require additional implementation and, for AI processing and rooms, suitable provider/backend services. The design deliberately avoids uploading or redistributing local music without the user's explicit action and appropriate rights.

## Build

Requirements: JDK 17, Android SDK platform 35, Android build-tools 35.0.0, Gradle 8.11.1.

This repository currently does not include the Gradle Wrapper. With Gradle 8.11.1 installed:

```bash
gradle --no-daemon assembleDebug
gradle --no-daemon testDebugUnitTest
```

GitHub Actions runs the same build and unit-test tasks on pushes and pull requests.

## Compatibility and identity

The app ID `dev.resonance.app` is intentionally unchanged. Renaming the Android application ID would make Android treat this as a different app and can prevent in-place upgrades. The visible product name is Aurix.studio.

## Security and privacy

- No microphone permission is requested by the current app. Karaoke should request it only when the user explicitly starts a recording or live vocal-analysis session.
- Notification permission is not requested as part of local-library access.
- The Song Studio bearer key is held only in screen memory and is not written to SharedPreferences.
- Configured generation endpoints must use HTTPS.
- The manifest disables Android cloud backup to avoid unintentionally exporting locally stored generated audio or app data.
- DJ Rooms should use short-lived, unguessable room credentials, TLS, host permissions and minimal retention of room metadata.

## Known limitations

- No true dual-player crossfade yet.
- Visualisation is RMS amplitude, not FFT.
- Song-generation endpoint contract currently accepts WAV responses only.
- Karaoke, AI remix generation and DJ Rooms are specified but not implemented yet.
- No physical-device test has been performed as part of this code change. Validate local playback, background playback, audio focus, Bluetooth routing, permissions and endpoint integration on target Android versions before release.

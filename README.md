# Aurix.studio

**Local-first Android music player and music-creation companion.**

Repository: [anandkaithwas/resonance](https://github.com/anandkaithwas/resonance)  
Android namespace/application ID: `dev.resonance.app` (kept stable to preserve installed-app upgrade compatibility).

## Current capabilities

- Local music discovery through MediaStore; audio permission is requested only when the user chooses to browse the local library.
- Background playback and media-session integration using AndroidX Media3.
- Direct HTTPS audio/radio URL playback where the stream is compatible with Media3.
- Three amplitude visualisation styles. The current visualiser is an RMS amplitude display, not an FFT frequency spectrum.
- Local-first AI DJ: SmartMix recommendations, optional voice announcements, shuffle and a single-deck fade. This is not a dual-deck crossfade.
- Song Studio can call a configured HTTPS endpoint that returns WAV audio. Without an endpoint it creates a short procedural demo tone; this is not AI music generation.
- **Aurix Karaoke:** import/play `.lrc` lyrics with timing offset, optional private microphone recording and estimated-pitch guidance. Microphone permission is requested only when Record is tapped.
- **Aurix AI Remix:** instant on-device playback effects are clearly separated from server-backed generative transformations. Generative jobs require an HTTPS provider, explicit rights confirmation, and store results privately.
- **Aurix DJ Rooms:** HTTPS room create/join/leave plus a WSS channel with server-authoritative playback state, sequence numbers, host controls, presence and reconnect snapshots. A reference Node.js server is included under `server/`.

The full product and technical brief is in [docs/FEATURES_KARAOKE_REMIX_DJ_ROOMS.md](docs/FEATURES_KARAOKE_REMIX_DJ_ROOMS.md). The remix provider HTTP/WSS contract is in [docs/REMIX_PROVIDER_CONTRACT.md](docs/REMIX_PROVIDER_CONTRACT.md).

## Build

Requirements: JDK 17, Android SDK platform 35, Android build-tools 35.0.0, Gradle 8.11.1.

This repository currently does not include the Gradle Wrapper. With Gradle 8.11.1 installed:

```bash
gradle --no-daemon assembleDebug
gradle --no-daemon testDebugUnitTest
```

GitHub Actions runs the same build and unit-test tasks on pushes and pull requests.

## DJ room server

See [server/README.md](server/README.md). The server requires TLS certificate/key paths and never receives or relays audio.

```bash
cd server
TLS_KEY_PATH=/path/to/key.pem TLS_CERT_PATH=/path/to/cert.pem PORT=8443 npm start
npm test
```

## Compatibility and identity

The app ID `dev.resonance.app` is intentionally unchanged. Renaming the Android application ID would make Android treat this as a different app and can prevent in-place upgrades. The visible product name is Aurix.studio.

## Security and privacy

- Microphone permission is requested only when the user starts a recording.
- Karaoke takes and generated remixes stay in app-private storage; sharing is a separate user action.
- Pitch feedback is optional and framed as guidance, not a professional vocal diagnosis.
- Instant playback effects never upload audio and do not create a new master file.
- Generative remixes disclose the provider upload and require explicit rights confirmation before submission.
- No provider credential is embedded in the APK. Remix keys are held in screen memory; use a secure backend token broker or user-managed credential.
- Configured generation/remix endpoints and DJ room servers must use HTTPS/WSS.
- DJ rooms send track identity only. Local files, file paths, audio bytes and phone/email identifiers are never sent to the server or other participants.
- Room credentials are short-lived random tokens; join codes are not permanent credentials. Rooms expire and revoke credentials when closed.
- The manifest disables Android cloud backup to avoid unintentionally exporting private app data.

## Known limitations

- No true dual-player crossfade yet.
- Visualisation is RMS amplitude, not FFT.
- Karaoke pitch detection is a lightweight autocorrelation estimate; professional scoring, vocal separation and word-level highlighting are out of scope.
- Song/remix endpoints currently accept WAV responses only.
- Generative remix jobs require a compatible external provider; no provider credential is bundled.
- DJ rooms require a deployed HTTPS/WSS server; the bundled server is a reference implementation.
- No physical-device test has been performed as part of this code change. Validate playback, audio focus, Bluetooth routing, permissions, recording, endpoint integration and room sync on target Android versions before release.

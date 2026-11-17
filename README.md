# Aurix.studio

**Local-first open-source Android music player & AI music studio.**  
*(Repository & internal package: `anandkaithwas/resonance` / `dev.resonance.app`)*

[![Android CI](https://github.com)](https://github.com)
[![License: GPL v3](https://shields.io)](https://gnu.org)
[![Android SDK](https://shields.io)](https://android.com)

---

## Overview

**Aurix.studio** combines a high-performance offline/online Android audio player with an integrated AI studio—built with a strict **local-first**, **GPL-3.0** open-source architecture.

### Core Features
- **Local & Online Audio Playback:** Powered by Jetpack Media3 (`1.6.1`) with background `MediaSessionService` support, hardware media button handling, and Android 15 lockscreen controls.
- **Visualiser Studio:** Real-time audio waveform and spectrum visualizer screen built in Jetpack Compose.
- **Local AI DJ Controller:** Smart queue transitions and local-first playback intelligence via `LocalAiDjController`.
- **AI Song Generation Studio:** Modular client (`SongGeneratorClient`) for AI track synthesis and studio workflows.
- **Fast Local Library Scanner:** Asynchronous local storage indexing via `LocalAudioScanner`.

---

## Tech Stack

- **Language:** Kotlin
- **UI Framework:** Jetpack Compose (BOM `2025.05.00`, Material 3)
- **Media Engine:** AndroidX Media3 `1.6.1` (`MediaSessionService`, `ExoPlayer`)
- **Target & Compile SDK:** Android 15 (API 35)
- **Build System:** Gradle `8.11.1`
- **License:** GNU General Public License v3.0 (GPL-3.0)

---

## Project Architecture (Milestone 2)

```text
app/src/main/java/dev/resonance/app/
├── MainActivity.kt               # Entry point & Compose navigation host
├── PlaybackService.kt            # Media3 MediaSessionService & SDK 35 foreground service
├── LocalAudioScanner.kt          # MediaStore / local file audio indexer
├── LocalAiDjController.kt        # Local-first AI DJ transition & queue logic
├── SongGeneratorClient.kt        # AI song generation client interface
└── ui/
    ├── VisualiserStudioScreen.kt # Real-time audio visualizer & studio UI
    └── components/
        └── AurixTopBar.kt        # Modular Aurix.studio brand header
```

---

## Roadmap

- [x] **Milestone 1 (M1):** Project scaffolding, Gradle 8.11.1 + SDK 35 setup, CI pipeline (`assembleDebug` & `testDebugUnitTest`).
- [x] **Milestone 2 (M2):** Core `PlaybackService` (Media3 1.6.1), `LocalAudioScanner`, `VisualiserStudioScreen`, `LocalAiDjController`, `SongGeneratorClient`, and **Aurix.studio** rebrand.
- [ ] **Milestone 3 (M3):** On-device SDK 35 lifecycle hardening, full audio effects/EQ pipeline, AI song generation backend integration, and F-Droid reproducible build metadata.

---

## Building & Downloads

Every push to `main` triggers GitHub Actions CI and attaches the latest debug APK artifact (`resonance-debug-apk`) under the **Actions** tab.

To build locally:
```bash
./gradlew assembleDebug
```

---

## License

This project is licensed under the **GNU General Public License v3.0** — see the [LICENSE](LICENSE) file for details.

# RESONANCE

Android-first, local-first music player and music studio.

## Current milestone: M1 playback foundation

- Offline library scanning with Android MediaStore.
- Local audio playback with Media3.
- Online playback from direct HTTPS audio URLs and compatible radio streams.
- Background playback via MediaSessionService.

Online streaming is not a catalogue integration. Only use URLs and providers that authorise playback. The app does not scrape protected services, bypass DRM or cache streams without permission.

## Build

Open this repository in Android Studio or run the Android CI workflow. Requirements: JDK 17, Android SDK 35, Gradle 8.11.1.

## Project principles

- Local-first and usable without an account.
- No ads, subscriptions, mandatory cloud AI or project-operated paid AI service.
- GPL-3.0-only target licence; review all dependency and model licences separately.
- Clean-room implementation: no copied application source, branding or protected assets.

See docs/PLAYBACK_M1.md for playback scope and device test checklist.

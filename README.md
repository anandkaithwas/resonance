# RESONANCE

**Free forever. Open source. Your music, your rules.**

RESONANCE is an independent, Android-first music application planned around five connected areas: a local-first music player, autonomous AI DJ, visualiser studio, AI music curator and Song Studio for original music creation.

## Product constraints
- Free to use: no ads, subscriptions, paid feature gates, usage-based AI charges or mandatory account.
- Local-first: core playback and local music intelligence must work offline.
- AI runs on-device by default; optional cloud AI is user-operated and self-hosted.
- Android 10+ (API 29+) is the minimum target.
- Respect music-provider terms and technical restrictions. Playback permission does not imply permission to analyse, cache, overlap, remix or DJ-mix a stream.
- Clean-room implementation: references inform high-level requirements only. No copied source, protected assets, branding or exact interface designs.

## Current status
Initial repository foundation. The current app shell is not yet a functioning music player. MediaStore scanning, real audio playback, background playback, DSP, DJ mixing, online providers, live visualisation and song generation remain future milestones.

## Stack
- Kotlin, Jetpack Compose, Material 3
- AndroidX Media3 for standard playback
- Oboe/C++ for future low-latency dual-deck mixing
- OpenGL ES for visualisation
- Room for local persistence
- On-device inference abstraction and optional self-hosted AI endpoint

## Build
Requirements: JDK 17, Android SDK Platform 35, Build Tools 35.0.0, Gradle 8.11.1.

```bash
gradle --no-daemon assembleDebug
gradle --no-daemon testDebugUnitTest
```

## Repository layout
- `app/` Android application
- `docs/` architecture, AI, customization, licensing and roadmap documents
- `.github/workflows/` cloud build and test workflow

## Licence
The intended licence is GNU GPL version 3.0 only (`GPL-3.0-only`). The complete unmodified licence text must be included before public distribution. Third-party dependencies, model weights, datasets, voices and assets require separate licence review.

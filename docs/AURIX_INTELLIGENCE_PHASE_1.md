# Aurix Intelligence Engine — Phase 1

## Product definition
Aurix has four identities in one local-first player: intelligent listening, DJ transitions, creative sound scenes, and expressive visual presentation. This phase introduces an on-device PCM intelligence pipeline without cloud inference or audio upload.

## Architecture
- **Playback**: existing Media3 ExoPlayer + MediaSession remain the playback authority.
- **Signal path**: Media3 PCM -> AurixAudioEngine -> output PCM; the existing visualiser amplitude stream is retained and now reflects processed audio.
- **MoodSync / dynamic EQ**: rolling low/mid/high energy estimates classify calm/balanced/energetic and smoothly adjust a three-band EQ.
- **Reality**: deterministic sound-scene curves (Natural, Club, Cinema, Night) shape the same local DSP path.
- **SoundUniverse**: existing visualiser receives post-processing amplitude buckets; richer FFT/particle rendering remains a UI phase.
- **MusicDNA**: the engine currently exposes RMS and three band-energy features as a local signal fingerprint; track-level persistence, BPM/key and similarity search are not yet implemented.
- **SmartMix**: remains a next stage. Real BPM/key-aware matching requires an offline beat/onset and chroma analysis pipeline and a two-deck playback architecture; the existing DJ controller is a single-player fade, not a true crossfade.
- **Voice**: current TextToSpeech announcements are retained. Offline speech-command recognition is not yet implemented.

## Privacy and constraints
No network calls, model downloads, cloud inference, or new permissions are introduced. This is adaptive DSP and heuristic classification, not a trained AI model. Audio is processed in memory and not persisted.

## Validation
Unit tests cover silence, clipping bounds, bypass identity, low-energy mood classification, and sound-scene selection. CI must still confirm Android compilation and test execution before this phase can be called build-verified.

## Next gates
1. Device A/B listening tests and per-device output calibration.
2. FFT/onset/chroma analysis and persistent MusicDNA records.
3. Two-player deck/crossfade queue engine for SmartMix.
4. Local speech recognition with explicit opt-in and a supported on-device model.
5. SoundUniverse visual redesign and user controls for scenes/strength.

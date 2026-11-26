# Aurix.studio — Karaoke, AI Remix and DJ Rooms

Status: Product/technical specification  
Target: Android app, namespace `dev.resonance.app`  
Branch: `feat/karaoke-ai-remix-dj-rooms`

This document is the build brief and acceptance specification for three Aurix.studio modules. The first implementation pass is on this branch; unimplemented or provider-dependent items are called out in the README and module docs.

## Product principles

1. Keep local playback useful without an account or cloud connection.
2. Clearly distinguish local audio effects from actual generative AI transformations.
3. Ask for microphone access only when the user starts a microphone-dependent feature.
4. Do not upload, distribute or remix copyrighted audio unless the user has the necessary rights and the selected service permits the operation.
5. Keep private recordings and generated versions private by default.
6. Do not bypass DRM or scrape restricted streaming services.

---

## 1. Aurix Karaoke

### User experience

Entry points:
- Now Playing → Lyrics / Karaoke
- Library track menu → Sing Karaoke

Karaoke screen:
- Album art or background visual.
- Current lyric line, upcoming line and word-level highlighting when timed word data exists.
- Seekable progress timeline.
- Lyrics timing offset control.
- Font size and contrast controls.
- Vocal/instrumental balance where supported by a legitimate source or separation model.
- Start/stop recording control, visibly indicating when the microphone is active.
- Optional pitch/timing feedback after a take.

### Lyrics source and formats

Implement a provider abstraction rather than hard-coding one lyrics vendor.

Initial local-first support:
- Embedded lyrics where available through media metadata.
- User-imported `.lrc` files.
- Standard timestamped LRC line synchronisation.

Future provider integration:
- Licensed lyrics provider with documented display and caching rights.
- Enhanced LRC / word-timed lyric format when licensed data supports it.

Never scrape lyrics from websites or silently persist provider lyrics beyond their licence.

### Recording and scoring

- Request `RECORD_AUDIO` only after the user explicitly taps Record or Live Vocal Feedback.
- Show a clear microphone-active state and recording duration.
- Save takes locally by default; sharing requires a separate user action.
- Pitch feedback should be opt-in and framed as guidance, not a professional vocal diagnosis.
- A first release can provide playback-synchronised lyrics without recording or scoring; this avoids blocking the core karaoke experience on microphone work.

### Acceptance criteria

- LRC timestamps remain synchronised after seek, pause, resume and track changes.
- Missing lyrics produce a useful empty state and allow importing an LRC file.
- Timing offset can be adjusted without modifying the original LRC.
- Microphone permission is not requested until recording/live analysis is selected.
- Recording can be stopped, discarded or saved locally.
- Lyrics source and usage restrictions are respected.

---

## 2. Aurix AI Remix

### Entry point

Now Playing → **Create Another Version**

### Version presets

- Slow / slowed + reverb
- Lo-fi
- Remix
- Acoustic
- EDM
- Chill / ambient
- Nightcore
- Instrumental
- Karaoke instrumental
- Custom text prompt

### Two distinct processing paths

**A. Instant playback effects (on-device, no generative AI)**

- Playback speed / pitch options with explicit independent pitch-lock behaviour where supported.
- Equaliser / tone shaping.
- Reverb and other licensed or original DSP effects.
- Save a preset, not a falsely labelled new audio master.

**B. AI-generated transformation (server/provider required)**

- User selects a source track and a style prompt.
- Explain whether source audio will leave the device and which provider processes it.
- Require confirmation that the user has the rights to submit the audio.
- Upload only after explicit confirmation, over HTTPS.
- Return a new audio asset, metadata and provider job status.
- Let the user preview, retry, discard or save the new version.
- Keep generated versions private by default and associate them with the source track.
- Preserve original audio; never overwrite the source.

### Provider contract (to be finalised against the chosen provider)

The app-side abstraction should expose:
- `submitRemix(source, preset, prompt, duration, rightsConfirmed)`
- `getJobStatus(jobId)`
- `downloadResult(jobId)`
- `cancelJob(jobId)`

The provider adapter must define accepted source formats, maximum size/duration, output format, timeout, rate limits, costs, data retention, commercial-use rights and whether vocals/lyrics can be transformed.

Do not implement a pretend AI remix by changing playback speed and calling it an AI-generated version. UI must label quick effects and generated remixes separately.

### Acceptance criteria

- User can audition instant effects without uploading the source.
- Generative remix clearly discloses upload/provider processing and requires explicit confirmation.
- Original file remains untouched.
- Failed or cancelled jobs do not leave partial/corrupt library files.
- Generated output is playable offline after successful save.
- No provider credentials are embedded in the Android APK; use a secure backend token broker or user-managed credential with secure storage.

---

## 3. Aurix DJ Rooms

### User experience

Entry points:
- Now Playing → Share DJ Room
- AI DJ → Create Room / Join Room

Room creation:
- Create a private room and generate a short, human-readable join code.
- Share code via Android Sharesheet.
- Host can play/pause, seek, skip, change queue and close the room.
- Participants see host, member list, current track and synchronised playback position.
- Show reconnecting / buffering / out-of-sync states.
- Host can enable guest queue suggestions; guests cannot control playback unless host grants permission.
- Participants can leave at any time; host can remove a participant or close the room.

### Synchronisation architecture

Use a small real-time backend:
- HTTPS API for create/join/leave and room metadata.
- WebSocket (TLS) channel for playback events and presence.
- Server-authoritative room state with monotonically increasing sequence numbers.
- Host commands: play, pause, seek, next, queue update and track change.
- Clients estimate clock offset and schedule playback against server timestamps.
- Periodic drift correction; small drift uses seek/speed correction, large drift re-buffers.
- Reconnect restores the latest room snapshot, not stale event replay.

Suggested event fields:
- roomId, sequence, eventType, serverTimestamp
- track identity / provider reference
- positionMs, isPlaying, playbackSpeed
- queue revision and host user/session ID

### Critical music-source constraint

A room code synchronises playback; it does not grant music-streaming rights.

Supported source policy:
1. Provider catalogue: each participant resolves and plays the same track through their own authorised provider entitlement.
2. Public/licensed streams: only sources whose terms permit multi-user listening.
3. Local-only tracks: support synchronisation only when every participant independently has the same audio file; use a local fingerprint/hash to match files. Do not upload or relay the host's local file by default.
4. DRM-protected or restricted sources: do not capture, proxy, retransmit or bypass restrictions.

If a participant does not have a matching/authorised source, show "Track unavailable on your device/provider" and let them remain in the room or skip according to host policy.

### Security and privacy

- Use cryptographically random, short-lived join codes with rate limiting.
- Require room membership tokens; never treat the visible code as a permanent credential.
- TLS for API and WebSocket traffic.
- Host moderation, guest permissions, block/report controls and participant removal.
- No public room directory by default.
- Store minimal room metadata; expire inactive rooms and revoke credentials on close.
- Do not expose phone numbers, email addresses or local file paths to other participants.

### Acceptance criteria

- Two or more devices can join the same room and observe the same host playback state.
- Play, pause, seek and next propagate reliably and recover after reconnect.
- Playback drift is measured and corrected within a defined tolerance under normal network conditions.
- Host-only controls are enforced server-side, not merely hidden in the UI.
- A guest without an authorised/matching track is not sent the host's local audio.
- Expired/closed room codes cannot be reused.

---

## Delivery sequence

### K1 — Karaoke foundation
- LRC parser and tests.
- Local LRC import and track matching.
- Lyrics screen synchronised to Media3 position.
- Timing offset and empty/error states.
- Recording deferred to K1.1.

### R1 — Remix foundation
- Now Playing entry point and preset chooser.
- Instant local playback effects clearly separated from AI generation.
- Define provider interface and backend contract.
- Integrate an actual provider only after provider selection and rights/cost review.

### D1 — DJ Rooms foundation
- Backend service and data model.
- Create/join/leave room API and short-lived join codes.
- WebSocket presence and host playback events.
- Android room UI and two-device sync tests.
- Add provider-aware track matching before enabling shared catalogue playback.

## Definition of done

A feature is not complete merely because its screen exists. Each module requires unit tests, integration tests, error/loading/empty states, accessibility checks, privacy review and physical-device validation. AI provider workflows and DJ Rooms additionally require a deployed backend and end-to-end tests with real provider/network responses.

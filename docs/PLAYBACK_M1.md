# M1 — Offline and online playback

## Implemented
- Local audio discovery through Android MediaStore.
- Audio library permission is requested only when the user opens the local library without permission.
- Local content URIs are played without copying the user's music files.
- Direct HTTPS media URLs can be played when Media3 supports their media format.
- Media3 ExoPlayer runs in a MediaSessionService for background playback and media-session integration.
- Basic play/pause and queue-next controls.
- A PCM16 RMS amplitude tap feeds the visualiser. Unsupported PCM encodings bypass the tap rather than failing playback.

## Deliberate limits
- An HTTPS webpage is not necessarily an audio stream; the URL must resolve to decodable media.
- This is not yet full provider catalogue integration.
- No YouTube scraping, DRM circumvention or unauthorised stream extraction.
- The current AI DJ transition fades a single player out and back in; it is not a dual-deck crossfade.
- Visualiser bars show amplitude buckets, not FFT frequency bands.
- Song Studio endpoint responses must be WAV. The no-endpoint fallback is a procedural demo tone, not AI music generation.

## Device validation required
1. Test Android 10, 13 and 14+.
2. Deny audio permission and verify direct HTTPS streams remain usable.
3. Grant audio permission and verify local tracks appear.
4. Play a local track, lock the screen and verify playback continues.
5. Verify notification and lock-screen media controls.
6. Test a known public HTTPS audio stream, invalid URLs and unsupported codecs.
7. Test pause/resume, next, seeking, audio focus interruptions and Bluetooth routing.
8. Verify the configured HTTPS generation service returns a valid WAV file.

A successful CI build and physical-device test are required before calling this production-ready.

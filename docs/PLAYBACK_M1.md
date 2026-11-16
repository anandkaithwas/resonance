# M1 — Offline and online playback

## Implemented in this milestone
- Local audio discovery through Android MediaStore.
- Runtime permission handling: READ_MEDIA_AUDIO on Android 13+ and READ_EXTERNAL_STORAGE on Android 10–12.
- Local content URIs are played without copying the user's audio files.
- Online playback accepts user-entered HTTPS media URLs, including directly playable hosted audio and compatible internet-radio streams.
- Media3 ExoPlayer runs inside MediaSessionService for background playback and Android media-session integration.
- Basic controls: play/pause, next queue item, seek when the source exposes a seekable duration.
- Playback errors are surfaced with Media3 error codes.

## Deliberate limits
- An HTTPS webpage is not necessarily an audio stream. The URL must resolve to media that Media3 can decode.
- This is not yet online catalogue search or account integration. Provider-specific integrations require documented APIs, authentication, attribution, playback permissions and terms review.
- No YouTube scraping, DRM circumvention or unauthorised stream extraction.
- This initial UI plays one selected item at a time. Queue management, artwork, persistent resume position, advanced audio focus tests, headset controls, Bluetooth routing and downloads remain future work.
- Online URLs are streamed, not downloaded for offline use. Downloading requires source licence and caching-permission checks.

## Device validation required
1. Test Android 10, 13 and 14+.
2. Deny audio permission and verify online streams remain available.
3. Grant audio permission and verify local tracks appear.
4. Play a local track, lock the screen and verify playback continues.
5. Verify notification and lock-screen media controls.
6. Play a known public HTTPS audio stream; test invalid URLs and unsupported codecs.
7. Test pause/resume, next, seeking, audio focus interruptions and Bluetooth routing.

A successful CI build and physical-device test are required before calling this production-ready.

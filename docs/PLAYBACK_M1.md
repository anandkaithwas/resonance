# M1 — Offline and online playback

## Included in this milestone

- Local audio discovery through Android MediaStore.
- Runtime permission handling for Android 13+ (READ_MEDIA_AUDIO) and Android 10–12 (READ_EXTERNAL_STORAGE).
- Local content URIs are played in place; RESONANCE does not copy local music.
- User-entered HTTPS media URLs for direct audio streams and compatible internet radio.
- Media3 ExoPlayer hosted in a MediaSessionService so playback can continue outside the Activity.
- Basic play/pause, next-item and seek controls.

## Scope and restrictions

- A webpage URL is not necessarily an audio stream. The URL must resolve to a Media3-supported media resource.
- This is not yet an online catalogue search or provider account integration.
- No YouTube scraping, DRM circumvention, or unauthorised stream extraction.
- Online streams are streamed, not downloaded. Offline downloads require source-specific caching rights.
- Queue management, robust error UI, audio focus and headset testing, Bluetooth routing, artwork, lyrics and persistence are follow-up work.

## Device test checklist

1. Install on Android 10, Android 13 and Android 14+.
2. Deny audio permission and verify online input remains available.
3. Grant permission and verify local tracks appear.
4. Start a local track, lock the screen and verify continued playback.
5. Verify notification and lock-screen media controls.
6. Test a known public HTTPS audio stream and an invalid URL.
7. Test pause/resume, seeking, audio focus interruptions, headset disconnect and Bluetooth routing.

CI build and physical-device testing are required before this milestone is considered verified.

# Aurix DJ Room Server

Small server-authoritative coordination service for [Aurix DJ Rooms](../docs/FEATURES_KARAOKE_REMIX_DJ_ROOMS.md). It synchronises playback metadata only; it never receives, stores or relays audio.

## Requirements

Node.js 20+ and a TLS certificate/key pair. TLS is mandatory — the server refuses to start without it.

```bash
TLS_KEY_PATH=/path/to/key.pem TLS_CERT_PATH=/path/to/cert.pem PORT=8443 npm start
```

For local development you may use a self-signed certificate, but Android will only trust it if you add a network security configuration or install the CA on the device. Production deployments should use a publicly trusted certificate.

## HTTPS API

- `POST /rooms` — `{ "hostName": "Host", "track": { ... } }` → `{ roomId, joinCode, hostToken, snapshot }`
- `POST /rooms/join` — `{ "joinCode": "ABC234", "displayName": "Guest" }` → `{ roomId, participantId, memberToken, snapshot }`
- `GET /rooms/{id}/snapshot` — bearer token → latest snapshot
- `POST /rooms/{id}/leave` — bearer token → `{ "left": true }`

Tokens are random 256-bit credentials; the visible join code is not a permanent credential. Rooms expire after 30 minutes of inactivity and are removed when empty. Requests are rate limited per IP.

## WSS channel

Connect to `wss://host/ws?roomId=...&token=...`.

The server immediately sends a `snapshot` event. On reconnect the snapshot always replaces local state; events are not replayed. Client commands:

```json
{ "type": "command", "command": { "type": "play", "positionMs": 12345 } }
```

Supported commands: `play`, `pause`, `seek`, `next`, `track_change`, `queue_update`, `host_policy`. Playback/track/queue commands are host-only by default; the host may grant playback control and queue suggestions. Every accepted command increments a server-authoritative `sequence` and timestamp. The server broadcasts `state` and `presence` events.

Track objects are whitelisted. Only `provider_catalogue`, `licensed_stream` and `local_fingerprint` source types are accepted; local files are identified by a short fingerprint and are never uploaded.

## Tests

```bash
npm test
```

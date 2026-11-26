# Aurix AI Remix Provider Contract

This document defines the app-side contract implemented by `dev.resonance.app.remix.HttpRemixProvider`. A provider must be reachable over HTTPS and must not require credentials embedded in the APK.

## Constraints

- Accepted source: a single audio file submitted as multipart field `source`. The app limits uploads to 100 MB and 15 minutes.
- Output: WAV audio returned as a binary body from the result endpoint. Other formats are currently rejected before saving.
- Timeouts: connect 15 s, read 120 s, total call 180 s.
- The app submits audio only after the user explicitly confirms rights.
- The app never overwrites the source. Results are downloaded to a temporary `.part` file and atomically renamed after validation.
- Provider credentials are sent as `Authorization: Bearer <key>` from memory supplied by the user or a secure broker.

## Endpoints

### `POST /remixes`

Content type: `multipart/form-data`

Parts:

- `metadata` — JSON with:
  - `preset` — one of `slowed_reverb`, `lofi`, `remix`, `acoustic`, `edm`, `chill`, `nightcore`, `instrumental`, `karaoke`, `custom`
  - `prompt` — style prompt, at most 1000 characters
  - `duration_ms` — source duration
  - `rights_confirmed` — always `true` when submitted
- `source` — audio file bytes

Response JSON:

```json
{ "id": "job_123", "status": "queued", "progress": 0 }
```

Status values: `queued`, `running`, `succeeded`, `failed`, `cancelled`.

### `GET /remixes/{id}`

Response JSON:

```json
{ "id": "job_123", "status": "running", "progress": 45, "error": "" }
```

### `GET /remixes/{id}/result`

Returns WAV bytes with a 2xx status. The app validates the RIFF/WAVE header, enforces a 100 MB cap, and writes atomically.

### `POST /remixes/{id}/cancel`

Cancels the job and returns a JSON object (any valid JSON object is accepted).

## Provider obligations to document before launch

- Accepted input formats/codecs and maximum duration/size.
- Output format, sample rate and expected loudness.
- Processing timeout, rate limits and cost per job.
- Data retention/deletion policy and whether training on submitted audio is used.
- Commercial-use rights for outputs.
- Whether vocals and lyrics may be transformed.
- Regions where the service is available.

The app deliberately does not pretend that changing playback speed is generative AI. Quick effects are implemented locally and labelled separately in the UI.

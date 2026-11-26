import assert from 'node:assert/strict';
import test from 'node:test';
import {
  ALLOWED_SOURCE_TYPES,
  applyCommand,
  createRoom,
  findParticipantByToken,
  isExpired,
  joinRoom,
  leaveRoom,
  randomJoinCode,
  roomSnapshot
} from '../src/roomState.js';

const localTrack = { sourceType: 'local_fingerprint', title: 'Local Song', fingerprint: 'abc123' };

test('join codes are six uppercase characters and unique', () => {
  const used = new Set();
  const codes = new Set(Array.from({ length: 50 }, () => {
    const code = randomJoinCode(used);
    used.add(code);
    return code;
  }));
  assert.equal(codes.size, 50);
  for (const code of codes) assert.match(code, /^[A-HJ-NP-Z2-9]{6}$/);
});

test('host-only commands are rejected for guests', () => {
  const room = createRoom({ hostName: 'Host', track: localTrack });
  const host = room.participants.get(room.hostId);
  const guest = joinRoom(room, 'Guest');
  assert.throws(() => applyCommand(room, guest, { type: 'play', positionMs: 100 }), /host/i);
  const state = applyCommand(room, host, { type: 'play', positionMs: 100 });
  assert.equal(state.isPlaying, true);
  assert.equal(state.positionMs, 100);
  assert.equal(state.sequence, 2);
});

test('sequence numbers increase monotonically and snapshots are server copies', () => {
  const room = createRoom();
  const host = room.participants.get(room.hostId);
  const first = applyCommand(room, host, { type: 'seek', positionMs: 5_000 });
  const second = applyCommand(room, host, { type: 'pause', positionMs: 5_000 });
  assert.ok(second.sequence > first.sequence);
  const snapshot = roomSnapshot(room);
  snapshot.state.sequence = -1;
  assert.equal(room.state.sequence, second.sequence);
});

test('guest queue suggestions require host approval', () => {
  const room = createRoom();
  const guest = joinRoom(room, 'Guest');
  assert.throws(() => applyCommand(room, guest, { type: 'queue_update', queue: [localTrack] }), /disabled/i);
  applyCommand(room, room.participants.get(room.hostId), {
    type: 'host_policy', guestQueueSuggestions: true, playbackPermissionForGuest: false
  });
  const state = applyCommand(room, guest, { type: 'queue_update', queue: [localTrack] });
  assert.equal(state.eventType, 'queue_suggestion');
  assert.equal(state.queue.length, 1);
});

test('host promotion happens when the host leaves', () => {
  const room = createRoom();
  const guest = joinRoom(room, 'Guest');
  leaveRoom(room, room.hostId);
  assert.equal(room.hostId, guest.id);
  assert.equal(room.participants.get(guest.id).isHost, true);
  assert.equal(findParticipantByToken(room, guest.token)?.id, guest.id);
});

test('unsupported and unlicensed source types are rejected', () => {
  const room = createRoom();
  const host = room.participants.get(room.hostId);
  assert.throws(() => applyCommand(room, host, {
    type: 'track_change', track: { sourceType: 'drm_stream', providerRef: 'x' }
  }), /source/i);
  assert.equal(ALLOWED_SOURCE_TYPES.has('local_fingerprint'), true);
});

test('rooms expire and can be touched', () => {
  const room = createRoom();
  room.expiresAt = Date.now() - 1;
  assert.equal(isExpired(room), true);
  room.expiresAt = Date.now() + 100;
  assert.equal(isExpired(room), false);
});

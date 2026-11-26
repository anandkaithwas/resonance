import crypto from 'node:crypto';

export const ROOM_TTL_MS = 30 * 60 * 1000;
export const ALLOWED_SOURCE_TYPES = new Set(['provider_catalogue', 'licensed_stream', 'local_fingerprint']);
const CODE_ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';

function randomToken() {
  return crypto.randomBytes(32).toString('base64url');
}

export function randomJoinCode(existingCodes = new Set()) {
  for (let attempt = 0; attempt < 100; attempt += 1) {
    let code = '';
    for (let i = 0; i < 6; i += 1) {
      code += CODE_ALPHABET[crypto.randomInt(CODE_ALPHABET.length)];
    }
    if (!existingCodes.has(code)) return code;
  }
  throw new Error('Unable to allocate a unique room code');
}

export function createRoom({ hostName = 'Host', track = null } = {}) {
  const now = Date.now();
  const roomId = crypto.randomUUID();
  const hostId = crypto.randomUUID();
  const hostToken = randomToken();
  return {
    id: roomId,
    code: randomJoinCode(),
    createdAt: now,
    expiresAt: now + ROOM_TTL_MS,
    hostId,
    participants: new Map([
      [hostId, {
        id: hostId,
        name: String(hostName).slice(0, 40) || 'Host',
        token: hostToken,
        isHost: true,
        canControlPlayback: true,
        canSuggestQueue: true,
        joinedAt: now,
        connectedAt: null
      }]
    ]),
    state: {
      sequence: 1,
      eventType: 'room_created',
      serverTimestamp: now,
      track: sanitiseTrack(track),
      positionMs: 0,
      isPlaying: false,
      playbackSpeed: 1,
      queue: [],
      queueRevision: 1,
      guestQueueSuggestions: false
    }
  };
}

function sanitiseTrack(track) {
  if (!track || typeof track !== 'object') return null;
  const sourceType = String(track.sourceType || '');
  if (!ALLOWED_SOURCE_TYPES.has(sourceType)) {
    throw new Error('Unsupported or unauthorised music source');
  }
  return {
    sourceType,
    provider: typeof track.provider === 'string' ? track.provider.slice(0, 60) : '',
    providerRef: typeof track.providerRef === 'string' ? track.providerRef.slice(0, 200) : '',
    title: typeof track.title === 'string' ? track.title.slice(0, 160) : '',
    artist: typeof track.artist === 'string' ? track.artist.slice(0, 160) : '',
    fingerprint: typeof track.fingerprint === 'string' ? track.fingerprint.slice(0, 128) : '',
    licensed: sourceType !== 'local_fingerprint' ? Boolean(track.licensed) : false
  };
}

export function findParticipantByToken(room, token) {
  if (!room || typeof token !== 'string') return null;
  for (const participant of room.participants.values()) {
    if (participant.token === token) return participant;
  }
  return null;
}

export function isExpired(room, now = Date.now()) {
  return !room || now >= room.expiresAt;
}

export function touch(room, now = Date.now()) {
  room.expiresAt = now + ROOM_TTL_MS;
}

export function joinRoom(room, displayName) {
  const id = crypto.randomUUID();
  const participant = {
    id,
    name: String(displayName || 'Guest').slice(0, 40) || 'Guest',
    token: randomToken(),
    isHost: false,
    canControlPlayback: false,
    canSuggestQueue: true,
    joinedAt: Date.now(),
    connectedAt: null
  };
  room.participants.set(id, participant);
  touch(room);
  return participant;
}

export function leaveRoom(room, participantId) {
  const removed = room.participants.get(participantId);
  if (!removed) return null;
  room.participants.delete(participantId);
  touch(room);
  if (removed.isHost && room.participants.size > 0) {
    const successor = [...room.participants.values()].sort((a, b) => a.joinedAt - b.joinedAt)[0];
    successor.isHost = true;
    successor.canControlPlayback = true;
    room.hostId = successor.id;
  }
  return removed;
}

export function participantsSnapshot(room) {
  return [...room.participants.values()].map((p) => ({
    id: p.id,
    name: p.name,
    isHost: p.isHost,
    canControlPlayback: p.canControlPlayback,
    canSuggestQueue: p.canSuggestQueue,
    connected: Boolean(p.connectedAt)
  }));
}

export function roomSnapshot(room) {
  return {
    roomId: room.id,
    joinCode: room.code,
    hostId: room.hostId,
    expiresAt: room.expiresAt,
    participants: participantsSnapshot(room),
    state: { ...room.state, queue: [...room.state.queue] }
  };
}

function requireHost(room, participant) {
  if (!participant || !participant.isHost || participant.id !== room.hostId) {
    throw new Error('Only the host may perform this action');
  }
}

function requirePlaybackPermission(room, participant) {
  if (!participant || (!participant.isHost && !participant.canControlPlayback)) {
    throw new Error('Playback control is restricted to the host');
  }
}

function publish(room, eventType, patch) {
  room.state = {
    ...room.state,
    ...patch,
    eventType,
    sequence: room.state.sequence + 1,
    serverTimestamp: Date.now()
  };
  touch(room);
  return room.state;
}

const HOST_COMMANDS = new Set(['play', 'pause', 'seek', 'next', 'track_change', 'host_policy']);

export function applyCommand(room, participant, command) {
  if (!command || typeof command !== 'object' || typeof command.type !== 'string') {
    throw new Error('Invalid command');
  }
  const type = command.type;
  if (HOST_COMMANDS.has(type)) requireHost(room, participant);

  switch (type) {
    case 'play':
    case 'pause':
    case 'seek': {
      requirePlaybackPermission(room, participant);
      const positionMs = Number(command.positionMs);
      if (!Number.isFinite(positionMs) || positionMs < 0 || positionMs > 24 * 60 * 60 * 1000) {
        throw new Error('Invalid playback position');
      }
      return publish(room, type, { positionMs, isPlaying: type === 'play' });
    }
    case 'track_change': {
      requirePlaybackPermission(room, participant);
      return publish(room, type, {
        track: sanitiseTrack(command.track),
        positionMs: 0,
        isPlaying: Boolean(command.isPlaying)
      });
    }
    case 'next': {
      requirePlaybackPermission(room, participant);
      const queue = room.state.queue;
      if (queue.length === 0) throw new Error('Queue is empty');
      const [next, ...rest] = queue;
      return publish(room, type, {
        track: sanitiseTrack(next),
        queue: rest,
        queueRevision: room.state.queueRevision + 1,
        positionMs: 0,
        isPlaying: true
      });
    }
    case 'queue_update': {
      if (!participant) throw new Error('Unknown participant');
      if (!participant.isHost && !(participant.canSuggestQueue && room.state.guestQueueSuggestions)) {
        throw new Error('Queue suggestions are disabled by the host');
      }
      if (!Array.isArray(command.queue) || command.queue.length > 100) {
        throw new Error('Queue must contain at most 100 items');
      }
      return publish(room, participant.isHost ? 'queue_update' : 'queue_suggestion', {
        queue: command.queue.map(sanitiseTrack),
        queueRevision: room.state.queueRevision + 1
      });
    }
    case 'host_policy': {
      requireHost(room, participant);
      const playbackPermissionForGuest = Boolean(command.playbackPermissionForGuest);
      for (const member of room.participants.values()) {
        if (!member.isHost) member.canControlPlayback = playbackPermissionForGuest;
      }
      return publish(room, type, {
        guestQueueSuggestions: Boolean(command.guestQueueSuggestions),
        playbackPermissionForGuest
      });
    }
    default:
      throw new Error(`Unsupported command: ${type}`);
  }
}

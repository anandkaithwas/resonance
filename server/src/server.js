import crypto from 'node:crypto';
import fs from 'node:fs';
import https from 'node:https';
import { URL } from 'node:url';
import {
  applyCommand,
  createRoom,
  findParticipantByToken,
  isExpired,
  joinRoom,
  leaveRoom,
  participantsSnapshot,
  roomSnapshot,
  touch
} from './roomState.js';

const PORT = Number(process.env.PORT || 8443);
const TLS_KEY_PATH = process.env.TLS_KEY_PATH;
const TLS_CERT_PATH = process.env.TLS_CERT_PATH;
const MAX_BODY_BYTES = 16 * 1024;
const MAX_WS_FRAME_BYTES = 1024 * 1024;

if (!TLS_KEY_PATH || !TLS_CERT_PATH) {
  console.error('DJ room server requires TLS_KEY_PATH and TLS_CERT_PATH. HTTPS/WSS is mandatory.');
  process.exit(1);
}

const rooms = new Map();
const codes = new Set();
const socketsByRoom = new Map();
const rateLimits = new Map();

function sendJson(res, status, payload) {
  const body = JSON.stringify(payload);
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(body),
    'Cache-Control': 'no-store'
  });
  res.end(body);
}

function readJson(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    req.on('data', (chunk) => {
      size += chunk.length;
      if (size > MAX_BODY_BYTES) {
        reject(new Error('Request body too large'));
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on('end', () => {
      try {
        resolve(JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}'));
      } catch {
        reject(new Error('Invalid JSON body'));
      }
    });
    req.on('error', reject);
  });
}

function rateLimited(req) {
  const key = req.socket.remoteAddress || 'unknown';
  const now = Date.now();
  const hits = (rateLimits.get(key) || []).filter((time) => now - time < 60_000);
  if (hits.length >= 30) {
    rateLimits.set(key, hits);
    return true;
  }
  hits.push(now);
  rateLimits.set(key, hits);
  return false;
}

function bearer(req) {
  const header = req.headers.authorization || '';
  return header.startsWith('Bearer ') ? header.slice(7).trim() : '';
}

function roomFromRequest(req) {
  const url = new URL(req.url, 'https://localhost');
  const match = url.pathname.match(/^\/rooms\/([^/]+)(?:\/.*)?$/);
  const room = match ? rooms.get(decodeURIComponent(match[1])) : null;
  return { url, room: room && !isExpired(room) ? room : null };
}

async function handleHttpRequest(req, res) {
  if (rateLimited(req)) return sendJson(res, 429, { error: 'Too many requests' });
  try {
    const { url, room } = roomFromRequest(req);
    if (req.method === 'POST' && url.pathname === '/rooms') {
      const body = await readJson(req);
      if (!room) {
        const created = createRoom({ hostName: body.hostName, track: body.track });
        rooms.set(created.id, created);
        codes.add(created.code);
        const snapshot = roomSnapshot(created);
        return sendJson(res, 201, {
          roomId: created.id,
          joinCode: created.code,
          hostToken: created.participants.get(created.hostId).token,
          snapshot
        });
      }
    }
    if (req.method === 'POST' && url.pathname === '/rooms/join') {
      const body = await readJson(req);
      const target = [...rooms.values()].find((candidate) => candidate.code === String(body.joinCode || '').toUpperCase());
      if (!target || isExpired(target)) return sendJson(res, 404, { error: 'Room not found or expired' });
      const participant = joinRoom(target, body.displayName);
      return sendJson(res, 200, {
        roomId: target.id,
        participantId: participant.id,
        memberToken: participant.token,
        snapshot: roomSnapshot(target)
      });
    }
    if (!room) return sendJson(res, 404, { error: 'Room not found or expired' });
    if (req.method === 'GET' && url.pathname === `/rooms/${room.id}/snapshot`) {
      const participant = findParticipantByToken(room, bearer(req));
      if (!participant) return sendJson(res, 401, { error: 'Invalid room credential' });
      touch(room);
      return sendJson(res, 200, { snapshot: roomSnapshot(room), you: publicParticipant(participant) });
    }
    if (req.method === 'POST' && url.pathname === `/rooms/${room.id}/leave`) {
      const participant = findParticipantByToken(room, bearer(req));
      if (!participant) return sendJson(res, 401, { error: 'Invalid room credential' });
      const wasHost = participant.isHost;
      leaveRoom(room, participant.id);
      closeParticipantSockets(room, participant.id);
      if (room.participants.size === 0) {
        rooms.delete(room.id);
        codes.delete(room.code);
        socketsByRoom.delete(room.id);
      } else {
        broadcast(room, { type: 'presence', participants: participantsSnapshot(room), hostChanged: wasHost });
      }
      return sendJson(res, 200, { left: true });
    }
    return sendJson(res, 404, { error: 'Unknown endpoint' });
  } catch (error) {
    return sendJson(res, 400, { error: error.message || 'Bad request' });
  }
}

function publicParticipant(participant) {
  return {
    id: participant.id,
    name: participant.name,
    isHost: participant.isHost,
    canControlPlayback: participant.canControlPlayback,
    canSuggestQueue: participant.canSuggestQueue
  };
}

function wsFrame(opcode, payload) {
  const body = Buffer.isBuffer(payload) ? payload : Buffer.from(String(payload), 'utf8');
  const mask = 0x80;
  let header;
  if (body.length < 126) {
    header = Buffer.from([mask | opcode, body.length]);
  } else if (body.length <= 0xffff) {
    header = Buffer.alloc(4);
    header[0] = mask | opcode;
    header[1] = 126;
    header.writeUInt16BE(body.length, 2);
  } else {
    header = Buffer.alloc(10);
    header[0] = mask | opcode;
    header[1] = 127;
    header.writeBigUInt64BE(BigInt(body.length), 2);
  }
  return Buffer.concat([header, body]);
}

function parseFrames(buffer) {
  const frames = [];
  let offset = 0;
  while (offset + 2 <= buffer.length) {
    const first = buffer[offset];
    const second = buffer[offset + 1];
    const fin = (first & 0x80) !== 0;
    const opcode = first & 0x0f;
    const masked = (second & 0x80) !== 0;
    let length = second & 0x7f;
    let cursor = offset + 2;
    if (length === 126) {
      if (cursor + 2 > buffer.length) break;
      length = buffer.readUInt16BE(cursor);
      cursor += 2;
    } else if (length === 127) {
      if (cursor + 8 > buffer.length) break;
      const value = buffer.readBigUInt64BE(cursor);
      if (value > BigInt(MAX_WS_FRAME_BYTES)) throw new Error('WebSocket frame too large');
      length = Number(value);
      cursor += 8;
    }
    if (length > MAX_WS_FRAME_BYTES) throw new Error('WebSocket frame too large');
    let mask;
    if (masked) {
      if (cursor + 4 > buffer.length) break;
      mask = buffer.subarray(cursor, cursor + 4);
      cursor += 4;
    }
    if (cursor + length > buffer.length) break;
    const payload = Buffer.from(buffer.subarray(cursor, cursor + length));
    if (masked) for (let i = 0; i < payload.length; i += 1) payload[i] ^= mask[i % 4];
    frames.push({ fin, opcode, payload });
    offset = cursor + length;
  }
  return { frames, remainder: buffer.subarray(offset) };
}

function send(socket, object) {
  if (socket.destroyed) return;
  socket.write(wsFrame(0x1, JSON.stringify(object)));
}

function broadcast(room, object) {
  const sockets = socketsByRoom.get(room.id);
  if (!sockets) return;
  const frame = wsFrame(0x1, JSON.stringify(object));
  for (const socket of sockets) if (!socket.destroyed) socket.write(frame);
}

function closeParticipantSockets(room, participantId) {
  const sockets = socketsByRoom.get(room.id);
  if (!sockets) return;
  for (const socket of sockets) {
    if (socket.participantId === participantId) {
      socket.write(wsFrame(0x8, Buffer.from([0x03, 0xe8])));
      socket.end();
    }
  }
}

function rejectUpgrade(socket, status, text) {
  socket.write(`HTTP/1.1 ${status} ${text}\r\nConnection: close\r\nContent-Length: 0\r\n\r\n`);
  socket.destroy();
}

function upgradeWebSocket(req, socket) {
  const url = new URL(req.url, 'https://localhost');
  if (url.pathname !== '/ws') return rejectUpgrade(socket, 404, 'Not Found');
  const room = rooms.get(url.searchParams.get('roomId') || '');
  if (!room || isExpired(room)) return rejectUpgrade(socket, 404, 'Not Found');
  const participant = findParticipantByToken(room, url.searchParams.get('token') || '');
  if (!participant) return rejectUpgrade(socket, 401, 'Unauthorized');
  const key = req.headers['sec-websocket-key'];
  if (!key) return rejectUpgrade(socket, 400, 'Bad Request');
  const accept = crypto.createHash('sha1')
    .update(`${key}258EAFA5-E914-47DA-95CA-C5AB0DC85B11`)
    .digest('base64');
  socket.write(
    'HTTP/1.1 101 Switching Protocols\r\n' +
    'Upgrade: websocket\r\nConnection: Upgrade\r\n' +
    `Sec-WebSocket-Accept: ${accept}\r\n\r\n`
  );
  if (!socketsByRoom.has(room.id)) socketsByRoom.set(room.id, new Set());
  const sockets = socketsByRoom.get(room.id);
  participant.connectedAt = Date.now();
  socket.participantId = participant.id;
  socket.roomId = room.id;
  socket.isAlive = true;
  sockets.add(socket);
  send(socket, {
    type: 'snapshot',
    snapshot: roomSnapshot(room),
    you: publicParticipant(participant)
  });
  broadcast(room, { type: 'presence', participants: participantsSnapshot(room) });

  let inbound = Buffer.alloc(0);
  socket.on('pong', () => { socket.isAlive = true; });
  socket.on('data', (chunk) => {
    inbound = Buffer.concat([inbound, chunk]);
    let parsed;
    try {
      parsed = parseFrames(inbound);
    } catch {
      socket.destroy();
      return;
    }
    inbound = parsed.remainder;
    for (const frame of parsed.frames) {
      if (!frame.fin) {
        send(socket, { type: 'error', message: 'Fragmented frames are not supported' });
        continue;
      }
      if (frame.opcode === 0x8) {
        socket.end(wsFrame(0x8, Buffer.from([0x03, 0xe8])));
        continue;
      }
      if (frame.opcode === 0x9) {
        socket.write(wsFrame(0xa, frame.payload));
        continue;
      }
      if (frame.opcode !== 0x1) continue;
      try {
        const message = JSON.parse(frame.payload.toString('utf8'));
        if (message.type !== 'command') {
          send(socket, { type: 'error', message: 'Unsupported message' });
          continue;
        }
        const currentRoom = rooms.get(room.id);
        const currentParticipant = currentRoom && findParticipantByToken(currentRoom, participant.token);
        if (!currentRoom || !currentParticipant) {
          send(socket, { type: 'error', message: 'Room membership expired' });
          continue;
        }
        const state = applyCommand(currentRoom, currentParticipant, message.command);
        broadcast(currentRoom, { type: 'state', state });
      } catch (error) {
        send(socket, { type: 'error', message: error.message || 'Command rejected' });
      }
    }
  });
  socket.on('close', () => {
    sockets.delete(socket);
    const currentRoom = rooms.get(room.id);
    if (!currentRoom) return;
    const member = currentRoom.participants.get(participant.id);
    if (!member) return;
    member.connectedAt = null;
    broadcast(currentRoom, { type: 'presence', participants: participantsSnapshot(currentRoom) });
  });
  socket.on('error', () => socket.destroy());
}

const server = https.createServer(
  { key: fs.readFileSync(TLS_KEY_PATH), cert: fs.readFileSync(TLS_CERT_PATH) },
  (req, res) => { handleHttpRequest(req, res).catch(() => sendJson(res, 500, { error: 'Internal error' })); }
);
server.on('upgrade', upgradeWebSocket);
server.listen(PORT, () => console.log(`Aurix DJ room server listening on https://0.0.0.0:${PORT}`));

setInterval(() => {
  const now = Date.now();
  for (const [id, room] of rooms) {
    if (isExpired(room, now)) {
      for (const socket of socketsByRoom.get(id) || []) socket.destroy();
      socketsByRoom.delete(id);
      rooms.delete(id);
      codes.delete(room.code);
    }
  }
  for (const [key, hits] of rateLimits) {
    const fresh = hits.filter((time) => now - time < 60_000);
    if (fresh.length) rateLimits.set(key, fresh); else rateLimits.delete(key);
  }
}, 60_000).unref();

for (const signal of ['SIGTERM', 'SIGINT']) {
  process.on(signal, () => server.close(() => process.exit(0)));
}

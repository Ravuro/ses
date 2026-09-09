'use strict';

/**
 * Telsiz relay — kanal başına oda tutan, gelen ikili çerçeveyi aynı odadaki
 * diğer herkese aynen ileten aptal bir röle.
 *
 * Sesi çözmez, saklamaz, kaydetmez: paketler istemcide üretildiği gibi
 * geçer. Böylece sunucu tarafında ne CPU ne de gizlilik yükü var.
 */

const http = require('http');
const { WebSocketServer } = require('ws');

const PORT = process.env.PORT || 8080;

const server = http.createServer((req, res) => {
  if (req.url === '/health' || req.url === '/') {
    res.writeHead(200, { 'Content-Type': 'text/plain; charset=utf-8' });
    res.end(`telsiz-relay calisiyor\nodalar: ${rooms.size}\nbagli: ${countClients()}\n`);
    return;
  }
  res.writeHead(404);
  res.end();
});

const wss = new WebSocketServer({ server, maxPayload: 4096 });

/** @type {Map<string, Set<import('ws').WebSocket>>} */
const rooms = new Map();

function countClients() {
  let n = 0;
  for (const set of rooms.values()) n += set.size;
  return n;
}

function roomOf(req) {
  let ch = '1';
  try {
    const url = new URL(req.url, 'http://x');
    ch = url.searchParams.get('ch') || '1';
  } catch (_) {
    // varsayılan kanalda kal
  }
  // Kanal numarası dışında bir şey gelmesin.
  if (!/^\d{1,3}$/.test(ch)) ch = '1';
  return ch;
}

wss.on('connection', (ws, req) => {
  const ch = roomOf(req);
  let set = rooms.get(ch);
  if (!set) {
    set = new Set();
    rooms.set(ch, set);
  }
  set.add(ws);
  ws.channel = ch;
  ws.isAlive = true;

  ws.on('pong', () => { ws.isAlive = true; });

  ws.on('message', (data, isBinary) => {
    if (!isBinary) return;
    const peers = rooms.get(ch);
    if (!peers) return;
    for (const peer of peers) {
      if (peer === ws) continue;
      if (peer.readyState !== peer.OPEN) continue;
      peer.send(data, { binary: true });
    }
  });

  const drop = () => {
    const peers = rooms.get(ch);
    if (!peers) return;
    peers.delete(ws);
    if (peers.size === 0) rooms.delete(ch);
  };

  ws.on('close', drop);
  ws.on('error', drop);
});

// Ölü bağlantıları temizle: mobil ağda kopan soketler kapandığını bildirmiyor.
const heartbeat = setInterval(() => {
  for (const set of rooms.values()) {
    for (const ws of set) {
      if (ws.isAlive === false) {
        ws.terminate();
        continue;
      }
      ws.isAlive = false;
      try { ws.ping(); } catch (_) { /* kapanmak üzere */ }
    }
  }
}, 25000);

wss.on('close', () => clearInterval(heartbeat));

server.listen(PORT, () => {
  console.log(`telsiz-relay :${PORT}`);
});

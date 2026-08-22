'use strict';

const http = require('http');
const fs = require('fs');
const os = require('os');
const path = require('path');
const store = require('./store');
const render = require('./render');
const probe = require('./probe');

const PORT = Number(process.env.PORT) || 8080;
const PUBLIC = path.join(__dirname, '..', 'public');
const POLL_MS = 25000;
const MAX_BODY = 8 * 1024;

const MIME = {
  '.css': 'text/css; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.ico': 'image/x-icon'
};

function sendHtml(res, html) {
  const body = Buffer.from(html, 'utf8');
  res.writeHead(200, {
    'Content-Type': 'text/html; charset=utf-8',
    'Content-Length': body.length,
    'Cache-Control': 'no-store'
  });
  res.end(body);
}

function sendJson(res, obj) {
  const body = Buffer.from(JSON.stringify(obj), 'utf8');
  res.writeHead(200, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': body.length,
    'Cache-Control': 'no-store'
  });
  res.end(body);
}

function sendStatic(res, urlPath) {
  const rel = path.normalize(urlPath).replace(/^([/\\])+/, '');
  const file = path.join(PUBLIC, rel);
  if (!file.startsWith(PUBLIC + path.sep)) {
    res.writeHead(403).end('forbidden');
    return;
  }
  fs.readFile(file, (err, data) => {
    if (err) {
      res.writeHead(404, { 'Content-Type': 'text/plain' }).end('not found');
      return;
    }
    res.writeHead(200, {
      'Content-Type': MIME[path.extname(file)] || 'application/octet-stream',
      'Content-Length': data.length,
      'Cache-Control': 'public, max-age=86400'
    });
    res.end(data);
  });
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    req.on('data', (c) => {
      size += c.length;
      if (size > MAX_BODY) {
        reject(new Error('body too large'));
        req.destroy();
        return;
      }
      chunks.push(c);
    });
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    req.on('error', reject);
  });
}

function parseBody(raw, contentType) {
  if (/application\/json/i.test(contentType || '')) {
    try { return JSON.parse(raw); } catch { return {}; }
  }
  const out = {};
  for (const [k, v] of new URLSearchParams(raw)) out[k] = v;
  return out;
}

function applyAction(body) {
  switch (body.act) {
    case 'add': store.addTodo(body.text); return true;
    case 'toggle': store.toggleTodo(body.id); return true;
    case 'del': store.deleteTodo(body.id); return true;
    case 'clear': store.clearDone(); return true;
    case 'sw-start': store.stopwatchStart(); return true;
    case 'sw-stop': store.stopwatchStop(); return true;
    case 'sw-toggle':
      if (store.getState().stopwatch.running) store.stopwatchStop();
      else store.stopwatchStart();
      return true;
    case 'sw-reset': store.stopwatchReset(); return true;
    case 'sw-lap': store.stopwatchLap(); return true;
    default: return false;
  }
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://localhost');
  const route = url.pathname;

  try {
    if (req.method === 'GET' && (route === '/' || route === '/index.html')) {
      sendHtml(res, render.renderKindlePage(store.getState(), store.getVersion()));
      return;
    }

    if (req.method === 'GET' && (route === '/remote' || route === '/remote/')) {
      sendHtml(res, render.renderRemotePage(store.getState(), store.getVersion()));
      return;
    }

    if (req.method === 'GET' && route === '/probe') {
      sendHtml(res, probe.renderProbePage());
      return;
    }

    if (req.method === 'GET' && route === '/api/poll') {
      const since = Number(url.searchParams.get('v'));
      const v = await store.waitForChange(since, POLL_MS);
      if (res.writableEnded || req.destroyed) return;
      sendJson(res, render.statePayload(store.getState(), v, url.searchParams.get('for')));
      return;
    }

    if (req.method === 'POST' && route === '/api/action') {
      const body = parseBody(await readBody(req), req.headers['content-type']);
      const ok = applyAction(body);
      const wantsJson = /application\/json/i.test(req.headers.accept || '');
      if (!wantsJson) {
        // No-JS fallback: plain form posts bounce back to the page they came from.
        res.writeHead(303, { Location: req.headers.referer || '/remote' }).end();
        return;
      }
      if (!ok) {
        res.writeHead(400, { 'Content-Type': 'application/json' })
          .end(JSON.stringify({ error: 'unknown action' }));
        return;
      }
      sendJson(res, render.statePayload(store.getState(), store.getVersion(), url.searchParams.get('for')));
      return;
    }

    if (req.method === 'GET' && route === '/favicon.ico') {
      res.writeHead(204).end();
      return;
    }

    if (req.method === 'GET') {
      sendStatic(res, route);
      return;
    }

    res.writeHead(405, { 'Content-Type': 'text/plain' }).end('method not allowed');
  } catch (err) {
    console.error(req.method, route, '->', err.message);
    if (!res.headersSent) res.writeHead(500, { 'Content-Type': 'text/plain' });
    res.end('server error');
  }
});

// Long-polls must be allowed to sit idle for the full hold.
server.keepAliveTimeout = POLL_MS + 15000;
server.headersTimeout = POLL_MS + 20000;
server.requestTimeout = 0;

function lanAddresses() {
  const out = [];
  for (const list of Object.values(os.networkInterfaces())) {
    for (const net of list || []) {
      if (net.family === 'IPv4' && !net.internal) out.push(net.address);
    }
  }
  return out;
}

server.listen(PORT, '0.0.0.0', () => {
  const hosts = lanAddresses();
  console.log('\n  Kindle Idle is running\n');
  console.log(`  Kindle screen   http://localhost:${PORT}/`);
  console.log(`  Phone remote    http://localhost:${PORT}/remote`);
  console.log(`  Device probe    http://localhost:${PORT}/probe`);
  if (hosts.length) {
    console.log('\n  On your network:');
    for (const h of hosts) {
      console.log(`    kindle  http://${h}:${PORT}/`);
      console.log(`    remote  http://${h}:${PORT}/remote`);
    }
  }
  console.log('');
});

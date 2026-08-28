'use strict';

const http = require('http');
const fs = require('fs');
const os = require('os');
const path = require('path');
const store = require('./store');
const render = require('./render');
const auth = require('./auth');

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

// Dark mode is a per-device choice, not shared state: the Kindle can stay on
// paper while the phone goes dark at night. A cookie rather than localStorage
// so the server can stamp the theme on the first paint -- on e-ink, letting a
// script correct it afterwards means a full white flash first.
function readTheme(req) {
  const match = /(?:^|;\s*)ki_theme=(dark|light)(?:\s*;|\s*$)/.exec(req.headers.cookie || '');
  return match ? match[1] : '';
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

// Where to send someone back to once they are in. Anything that is not a
// plain path on this server is dropped rather than corrected: an open redirect
// is the standard way a login form gets turned into a phishing hop.
function safeNext(value) {
  const next = String(value || '');
  if (!next.startsWith('/') || next.startsWith('//') || next.startsWith('/\\')) return '/';
  if (next.startsWith('/login') || next.startsWith('/logout')) return '/';
  return next;
}

// Cookies ride along with a cross-site form post, so `SameSite=Lax` is the
// first line and this is the second -- old WebKit, the Kindle's included,
// predates SameSite entirely and ignores it. A browser that sends `Origin`
// must send one matching this host; one that sends none (the Kindle, on a
// same-origin form post) is let through, which is the best a server can do.
function sameOrigin(req) {
  const origin = req.headers.origin;
  if (!origin || origin === 'null') return true;
  try {
    return new URL(origin).host === req.headers.host;
  } catch (err) {
    return false;
  }
}

function wantsJson(req) {
  return /application\/json/i.test(req.headers.accept || '');
}

// The gate every route below /login passes through. A page request bounces to
// the login form; a request from one of the two clients gets a 401 it can act
// on, because a 303 to an HTML page is not something an XHR expecting JSON can
// do anything sensible with.
function gate(req, res, url) {
  if (auth.hasSession(req)) return true;
  if (wantsJson(req)) {
    res.writeHead(401, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' })
      .end(JSON.stringify({ error: 'auth' }));
    return false;
  }
  const next = encodeURIComponent(url.pathname + (url.search || ''));
  res.writeHead(303, { Location: '/login?next=' + next, 'Cache-Control': 'no-store' }).end();
  return false;
}

function applyAction(body) {
  switch (body.act) {
    case 'add': store.addTodo(body.text); return true;
    case 'toggle': store.toggleTodo(body.id); return true;
    case 'del': store.deleteTodo(body.id); return true;
    case 'clear': store.clearDone(); return true;
    // An unknown id is a no-op rather than an error, the same as toggling a
    // todo that has since been deleted; the reply echoes the scene in force.
    case 'scene': store.setScene(body.id); return true;
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
    // Two things are served without a session: the login form, and the
    // favicon the browser asks for before it has followed the redirect.
    if (route === '/login') {
      if (req.method === 'GET') {
        sendHtml(res, render.renderLoginPage({
          theme: readTheme(req),
          next: safeNext(url.searchParams.get('next')),
          locked: auth.lockedFor(req)
        }));
        return;
      }
      if (req.method === 'POST') {
        if (!sameOrigin(req)) {
          res.writeHead(403, { 'Content-Type': 'text/plain' }).end('forbidden');
          return;
        }
        const form = parseBody(await readBody(req), req.headers['content-type']);
        const next = safeNext(form.next);
        // Two doors, one counter: the username/password form and the pairing
        // code share the lockout, so alternating between them cannot buy an
        // attacker a fresh set of free tries at either.
        const byCode = String(form.code || '').replace(/[^0-9]/g, '') !== '';
        const locked = auth.lockedFor(req);
        // A locked-out address is not told whether it guessed right, so the
        // lockout cannot be used as an oracle for one last free check.
        const ok = !locked && (byCode
          ? auth.redeemPairingCode(form.code)
          : await auth.verifyLogin(form.user, form.pass));

        if (!ok) {
          if (!locked) auth.recordFailure(req);
          res.writeHead(locked ? 429 : 401, {
            'Content-Type': 'text/html; charset=utf-8',
            'Cache-Control': 'no-store'
          });
          res.end(render.renderLoginPage({
            theme: readTheme(req),
            next,
            error: byCode ? 'code' : true,
            locked: auth.lockedFor(req)
          }));
          return;
        }
        auth.recordSuccess(req);
        res.writeHead(303, {
          Location: next,
          'Set-Cookie': auth.sessionCookie(),
          'Cache-Control': 'no-store'
        }).end();
        return;
      }
    }

    if (route === '/logout' && req.method === 'POST') {
      if (!sameOrigin(req)) {
        res.writeHead(403, { 'Content-Type': 'text/plain' }).end('forbidden');
        return;
      }
      res.writeHead(303, {
        Location: '/login',
        'Set-Cookie': auth.clearedCookie(),
        'Cache-Control': 'no-store'
      }).end();
      return;
    }

    if (req.method === 'GET' && route === '/favicon.ico') {
      res.writeHead(204).end();
      return;
    }

    if (!gate(req, res, url)) return;

    if (req.method === 'POST' && !sameOrigin(req)) {
      res.writeHead(403, { 'Content-Type': 'text/plain' }).end('forbidden');
      return;
    }

    // The pairing desk. GET shows the code in force, minting one only if
    // none is live, so reloading the page does not invalidate a code already
    // written on somebody's hand. POST is the deliberate "give me a new one".
    // Neither puts the code in a URL: it is a credential, and query strings
    // end up in history and logs.
    if (route === '/pair' && (req.method === 'GET' || req.method === 'POST')) {
      if (req.method === 'POST') {
        auth.newPairingCode();
        res.writeHead(303, { Location: '/pair', 'Cache-Control': 'no-store' }).end();
        return;
      }
      const pair = auth.currentPairingCode();
      sendHtml(res, render.renderPairPage({
        code: pair.code,
        expiresAt: pair.expiresAt,
        theme: readTheme(req),
        origin: 'http://' + (req.headers.host || 'this server') + '/'
      }));
      return;
    }

    if (req.method === 'GET' && (route === '/' || route === '/index.html')) {
      sendHtml(res, render.renderKindlePage(store.getState(), store.getVersion(), readTheme(req)));
      return;
    }

    if (req.method === 'GET' && (route === '/remote' || route === '/remote/')) {
      sendHtml(res, render.renderRemotePage(store.getState(), store.getVersion(), readTheme(req)));
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

// The password is printed once, on the run that generated it. After that the
// only copy is the scrypt hash in data/auth.json, so a lost one is reset with
// `npm run passwd` rather than recovered.
function authBanner(info) {
  if (info.source === 'generated') {
    console.log('  An account was created for this server. Write it down --');
    console.log('  the password is not stored in readable form and will not');
    console.log('  be shown again.\n');
    console.log(`      username   ${info.username}`);
    console.log(`      password   ${info.password}\n`);
    console.log('  Sign in on your phone at /remote, then use Pair a device');
    console.log('  to get a six-digit code for the Kindle.');
    console.log('  Change the account any time with:  npm run passwd\n');
    return;
  }
  const from = info.source === 'env' ? 'KI_USER / KI_PASSWORD' : 'data/auth.json';
  console.log(`  Locked. Signed in as "${info.username}", from ${from}.\n`);
}

function start(info) {
  server.listen(PORT, '0.0.0.0', () => {
  const hosts = lanAddresses();
  console.log('\n  Kindle Idle is running\n');
  authBanner(info);
  console.log(`  Kindle screen   http://localhost:${PORT}/`);
  console.log(`  Phone remote    http://localhost:${PORT}/remote`);
  if (hosts.length) {
    console.log('\n  On your network:');
    for (const h of hosts) {
      console.log(`    kindle  http://${h}:${PORT}/`);
      console.log(`    remote  http://${h}:${PORT}/remote`);
    }
  }
  console.log('');
  });
}

// Hashing is async now, so the account has to be ready before the socket is:
// binding first would open a window where requests arrive with no credentials
// loaded to check them against.
auth.init().then(start).catch((err) => {
  console.error('\n  Could not load the account:', err.message, '\n');
  process.exit(1);
});

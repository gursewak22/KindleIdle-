'use strict';

// The gate, and the pairing desk.
//
// Two ways in, because the two devices have very different keyboards. The
// phone signs in with a username and password, the way anything else does.
// The Kindle -- whose on-screen keyboard makes a real password a chore -- gets
// a six-digit code generated on the phone, good for thirty minutes and one
// device. See docs/adr/0009.
//
// Sessions are stateless either way: a signed token in a cookie, verified by
// recomputing the HMAC. Nothing is kept in memory, so a restart does not sign
// the Kindle out -- which matters, because it may sit untouched for weeks.

const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

const DATA_DIR = path.join(__dirname, '..', 'data');
const FILE = path.join(DATA_DIR, 'auth.json');
const TMP = FILE + '.tmp';

const CONFIG_VERSION = 2;
const COOKIE = 'ki_session';
const SESSION_MS = 365 * 24 * 60 * 60 * 1000;

// A pairing code is a credential with a very small keyspace, so it leans
// entirely on the throttle below and on dying young.
const PAIR_MS = 30 * 60 * 1000;
const PAIR_MAX_TRIES = 10;

// scrypt at these parameters costs ~16 MB and tens of milliseconds per
// attempt. That is deliberate -- it is what stands between a guessed password
// and the list -- and it runs on the threadpool rather than the event loop,
// because sign-in is an unauthenticated endpoint and the Kindle's long-poll
// must not stall while someone is guessing at it.
const SCRYPT = { N: 16384, r: 8, p: 1 };
const KEYLEN = 64;

const DEFAULT_USER = 'kindle';

let conf = null;      // { secret, salt, user: { name, hash } }
let signKey = null;   // HMAC key, derived from the secret AND the credentials
let pairing = null;   // { code, expiresAt, tries } -- one at a time, in memory

/* ---------------------------------------------------------------------------
   password storage
--------------------------------------------------------------------------- */

// NFKC so a password typed with a composed accent on the phone matches the
// decomposed form another keyboard may produce.
function hashPass(plain, salt) {
  return new Promise((resolve, reject) => {
    crypto.scrypt(String(plain).normalize('NFKC'), salt, KEYLEN, SCRYPT, (err, key) => {
      if (err) reject(err);
      else resolve(key.toString('hex'));
    });
  });
}

function normalUser(name) {
  return String(name || '').normalize('NFKC').trim().toLowerCase();
}

// Length is not hidden -- comparing buffers requires equal lengths -- but the
// content is. For a username that is a fair trade; for the hashes, both sides
// are fixed-length anyway.
function safeEqual(a, b) {
  const ab = Buffer.from(String(a), 'utf8');
  const bb = Buffer.from(String(b), 'utf8');
  if (ab.length !== bb.length) return false;
  return crypto.timingSafeEqual(ab, bb);
}

// Pronounceable, because it gets read off a console and typed on a phone.
// Eight syllables from 75 each is ~50 bits, far past what a rate-limited login
// can be walked through.
const CONS = 'bdfgjklmnprstvz';
const VOWELS = 'aeiou';

function generatePassword() {
  const bytes = crypto.randomBytes(32);
  let out = '';
  for (let i = 0; i < 8; i++) {
    if (i && i % 2 === 0) out += '-';
    out += CONS[bytes[i * 2] % CONS.length] + VOWELS[bytes[i * 2 + 1] % VOWELS.length];
  }
  return out;
}

// No `mode` here: on Windows Node's mode argument only toggles the read-only
// flag, so passing 0o600 would claim a protection the file does not have. The
// file is as private as the folder it sits in, and README says so.
function writeConf(obj) {
  fs.mkdirSync(DATA_DIR, { recursive: true });
  fs.writeFileSync(TMP, JSON.stringify(obj, null, 2));
  fs.renameSync(TMP, FILE);
}

function readConf() {
  try {
    const raw = JSON.parse(fs.readFileSync(FILE, 'utf8'));
    if (raw.version !== CONFIG_VERSION) return null;
    if (typeof raw.secret !== 'string' || typeof raw.salt !== 'string') return null;
    if (!raw.user || typeof raw.user.name !== 'string' || typeof raw.user.hash !== 'string') return null;
    return raw;
  } catch (err) { /* missing, corrupt, or an older shape -- start over */ }
  return null;
}

// Deriving the signing key from the credentials as well as the secret is what
// makes a password change sign every device out: the tokens already issued
// still verify against a key that no longer exists.
function deriveSignKey(secret, user) {
  return crypto.createHmac('sha256', secret)
    .update('ki-session|' + user.name + '|' + user.hash)
    .digest();
}

/* Loads the account, creating one on first run. Returns a summary for the
   startup banner: { source, username, password }, where `password` is present
   only on the run that generated it. */
async function init() {
  const file = readConf();
  let generated = null;
  let source;
  let user;
  let secretHex;
  let saltHex;

  // An env account outranks the stored one without rewriting it, so
  // `KI_PASSWORD=... npm start` is an override for one run, not a change.
  if (process.env.KI_PASSWORD) {
    source = 'env';
    secretHex = (file && file.secret) || crypto.randomBytes(32).toString('hex');
    saltHex = (file && file.salt) || crypto.randomBytes(16).toString('hex');
    user = {
      name: normalUser(process.env.KI_USER || (file && file.user && file.user.name) || DEFAULT_USER),
      hash: await hashPass(process.env.KI_PASSWORD, Buffer.from(saltHex, 'hex'))
    };
    if (!file) writeConf({ version: CONFIG_VERSION, secret: secretHex, salt: saltHex, user, updatedAt: new Date().toISOString() });
  } else if (file) {
    source = 'file';
    secretHex = file.secret;
    saltHex = file.salt;
    user = file.user;
  } else {
    // Never leave the door open waiting to be configured: the first run makes
    // an account, prints it once, and is shut from that moment on.
    source = 'generated';
    generated = generatePassword();
    secretHex = crypto.randomBytes(32).toString('hex');
    saltHex = crypto.randomBytes(16).toString('hex');
    user = {
      name: DEFAULT_USER,
      hash: await hashPass(generated, Buffer.from(saltHex, 'hex'))
    };
    writeConf({ version: CONFIG_VERSION, secret: secretHex, salt: saltHex, user, updatedAt: new Date().toISOString() });
  }

  conf = { secret: Buffer.from(secretHex, 'hex'), salt: Buffer.from(saltHex, 'hex'), user };
  signKey = deriveSignKey(conf.secret, user);
  return { source, username: user.name, password: generated };
}

// Used by server/passwd.js. Rewriting the account invalidates every
// outstanding session by changing the signing key underneath it.
async function setAccount(name, password) {
  const user = normalUser(name);
  if (!user) throw new Error('username cannot be empty');
  if (!/^[a-z0-9._-]{1,32}$/.test(user)) {
    throw new Error('username may use letters, digits, dot, dash and underscore only');
  }
  if (String(password).length < 8) throw new Error('password must be at least 8 characters');

  const existing = readConf();
  const salt = crypto.randomBytes(16);
  writeConf({
    version: CONFIG_VERSION,
    secret: (existing && existing.secret) || crypto.randomBytes(32).toString('hex'),
    salt: salt.toString('hex'),
    user: { name: user, hash: await hashPass(password, salt) },
    updatedAt: new Date().toISOString()
  });
}

// The password is hashed even when the username is already wrong, so a wrong
// username costs exactly what a wrong password does and cannot be picked out
// by how long the answer took.
async function verifyLogin(name, password) {
  if (!conf) return false;
  const hash = await hashPass(password == null ? '' : password, conf.salt);
  const nameOk = safeEqual(normalUser(name), conf.user.name);
  const passOk = safeEqual(hash, conf.user.hash);
  return nameOk && passOk;
}

/* ---------------------------------------------------------------------------
   pairing codes

   One live code at a time: asking for a code shows the one already in force,
   and asking for a new one replaces it. That keeps "which code is on my
   phone right now" answerable by looking at the phone.
--------------------------------------------------------------------------- */

function livePairing() {
  if (pairing && pairing.expiresAt > Date.now()) return pairing;
  pairing = null;
  return null;
}

// randomInt rather than randomBytes % 1e6: the modulo would make the low
// codes fractionally likelier, and there are few enough of them already.
function newPairingCode() {
  pairing = {
    code: String(crypto.randomInt(0, 1000000)).padStart(6, '0'),
    expiresAt: Date.now() + PAIR_MS,
    tries: 0
  };
  return pairing;
}

function currentPairingCode() {
  return livePairing() || newPairingCode();
}

function clearPairingCode() {
  pairing = null;
}

/* Consumes the code. Single use: the first device to get it right takes it,
   and a wrong guess is spent too -- ten of those and the code is abandoned,
   so a brute force cannot outlive the code it is attacking. */
function redeemPairingCode(input) {
  const live = livePairing();
  if (!live) return false;
  const digits = String(input || '').replace(/\D/g, '');
  live.tries++;
  if (live.tries > PAIR_MAX_TRIES) {
    pairing = null;
    return false;
  }
  if (digits.length !== 6 || !safeEqual(digits, live.code)) return false;
  pairing = null;
  return true;
}

/* ---------------------------------------------------------------------------
   sessions
--------------------------------------------------------------------------- */

function sign(payload) {
  return crypto.createHmac('sha256', signKey).update(payload).digest('base64')
    .replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function issueToken() {
  const exp = Date.now() + SESSION_MS;
  const payload = exp.toString(36) + '.' + crypto.randomBytes(9).toString('hex');
  return payload + '.' + sign(payload);
}

function validToken(token) {
  if (typeof token !== 'string' || token.length > 300) return false;
  const cut = token.lastIndexOf('.');
  if (cut < 1) return false;
  const payload = token.slice(0, cut);
  if (!safeEqual(token.slice(cut + 1), sign(payload))) return false;
  const exp = parseInt(payload.slice(0, payload.indexOf('.')), 36);
  return Number.isFinite(exp) && exp > Date.now();
}

function readCookie(req, name) {
  const raw = req.headers.cookie || '';
  const match = new RegExp('(?:^|;\\s*)' + name + '=([^;]*)').exec(raw);
  return match ? match[1] : '';
}

function hasSession(req) {
  return validToken(readCookie(req, COOKIE));
}

// No `Secure`: this is plain HTTP on a LAN, and a Secure cookie would simply
// never come back. HttpOnly still holds -- no script here reads the session,
// so nothing injected into a page can read it either.
function sessionCookie() {
  return COOKIE + '=' + issueToken() +
    '; Path=/; Max-Age=' + Math.floor(SESSION_MS / 1000) + '; HttpOnly; SameSite=Lax';
}

function clearedCookie() {
  return COOKIE + '=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax';
}

/* ---------------------------------------------------------------------------
   login throttle

   Guessing is what both credentials have to survive, and the six-digit code
   has only a million of itself to hide behind, so failures from one address
   slow down hard. Both doors share the counter: an attacker cannot get five
   fresh password tries by alternating with code tries. Keyed by IP because
   that is all a LAN offers; the map is pruned so a spoofed flood cannot grow
   it without bound.
--------------------------------------------------------------------------- */

const FREE_TRIES = 5;
const BASE_LOCK_MS = 30 * 1000;
const MAX_LOCK_MS = 15 * 60 * 1000;
const MAX_TRACKED = 500;

const attempts = new Map();

function prune(now) {
  for (const [ip, rec] of attempts) {
    if (rec.until < now && now - rec.last > MAX_LOCK_MS) attempts.delete(ip);
  }
  if (attempts.size <= MAX_TRACKED) return;
  // Still too many: drop the coldest. Losing a fail count only ever costs an
  // attacker-shaped client its lockout, never a legitimate one its access.
  const cold = [...attempts.entries()].sort((a, b) => a[1].last - b[1].last);
  for (let i = 0; i < cold.length - MAX_TRACKED; i++) attempts.delete(cold[i][0]);
}

function clientIp(req) {
  return req.socket.remoteAddress || 'unknown';
}

// Returns 0 when the address may try, or the milliseconds left on its lockout.
function lockedFor(req) {
  const rec = attempts.get(clientIp(req));
  if (!rec) return 0;
  return Math.max(0, rec.until - Date.now());
}

function recordFailure(req) {
  const now = Date.now();
  const ip = clientIp(req);
  const rec = attempts.get(ip) || { fails: 0, until: 0, last: now };
  rec.fails++;
  rec.last = now;
  if (rec.fails > FREE_TRIES) {
    const lock = Math.min(BASE_LOCK_MS * Math.pow(2, rec.fails - FREE_TRIES - 1), MAX_LOCK_MS);
    rec.until = now + lock;
  }
  attempts.set(ip, rec);
  prune(now);
}

function recordSuccess(req) {
  attempts.delete(clientIp(req));
}

module.exports = {
  init,
  setAccount,
  verifyLogin,
  hasSession,
  sessionCookie,
  clearedCookie,
  currentPairingCode,
  newPairingCode,
  clearPairingCode,
  redeemPairingCode,
  lockedFor,
  recordFailure,
  recordSuccess,
  PAIR_MS
};

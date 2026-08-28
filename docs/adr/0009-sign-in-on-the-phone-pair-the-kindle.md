# ADR 0009: Sign in on the phone, pair the Kindle with a short-lived code

- **Status:** Accepted
- **Date:** 2026-08-25

## Context

The server binds `0.0.0.0`, so everything it serves is reachable by anything on
the LAN — a guest on the wifi, a housemate, anything on the network that has
been compromised. Until now the answer was a note in the README saying to keep
it on a trusted network, which is a hope rather than a control: the todo list
is readable, and the actions that mutate it need nothing but the URL.

The obvious design — one password, typed on whichever device is asking — runs
straight into the hardware. The two screens have nothing in common as input
devices. The phone has a real keyboard and a password manager. The Kindle has
a laggy on-screen keyboard behind an e-ink panel, where every keystroke costs a
partial refresh and a mistyped character is genuinely painful to correct. A
password strong enough to be worth having is a password nobody wants to type
there, and one comfortable to type there is not worth having.

Two further constraints shape the rest.

**TLS is not available.** The Kindle's browser predates modern certificate
handling; a self-signed certificate is a wall on that device, and there is no
public hostname on a LAN to get a real one for. So credentials and the session
cookie cross the network in clear. This is the real cost of the design and it
is not one we can engineer away here.

**The Kindle cannot be asked to sign in often.** It sits on a shelf for weeks
untouched. A session that expires on a normal schedule, or dies when the server
restarts, means walking over to the shelf to type on e-ink.

## Decision

Split the credential by device, and let the strong one issue the weak one.

**The phone signs in with a username and password.** One account, stored in
`data/auth.json` as a username plus an scrypt hash (N=16384, r=8, p=1).
There is deliberately no user model beyond that: every piece of state here is
shared by construction — one todo list, one stopwatch, one scene — so separate
accounts would give each an identical view of identical state. The username is
not a second secret; it is there because a login that asks for one is the login
people know how to use. It is compared case-insensitively and trimmed, and the
password is hashed even when the username is already wrong, so a wrong username
costs exactly what a wrong password does and cannot be found by timing.

**The Kindle pairs with a six-digit code.** A signed-in phone opens `/pair` and
gets a code good for thirty minutes and one device. That is the whole reason
for the split: the digits are typed once on the shelf, and everything that
makes a password worth having stays on the device with a keyboard. One code
exists at a time — reloading `/pair` shows the code already in force rather
than minting another, so the number written on somebody's hand stays the
number that works, and **New code** is the deliberate way to replace it.

A six-digit code is only a million possibilities, so it does not defend itself;
three things defend it. It is single use, so a shoulder-surfed code is spent
the moment the Kindle takes it. It dies after ten wrong guesses, so a brute
force cannot outlive the code it is attacking. And it shares the per-IP
throttle with the password form — five free attempts, then a lockout doubling
from 30 seconds to a fifteen-minute cap — so an attacker cannot buy fresh tries
at one door by alternating with the other.

**Both doors issue the same session:** a stateless cookie holding
`exp.nonce.HMAC(exp.nonce)`, good for a year. The thirty minutes limits the
code, not what the code grants — a paired Kindle is a signed-in Kindle, which
was the point. Nothing is kept server-side, so a restart does not sign anything
out. The signing key is derived from the secret **and** the stored credentials,
which is what makes `npm run passwd` sign every device out: the outstanding
tokens still verify against a key that no longer exists.

Everything is gated except `GET /login`, `POST /login`, `POST /logout` and the
favicon — the static directory included, so an unauthenticated stranger cannot
even read the client code. An unauthenticated page request gets a `303` to the
login form; a request that asked for JSON gets a `401`, because a redirect to
an HTML page is not something an XHR can act on. Both clients treat a `401` as
"reload", which lands them on the form.

The login and pairing pages carry their own inline CSS and no script that
matters. `/login` is served before a session exists, so linking `/remote.css`
would mean opening the static directory to reach it; a plain form post is the
one interaction the Kindle's browser has never got wrong, and both doors are
plain forms on the one page, because the device most likely to need the code
door is the one least able to run a script to reveal it.

Hashing runs on the threadpool, not the event loop. scrypt is meant to cost
~33 ms, and it is reachable unauthenticated: doing that synchronously would let
a handful of concurrent guesses stall the Kindle's long-poll along with
everything else.

Every POST is checked for a cross-site `Origin`, because `SameSite=Lax` does
not exist as far as the Kindle's WebKit is concerned. A request with no
`Origin` at all is allowed, which is what a same-origin form post from that
browser looks like.

## Consequences

- Nothing on the LAN reads or changes the list without signing in. That was
  the point.
- The strong credential is only ever typed where typing is easy. The Kindle
  types six digits, once, and is then signed in for a year.
- **Credentials and the session cookie travel in clear over HTTP.** Anyone
  positioned to read packets on the network — not merely connected to it —
  can take either, and a pairing code is six digits going past in plaintext.
  This raises the bar from "knows the IP" to "can sniff the wire", which is
  the honest description of what a LAN appliance with an un-TLS-able client
  can buy. Anywhere less trusted than a home network needs a reverse proxy
  terminating TLS in front, with its own auth in front of this one.
- A pairing code is a real credential for thirty minutes. It is shown on a
  phone screen, which is the intended weak point: whoever can see the phone
  can pair a device. Single use and the ten-guess limit keep that window
  narrow, and generating a new code closes it immediately.
- Codes live in memory only. A restart during those thirty minutes forgets the
  outstanding code — generate another. Storing them would mean writing a live
  credential to disk to save a step that takes five seconds.
- There is no way to sign out one device without signing out all of them, and
  no record of which devices are signed in. Stateless sessions bought the
  restart-survival, and this is the other side of it. A stolen cookie stays
  good for a year, which is what `npm run passwd` is for.
- Sign-in is a full page load on the Kindle — the one full-screen e-ink
  repaint this design accepts, because it happens once a year.
- `data/auth.json` holds the HMAC secret as well as the hash, so anyone who
  can read that file can forge a session without knowing the password. It is
  as private as the folder it sits in and no more: Node's `mode` argument only
  toggles the read-only flag on Windows, so claiming `0600` there would be a
  protection the file does not have. Deleting it signs every device out and
  generates a new account.
- One account means no way to give someone access you can revoke on its own.
  Adding that would mean a real user store, and every account would still see
  the same shared list.

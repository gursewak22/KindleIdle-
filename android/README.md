# Kindle Idle — Android host

Runs the Kindle Idle server on an Android phone, so the Kindle has something
to talk to without a computer being left on.

The Node server in `../server` is unchanged and still works exactly as it did.
This is a second host for the same thing: same URLs, same two web pages, same
`data/` file formats.

```
  phone (this app)                          kindle
  ├─ HTTP server on 0.0.0.0:8080  ──────►   http://192.168.1.x:8080/
  ├─ shared state ─────────────────────┐
  └─ native remote screens ◄───────────┘    (the phone's own UI, in-process)
```

## Building

Open the `android/` folder in Android Studio and press Run, or:

```
cd android
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug           # to a connected phone
./gradlew testDebugUnitTest      # the scrypt vectors
```

Built and tested against AGP 9.3.2, Gradle 9.7.1, JDK 25, `compileSdk` 37.
`minSdk` is 24. If you are on an older toolchain, the versions to move are in
`build.gradle.kts` and `gradle/wrapper/gradle-wrapper.properties`.

## Using it

1. Put the phone and the Kindle on the same Wi-Fi.
2. Open the app and **allow local network access when asked**. On Android 16
   and later this is a separate permission from internet access, and without
   it the server runs perfectly while being invisible to every other device
   (see Troubleshooting). It also creates an account on first run and shows
   the username and password **once** — write them down. The password is
   stored only as a scrypt hash, so a lost one is replaced from the Account
   section, not recovered.
3. Turn the server on. The Host tab lists the addresses the Kindle can reach.
4. On the Kindle, open `http://<phone>:8080/`.
5. Back on the phone, tap **Get a code** and type the six digits on the
   Kindle. It stays signed in for a year.

The Kindle is now showing the idle screen. The phone can drive it either from
the app's own tabs or from `/remote` in a browser — they are the same state.

### The tabs

| tab | what it is |
|---|---|
| **Host** | start/stop, LAN addresses, pairing code, account, port |
| **Idle** | the same idle screen the Kindle shows — clock, animated scene, up next. Tap for full screen |
| **Tasks** | the list |
| **Timer** | the stopwatch |
| **Scenes** | the picker |

The Idle tab holds the screen on while it is open, and going full screen hides
the system bars, so a spare phone on a stand is an idle screen too. Back or a
second tap leaves.

### Troubleshooting

**The phone can open it but the Kindle and everything else cannot.**

Android 16 (API 36) split local network access out of `INTERNET` into its own
runtime permission, `ACCESS_LOCAL_NETWORK`. Denied, the failure is almost
perfectly disguised: the socket binds, `ss` shows it `LISTEN`ing on `*:8080`,
the TCP handshake completes, and connections reach `ESTAB` — the request bytes
simply never arrive, and the server times out waiting for a request that was
never delivered. Loopback is exempt, so the phone's own browser and
`adb forward` both work, which makes it look like a network fault rather than
a permission.

Grant it in **Settings → Apps → Kindle Idle Host → Permissions → Local network
devices**, or:

```
adb shell pm grant com.kindleidle.host android.permission.ACCESS_LOCAL_NETWORK
```

The Host tab says so in red when the permission is missing, because there is
nothing else to see from the inside.

To confirm the diagnosis rather than guess at it:

```
adb shell appops get com.kindleidle.host | grep LOCAL_NETWORK
```

A `rejectTime` that updates each time something tries to reach the phone is
the tell.

### Keeping it alive

The server runs in a foreground service holding a Wi-Fi lock and a partial
wake lock, because a long-poll that waits 25 seconds has nothing else keeping
the radio and CPU awake. Two things still get in the way on most phones:

- **Battery optimisation.** Exempt the app in Android's battery settings, or
  the system will eventually stop it overnight.
- **Wi-Fi sleep.** Some manufacturers turn the radio off with the screen
  regardless of the lock. If the Kindle goes stale only while the phone is
  idle, that is what happened.

The Wi-Fi lock uses the deprecated `WIFI_MODE_FULL_HIGH_PERF` on purpose.
`WIFI_MODE_FULL_LOW_LATENCY` is the newer constant and looks like the better
choice, but it is only active while the app is in the foreground — it lets go
of the radio at exactly the moment the Kindle becomes the only thing still
asking.

Ports below 1024 are not available to an app, so 80 is not an option; the
default is 8080.

## What is shared with the Node server, and what is not

The web assets in `../public` are **not copied**. `app/build.gradle.kts` adds
that folder as an asset directory, so both hosts serve the same bytes and a
change to `kindle.js` cannot land in one and not the other.

The scenes are **generated, not ported**. `tools/gen-scenes.js` requires
`../server/idle.js`, asks it for its output, and writes
`app/src/main/assets/scenes.json` — both the exact SVG strings for the web
pages and every layer flattened to plain path data for the native screens. It
then checks that reassembling the pieces reproduces `idle.renderScenes()`
character for character and fails the build if it does not, so 468 lines of
hand-tuned SVG geometry stay in one place.

```
node android/tools/gen-scenes.js
```

The Gradle build runs this before every compile when Node is on `PATH`, and
skips it otherwise, since the generated file is checked in.

The rest — routing, state, auth, markup — is a port, in `core/` and `net/`.
Each file names the one it came from. The file formats match, so a `data/`
folder can be carried between the two hosts in either direction:

| file | shared |
|---|---|
| `data/state.json` | todos, scene, stopwatch |
| `data/auth.json` | scrypt hash, session secret — same parameters (N=16384, r=8, p=1) |

On Android these live in the app's private storage rather than the repo.

## Differences from the Node host

- **No `KI_PASSWORD` environment override.** A phone has no shell to set it
  from; the Account section does the same job as `npm run passwd`.
- **The native tabs are not a web view.** They read and write the same
  in-process store the HTTP server does, so there is no round trip and no
  second copy of the state. The idle screen and the picker draw the scenes
  natively from flattened path data, animating on the same FRAME_MS schedule
  idle.js sets, rather than running a WebView apiece.
- **Static files are served by extension.** Only `.css`, `.js`, `.svg`,
  `.png` and `.ico` — which is everything `public/` holds — so the generated
  `scenes.json` sitting beside them is not reachable over HTTP.
- **The date line** uses a fixed `EEEE, d MMMM` pattern in the phone's locale,
  where Node uses `toLocaleDateString`.

## Layout

```
tools/gen-scenes.js           idle.js -> assets/scenes.json, with a round-trip check
app/src/main/
  java/com/kindleidle/host/
    MainActivity.kt           five tabs: Host, Idle, Tasks, Timer, Scenes
    ServerController.kt       one store, one account, one socket
    ServerService.kt          foreground service, Wi-Fi + wake locks
    core/Scrypt.kt            RFC 7914, so auth.json matches Node's
    core/Auth.kt              <- server/auth.js
    core/Store.kt             <- server/store.js
    core/Render.kt            <- server/render.js
    core/Scenes.kt            <- server/idle.js, via scenes.json
    net/Http.kt               HTTP/1.1, sized for 25s long-poll holds
    net/Router.kt             <- server/index.js
    net/Lan.kt                the addresses the Kindle can actually reach
    ui/IdleScreen.kt          the Kindle's screen, on the phone
    ui/SceneView.kt           native scene drawing, animated
    ui/SvgPath.kt             SVG path data -> android.graphics.Path
    ui/Screens.kt             Host, Tasks, Timer, Scenes
  assets/scenes.json          generated
app/src/test/                 scrypt against the published vectors
```

## Verified

- `gen-scenes.js` round-trips against `idle.renderScenes()` byte for byte,
  and every layer it flattens comes from that same checked output.
- `ScryptTest` passes RFC 7914 vectors 1–3, including vector 3, which uses the
  exact parameters this app runs with.
- `HttpServerTest` drives the server over real sockets: keep-alive, sequential
  connections past the cap, abandoned and silent connections, a `503` rather
  than a silent reset when full, and recovery once dead connections age out.
- Served over Wi-Fi to another machine on a Pixel 10 Pro (Android 17):
  `/` → 303, `/login` → 200, `/favicon.ico` → 204, all in 20–40 ms.

Not yet exercised: the pages as the Kindle's own browser renders them, how the
scenes look drawn natively, and an overnight run with the screen off.

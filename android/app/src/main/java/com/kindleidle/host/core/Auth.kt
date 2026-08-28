package com.kindleidle.host.core

import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The gate, and the pairing desk -- ported from server/auth.js, including the
 * file format, so `data/auth.json` works in either host.
 *
 * Two ways in, because the two devices have very different keyboards. The
 * phone signs in with a username and password. The Kindle -- whose on-screen
 * keyboard makes a real password a chore -- gets a six-digit code generated
 * on the phone, good for thirty minutes and one device. See docs/adr/0009.
 *
 * Sessions are stateless: a signed token in a cookie, verified by recomputing
 * the HMAC. Nothing is kept in memory, so restarting the service -- or
 * Android killing it overnight -- does not sign the Kindle out.
 *
 * One thing the Node version has that this one does not: the KI_PASSWORD
 * environment override. A phone has no shell to set it from, so the app's
 * own Account screen calls [setAccount] instead.
 */
class Auth(dataDir: File) {

    private val file = File(dataDir, "auth.json")
    private val tmp = File(dataDir, "auth.json.tmp")

    private val random = SecureRandom()

    private var secret: ByteArray = ByteArray(0)
    private var salt: ByteArray = ByteArray(0)
    private var userName: String = ""
    private var userHash: String = ""
    private var signKey: ByteArray = ByteArray(0)

    /** One live pairing code at a time, in memory only. */
    private class Pairing(val code: String, val expiresAt: Long, var tries: Int = 0)
    private var pairing: Pairing? = null

    class Info(val source: String, val username: String, val password: String?)

    class PairingCode(val code: String, val expiresAt: Long)

    /* ---------------------------------------------------------------------
       account
    --------------------------------------------------------------------- */

    /** Loads the account, creating one on first run. */
    fun init(): Info {
        val stored = readConf()
        if (stored != null) {
            secret = hexToBytes(stored.getString("secret"))
            salt = hexToBytes(stored.getString("salt"))
            val u = stored.getJSONObject("user")
            userName = u.getString("name")
            userHash = u.getString("hash")
            signKey = deriveSignKey()
            return Info("file", userName, null)
        }

        // Never leave the door open waiting to be configured: the first run
        // makes an account, shows it once, and is shut from that moment on.
        val generated = generatePassword()
        secret = ByteArray(32).also { random.nextBytes(it) }
        salt = ByteArray(16).also { random.nextBytes(it) }
        userName = DEFAULT_USER
        userHash = hashPass(generated, salt)
        writeConf()
        signKey = deriveSignKey()
        return Info("generated", userName, generated)
    }

    /**
     * Replaces the account. Rewriting it invalidates every outstanding
     * session by changing the signing key underneath it -- which is the point:
     * changing the password signs every device out.
     */
    fun setAccount(name: String, password: String) {
        val clean = normalUser(name)
        require(clean.isNotEmpty()) { "Username cannot be empty." }
        require(Regex("^[a-z0-9._-]{1,32}$").matches(clean)) {
            "Username may use letters, digits, dot, dash and underscore only."
        }
        require(password.length >= 8) { "Password must be at least 8 characters." }

        salt = ByteArray(16).also { random.nextBytes(it) }
        if (secret.isEmpty()) secret = ByteArray(32).also { random.nextBytes(it) }
        userName = clean
        userHash = hashPass(password, salt)
        writeConf()
        signKey = deriveSignKey()
        pairing = null
    }

    val username: String get() = userName

    /**
     * The password is hashed even when the username is already wrong, so a
     * wrong username costs exactly what a wrong password does and cannot be
     * picked out by how long the answer took.
     */
    fun verifyLogin(name: String?, password: String?): Boolean {
        val hash = hashPass(password ?: "", salt)
        val nameOk = safeEqual(normalUser(name ?: ""), userName)
        val passOk = safeEqual(hash, userHash)
        return nameOk && passOk
    }

    // NFKC so a password typed with a composed accent on the phone matches
    // the decomposed form another keyboard may produce.
    private fun hashPass(plain: String, withSalt: ByteArray): String {
        val norm = Normalizer.normalize(plain, Normalizer.Form.NFKC)
        val key = Scrypt.derive(norm.toByteArray(Charsets.UTF_8), withSalt, N, R, P, KEYLEN)
        return bytesToHex(key)
    }

    private fun normalUser(name: String) =
        Normalizer.normalize(name, Normalizer.Form.NFKC).trim().lowercase(Locale.ROOT)

    private fun deriveSignKey(): ByteArray =
        hmac(secret, "ki-session|$userName|$userHash".toByteArray(Charsets.UTF_8))

    /* ---------------------------------------------------------------------
       pairing codes

       One live code at a time: asking for a code shows the one already in
       force, and asking for a new one replaces it. That keeps "which code is
       on my phone right now" answerable by looking at the phone.
    --------------------------------------------------------------------- */

    private fun livePairing(): Pairing? {
        val p = pairing
        if (p != null && p.expiresAt > System.currentTimeMillis()) return p
        pairing = null
        return null
    }

    fun newPairingCode(): PairingCode {
        // A uniform draw over the whole range: taking a modulo of random
        // bytes would make the low codes fractionally likelier, and there are
        // few enough of them already.
        val code = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000))
        val p = Pairing(code, System.currentTimeMillis() + PAIR_MS)
        pairing = p
        return PairingCode(p.code, p.expiresAt)
    }

    fun currentPairingCode(): PairingCode {
        val live = livePairing() ?: return newPairingCode()
        return PairingCode(live.code, live.expiresAt)
    }

    fun clearPairingCode() {
        pairing = null
    }

    /**
     * Consumes the code. Single use: the first device to get it right takes
     * it, and a wrong guess is spent too -- ten of those and the code is
     * abandoned, so a brute force cannot outlive the code it is attacking.
     */
    fun redeemPairingCode(input: String?): Boolean {
        val live = livePairing() ?: return false
        val digits = (input ?: "").filter { it.isDigit() }
        live.tries++
        if (live.tries > PAIR_MAX_TRIES) {
            pairing = null
            return false
        }
        if (digits.length != 6 || !safeEqual(digits, live.code)) return false
        pairing = null
        return true
    }

    /* ---------------------------------------------------------------------
       sessions
    --------------------------------------------------------------------- */

    private fun sign(payload: String): String {
        val mac = hmac(signKey, payload.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(mac, Base64.NO_PADDING or Base64.NO_WRAP or Base64.URL_SAFE)
    }

    private fun issueToken(): String {
        val exp = System.currentTimeMillis() + SESSION_MS
        val nonce = ByteArray(9).also { random.nextBytes(it) }
        val payload = java.lang.Long.toString(exp, 36) + "." + bytesToHex(nonce)
        return "$payload.${sign(payload)}"
    }

    private fun validToken(token: String?): Boolean {
        if (token == null || token.length > 300) return false
        val cut = token.lastIndexOf('.')
        if (cut < 1) return false
        val payload = token.substring(0, cut)
        if (!safeEqual(token.substring(cut + 1), sign(payload))) return false
        val dot = payload.indexOf('.')
        if (dot < 1) return false
        val exp = try {
            java.lang.Long.parseLong(payload.substring(0, dot), 36)
        } catch (e: NumberFormatException) {
            return false
        }
        return exp > System.currentTimeMillis()
    }

    fun hasSession(cookieHeader: String?): Boolean =
        validToken(readCookie(cookieHeader, COOKIE))

    // No `Secure`: this is plain HTTP on a LAN, and a Secure cookie would
    // simply never come back. HttpOnly still holds -- no script here reads
    // the session, so nothing injected into a page can read it either.
    fun sessionCookie(): String =
        "$COOKIE=${issueToken()}; Path=/; Max-Age=${SESSION_MS / 1000}; HttpOnly; SameSite=Lax"

    fun clearedCookie(): String =
        "$COOKIE=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax"

    /* ---------------------------------------------------------------------
       login throttle

       Guessing is what both credentials have to survive, and the six-digit
       code has only a million of itself to hide behind, so failures from one
       address slow down hard. Both doors share the counter: an attacker
       cannot get five fresh password tries by alternating with code tries.
    --------------------------------------------------------------------- */

    private class Attempt(var fails: Int = 0, var until: Long = 0, var last: Long = 0)

    private val attempts = HashMap<String, Attempt>()

    /** Returns 0 when the address may try, or the milliseconds left on its lockout. */
    fun lockedFor(ip: String): Long = synchronized(attempts) {
        val rec = attempts[ip] ?: return 0
        maxOf(0, rec.until - System.currentTimeMillis())
    }

    fun recordFailure(ip: String) = synchronized(attempts) {
        val now = System.currentTimeMillis()
        val rec = attempts.getOrPut(ip) { Attempt(last = now) }
        rec.fails++
        rec.last = now
        if (rec.fails > FREE_TRIES) {
            val shift = (rec.fails - FREE_TRIES - 1).coerceAtMost(20)
            val lock = minOf(BASE_LOCK_MS shl shift, MAX_LOCK_MS)
            rec.until = now + lock
        }
        prune(now)
    }

    fun recordSuccess(ip: String) = synchronized(attempts) {
        attempts.remove(ip)
        Unit
    }

    private fun prune(now: Long) {
        val it = attempts.entries.iterator()
        while (it.hasNext()) {
            val rec = it.next().value
            if (rec.until < now && now - rec.last > MAX_LOCK_MS) it.remove()
        }
        if (attempts.size <= MAX_TRACKED) return
        // Still too many: drop the coldest. Losing a fail count only ever
        // costs an attacker-shaped client its lockout, never a legitimate
        // client its access.
        val cold = attempts.entries.sortedBy { it.value.last }
        for (i in 0 until cold.size - MAX_TRACKED) attempts.remove(cold[i].key)
    }

    /* ---------------------------------------------------------------------
       storage
    --------------------------------------------------------------------- */

    private fun readConf(): JSONObject? {
        return try {
            if (!file.exists()) return null
            val raw = JSONObject(file.readText())
            if (raw.optInt("version") != CONFIG_VERSION) return null
            if (raw.optString("secret").isEmpty() || raw.optString("salt").isEmpty()) return null
            val u = raw.optJSONObject("user") ?: return null
            if (u.optString("name").isEmpty() || u.optString("hash").isEmpty()) return null
            raw
        } catch (e: Exception) {
            // Missing, corrupt, or an older shape -- start over.
            null
        }
    }

    private fun writeConf() {
        val stamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(java.util.Date())

        val json = JSONObject().apply {
            put("version", CONFIG_VERSION)
            put("secret", bytesToHex(secret))
            put("salt", bytesToHex(salt))
            put("user", JSONObject().apply {
                put("name", userName)
                put("hash", userHash)
            })
            put("updatedAt", stamp)
        }.toString(2)

        file.parentFile?.mkdirs()
        tmp.writeText(json)
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) tmp.copyTo(file, overwrite = true)
        }
    }

    /* ------------------------------------------------------------------ */

    private fun hmac(key: ByteArray, message: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(if (key.isEmpty()) ByteArray(32) else key, "HmacSHA256"))
        return mac.doFinal(message)
    }

    // Length is not hidden -- comparing buffers requires equal lengths -- but
    // the content is.
    private fun safeEqual(a: String, b: String): Boolean {
        val ab = a.toByteArray(Charsets.UTF_8)
        val bb = b.toByteArray(Charsets.UTF_8)
        if (ab.size != bb.size) return false
        return MessageDigest.isEqual(ab, bb)
    }

    // Pronounceable, because it gets read off one screen and typed on
    // another. Eight syllables from 75 each is ~50 bits, far past what a
    // rate-limited login can be walked through.
    private fun generatePassword(): String {
        val bytes = ByteArray(32).also { random.nextBytes(it) }
        val out = StringBuilder()
        for (i in 0 until 8) {
            if (i > 0 && i % 2 == 0) out.append('-')
            out.append(CONS[(bytes[i * 2].toInt() and 0xff) % CONS.length])
            out.append(VOWELS[(bytes[i * 2 + 1].toInt() and 0xff) % VOWELS.length])
        }
        return out.toString()
    }

    companion object {
        private const val CONFIG_VERSION = 2
        private const val COOKIE = "ki_session"
        private const val SESSION_MS = 365L * 24 * 60 * 60 * 1000

        const val PAIR_MS = 30L * 60 * 1000
        private const val PAIR_MAX_TRIES = 10

        // scrypt at these parameters costs ~16 MB and tens of milliseconds
        // per attempt -- deliberately, and matching server/auth.js so the two
        // hosts read the same file.
        private const val N = 16384
        private const val R = 8
        private const val P = 1
        private const val KEYLEN = 64

        private const val DEFAULT_USER = "kindle"

        private const val FREE_TRIES = 5
        private const val BASE_LOCK_MS = 30L * 1000
        private const val MAX_LOCK_MS = 15L * 60 * 1000
        private const val MAX_TRACKED = 500

        private const val CONS = "bdfgjklmnprstvz"
        private const val VOWELS = "aeiou"

        private val HEX = "0123456789abcdef".toCharArray()

        fun bytesToHex(bytes: ByteArray): String {
            val out = CharArray(bytes.size * 2)
            for (i in bytes.indices) {
                val v = bytes[i].toInt() and 0xff
                out[i * 2] = HEX[v ushr 4]
                out[i * 2 + 1] = HEX[v and 0x0f]
            }
            return String(out)
        }

        fun hexToBytes(hex: String): ByteArray {
            val out = ByteArray(hex.length / 2)
            for (i in out.indices) {
                out[i] = ((Character.digit(hex[i * 2], 16) shl 4) or
                    Character.digit(hex[i * 2 + 1], 16)).toByte()
            }
            return out
        }

        fun readCookie(header: String?, name: String): String? {
            val raw = header ?: return null
            for (part in raw.split(';')) {
                val t = part.trim()
                if (t.startsWith("$name=")) return t.substring(name.length + 1)
            }
            return null
        }
    }
}

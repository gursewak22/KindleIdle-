package com.kindleidle.host.core

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * scrypt (RFC 7914), because the JDK has no implementation of it and the
 * alternative -- switching this host to PBKDF2 -- would mean the Android app
 * and the Node server no longer read each other's `auth.json`.
 *
 * Parameters are the ones server/auth.js uses: N=16384, r=8, p=1, 64-byte
 * key. That costs about 16 MB and tens of milliseconds per attempt, which is
 * the point: it is what stands between a guessed password and the list.
 *
 * The 16 MB is allocated per call and released straight after, so sign-in
 * costs a large short-lived allocation rather than a permanent one. On a
 * phone that is fine; it is one attempt at a time behind the same throttle
 * the Node server uses.
 */
object Scrypt {

    /** SHA-256's HMAC block size, used only for the empty-key case below. */
    private const val HMAC_BLOCK = 64

    fun derive(
        password: ByteArray,
        salt: ByteArray,
        n: Int,
        r: Int,
        p: Int,
        dkLen: Int
    ): ByteArray {
        require(n > 1 && (n and (n - 1)) == 0) { "N must be a power of two above 1" }
        require(r >= 1 && p >= 1) { "r and p must be at least 1" }
        require(dkLen >= 1) { "dkLen must be at least 1" }
        // Guards the IntArray sizing below against overflow on absurd inputs.
        require(r.toLong() * p <= 1 shl 24) { "r * p is too large" }

        val blockWords = 32 * r
        val bBytes = pbkdf2(password, salt, 1, 128 * r * p)
        val b = IntArray(bBytes.size / 4)
        decodeLE(bBytes, b)

        // Scratch, allocated once and reused across the p iterations.
        val v = IntArray(blockWords * n)
        val y = IntArray(blockWords)
        val x = IntArray(16)

        for (i in 0 until p) roMix(b, i * blockWords, r, n, v, y, x)

        encodeLE(b, bBytes)
        return pbkdf2(password, bBytes, 1, dkLen)
    }

    /* ---------------------------------------------------------------------
       ROMix / BlockMix / Salsa20-8, straight from RFC 7914 sections 4-6
    --------------------------------------------------------------------- */

    private fun roMix(b: IntArray, off: Int, r: Int, n: Int, v: IntArray, y: IntArray, x: IntArray) {
        val words = 32 * r

        for (i in 0 until n) {
            System.arraycopy(b, off, v, i * words, words)
            blockMix(b, off, y, x, r)
        }

        val mask = n - 1
        for (i in 0 until n) {
            // Integerify: the first word of the last 64-byte block. Stored
            // little-endian already, so this is the value the spec asks for.
            val j = (b[off + (2 * r - 1) * 16] and mask)
            val vOff = j * words
            for (k in 0 until words) b[off + k] = b[off + k] xor v[vOff + k]
            blockMix(b, off, y, x, r)
        }
    }

    private fun blockMix(b: IntArray, off: Int, y: IntArray, x: IntArray, r: Int) {
        System.arraycopy(b, off + (2 * r - 1) * 16, x, 0, 16)
        for (i in 0 until 2 * r) {
            val src = off + i * 16
            for (k in 0 until 16) x[k] = x[k] xor b[src + k]
            salsa8(x)
            // Even blocks to the front half, odd blocks to the back half.
            val dst = if (i and 1 == 0) (i / 2) * 16 else (r + i / 2) * 16
            System.arraycopy(x, 0, y, dst, 16)
        }
        System.arraycopy(y, 0, b, off, 32 * r)
    }

    private fun rotl(v: Int, s: Int) = (v shl s) or (v ushr (32 - s))

    private fun salsa8(block: IntArray) {
        val x = block.copyOf()

        // Eight rounds is four column/row double rounds.
        repeat(4) {
            x[4] = x[4] xor rotl(x[0] + x[12], 7)
            x[8] = x[8] xor rotl(x[4] + x[0], 9)
            x[12] = x[12] xor rotl(x[8] + x[4], 13)
            x[0] = x[0] xor rotl(x[12] + x[8], 18)

            x[9] = x[9] xor rotl(x[5] + x[1], 7)
            x[13] = x[13] xor rotl(x[9] + x[5], 9)
            x[1] = x[1] xor rotl(x[13] + x[9], 13)
            x[5] = x[5] xor rotl(x[1] + x[13], 18)

            x[14] = x[14] xor rotl(x[10] + x[6], 7)
            x[2] = x[2] xor rotl(x[14] + x[10], 9)
            x[6] = x[6] xor rotl(x[2] + x[14], 13)
            x[10] = x[10] xor rotl(x[6] + x[2], 18)

            x[3] = x[3] xor rotl(x[15] + x[11], 7)
            x[7] = x[7] xor rotl(x[3] + x[15], 9)
            x[11] = x[11] xor rotl(x[7] + x[3], 13)
            x[15] = x[15] xor rotl(x[11] + x[7], 18)

            x[1] = x[1] xor rotl(x[0] + x[3], 7)
            x[2] = x[2] xor rotl(x[1] + x[0], 9)
            x[3] = x[3] xor rotl(x[2] + x[1], 13)
            x[0] = x[0] xor rotl(x[3] + x[2], 18)

            x[6] = x[6] xor rotl(x[5] + x[4], 7)
            x[7] = x[7] xor rotl(x[6] + x[5], 9)
            x[4] = x[4] xor rotl(x[7] + x[6], 13)
            x[5] = x[5] xor rotl(x[4] + x[7], 18)

            x[11] = x[11] xor rotl(x[10] + x[9], 7)
            x[8] = x[8] xor rotl(x[11] + x[10], 9)
            x[9] = x[9] xor rotl(x[8] + x[11], 13)
            x[10] = x[10] xor rotl(x[9] + x[8], 18)

            x[12] = x[12] xor rotl(x[15] + x[14], 7)
            x[13] = x[13] xor rotl(x[12] + x[15], 9)
            x[14] = x[14] xor rotl(x[13] + x[12], 13)
            x[15] = x[15] xor rotl(x[14] + x[13], 18)
        }

        for (i in 0 until 16) block[i] += x[i]
    }

    /* ---------------------------------------------------------------------
       PBKDF2-HMAC-SHA256

       Hand-rolled rather than SecretKeyFactory, which only learned
       PBKDF2WithHmacSHA256 at API 26 -- this app runs from API 24.
    --------------------------------------------------------------------- */

    private fun pbkdf2(password: ByteArray, salt: ByteArray, iterations: Int, dkLen: Int): ByteArray {
        // SecretKeySpec rejects a zero-length key, and RFC 7914's first test
        // vector uses an empty password. HMAC pads any key shorter than the
        // block size with zeros, so an all-zero block is the same key.
        val keyBytes = if (password.isEmpty()) ByteArray(HMAC_BLOCK) else password
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(keyBytes, "HmacSHA256"))

        val hLen = mac.macLength
        val blocks = (dkLen + hLen - 1) / hLen
        val out = ByteArray(dkLen)
        val u = ByteArray(hLen)
        val t = ByteArray(hLen)

        for (i in 1..blocks) {
            mac.update(salt)
            mac.update(byteArrayOf(
                (i ushr 24).toByte(), (i ushr 16).toByte(), (i ushr 8).toByte(), i.toByte()
            ))
            mac.doFinal(u, 0)
            System.arraycopy(u, 0, t, 0, hLen)

            for (round in 2..iterations) {
                mac.update(u)
                mac.doFinal(u, 0)
                for (k in 0 until hLen) t[k] = (t[k].toInt() xor u[k].toInt()).toByte()
            }

            val offset = (i - 1) * hLen
            System.arraycopy(t, 0, out, offset, minOf(hLen, dkLen - offset))
        }
        return out
    }

    /* ------------------------------------------------------------------ */

    private fun decodeLE(src: ByteArray, dst: IntArray) {
        for (i in dst.indices) {
            val o = i * 4
            dst[i] = (src[o].toInt() and 0xff) or
                ((src[o + 1].toInt() and 0xff) shl 8) or
                ((src[o + 2].toInt() and 0xff) shl 16) or
                ((src[o + 3].toInt() and 0xff) shl 24)
        }
    }

    private fun encodeLE(src: IntArray, dst: ByteArray) {
        for (i in src.indices) {
            val o = i * 4
            val v = src[i]
            dst[o] = v.toByte()
            dst[o + 1] = (v ushr 8).toByte()
            dst[o + 2] = (v ushr 16).toByte()
            dst[o + 3] = (v ushr 24).toByte()
        }
    }
}

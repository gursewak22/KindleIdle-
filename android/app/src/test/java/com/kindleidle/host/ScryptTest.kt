package com.kindleidle.host

import com.kindleidle.host.core.Scrypt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * scrypt is the one piece of this port where being subtly wrong would not
 * show up in use: a broken implementation still hashes consistently, so
 * sign-in would work while the stored hashes quietly stopped matching the
 * Node server's. These are the published vectors, which is the only way to
 * catch that.
 *
 * Vectors are from RFC 7914 section 11. If one of these fails, check the
 * expected constant against the RFC before suspecting Scrypt.kt.
 */
class ScryptTest {

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    private fun clean(s: String) = s.replace(Regex("\\s"), "")

    @Test
    fun `RFC 7914 vector 1 - empty password and salt`() {
        val out = Scrypt.derive(
            password = "".toByteArray(),
            salt = "".toByteArray(),
            n = 16, r = 1, p = 1, dkLen = 64
        )
        assertEquals(
            clean(
                """
                77d6576238657b203b19ca42c18a0497
                f16b4844e3074ae8dfdffa3fede21442
                fcd0069ded0948f8326a753a0fc81f17
                e8d3e0fb2e0d3628cf35e20c38d18906
                """
            ),
            hex(out)
        )
    }

    @Test
    fun `RFC 7914 vector 2 - password and NaCl`() {
        val out = Scrypt.derive(
            password = "password".toByteArray(),
            salt = "NaCl".toByteArray(),
            n = 1024, r = 8, p = 16, dkLen = 64
        )
        assertEquals(
            clean(
                """
                fdbabe1c9d3472007856e7190d01e9fe
                7c6ad7cbc8237830e77376634b373162
                2eaf30d92e22a3886ff109279d9830da
                c727afb94a83ee6d8360cbdfa2cc0640
                """
            ),
            hex(out)
        )
    }

    /**
     * The parameters server/auth.js actually uses, so this covers the exact
     * configuration the app runs in.
     */
    @Test
    fun `RFC 7914 vector 3 - the parameters this app uses`() {
        val out = Scrypt.derive(
            password = "pleaseletmein".toByteArray(),
            salt = "SodiumChloride".toByteArray(),
            n = 16384, r = 8, p = 1, dkLen = 64
        )
        assertEquals(
            clean(
                """
                7023bdcb3afd7348461c06cd81fd38eb
                fda8fbba904f8e3ea9b543f6545da1f2
                d5432955613f0fcf62d49705242a9af9
                e61e85dc0d651e40dfcf017b45575887
                """
            ),
            hex(out)
        )
    }

    /* ---------------------------------------------------------------------
       Properties that hold regardless of the published constants, so this
       file still says something useful even if a vector above is mistyped.
    --------------------------------------------------------------------- */

    @Test
    fun `same input gives the same key`() {
        val a = Scrypt.derive("hunter2".toByteArray(), "salt".toByteArray(), 256, 8, 1, 64)
        val b = Scrypt.derive("hunter2".toByteArray(), "salt".toByteArray(), 256, 8, 1, 64)
        assertEquals(hex(a), hex(b))
    }

    @Test
    fun `a different salt gives a different key`() {
        val a = Scrypt.derive("hunter2".toByteArray(), "salt-a".toByteArray(), 256, 8, 1, 64)
        val b = Scrypt.derive("hunter2".toByteArray(), "salt-b".toByteArray(), 256, 8, 1, 64)
        assertNotEquals(hex(a), hex(b))
    }

    @Test
    fun `a different password gives a different key`() {
        val a = Scrypt.derive("hunter2".toByteArray(), "salt".toByteArray(), 256, 8, 1, 64)
        val b = Scrypt.derive("hunter3".toByteArray(), "salt".toByteArray(), 256, 8, 1, 64)
        assertNotEquals(hex(a), hex(b))
    }

    @Test
    fun `key length is honoured`() {
        assertEquals(32, Scrypt.derive("x".toByteArray(), "y".toByteArray(), 16, 1, 1, 32).size)
        assertEquals(64, Scrypt.derive("x".toByteArray(), "y".toByteArray(), 16, 1, 1, 64).size)
        assertEquals(100, Scrypt.derive("x".toByteArray(), "y".toByteArray(), 16, 1, 1, 100).size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `N must be a power of two`() {
        Scrypt.derive("x".toByteArray(), "y".toByteArray(), 15, 1, 1, 64)
    }
}

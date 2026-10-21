package io.openflux.desktop

import io.openflux.desktop.model.Sha256
import io.openflux.desktop.model.sha256Hex
import kotlin.test.Test
import kotlin.test.assertEquals

/** The plain-Kotlin SHA-256 iOS uses gives what MessageDigest gives, at every padding edge. */
class Sha256Test {
    @Test
    fun knownVectors() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Sha256.hex(ByteArray(0)))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Sha256.hex("abc".encodeToByteArray()))
    }

    @Test
    fun sameAsMessageDigest() {
        for (n in 0..200) {
            val text = buildString { repeat(n) { append("яa1"[it % 3]) } }
            assertEquals(sha256Hex(text), Sha256.hex(text.encodeToByteArray()), "length $n")
        }
    }
}

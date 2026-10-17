package io.openflux.desktop

import io.openflux.desktop.data.readUpTo
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class StreamLimitsTest {
    @Test
    fun readsEverythingBelowTheLimit() {
        val data = ByteArray(20_000) { it.toByte() }
        assertContentEquals(data, ByteArrayInputStream(data).readUpTo(30_000))
    }

    @Test
    fun stopsAtTheLimit() {
        val data = ByteArray(20_000) { it.toByte() }
        assertContentEquals(data.copyOf(9_000), ByteArrayInputStream(data).readUpTo(9_000))
    }

    @Test
    fun anEmptyStreamIsEmpty() {
        assertEquals(0, ByteArrayInputStream(ByteArray(0)).readUpTo(100).size)
    }
}

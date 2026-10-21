package io.openflux.desktop.data

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Reads at most [limit] bytes (fewer at the end of the stream). The same as Java 11's `InputStream.readNBytes(int)`,
 * which Android only has from API 33: calling it on Android 12 ended the app with NoSuchMethodError.
 */
fun InputStream.readUpTo(limit: Int): ByteArray {
    val out = ByteArrayOutputStream(minOf(limit, BUFFER))
    val buffer = ByteArray(BUFFER)
    var left = limit
    while (left > 0) {
        val n = read(buffer, 0, minOf(buffer.size, left))
        if (n < 0) break
        out.write(buffer, 0, n)
        left -= n
    }
    return out.toByteArray()
}

private const val BUFFER = 8192

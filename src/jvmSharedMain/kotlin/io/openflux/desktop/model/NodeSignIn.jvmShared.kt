package io.openflux.desktop.model

import java.security.MessageDigest

// MessageDigest and String.toByteArray are on every Android version the app supports.
actual fun sha256Hex(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { b -> ((b.toInt() and 0xff) + 0x100).toString(16).substring(1) }

package io.openflux.desktop.model

actual fun sha256Hex(text: String): String = Sha256.hex(text.encodeToByteArray())

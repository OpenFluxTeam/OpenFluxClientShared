package io.openflux.app.cosmic

actual fun cosmicSceneDefault(): Boolean = System.getProperty("openflux.cosmicScene") != "off"

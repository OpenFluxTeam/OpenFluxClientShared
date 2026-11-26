package io.openflux.app.shell

// The desktop and Android apps carry everything; PlatformServices decides at run time.
actual val buildExtras: BuildExtras? = FullBuildExtras

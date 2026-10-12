package io.openflux.app.shell

import androidx.navigation3.runtime.NavEntry
import io.openflux.app.nav.AppNavigator
import io.openflux.app.nav.Dest
import io.openflux.app.nav.ListDetail
import io.openflux.app.nav.Section
import io.openflux.app.nav.destEntry
import io.openflux.app.servers.AddServerScreen
import io.openflux.app.servers.NewNodeScreen
import io.openflux.app.servers.NodeScreen
import io.openflux.app.servers.ServerScreen
import io.openflux.app.servers.ServersRoot

/** The one place that says which screen shows which destination, and which are lists and details. */
fun appEntry(dest: Dest, navigator: AppNavigator): NavEntry<Dest> = when (dest) {
    Dest.Servers -> destEntry(dest, ListDetail.list(Section.Servers)) { ServersRoot(selected = navigator.stack(Section.Servers).last()) }
    Dest.AddServer -> destEntry(dest) { AddServerScreen() }
    is Dest.Server -> destEntry(dest, ListDetail.detail(Section.Servers)) { ServerScreen(dest) }
    is Dest.Node -> destEntry(dest, ListDetail.detail(Section.Servers)) { NodeScreen(dest) }
    is Dest.NewNode -> destEntry(dest) { NewNodeScreen(dest) }
    else -> placeholderEntry(dest, navigator)
}

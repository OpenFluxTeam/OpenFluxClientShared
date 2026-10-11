package io.openflux.desktop.ui.connections

import androidx.compose.runtime.Composable
import cafe.adriel.voyager.navigator.tab.Tab
import cafe.adriel.voyager.navigator.tab.TabOptions
import io.openflux.desktop.ui.components.AppIcons
import io.openflux.desktop.ui.nav.PlaceHost
import io.openflux.desktop.ui.nav.Section
import org.jetbrains.compose.resources.painterResource

/** "Подключения": everything one connects with: the OpenFlux account, keys, links and the profiles they make. */
object ConnectionsTab : Tab {
    override val options: TabOptions
        @Composable get() = TabOptions(index = 1u, title = Section.Connections.title, icon = painterResource(AppIcons.Public))

    @Composable
    override fun Content() = PlaceHost(Section.Connections)
}

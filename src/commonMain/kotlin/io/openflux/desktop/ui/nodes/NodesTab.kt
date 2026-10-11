package io.openflux.desktop.ui.nodes

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import cafe.adriel.voyager.navigator.tab.Tab
import cafe.adriel.voyager.navigator.tab.TabOptions
import io.openflux.desktop.ui.nav.PlaceHost
import io.openflux.desktop.ui.nav.Section

/** "Ноды": what one runs: nodes on one's own servers or hosting, made here, changed here, given to others. */
object NodesTab : Tab {
    override val options: TabOptions
        @Composable get() = TabOptions(index = 2u, title = Section.Nodes.title, icon = rememberVectorPainter(Icons.Rounded.Dns))

    @Composable
    override fun Content() = PlaceHost(Section.Nodes)
}

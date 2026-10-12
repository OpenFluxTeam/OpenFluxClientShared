package io.openflux.app.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.openflux.app.theme.AppTheme2
import io.openflux.desktop.ui.LocalScrollbars

/**
 * The frame of a screen: the back arrow, the title and the actions, then the page (scrolling when [scroll]),
 * and [bottomBar] pinned under it (the "Изменено · Применить" bar of an editor).
 */
@Composable
fun ScreenScaffold(
    title: String?,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: (@Composable () -> Unit)? = null,
    scroll: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val sp = AppTheme2.spacing
    Column(modifier.fillMaxSize()) {
        if (title != null || onBack != null) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = if (onBack != null) sp.xs else sp.page, end = sp.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onBack != null) {
                    IconButton2(Icons.AutoMirrored.Rounded.ArrowBack, "Назад", onBack)
                    Spacer(Modifier.width(sp.xs))
                }
                Text(
                    title.orEmpty(),
                    modifier = Modifier.weight(1f),
                    style = AppTheme2.type.screenTitle,
                    color = AppTheme2.colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(sp.xs), verticalAlignment = Alignment.CenterVertically, content = actions)
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (scroll) {
                val state = rememberScrollState()
                Column(
                    Modifier.fillMaxSize().verticalScroll(state).padding(horizontal = sp.page, vertical = sp.s),
                    verticalArrangement = Arrangement.spacedBy(sp.l),
                    content = content,
                )
                LocalScrollbars.current.Vertical(state, Modifier.align(Alignment.CenterEnd))
            } else {
                Column(Modifier.fillMaxSize().padding(horizontal = sp.page), content = content)
            }
        }
        if (bottomBar != null) bottomBar()
    }
}

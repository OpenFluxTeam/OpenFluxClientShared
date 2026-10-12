package io.openflux.app.shell

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.NavEntry
import io.openflux.app.components.ScreenScaffold
import io.openflux.app.nav.AppNavigator
import io.openflux.app.nav.Dest
import io.openflux.app.nav.destEntry
import io.openflux.app.theme.AppTheme2

/** A screen's title until its real screen exists. */
private fun titleOf(dest: Dest): String = when (dest) {
    is Dest.Profile -> "Профиль"
    is Dest.Server -> dest.id
    is Dest.Node -> "Нода ${dest.channel}"
    is Dest.Give -> "Поделиться"
    is Dest.SettingsPage -> dest.page
    else -> dest.owner.title
}

/** Stands for a screen the next parts of the rewrite build (spec 9). */
@Composable
fun Placeholder(dest: Dest, onBack: (() -> Unit)?) {
    ScreenScaffold(titleOf(dest), onBack = onBack) {
        Text("Экран появится в следующей части", style = AppTheme2.type.body, color = AppTheme2.colors.textSecondary)
    }
}

fun placeholderEntry(dest: Dest, navigator: AppNavigator): NavEntry<Dest> =
    destEntry(dest) { Placeholder(dest, onBack = if (dest.isRoot) null else ({ navigator.back() })) }

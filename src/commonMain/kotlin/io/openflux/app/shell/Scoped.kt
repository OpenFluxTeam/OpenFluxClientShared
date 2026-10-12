package io.openflux.app.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.openflux.app.servers.ServerLink
import kotlinx.coroutines.CoroutineScope

/** The app's one SSH link to the person's servers. */
val LocalServerLink = staticCompositionLocalOf<ServerLink> { error("ServerLink is not provided") }

/** A screen's model that lives as long as the screen's entry in its stack; [close] when it leaves. */
class ScopedModel<T : Any>(make: (CoroutineScope) -> T) : ViewModel() {
    val value: T = make(viewModelScope)

    override fun onCleared() {
        (value as? AutoCloseable)?.close()
    }
}

/** The model of the screen being shown, made once per entry (Navigation 3 scopes the store to the entry). */
@Composable
inline fun <reified T : Any> rememberScreenModel(noinline make: (CoroutineScope) -> T): T =
    viewModel<ScopedModel<T>>(initializer = { ScopedModel(make) }).value

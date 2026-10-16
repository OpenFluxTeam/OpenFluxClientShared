package io.openflux.app.shell

import androidx.compose.foundation.background
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.material3.ripple
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import io.openflux.app.home.statusLine
import io.openflux.app.theme.Motion
import io.openflux.app.theme.accentBrush
import io.openflux.app.theme.appBackground
import io.openflux.app.theme.glow
import io.openflux.app.theme.pressScale
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.openflux.app.components.Status
import io.openflux.app.components.StatusDot
import io.openflux.app.nav.AppNavigator
import io.openflux.app.nav.Section
import io.openflux.app.theme.AppTheme
import io.openflux.desktop.model.ConnectionState
import io.openflux.desktop.model.isActive
import io.openflux.desktop.model.profile
import io.openflux.desktop.service.LocalAppContainer
import io.openflux.app.platform.LocalShortcuts
import io.openflux.app.platform.LocalTouchUi

/** The window's width class: a phone gets a bottom bar; below [Medium] the side column shows icons only. */
enum class WidthClass { Phone, Compact, Medium, Expanded }

fun widthClassOf(width: Dp): WidthClass = when {
    width < 600.dp -> WidthClass.Phone
    width < 1000.dp -> WidthClass.Compact
    width < 1440.dp -> WidthClass.Medium
    else -> WidthClass.Expanded
}

/** A list beside its detail needs this much room (spec 4.3). */
private val WideContent = 900.dp
private val RailCompact = 72.dp
private val RailFull = 232.dp

private val Section.icon: ImageVector
    get() = when (this) {
        Section.Home -> Icons.Rounded.Home
        Section.Servers -> Icons.Rounded.Dns
        Section.Share -> Icons.Rounded.Share
        Section.Settings -> Icons.Rounded.Settings
    }

private val Section.shortcut: String
    get() = "Ctrl+" + (Section.entries.indexOf(this) + 1)

/**
 * The frame around the sections (spec 4.1): a bottom bar with ＋ in the middle on a phone, a side column with ＋
 * on top in a wider window; Ctrl+1…4, Ctrl+N and Ctrl+Enter on a keyboard. [content] gets whether there is room
 * for a list beside its detail.
 */
@Composable
fun Shell(navigator: AppNavigator, onAdd: () -> Unit, content: @Composable (wide: Boolean) -> Unit) {
    val touch = LocalTouchUi.current
    ShellShortcuts(navigator, onAdd, enabled = !touch)
    BoxWithConstraints(Modifier.fillMaxSize().appBackground().windowInsetsPadding(WindowInsets.safeDrawing)) {
        val window = maxWidth
        val widthClass = widthClassOf(window)
        if (widthClass == WidthClass.Phone) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth()) { content(false) }
                // The keyboard takes the room: the bar would only float above it.
                if (WindowInsets.ime.getBottom(LocalDensity.current) == 0) BottomBar(navigator, onAdd)
            }
        } else {
            val rail = if (widthClass == WidthClass.Compact) RailCompact else RailFull
            Row(Modifier.fillMaxSize()) {
                SideRail(navigator, onAdd, labels = widthClass != WidthClass.Compact, modifier = Modifier.width(rail).fillMaxHeight())
                Box(Modifier.width(1.dp).fillMaxHeight().background(AppTheme.colors.border))
                Box(Modifier.weight(1f).fillMaxHeight()) { content(window - rail >= WideContent) }
            }
        }
    }
}

@Composable
private fun ShellShortcuts(navigator: AppNavigator, onAdd: () -> Unit, enabled: Boolean) {
    val shortcuts = LocalShortcuts.current
    val container = LocalAppContainer.current
    DisposableEffect(navigator, enabled) {
        if (!enabled) return@DisposableEffect onDispose { }
        val unregister = shortcuts.register { event ->
            if (event.type != KeyEventType.KeyDown || !event.isCtrlPressed) return@register false
            val place = when (event.key) {
                Key.One -> Section.Home
                Key.Two -> Section.Servers
                Key.Three -> Section.Share
                Key.Four -> Section.Settings
                else -> null
            }
            when {
                place != null -> { navigator.select(place); true }
                event.key == Key.N -> { onAdd(); true }
                event.key == Key.Enter -> { toggleConnection(container); true }
                else -> false
            }
        }
        onDispose { unregister() }
    }
}

/** Ctrl+Enter, the tray, the quick-settings tile: disconnect, or connect the chosen profile (else the first). */
fun toggleConnection(container: io.openflux.desktop.service.AppContainer) {
    if (container.connection.state.value.isActive) stopConnection(container) else connectChosen(container)
}

/** Connects the chosen profile (else the first) as a client: Ctrl+Enter, the tray, the tile, "Подключаться при запуске". */
fun connectChosen(container: io.openflux.desktop.service.AppContainer) {
    val list = container.profiles.profiles.value
    val id = container.settings.settings.value.selectedProfileId
    val profile = list.firstOrNull { it.id == id } ?: list.firstOrNull() ?: return
    connectAsClient(container, profile)
}

/** Connects this device through [profile] and remembers it as the chosen one. The exit node starts only from «Это устройство». */
fun connectAsClient(container: io.openflux.desktop.service.AppContainer, profile: io.openflux.desktop.model.Profile) {
    container.settings.update { it.copy(selectedProfileId = profile.id, mode = io.openflux.desktop.model.ConnectionMode.Client) }
    container.connection.connect(profile, io.openflux.desktop.model.ConnectionMode.Client)
}

/** Disconnects. An exit node stopped this way leaves the device a client: only «Это устройство» starts the node. */
fun stopConnection(container: io.openflux.desktop.service.AppContainer) {
    container.connection.disconnect()
    container.settings.update { if (it.mode == io.openflux.desktop.model.ConnectionMode.Exit) it.copy(mode = io.openflux.desktop.model.ConnectionMode.Client) else it }
}

@Composable
private fun connectionStatus(): Status? {
    val state by LocalAppContainer.current.connection.state.collectAsState()
    return when (state) {
        is ConnectionState.Idle -> null
        is ConnectionState.Connected -> Status.On
        is ConnectionState.Failed -> Status.Error
        else -> Status.Busy
    }
}

@Composable
private fun BottomBar(navigator: AppNavigator, onAdd: () -> Unit) {
    val c = AppTheme.colors
    val status = connectionStatus()
    BoxWithConstraints(
        Modifier.fillMaxWidth().background(c.sidebar)
            .drawBehind { drawLine(c.border, Offset.Zero, Offset(size.width, 0f), 1.dp.toPx()) },
    ) {
        // Too narrow for every label at this text size: only the chosen place keeps its label.
        val allLabels = maxWidth / 5 >= 76.dp * LocalDensity.current.fontScale
        Row(Modifier.fillMaxWidth().padding(vertical = AppTheme.spacing.xs), verticalAlignment = Alignment.CenterVertically) {
            val (left, right) = Section.entries.chunked(2).let { it[0] to it[1] }
            for (s in left) BarItem(s, navigator, if (s == Section.Home) status else null, allLabels, Modifier.weight(1f))
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { AddButton(onAdd) }
            for (s in right) BarItem(s, navigator, null, allLabels, Modifier.weight(1f))
        }
    }
}

@Composable
private fun BarItem(section: Section, navigator: AppNavigator, status: Status?, label: Boolean, modifier: Modifier) {
    val c = AppTheme.colors
    val selected = navigator.section == section
    // The chosen place: a pill of the accent's wash grows behind its icon (2.x), the icon and label light up.
    val pill by animateFloatAsState(if (selected) 1f else 0f, Motion.bouncy())
    val color by animateColorAsState(if (selected) c.accentText else c.textSecondary)
    val labelColor by animateColorAsState(if (selected) c.text else c.textSecondary)
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier.heightIn(min = 56.dp).clip(RoundedCornerShape(AppTheme.shapes.medium))
            .clickable(interaction, indication = null, role = Role.Tab) { navigator.select(section) }
            .semantics { this.selected = selected; contentDescription = section.title }
            .padding(horizontal = 2.dp, vertical = AppTheme.spacing.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.width(56.dp).height(30.dp).pressScale(interaction, pressed = 0.9f)
                .drawBehind {
                    if (pill > 0f) {
                        val w = size.width * (0.5f + 0.5f * pill)
                        drawRoundRect(
                            c.accentSoft.copy(alpha = (c.accentSoft.alpha * 1.4f * pill).coerceAtMost(1f)),
                            topLeft = Offset((size.width - w) / 2, 0f),
                            size = Size(w, size.height),
                            cornerRadius = CornerRadius(size.height / 2),
                        )
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Box {
                Icon(section.icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
                if (status != null) StatusDot(status, Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-2).dp))
            }
        }
        if (label || selected) {
            Spacer(Modifier.height(3.dp))
            // The chosen place's label alone may still be long on a tiny screen: it shrinks rather than gets cut.
            BasicText(
                section.title,
                style = AppTheme.type.caption.copy(color = labelColor, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
                autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = AppTheme.type.caption.fontSize, stepSize = 0.5.sp),
            )
        }
    }
}

/** ＋ in a circle of the accent's gradient, glowing a little. */
@Composable
private fun AddButton(onAdd: () -> Unit, modifier: Modifier = Modifier) {
    val c = AppTheme.colors
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier.size(48.dp).pressScale(interaction, pressed = 0.9f, hovered = 1.06f)
            .shadow(10.dp, CircleShape, ambientColor = c.glow, spotColor = c.glow)
            .clip(CircleShape).background(c.accentBrush)
            .clickable(interaction, ripple(color = c.onAccent), role = Role.Button, onClick = onAdd)
            .semantics { contentDescription = "Добавить" },
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.Rounded.Add, contentDescription = null, tint = c.onAccent) }
}

private val RailItemHeight = 44.dp
private val RailItemGap = 4.dp

@Composable
private fun SideRail(navigator: AppNavigator, onAdd: () -> Unit, labels: Boolean, modifier: Modifier) {
    val c = AppTheme.colors
    val sp = AppTheme.spacing
    val status = connectionStatus()
    Column(
        modifier.background(c.sidebar).padding(vertical = sp.l, horizontal = sp.s),
        horizontalAlignment = if (labels) Alignment.Start else Alignment.CenterHorizontally,
    ) {
        RailBrand(labels)
        Spacer(Modifier.height(sp.l))
        Hint("Добавить · Ctrl+N", enabled = !labels) {
            if (labels) {
                // ＋ is a tonal button here: the filled accent belongs to the chosen place below it.
                val interaction = remember { MutableInteractionSource() }
                val hovered by interaction.collectIsHoveredAsState()
                val fill by animateColorAsState(if (hovered) c.accent.copy(alpha = 0.26f) else c.accentSoft)
                val shape = RoundedCornerShape(AppTheme.shapes.medium)
                Row(
                    Modifier.fillMaxWidth().heightIn(min = RailItemHeight).pressScale(interaction).clip(shape).background(fill)
                        .border(1.dp, c.accent.copy(alpha = 0.45f), shape)
                        .clickable(interaction, ripple(color = c.accent), role = Role.Button, onClick = onAdd)
                        .semantics { contentDescription = "Добавить" }.padding(horizontal = sp.m),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null, tint = c.accentText)
                    Spacer(Modifier.width(sp.m))
                    Text("Добавить", style = AppTheme.type.title, color = c.accentText, maxLines = 1)
                }
            } else {
                AddButton(onAdd)
            }
        }
        Spacer(Modifier.height(sp.l))
        val index = Section.entries.indexOf(navigator.section).coerceAtLeast(0)
        // One filled pill slides to the chosen place (2.x), glowing under it.
        val y by animateDpAsState((RailItemHeight + RailItemGap) * index, Motion.bouncy())
        Box(if (labels) Modifier.fillMaxWidth() else Modifier.width(RailItemHeight + 4.dp)) {
            val shape = RoundedCornerShape(AppTheme.shapes.medium)
            Box(
                Modifier.offset(y = y).fillMaxWidth().height(RailItemHeight)
                    .shadow(12.dp, shape, ambientColor = c.glow, spotColor = c.glow)
                    .background(c.accentBrush, shape),
            )
            Column(verticalArrangement = Arrangement.spacedBy(RailItemGap)) {
                for (s in Section.entries) RailItem(s, navigator, labels, if (s == Section.Home) status else null)
            }
        }
        Spacer(Modifier.weight(1f))
        StatusCard(labels, onClick = { navigator.select(Section.Home) })
    }
}

@Composable
private fun RailItem(s: Section, navigator: AppNavigator, labels: Boolean, status: Status?) {
    val c = AppTheme.colors
    val sp = AppTheme.spacing
    val selected = navigator.section == s
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val wash by animateColorAsState(if (hovered && !selected) c.surfaceHigh else c.surfaceHigh.copy(alpha = 0f))
    val tint by animateColorAsState(if (selected) c.onAccent else c.textSecondary)
    val textColor by animateColorAsState(if (selected) c.onAccent else c.text)
    Hint("${s.title} · ${s.shortcut}", enabled = true) {
        Row(
            Modifier.fillMaxWidth().height(RailItemHeight).pressScale(interaction, pressed = 0.97f)
                .clip(RoundedCornerShape(AppTheme.shapes.medium)).background(wash)
                .clickable(interaction, indication = null, role = Role.Tab) { navigator.select(s) }
                .semantics { this.selected = selected; if (!labels) contentDescription = s.title }
                .padding(horizontal = if (labels) sp.m else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (labels) Arrangement.Start else Arrangement.Center,
        ) {
            Box {
                Icon(s.icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
                if (status != null) StatusDot(status, Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-2).dp))
            }
            if (labels) {
                Spacer(Modifier.width(sp.m))
                Text(s.title, Modifier.weight(1f), style = AppTheme.type.body.copy(fontWeight = FontWeight.SemiBold), color = textColor, maxLines = 1)
                AnimatedVisibility(hovered && !selected, enter = fadeIn(), exit = fadeOut()) {
                    Text(s.shortcut, style = AppTheme.type.caption, color = c.textMuted, maxLines = 1)
                }
            }
        }
    }
}

/** The logo, with the name and what it is beside a wide column (2.x). */
@Composable
private fun RailBrand(labels: Boolean) {
    if (labels) io.openflux.app.components.Brand(Modifier.padding(horizontal = AppTheme.spacing.xs)) else io.openflux.app.components.BrandTile()
}

/** The connection at the foot of the column (2.x): its state and what it goes through; a click opens Главная. */
@Composable
private fun StatusCard(labels: Boolean, onClick: () -> Unit) {
    val container = LocalAppContainer.current
    val c = AppTheme.colors
    val state by container.connection.state.collectAsState()
    val profiles by container.profiles.profiles.collectAsState()
    val settings by container.settings.settings.collectAsState()
    val name = state.profile?.name ?: (profiles.firstOrNull { it.id == settings.selectedProfileId } ?: profiles.firstOrNull())?.name
    val status = connectionStatus() ?: Status.Off
    val title = statusLine(state)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val fill by animateColorAsState(if (hovered) c.surfaceHigh else c.surface)
    val edge by animateColorAsState(
        when (status) {
            Status.On -> c.success.copy(alpha = 0.45f)
            Status.Error -> c.danger.copy(alpha = 0.45f)
            Status.Busy -> c.warning.copy(alpha = 0.4f)
            else -> c.border
        },
    )
    val shape = RoundedCornerShape(AppTheme.shapes.medium)
    Hint(title, enabled = !labels) {
        Row(
            Modifier.then(if (labels) Modifier.fillMaxWidth() else Modifier.size(RailItemHeight + 4.dp)).pressScale(interaction, pressed = 0.97f)
                .clip(shape).background(fill).border(1.dp, edge, shape)
                .clickable(interaction, ripple(color = c.accent), onClick = onClick)
                // One description for a screen reader; its texts would otherwise repeat Home's in every search.
                .clearAndSetSemantics { contentDescription = listOfNotNull(title, name).joinToString(" · "); role = Role.Button }
                .padding(if (labels) AppTheme.spacing.m else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (labels) Arrangement.Start else Arrangement.Center,
        ) {
            StatusDot(status)
            if (labels) {
                Spacer(Modifier.width(AppTheme.spacing.m))
                Column(Modifier.weight(1f)) {
                    AnimatedContent(title, transitionSpec = { (fadeIn() + slideInVertically { it / 2 }) togetherWith (fadeOut() + slideOutVertically { -it / 2 }) }) {
                        Text(it, style = AppTheme.type.body.copy(fontWeight = FontWeight.SemiBold), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(name ?: "Нет подключений", style = AppTheme.type.caption, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** A tooltip on a pointer screen; nothing on touch. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Hint(text: String, enabled: Boolean, content: @Composable () -> Unit) {
    if (!enabled || LocalTouchUi.current) {
        content()
        return
    }
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Right),
        tooltip = { PlainTooltip { Text(text) } },
        state = rememberTooltipState(),
    ) { content() }
}

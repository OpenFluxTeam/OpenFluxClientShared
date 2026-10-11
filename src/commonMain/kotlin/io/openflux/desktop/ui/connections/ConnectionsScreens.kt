package io.openflux.desktop.ui.connections

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FileCopy
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.openflux.desktop.model.ConnectionState
import io.openflux.desktop.model.Profile
import io.openflux.desktop.model.SubscriptionState
import io.openflux.desktop.model.ValueKind
import io.openflux.desktop.model.editableNode
import io.openflux.desktop.model.isActive
import io.openflux.desktop.model.managed
import io.openflux.desktop.model.profile
import io.openflux.desktop.service.LocalAppContainer
import io.openflux.desktop.ui.LocalScrollbars
import io.openflux.desktop.ui.LocalTouchUi
import io.openflux.desktop.ui.components.AppButton
import io.openflux.desktop.ui.components.AppCard
import io.openflux.desktop.ui.components.AppIconButton
import io.openflux.desktop.ui.components.AppIcons
import io.openflux.desktop.ui.components.AppMenu
import io.openflux.desktop.ui.components.AppTextField
import io.openflux.desktop.ui.components.Banner
import io.openflux.desktop.ui.components.ButtonRow
import io.openflux.desktop.ui.components.ButtonStyle
import io.openflux.desktop.ui.components.ContextMenuArea
import io.openflux.desktop.ui.components.HorizontalRule
import io.openflux.desktop.ui.components.IconBubble
import io.openflux.desktop.ui.components.KeyValueRow
import io.openflux.desktop.ui.components.LocalToaster
import io.openflux.desktop.ui.components.MenuAction
import io.openflux.desktop.ui.components.PageHeader
import io.openflux.desktop.ui.components.SectionLabel
import io.openflux.desktop.ui.components.StatusBadge
import io.openflux.desktop.ui.components.Tone
import io.openflux.desktop.ui.look
import io.openflux.desktop.ui.nav.AppActions
import io.openflux.desktop.ui.nav.DetailScreen
import io.openflux.desktop.ui.nav.Dest
import io.openflux.desktop.ui.nav.LocalActions
import io.openflux.desktop.ui.nav.LocalModels
import io.openflux.desktop.ui.nav.NodeKind
import io.openflux.desktop.ui.nodes.ownNode
import io.openflux.desktop.ui.profiles.ProfileEditor
import io.openflux.desktop.ui.profiles.ProfilesScreenModel
import io.openflux.desktop.ui.subscription.AddConnectionActions
import io.openflux.desktop.ui.subscription.AddConnectionPage
import io.openflux.desktop.ui.subscription.AuthStep
import io.openflux.desktop.ui.subscription.SubscriptionStrip
import io.openflux.desktop.ui.theme.AppTheme

/**
 * "Подключения": everything one connects *with*, in one list: the OpenFlux subscription first, then the nodes the
 * person runs, then the rest. [selectedId] marks the profile whose screen is open beside the list; [split] is true
 * when that screen is shown next to the list (a wide window), so choosing another profile replaces it.
 */
@Composable
fun ConnectionsList(selectedId: String?, split: Boolean, modifier: Modifier = Modifier) {
    val models = LocalModels.current
    val actions = LocalActions.current
    val model = models.profiles
    val container = LocalAppContainer.current
    val profiles by model.profiles.profiles.collectAsState()
    val state by model.connection.state.collectAsState()
    val subscription by container.subscription.state.collectAsState()
    val touch = LocalTouchUi.current
    val filtered = model.filter(profiles)
    val listState = rememberLazyListState()
    val scrollbars = LocalScrollbars.current
    val (subscribed, rest) = filtered.partition { it.managed }
    val (nodes, others) = rest.partition { it.ownNode }

    Column(modifier.padding(top = AppTheme.spacing.xl)) {
        PageHeader("Подключения", "${profiles.size} ${plural(profiles.size)}", Modifier.padding(horizontal = AppTheme.spacing.xl)) {
            AppIconButton("Импорт ссылки или QR", { model.importOpen = true }, resource = AppIcons.QrScan)
            AppButton("Добавить", actions::addConnection, leading = Icons.Rounded.Add)
        }
        Spacer(Modifier.height(AppTheme.spacing.m))
        if (profiles.size > 4) {
            AppTextField(
                value = model.query,
                onValueChange = { model.query = it },
                placeholder = "Поиск по названию или адресу",
                modifier = Modifier.padding(horizontal = AppTheme.spacing.xl),
                trailing = {
                    Icon(Icons.Rounded.Search, null, tint = AppTheme.colors.textHint, modifier = Modifier.padding(end = AppTheme.spacing.s).size(18.dp))
                },
            )
            Spacer(Modifier.height(AppTheme.spacing.m))
        }
        Box(Modifier.weight(1f)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(horizontal = AppTheme.spacing.m),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                item(key = "subscription") {
                    SubscriptionEntry(subscription, Modifier.padding(horizontal = AppTheme.spacing.xs, vertical = AppTheme.spacing.xs))
                }
                if (profiles.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            "Подключений пока нет. Войдите в аккаунт, вставьте ключ или ссылку, либо поставьте свою ноду.",
                            style = AppTheme.typography.body, color = AppTheme.colors.textSecondary,
                            modifier = Modifier.padding(AppTheme.spacing.m),
                        )
                    }
                } else if (filtered.isEmpty()) {
                    item(key = "none") {
                        Text("Ничего не найдено", style = AppTheme.typography.body, color = AppTheme.colors.textSecondary, modifier = Modifier.padding(AppTheme.spacing.m))
                    }
                }
                connectionGroup("Локации подписки", subscribed, selectedId, state, split, touch, model, actions)
                connectionGroup("Мои ноды", nodes, selectedId, state, split, touch, model, actions)
                connectionGroup(if (subscribed.isEmpty() && nodes.isEmpty()) null else "Остальные", others, selectedId, state, split, touch, model, actions)
                item { Spacer(Modifier.height(AppTheme.spacing.l)) }
            }
            scrollbars.Vertical(listState, Modifier.align(Alignment.CenterEnd))
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.connectionGroup(
    title: String?,
    items: List<Profile>,
    selectedId: String?,
    state: ConnectionState,
    split: Boolean,
    touch: Boolean,
    model: ProfilesScreenModel,
    actions: AppActions,
) {
    if (items.isEmpty()) return
    if (title != null) {
        item(key = "group-$title") {
            Text(
                title.uppercase(), style = AppTheme.typography.label, color = AppTheme.colors.textSecondary,
                modifier = Modifier.padding(start = AppTheme.spacing.m, top = AppTheme.spacing.m, bottom = AppTheme.spacing.xs),
            )
        }
    }
    items(items, key = { it.id }) { profile ->
        val running = state.isActive && state.profile?.id == profile.id
        ProfileRow(
            profile = profile,
            selected = profile.id == selectedId,
            runningLook = if (running) state.look() else null,
            onClick = { actions.openProfile(profile.id, replace = split) },
            onDoubleClick = { model.connect(profile) },
            menu = { profileActions(model, actions, profile, running) },
            onToggle = { if (running) model.connection.disconnect() else model.connect(profile) },
            onEdit = { actions.editProfile(profile) },
            touch = touch,
        )
    }
}

/** The OpenFlux account at the top of the list: its state, or the way to get one. */
@Composable
private fun SubscriptionEntry(subscription: SubscriptionState?, modifier: Modifier = Modifier) {
    val actions = LocalActions.current
    val platform = LocalAppContainer.current.platform
    if (subscription != null) {
        SubscriptionStrip(subscription, platform, onOpen = { actions.showAccount(true) }, modifier = modifier)
    } else {
        AppCard(modifier.fillMaxWidth(), padding = AppTheme.spacing.m) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBubble(AppIcons.Person)
                Spacer(Modifier.width(AppTheme.spacing.m))
                Column(Modifier.weight(1f)) {
                    Text("Аккаунт OpenFlux", style = AppTheme.typography.bodyStrong, color = AppTheme.colors.text)
                    Text("Войдите: локации появятся сами. Пробный период бесплатно.", style = AppTheme.typography.caption, color = AppTheme.colors.textSecondary)
                }
                Spacer(Modifier.width(AppTheme.spacing.s))
                AppButton("Войти", { actions.showAccount(false) }, style = ButtonStyle.Secondary)
            }
        }
    }
}

private fun plural(n: Int): String {
    val mod10 = n % 10
    val mod100 = n % 100
    return when {
        mod10 == 1 && mod100 != 11 -> "подключение"
        mod10 in 2..4 && mod100 !in 12..14 -> "подключения"
        else -> "подключений"
    }
}

/** The actions of a profile in its row's menu: what can be done without opening it. */
internal fun profileActions(model: ProfilesScreenModel, actions: AppActions, profile: Profile, running: Boolean): List<MenuAction> = listOf(
    if (running) MenuAction("Отключить", model.connection::disconnect, Icons.Rounded.Stop)
    else MenuAction("Подключить", { model.connect(profile) }, Icons.Rounded.PlayArrow),
) + if (profile.managed) emptyList() else buildList {
    if (profile.ownNode) add(MenuAction("Управлять нодой", { actions.openNode(profile.id) }, Icons.Rounded.Dns))
    add(MenuAction("Изменить профиль", { actions.editProfile(profile) }, Icons.Rounded.Edit))
    add(MenuAction("QR и ссылка", { model.shareFor = profile }, Icons.Rounded.QrCode2, enabled = profile.transport.shareable))
    add(MenuAction("Копировать ссылку", { model.copyLink(profile) }, Icons.Rounded.ContentCopy, enabled = profile.transport.shareable))
    add(MenuAction("Дублировать", { model.duplicate(profile)?.let { actions.openProfile(it.id) } }, Icons.Rounded.FileCopy))
    add(MenuAction("Удалить", { model.deleteFor = profile }, Icons.Rounded.Delete, danger = true))
}

// ---- one profile ----

/** What a profile is made of, and what can be done with it. */
@Composable
fun ProfileScreen(id: String) {
    val models = LocalModels.current
    val actions = LocalActions.current
    val model = models.profiles
    val toaster = LocalToaster.current
    val profile = model.profiles.profiles.collectAsState().value.firstOrNull { it.id == id }
    val state by model.connection.state.collectAsState()
    if (profile == null) {
        // Deleted from somewhere else while open: nothing to show.
        LaunchedEffect(Unit) { actions.back() }
        return
    }
    val running = state.isActive && state.profile?.id == profile.id
    DetailScreen(
        title = profile.name,
        subtitle = profile.summary,
        leading = { IconBubble(AppIcons.byName(profile.icon), size = 48.dp) },
        actions = { if (running) StatusBadge(state.look().title, state.look().tone) },
        maxWidth = 800.dp,
    ) {
        ButtonRow {
            if (running) AppButton("Отключить", model.connection::disconnect, style = ButtonStyle.Danger, leading = Icons.Rounded.Stop)
            else AppButton("Подключить", { model.connect(profile) }, leading = Icons.Rounded.PlayArrow)
            if (!profile.managed) {
                AppButton(if (profile.ownNode) "Параметры профиля" else "Изменить", { actions.editProfile(profile) }, style = ButtonStyle.Secondary, leading = Icons.Rounded.Edit)
                if (profile.transport.shareable) {
                    AppButton("QR и ссылка", { model.shareFor = profile }, style = ButtonStyle.Secondary, leading = Icons.Rounded.QrCode2)
                }
            }
        }
        if (profile.managed) {
            Spacer(Modifier.height(AppTheme.spacing.l))
            Banner(
                "Эта локация приходит с подпиской: список и ключи обновляются сами, менять их нельзя.",
                Tone.Accent, icon = Icons.Rounded.Info,
                action = { AppButton("Подписка", { actions.showAccount(true) }, style = ButtonStyle.Secondary) },
            )
        }
        if (profile.ownNode) {
            Spacer(Modifier.height(AppTheme.spacing.l))
            NodeEntryCard(
                "Это ваша нода",
                "Сервер, транспорты и новые пользователи меняются на экране ноды.",
                "Управлять нодой", { actions.openNode(profile.id) },
            )
        } else if (profile.editableNode) {
            Spacer(Modifier.height(AppTheme.spacing.l))
            NodeEntryCard(
                "Вы запускали эту ноду?",
                "Если у вас есть доступ к её серверу, можно управлять ей из приложения: менять документы и транспорты.",
                "Это моя нода", {
                    model.markAsOwnNode(profile)
                    actions.openNode(profile.id)
                },
            )
        }
        Spacer(Modifier.height(AppTheme.spacing.xxl))
        SectionLabel("Транспорты")
        Spacer(Modifier.height(AppTheme.spacing.s))
        AppCard(padding = 0.dp) {
            profile.carriers.forEachIndexed { index, carrier ->
                val value = when {
                    profile.managed -> "скрыто: приходит с подпиской"
                    carrier.type.kind == ValueKind.Token -> "токен скрыт · UID ${carrier.uid}"
                    else -> carrier.value
                }
                KeyValueRow(
                    key = carrier.type.label + if (profile.session) " · приоритет ${carrier.priority}" else "",
                    value = value,
                ) {
                    if (carrier.type.kind != ValueKind.Token && !profile.managed) {
                        AppIconButton("Копировать", {
                            model.copy(carrier.value)
                            toaster.show("Скопировано")
                        }, icon = Icons.Rounded.ContentCopy)
                    }
                }
                if (index < profile.carriers.lastIndex) HorizontalRule()
            }
        }
        Spacer(Modifier.height(AppTheme.spacing.xl))
        SectionLabel("Параметры")
        Spacer(Modifier.height(AppTheme.spacing.s))
        AppCard(padding = 0.dp) {
            if (profile.stream) {
                KeyValueRow("Режим", "Без сервера: нода на PHP-хостинге")
                HorizontalRule()
                KeyValueRow("Шифрование", "Без ключа: содержимое защищает TLS самих приложений")
            } else {
                KeyValueRow("Режим", if (profile.session) "Session (несколько транспортов)" else "Обычный")
                HorizontalRule()
                KeyValueRow("Шифрование", if (profile.secret.isNotEmpty()) "AES-256-GCM" + if (profile.managed) "" else ", ключ ${profile.secret.length} симв." else "Без ключа")
                if (!profile.session) {
                    HorizontalRule()
                    KeyValueRow("Кодек", profile.codec.label)
                }
            }
            HorizontalRule()
            KeyValueRow("Источник", profile.source.label)
        }
        val problems = profile.problems()
        if (problems.isNotEmpty()) {
            Spacer(Modifier.height(AppTheme.spacing.l))
            Banner(problems.joinToString("\n"), Tone.Warning)
        }
        if (!profile.managed) {
            Spacer(Modifier.height(AppTheme.spacing.xl))
            ButtonRow {
                AppButton("Дублировать", { model.duplicate(profile)?.let { actions.openProfile(it.id) } }, style = ButtonStyle.Ghost, leading = Icons.Rounded.FileCopy)
                AppButton("Удалить", { model.deleteFor = profile }, style = ButtonStyle.Ghost, leading = Icons.Rounded.Delete)
            }
        }
    }
}

@Composable
internal fun NodeEntryCard(title: String, text: String, button: String, onClick: () -> Unit) {
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBubble(Icons.Rounded.Dns)
            Spacer(Modifier.width(AppTheme.spacing.m))
            Column(Modifier.weight(1f)) {
                Text(title, style = AppTheme.typography.bodyStrong, color = AppTheme.colors.text)
                Text(text, style = AppTheme.typography.bodySmall, color = AppTheme.colors.textSecondary)
            }
        }
        Spacer(Modifier.height(AppTheme.spacing.m))
        AppButton(button, onClick, style = ButtonStyle.Secondary, leading = Icons.Rounded.Dns)
    }
}

/** The fields of a profile; a draft is begun when the screen opens and dropped when it closes. */
@Composable
fun ProfileEditScreen(dest: Dest.ProfileEdit) {
    val models = LocalModels.current
    val actions = LocalActions.current
    val model = models.profiles
    // A draft of this profile: begun by whoever opened the screen; made here when it is not there (a rebuilt screen).
    val editor = model.editor
    val fits = editor != null && (if (dest.id == null) editor.isNew else editor.draft.id == dest.id)
    LaunchedEffect(dest, fits) { if (!fits) model.beginEdit(dest.id) }
    if (!fits || editor == null) return
    ProfileEditor(model, editor, onClose = actions::finishEdit)
}

/** "Добавить подключение": a key or link, an account, a node of one's own, by hand. */
@Composable
fun AddConnectionScreen() {
    val models = LocalModels.current
    val actions = LocalActions.current
    val model = models.account
    val profiles = models.profiles
    val container = LocalAppContainer.current
    // A subscription taken here (a key confirmed) is shown at once: the account replaces this screen.
    val nav = io.openflux.desktop.ui.nav.LocalNav.current
    val subscription by model.manager.state.collectAsState()
    val hadSubscription = remember { subscription != null }
    LaunchedEffect(subscription != null) {
        if (subscription != null && !hadSubscription && nav.top == Dest.AddConnection) nav.replaceTop(Dest.Account)
    }
    // A key that came from outside (a link opened in the app): the field takes it.
    val pendingKey by container.pendingKey.collectAsState()
    LaunchedEffect(pendingKey) {
        val key = pendingKey ?: return@LaunchedEffect
        container.pendingKey.value = null
        model.keyChanged(key)
    }
    DetailScreen(
        title = "Добавить подключение",
        subtitle = "Вставьте ключ подписки или ссылку, отсканируйте QR-код или войдите в аккаунт",
        maxWidth = 640.dp,
    ) {
        AddConnectionPage(
            model,
            AddConnectionActions(
                onAccount = { actions.signIn(AuthStep.Choose) },
                onRestore = { actions.signIn(AuthStep.Recover) },
                onOwnNode = { actions.createNode(NodeKind.Server) },
                onNoServer = { actions.createNode(NodeKind.Hosting) },
                onManual = actions::newProfile,
                onProfileLink = { link -> profiles.importText = link; profiles.importOpen = true },
            ),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ProfileRow(
    profile: Profile,
    selected: Boolean,
    runningLook: io.openflux.desktop.ui.StateLook?,
    onClick: () -> Unit,
    onDoubleClick: () -> Unit,
    menu: () -> List<MenuAction>,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    touch: Boolean,
) {
    val colors = AppTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val fill by animateColorAsState(
        when {
            selected -> colors.accentSoft
            hovered -> colors.surfaceTonal.copy(alpha = if (colors.isDark) 1f else 0.6f)
            else -> Color.Transparent
        },
    )
    var moreOpen by remember { mutableStateOf(false) }
    ContextMenuArea(menu) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(AppTheme.dimens.listRow)
                .clip(AppTheme.shapes.card)
                .background(fill)
                .then(if (selected) Modifier.border(1.dp, colors.accent.copy(alpha = 0.45f), AppTheme.shapes.card) else Modifier)
                .hoverable(interaction)
                .combinedClickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onClick,
                    onDoubleClick = if (touch) null else onDoubleClick,
                    onLongClick = if (touch) ({ moreOpen = true }) else null,
                )
                .pointerHoverIcon(PointerIcon.Hand)
                .padding(horizontal = AppTheme.spacing.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBubble(AppIcons.byName(profile.icon))
            Spacer(Modifier.width(AppTheme.spacing.m))
            Column(Modifier.weight(1f)) {
                Text(profile.name, style = AppTheme.typography.bodyStrong, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(profile.summary, style = AppTheme.typography.caption, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (!touch && (hovered || moreOpen)) {
                AppIconButton(if (runningLook != null) "Отключить" else "Подключить", onToggle,
                    icon = if (runningLook != null) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, tint = colors.accent)
                if (!profile.managed) AppIconButton("Изменить", onEdit, icon = Icons.Rounded.Edit)
                Box {
                    AppIconButton("Ещё", { moreOpen = true }, icon = Icons.Rounded.MoreVert)
                    AppMenu(moreOpen, { moreOpen = false }, menu())
                }
            } else {
                if (runningLook != null) StatusBadge(runningLook.title, runningLook.tone)
                // No hover on a touch screen: the menu stays in reach.
                if (touch) Box {
                    AppIconButton("Ещё", { moreOpen = true }, icon = Icons.Rounded.MoreVert)
                    AppMenu(moreOpen, { moreOpen = false }, menu())
                }
            }
        }
    }
}


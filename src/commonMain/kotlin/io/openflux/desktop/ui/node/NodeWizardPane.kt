package io.openflux.desktop.ui.node

import io.openflux.desktop.ui.PlatformBackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.openflux.desktop.model.NodeCoreSource
import io.openflux.desktop.model.NodeTransports
import io.openflux.desktop.model.TransportType
import io.openflux.desktop.service.LocalAppContainer
import io.openflux.desktop.ui.LocalScrollbars
import io.openflux.desktop.ui.components.AppButton
import io.openflux.desktop.ui.components.AppCard
import io.openflux.desktop.ui.components.AppDialog
import io.openflux.desktop.ui.components.AppIconButton
import io.openflux.desktop.ui.components.AppIcons
import io.openflux.desktop.ui.components.AppTextField
import io.openflux.desktop.ui.components.Banner
import io.openflux.desktop.ui.components.ButtonStyle
import io.openflux.desktop.ui.components.HorizontalRule
import io.openflux.desktop.ui.components.KeyValueRow
import io.openflux.desktop.ui.components.LocalToaster
import io.openflux.desktop.ui.components.QrCode
import io.openflux.desktop.ui.components.SectionLabel
import io.openflux.desktop.ui.components.Segmented
import io.openflux.desktop.ui.components.SwitchRow
import io.openflux.desktop.ui.components.TextAction
import io.openflux.desktop.ui.components.Tone
import io.openflux.desktop.ui.LocalTouchUi
import io.openflux.desktop.ui.theme.AppTheme

/**
 * The "Своя нода" wizard in the Profiles section. [onClose] leaves it;
 * [onSaved] opens the saved profile.
 */
@Composable
fun NodeWizardPane(
    model: NodeWizardModel,
    onClose: () -> Unit,
    onSaved: (String) -> Unit,
    onAdvanced: (io.openflux.desktop.model.Profile) -> Unit = {},
) {
    var confirmClose by remember { mutableStateOf(false) }
    var qrOpen by remember { mutableStateOf(false) }
    val requestClose = {
        if (model.busy == null) {
            if (model.unsaved) confirmClose = true else onClose()
        }
    }
    // Back steps back through the wizard, then leaves it.
    PlatformBackHandler(enabled = LocalTouchUi.current && model.busy == null) {
        if (model.profileTarget == null && (model.step == WizardStep.Document || model.step == WizardStep.Plan)) model.back() else requestClose()
    }
    val scroll = rememberScrollState()
    val scrollbars = LocalScrollbars.current
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (model.busy != null) LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp), color = AppTheme.colors.accent)
            else Spacer(Modifier.height(3.dp))
            Column(
                Modifier.fillMaxSize().verticalScroll(scroll)
                    .padding(horizontal = AppTheme.spacing.page, vertical = AppTheme.spacing.xl),
            ) {
                Column(Modifier.widthIn(max = 640.dp)) {
                    if (model.profileTarget != null) {
                        NodeEditor(model, onShowQr = { qrOpen = true }, onSaved = onSaved, onClose = requestClose, onAdvanced = onAdvanced)
                    } else {
                        Header(model, requestClose)
                        Spacer(Modifier.height(AppTheme.spacing.xl))
                        when (model.step) {
                            WizardStep.Server -> ServerStep(model)
                            WizardStep.Document -> DocumentStep(model)
                            WizardStep.Plan -> PlanStep(model)
                            WizardStep.Verify -> VerifyStep(model)
                            WizardStep.Done -> DoneStep(model, onShowQr = { qrOpen = true }, onSaved = onSaved, onClose = requestClose)
                        }
                    }
                    Status(model)
                }
            }
        }
        scrollbars.Vertical(scroll, Modifier.align(Alignment.CenterEnd))
    }

    model.hostKeyPrompt?.let { prompt ->
        AppDialog(
            title = if (prompt.mismatch) "Ключ сервера изменился" else "Новый сервер",
            onDismiss = model::dismissHostKey,
            primary = if (prompt.mismatch) "Сервер переустановлен" else "Доверять",
            primaryStyle = if (prompt.mismatch) ButtonStyle.Danger else ButtonStyle.Primary,
            onPrimary = model::trustHostKey,
        ) {
            Text(if (prompt.mismatch) "Сервер представился другим ключом:" else "Отпечаток ключа сервера:",
                style = AppTheme.typography.body, color = AppTheme.colors.text)
            Spacer(Modifier.height(AppTheme.spacing.s))
            SelectionContainer { Text(prompt.fingerprint, style = AppTheme.typography.mono, color = AppTheme.colors.text) }
            Spacer(Modifier.height(AppTheme.spacing.m))
            Text(
                if (prompt.mismatch) {
                    "Так бывает после переустановки системы, но так же выглядит и подмена сервера. " +
                        "Продолжайте, только если вы сами переустанавливали сервер."
                } else {
                    "Если панель провайдера показывает отпечаток, сверьте его. Доверять этому серверу?"
                },
                style = AppTheme.typography.body,
                color = AppTheme.colors.textSecondary,
            )
        }
    }
    if (confirmClose) {
        AppDialog(
            title = "Выйти без сохранения?",
            onDismiss = { confirmClose = false },
            primary = "Сохранить профиль",
            onPrimary = {
                confirmClose = false
                model.save()?.let { onSaved(it.id) }
            },
            secondary = "Остаться",
        ) {
            Text("Канал уже установлен на сервере, но профиль не сохранён: без него к каналу не подключиться.",
                style = AppTheme.typography.body, color = AppTheme.colors.text)
            Spacer(Modifier.height(AppTheme.spacing.m))
            TextAction("Выйти, не сохраняя", { confirmClose = false; onClose() }, color = AppTheme.colors.danger)
        }
    }
    if (qrOpen) {
        AppDialog(title = "Отсканируйте в OpenFlux", onDismiss = { qrOpen = false }, secondary = "Закрыть") {
            val matrix = remember(model.shareLink) { model.qr() }
            val hint = "На другом устройстве: Профили → Импорт → Сканировать QR. В коде ключ канала: показывайте его только тому, кто будет им пользоваться."
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val width = maxWidth
                if (width >= 440.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        QrCode(matrix, 220.dp)
                        Spacer(Modifier.width(AppTheme.spacing.l))
                        Text(hint, style = AppTheme.typography.body, color = AppTheme.colors.textSecondary)
                    }
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        QrCode(matrix, minOf(width, 260.dp))
                        Spacer(Modifier.height(AppTheme.spacing.l))
                        Text(hint, style = AppTheme.typography.body, color = AppTheme.colors.textSecondary)
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(model: NodeWizardModel, onClose: () -> Unit) {
    val (title, subtitle) = when (model.step) {
        WizardStep.Server -> if (model.profileTarget != null) {
            "Нода профиля «${model.profileTarget?.name}»" to "Введите пароль от сервера, чтобы изменить транспорты на нём."
        } else {
            "Своя нода" to "Сервер (VDS) с Linux и systemd: Debian, Ubuntu и похожие."
        }
        WizardStep.Document -> "Транспорты канала" to "Через что устройство и нода будут обмениваться зашифрованным трафиком."
        WizardStep.Plan -> "Будут изменения" to "На сервере будет сделано только это."
        WizardStep.Verify -> "Проверка канала" to "Подключаюсь к новой ноде и открываю сайт через неё."
        WizardStep.Done -> (if (model.editing != null) "Транспорты изменены" else "Нода готова") to when {
            model.verifiedIp.isNotEmpty() -> "Трафик выходит в интернет с адреса ${model.verifiedIp}."
            model.sessionProven -> "Нода ответила по защищённому каналу. Адрес выхода в режиме VPN видно в браузере: api.ipify.org."
            else -> "Канал установлен, но проверка не завершена."
        }
    }
    Row(verticalAlignment = Alignment.Top) {
        if (model.step == WizardStep.Document || model.step == WizardStep.Plan) {
            AppIconButton("Назад", model::back, resource = AppIcons.Back, enabled = model.busy == null)
            Spacer(Modifier.width(AppTheme.spacing.s))
        }
        Column(Modifier.weight(1f)) {
            Text("Шаг ${model.step.number} из 4", style = AppTheme.typography.label, color = AppTheme.colors.accent)
            Spacer(Modifier.height(AppTheme.spacing.xs))
            Text(title, style = AppTheme.typography.pageTitle, color = AppTheme.colors.text)
            Text(subtitle, style = AppTheme.typography.body, color = AppTheme.colors.textSecondary)
        }
        AppButton("Закрыть", onClose, style = ButtonStyle.Ghost, enabled = model.busy == null)
    }
}

@Composable
private fun Status(model: NodeWizardModel) {
    val busy = model.busy
    val error = model.error
    val notice = model.notice
    // The document step shows the browser's progress next to the browser.
    if ((busy == null || model.documentProgress != null) && error == null && notice == null) return
    Spacer(Modifier.height(AppTheme.spacing.l))
    when {
        busy != null && model.documentProgress == null -> Banner(busy, Tone.Accent, icon = Icons.Rounded.Info)
        error != null -> Banner(error, Tone.Danger, icon = Icons.Rounded.ErrorOutline)
        notice != null -> Banner(notice, Tone.Success, icon = Icons.Rounded.CheckCircle)
    }
}

@Composable
private fun Note(text: String) {
    Spacer(Modifier.height(AppTheme.spacing.s))
    Text(text, style = AppTheme.typography.bodySmall, color = AppTheme.colors.textSecondary)
}

@Composable
private fun Actions(content: @Composable () -> Unit) {
    Spacer(Modifier.height(AppTheme.spacing.xl))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s), verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s)) {
        content()
    }
}

// ---- step 1 ----

@Composable
private fun ColumnScope.ServerStep(model: NodeWizardModel) {
    val settings by LocalAppContainer.current.settings.settings.collectAsState()
    val idle = model.busy == null
    val forProfile = model.profileTarget != null
    if (forProfile) {
        Note("Адрес и порт канала взяты из профиля. Меняется только набор документов; ключ шифрования, порт и ссылки остаются прежними.")
        Spacer(Modifier.height(AppTheme.spacing.l))
    }
    if (settings.knownServers.isNotEmpty() && !forProfile) {
        SectionLabel("Недавние серверы")
        Note("Новый канал встанет рядом с уже установленными: так добавляют ещё одного пользователя.")
        Spacer(Modifier.height(AppTheme.spacing.s))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s), verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s)) {
            settings.knownServers.forEach { server ->
                AppButton("${server.user}@${server.host}:${server.port}", { model.useServer(server) }, style = ButtonStyle.Secondary, enabled = idle)
            }
        }
        Spacer(Modifier.height(AppTheme.spacing.xl))
    }
    SectionLabel("Сервер")
    Spacer(Modifier.height(AppTheme.spacing.s))
    Row(horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.m)) {
        AppTextField(model.host, { model.host = it.trim() }, Modifier.weight(1f), label = "Адрес (IP или домен)", placeholder = "203.0.113.10", enabled = idle)
        AppTextField(model.port, { model.port = it.filter(Char::isDigit).take(5) }, Modifier.width(110.dp), label = "SSH-порт",
            keyboardType = KeyboardType.Number, enabled = idle)
    }
    Spacer(Modifier.height(AppTheme.spacing.m))
    AppTextField(model.user, { model.user = it.trim() }, label = "Логин", enabled = idle)
    Spacer(Modifier.height(AppTheme.spacing.l))
    SectionLabel("Вход")
    Spacer(Modifier.height(AppTheme.spacing.s))
    Segmented(listOf(false, true), model.useKey, { if (it) "Ключ" else "Пароль" }, { model.useKey = it }, enabled = idle)
    Spacer(Modifier.height(AppTheme.spacing.m))
    if (model.useKey) {
        AppTextField(model.privateKey, { model.privateKey = it }, label = "Приватный ключ (OpenSSH или PEM)",
            placeholder = "-----BEGIN OPENSSH PRIVATE KEY-----", singleLine = false, minLines = 4, monospace = true, enabled = idle)
        Spacer(Modifier.height(AppTheme.spacing.s))
        AppButton("Выбрать файл ключа…", model::readKeyFile, style = ButtonStyle.Secondary, leading = Icons.Rounded.FolderOpen, enabled = idle)
        Spacer(Modifier.height(AppTheme.spacing.m))
        AppTextField(model.passphrase, { model.passphrase = it }, label = "Пароль ключа (если есть)", secret = true, enabled = idle)
    } else {
        AppTextField(model.password, { model.password = it }, label = "Пароль", secret = true, enabled = idle)
    }
    if (forProfile && model.useKey) {
        Spacer(Modifier.height(AppTheme.spacing.m))
        AppTextField(model.sudoPassword, { model.sudoPassword = it }, label = "Пароль sudo (если сервер его просит)", secret = true, enabled = idle)
    }
    Note("Пароль и ключ нужны только на время установки: OpenFlux их не сохраняет.")
    if (!forProfile) {
        Spacer(Modifier.height(AppTheme.spacing.l))
        SectionLabel("Ядро на сервере")
        Spacer(Modifier.height(AppTheme.spacing.s))
        Segmented(NodeCoreSource.entries, model.nodeCore, { it.label }, { model.nodeCore = it }, enabled = idle)
        Note(
            "Нода ставит ядро из релизов ${model.nodeCore.repo} на GitHub и, если включить автообновление, " +
                "обновляется по ним же. Ядро одно на все каналы сервера: при другом выборе сервер перейдёт на него.",
        )
    }
    Actions {
        AppButton(if (idle) "Подключиться" else "Подключаюсь…", { model.connect() }, enabled = idle)
    }
}

// ---- step 2 ----

@Composable
private fun ColumnScope.DocumentStep(model: NodeWizardModel) {
    val idle = model.busy == null
    model.probe?.let { probe ->
        AppCard(padding = 0.dp) {
            KeyValueRow("Сервер", "${model.user.trim()}@${model.host.trim()}")
            HorizontalRule()
            KeyValueRow("Система", listOf(probe.os, probe.arch).filter(String::isNotBlank).joinToString(", "))
            if (probe.channels.isNotEmpty()) {
                HorizontalRule()
                KeyValueRow("Каналов OpenFlux", probe.channels.size.toString())
            }
        }
        Spacer(Modifier.height(AppTheme.spacing.l))
        if (model.editing == null && probe.channels.isNotEmpty()) {
            InstalledChannels(model, probe.channels)
            Spacer(Modifier.height(AppTheme.spacing.l))
        }
    }
    val editing = model.editing
    if (editing != null) {
        Banner(
            "Меняются транспорты канала ${editing.id} (порт ${editing.port}, ${if (editing.running) "работает" else editing.state.ifBlank { "состояние неизвестно" }}). " +
                "Ключ, порт и контекст шифрования останутся прежними: выданные ссылки продолжат работать.",
            Tone.Accent, icon = Icons.Rounded.Info,
        )
        if (!model.editingHasProfile) {
            Note("Профиля этого канала на устройстве нет: изменения применятся на сервере, а проверить канал и обновить ссылку можно там, где он создан.")
        }
        Spacer(Modifier.height(AppTheme.spacing.l))
    }
    AppTextField(model.name, { model.name = it }, label = "Название профиля", enabled = idle && editing == null)
    if (editing == null) {
        model.channel?.let { Note("Канал: ${it.id}. Для него создан отдельный ключ шифрования, его знают только это устройство и нода.") }
    }

    TransportsEditor(model)
    if (!model.needsYandexDocument || model.documentUrl.isNotEmpty()) {
        Actions {
            if (editing != null) {
                AppButton(if (idle) "Применить" else "Подождите…", model::next, enabled = idle)
                AppButton("Отменить изменение", model::cancelEditing, style = ButtonStyle.Secondary, enabled = idle)
            } else {
                AppButton(if (idle) "Далее" else "Подождите…", model::next, enabled = idle)
            }
        }
    } else if (editing != null) {
        Actions {
            AppButton("Отменить изменение", model::cancelEditing, style = ButtonStyle.Secondary, enabled = idle)
        }
    }
}

/** The carriers of a channel and the documents they need: shared by the wizard's step and the node screen. */
@Composable
private fun ColumnScope.TransportsEditor(model: NodeWizardModel) {
    val idle = model.busy == null
    val editing = model.editing
    Spacer(Modifier.height(AppTheme.spacing.xl))
    SectionLabel("Транспорты")
    Note(
        "Можно выбрать несколько: если один перестанет работать, устройство перейдёт на следующий. " +
            "Прямое подключение к серверу (Direct) есть всегда: это резерв и через него идёт проверка.",
    )
    Spacer(Modifier.height(AppTheme.spacing.s))
    val switchable = idle
    AppCard(padding = 0.dp) {
        SwitchRow(
            "Яндекс Документ (Volga)",
            "Ссылка на ваш документ на Яндекс Диске с правом редактирования.",
            model.useVolga, { model.useVolga = it }, enabled = switchable,
        )
        HorizontalRule()
        SwitchRow(
            "Yandex Docs",
            "Тот же документ Яндекса через прежний транспорт Yandex Docs.",
            model.useYandexDocs, { model.useYandexDocs = it }, enabled = switchable,
        )
        HorizontalRule()
        SwitchRow(
            "Mail.ru Документы",
            "Публичная ссылка на ваш документ в Облаке Mail.ru с правом редактирования.",
            model.useMailru, { model.useMailru = it }, enabled = switchable,
        )
        HorizontalRule()
        SwitchRow(
            "Яндекс Доска",
            "Доска Яндекс Досок, открытая гостям на редактирование.",
            model.useBoards, { model.useBoards = it }, enabled = switchable,
        )
        HorizontalRule()
        SwitchRow(
            "Cups.online",
            "Комнаты мастер создаст сам, вводить ничего не нужно.",
            model.useCups, { model.useCups = it }, enabled = switchable,
        )
        HorizontalRule()
        SwitchRow("Direct", "Прямое TCP-подключение к серверу, всегда включено.", checked = true, onCheckedChange = {}, enabled = false)
    }

    if (model.useMailru) {
        Spacer(Modifier.height(AppTheme.spacing.l))
        SectionLabel("Документ Mail.ru")
        Spacer(Modifier.height(AppTheme.spacing.s))
        if (model.mailruInput.isNotEmpty()) {
            Banner("Документ готов: ${model.mailruInput}", Tone.Success, icon = Icons.Rounded.CheckCircle)
            Spacer(Modifier.height(AppTheme.spacing.s))
        }
        Actions {
            AppButton(
                if (model.mailruInput.isEmpty()) "Войти в Mail.ru и создать документ" else "Создать другой документ",
                model::createMailruDocument,
                leadingResource = AppIcons.byName("ic_mailru"),
                style = if (model.mailruInput.isEmpty()) ButtonStyle.Primary else ButtonStyle.Secondary,
                enabled = idle,
            )
        }
        Note(
            "Вход откроется в окне встроенного браузера, как в Настройках → Аккаунты: если вход уже сохранён, вводить " +
                "ничего не придётся. Документ появится в папке openflux в Облаке с редактированием по ссылке.",
        )
        Spacer(Modifier.height(AppTheme.spacing.l))
        Text("Или свой документ", style = AppTheme.typography.bodyStrong, color = AppTheme.colors.text)
        Spacer(Modifier.height(AppTheme.spacing.s))
        AppTextField(model.mailruInput, { model.mailruInput = it.trim() }, placeholder = "https://cloud.mail.ru/public/…",
            monospace = true, enabled = idle, helper = "Облако Mail.ru → документ → «Поделиться» → доступ по ссылке с редактированием")
    }

    if (model.useBoards) {
        Spacer(Modifier.height(AppTheme.spacing.l))
        SectionLabel("Доска Яндекса")
        Spacer(Modifier.height(AppTheme.spacing.s))
        if (model.boardInput.isNotEmpty()) {
            Banner("Доска готова: ${model.boardInput}", Tone.Success, icon = Icons.Rounded.CheckCircle)
            Spacer(Modifier.height(AppTheme.spacing.s))
        }
        Actions {
            AppButton(
                if (model.boardInput.isEmpty()) "Войти в Яндекс и создать доску" else "Создать другую доску",
                model::createBoard,
                leadingResource = AppIcons.Yandex,
                style = if (model.boardInput.isEmpty()) ButtonStyle.Primary else ButtonStyle.Secondary,
                enabled = idle,
            )
        }
        Note("Доска появится в ваших Яндекс Досках с доступом гостям на редактирование.")
        Spacer(Modifier.height(AppTheme.spacing.l))
        Text("Или своя доска", style = AppTheme.typography.bodyStrong, color = AppTheme.colors.text)
        Spacer(Modifier.height(AppTheme.spacing.s))
        AppTextField(model.boardInput, { model.boardInput = it.trim() }, placeholder = "https://boards.yandex.ru/whiteboard/?hash=…",
            monospace = true, enabled = idle, helper = "«Поделиться» → гостевой доступ с правом редактирования")
    }

    if (model.needsYandexDocument) {
        Spacer(Modifier.height(AppTheme.spacing.l))
        VolgaDocument(model)
    }

    if (editing != null && model.needsSudoPassword) {
        Spacer(Modifier.height(AppTheme.spacing.l))
        AppTextField(model.sudoPassword, { model.sudoPassword = it }, label = "Пароль sudo пользователя ${model.user.trim()}",
            secret = true, enabled = idle, helper = "Для изменения канала нужны права root")
    }
}

/** The channels already on the server: each can get other documents, its links stay. */
@Composable
private fun InstalledChannels(model: NodeWizardModel, channels: List<String>) {
    val idle = model.busy == null
    SectionLabel("Каналы на сервере")
    Note("Документы канала можно добавить или убрать: ключ, порт и контекст останутся, выданные ссылки продолжат работать.")
    Spacer(Modifier.height(AppTheme.spacing.s))
    if (model.needsSudoPassword && model.useKey) {
        AppTextField(model.sudoPassword, { model.sudoPassword = it }, label = "Пароль sudo пользователя ${model.user.trim()}",
            secret = true, enabled = idle, helper = "Чтобы прочитать и изменить канал, нужны права root")
        Spacer(Modifier.height(AppTheme.spacing.s))
    }
    AppCard(padding = 0.dp) {
        channels.forEachIndexed { i, id ->
            if (i > 0) HorizontalRule()
            Row(
                Modifier.fillMaxWidth().padding(horizontal = AppTheme.spacing.l, vertical = AppTheme.spacing.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(id, style = AppTheme.typography.bodyStrong, color = AppTheme.colors.text, modifier = Modifier.weight(1f))
                AppButton("Изменить транспорты", { model.editChannel(id) }, style = ButtonStyle.Secondary, enabled = idle)
            }
        }
    }
}

/** The Yandex document: the user's own, checked the way the node will open it. */
@Composable
private fun ColumnScope.VolgaDocument(model: NodeWizardModel) {
    val idle = model.busy == null
    val progress = model.documentProgress
    // The document is made with the Yandex sign-in, saved in the Accounts
    // window; the user's own link stays as the other way.
    val signIn = true
    SectionLabel(if (signIn) "Документ Яндекса" else "Свой пустой документ")
    if (model.documentUrl.isNotEmpty()) {
        Spacer(Modifier.height(AppTheme.spacing.s))
        Banner("Документ готов: ${model.documentUrl}", Tone.Success, icon = Icons.Rounded.CheckCircle)
    }
    if (signIn) {
        Actions {
            if (progress != null) {
                AppButton("Отменить вход в Яндекс", model::cancelDocument, style = ButtonStyle.Secondary)
            } else {
                AppButton(
                    if (model.documentUrl.isEmpty()) "Войти в Яндекс и создать документ" else "Создать другой документ",
                    model::createDocument,
                    leadingResource = AppIcons.Yandex,
                    style = if (model.documentUrl.isEmpty()) ButtonStyle.Primary else ButtonStyle.Secondary,
                    enabled = idle,
                )
            }
        }
        if (progress != null) {
            Spacer(Modifier.height(AppTheme.spacing.m))
            Banner(progress, Tone.Accent, icon = Icons.Rounded.Info)
        }
        // The page itself opens in the sign-in window, like on the Accounts tab.
        Note(
            "Вход и Диск откроются в окне встроенного браузера, как в Настройках → Аккаунты: если вход уже сохранён, " +
                "вводить ничего не придётся. Документ появится в папке openflux на вашем Яндекс Диске с доступом " +
                "«Редактирование» по ссылке.",
        )
        Spacer(Modifier.height(AppTheme.spacing.l))
        Text("Или свой пустой документ", style = AppTheme.typography.bodyStrong, color = AppTheme.colors.text)
    }
    Spacer(Modifier.height(AppTheme.spacing.s))
    AppTextField(model.documentInput, { model.documentInput = it.trim() }, placeholder = "https://disk.yandex.ru/edit/d/…",
        monospace = true, enabled = idle, helper = "Ссылка с доступом «Редактирование» из «Поделиться»")
    Actions {
        AppButton("Проверить ссылку", model::checkDocument, style = ButtonStyle.Secondary,
            enabled = idle && model.documentInput.isNotBlank())
    }
}

// ---- step 3 ----

@Composable
private fun ColumnScope.PlanStep(model: NodeWizardModel) {
    val plan = model.plan ?: return
    val idle = model.busy == null
    AppCard {
        plan.actions.forEach { action ->
            Row(Modifier.padding(vertical = AppTheme.spacing.xs)) {
                Text("•", style = AppTheme.typography.body, color = AppTheme.colors.accent)
                Spacer(Modifier.width(AppTheme.spacing.s))
                Text(action, style = AppTheme.typography.body, color = AppTheme.colors.text)
            }
        }
    }
    Spacer(Modifier.height(AppTheme.spacing.m))
    AppCard(padding = 0.dp) {
        KeyValueRow("Канал", plan.channel.ifEmpty { model.channel?.id.orEmpty() })
        HorizontalRule()
        KeyValueRow("Порт резервного канала", plan.port.toString())
        HorizontalRule()
        KeyValueRow("Транспорты", NodeTransports.describe(model.transportTypes))
        if (model.transportTypes.contains(TransportType.VYANDEX)) {
            HorizontalRule()
            KeyValueRow("Документ Яндекса", model.documentUrl)
        }
    }
    val keep = if (plan.untouched.isNotEmpty()) "Каналы, которые уже есть на сервере, не изменятся: ${plan.untouched.joinToString()}. " else ""
    Note(keep + "Остальные программы на сервере (Docker, VPN, панели) мастер не трогает.")
    Spacer(Modifier.height(AppTheme.spacing.m))
    AppCard(padding = 0.dp) {
        SwitchRow(
            "Автообновление ядра",
            "Раз в 6 часов сервер проверяет новые релизы ноды на GitHub, сверяет хеши и обновляется сам. " +
                "Если канал на новом ядре не поднялся, возвращает прежнее. Действует на все каналы сервера.",
            model.autoUpdate, model::changeAutoUpdate, enabled = idle,
        )
    }
    if (model.nodeSignedIn) {
        Spacer(Modifier.height(AppTheme.spacing.m))
        Banner(
            "Нода будет открывать документ под вашим аккаунтом Яндекса: так Яндекс не требует от сервера капчу. " +
                "Кто получит root на сервере, получит и доступ к этому аккаунту, поэтому лучше входить отдельным аккаунтом для документов.",
            Tone.Warning,
            icon = Icons.Rounded.Warning,
            action = { TextAction("Не передавать", model::forgetYandexSignIn, enabled = idle) },
        )
    }
    if (model.documentWarning.isNotEmpty()) {
        Spacer(Modifier.height(AppTheme.spacing.m))
        Banner(model.documentWarning, Tone.Neutral, icon = Icons.Rounded.Info)
    }
    if (model.needsSudoPassword) {
        Spacer(Modifier.height(AppTheme.spacing.l))
        AppTextField(model.sudoPassword, { model.sudoPassword = it }, label = "Пароль sudo пользователя ${model.user.trim()}",
            secret = true, enabled = idle, helper = "Для установки нужны права root")
    }
    Actions {
        AppButton(if (idle) "Установить ноду" else "Устанавливаю…", model::install, enabled = idle)
        AppButton("Назад", model::back, style = ButtonStyle.Secondary, enabled = idle)
    }
}

// ---- step 4 ----

@Composable
private fun ColumnScope.VerifyStep(model: NodeWizardModel) {
    val failed = model.verifyFailed
    if (failed == null) {
        if (model.transportTypes.contains(TransportType.VYANDEX)) {
            Text(
                "Если Яндекс попросит ноду пройти проверку, откроется окно с капчей: она показывается с адреса сервера, пройдите её как обычно.",
                style = AppTheme.typography.body,
                color = AppTheme.colors.textSecondary,
            )
        }
        model.primaryType?.let { primary ->
            Actions {
                AppButton("Хватит ждать ${primary.shortLabel}", model::stopWaiting, style = ButtonStyle.Secondary)
            }
        }
        return
    }
    Banner(failed, Tone.Danger, icon = Icons.Rounded.ErrorOutline)
    val idle = model.busy == null
    model.channel?.let { channel ->
        Note(
            if (model.installed) "Нода установлена на сервере как openflux-node@${channel.id}. Можно повторить проверку или удалить канал с сервера."
            else "Канал ${channel.id} удалён с сервера.",
        )
    }
    Actions {
        AppButton("Повторить проверку", model::verify, leading = Icons.Rounded.Refresh, enabled = idle && model.installed)
        if (model.installed) {
            if (model.canRemoveChannel) {
                AppButton("Удалить канал с сервера", model::removeChannel, style = ButtonStyle.Secondary, leading = Icons.Rounded.Delete, enabled = idle)
            }
            AppButton("Сохранить профиль всё равно", model::skipVerification, style = ButtonStyle.Ghost, enabled = idle)
        }
    }
}

// ---- done ----

@Composable
private fun ColumnScope.DoneStep(model: NodeWizardModel, onShowQr: () -> Unit, onSaved: (String) -> Unit, onClose: () -> Unit) {
    val toaster = LocalToaster.current
    if ((model.verifiedIp.isNotEmpty() || model.sessionProven) && !model.primaryUp) {
        Banner(
            "Сейчас работает резервный канал (прямое подключение к серверу). Канал через ${model.primaryType?.shortLabel.orEmpty()} ещё не поднялся" +
                if (model.primaryType == TransportType.VYANDEX) ": когда нода попросит проверку, OpenFlux покажет её, пройдите её." else ": OpenFlux переключится на него, когда он заработает.",
            Tone.Warning,
            icon = Icons.Rounded.Warning,
        )
        Spacer(Modifier.height(AppTheme.spacing.m))
    }
    AppCard(padding = 0.dp) {
        KeyValueRow("Профиль", model.name.trim().ifEmpty { model.profile?.name.orEmpty() })
        HorizontalRule()
        KeyValueRow("Канал", model.resultChannel)
        if (model.verifiedIp.isNotEmpty()) {
            HorizontalRule()
            KeyValueRow("Внешний адрес", model.verifiedIp, valueColor = AppTheme.colors.success)
        }
    }
    Note("Чтобы добавить ещё одного пользователя, запустите мастер снова: появится отдельный канал со своим документом и ключом.")
    Actions {
        if (model.saved) {
            AppButton("Открыть профиль", { model.profile?.let { onSaved(it.id) } }, leading = Icons.Rounded.CheckCircle)
        } else {
            AppButton("Сохранить на этом устройстве", {
                model.save()?.let { toaster.show("Профиль «${it.name}» сохранён", Tone.Success) }
            })
        }
        AppButton("QR для другого устройства", onShowQr, style = ButtonStyle.Secondary, leading = Icons.Rounded.QrCode2)
        AppButton("Копировать ссылку", {
            model.copyLink()
            toaster.show("Ссылка скопирована", Tone.Success)
        }, style = ButtonStyle.Secondary, leading = Icons.Rounded.ContentCopy)
    }
    Spacer(Modifier.height(AppTheme.spacing.m))
    Banner("В QR и ссылке лежит ключ канала: передавайте их только тому, кто будет им пользоваться.", Tone.Neutral, icon = Icons.Rounded.Lock)
    Actions {
        AppButton("Готово", onClose, style = ButtonStyle.Ghost)
    }
}

// ---- the node screen: a profile's node, changed on one screen ----

/**
 * The screen of a node the person runs: its profile, the way into its server and the transports of its channel
 * in one place, not step by step. "Загрузить с сервера" reads the channel, the switches below are what the
 * server will have, "Применить" changes the server and the profile together.
 */
@Composable
private fun ColumnScope.NodeEditor(
    model: NodeWizardModel,
    onShowQr: () -> Unit,
    onSaved: (String) -> Unit,
    onClose: () -> Unit,
    onAdvanced: (io.openflux.desktop.model.Profile) -> Unit,
) {
    val idle = model.busy == null
    val editing = model.editing
    val loaded = editing != null
    val done = model.step == WizardStep.Done
    val verifying = model.step == WizardStep.Verify
    // The profile follows the server: saved as soon as the channel is changed and checked.
    LaunchedEffect(done) { if (done && !model.saved) model.save() }

    Row(verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(model.profileTarget?.name.orEmpty().ifBlank { "Нода" }, style = AppTheme.typography.pageTitle, color = AppTheme.colors.text)
            Text(
                "Профиль, доступ к серверу и транспорты канала на одном экране.",
                style = AppTheme.typography.body, color = AppTheme.colors.textSecondary,
            )
        }
        AppButton("Закрыть", onClose, style = ButtonStyle.Ghost, enabled = idle)
    }
    Spacer(Modifier.height(AppTheme.spacing.xl))

    if (done) {
        NodeEditorResult(model, onShowQr, onSaved)
        return
    }

    SectionLabel("Профиль")
    Spacer(Modifier.height(AppTheme.spacing.s))
    AppTextField(model.name, { model.name = it }, label = "Название", enabled = idle && !verifying)
    model.profileTarget?.let { p ->
        Spacer(Modifier.height(AppTheme.spacing.xs))
        TextAction("Ключ, приоритеты, режим и другие параметры профиля…", { onAdvanced(p) })
    }

    Spacer(Modifier.height(AppTheme.spacing.xl))
    SectionLabel("Сервер")
    Spacer(Modifier.height(AppTheme.spacing.s))
    if (!loaded) {
        Row(horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.m)) {
            AppTextField(model.host, { model.host = it.trim() }, Modifier.weight(1f), label = "Адрес (IP или домен)", enabled = idle)
            AppTextField(
                model.port, { model.port = it.filter(Char::isDigit).take(5) }, Modifier.width(110.dp), label = "SSH-порт",
                keyboardType = KeyboardType.Number, enabled = idle,
            )
        }
        Spacer(Modifier.height(AppTheme.spacing.m))
        AppTextField(model.user, { model.user = it.trim() }, label = "Логин", enabled = idle)
        Spacer(Modifier.height(AppTheme.spacing.m))
        Segmented(listOf(false, true), model.useKey, { if (it) "Вход по ключу" else "Вход по паролю" }, { model.useKey = it }, enabled = idle)
        Spacer(Modifier.height(AppTheme.spacing.m))
        if (model.useKey) {
            AppTextField(
                model.privateKey, { model.privateKey = it }, label = "Приватный ключ (OpenSSH или PEM)",
                placeholder = "-----BEGIN OPENSSH PRIVATE KEY-----", singleLine = false, minLines = 4, monospace = true, enabled = idle,
            )
            Spacer(Modifier.height(AppTheme.spacing.s))
            AppButton("Выбрать файл ключа…", model::readKeyFile, style = ButtonStyle.Secondary, leading = Icons.Rounded.FolderOpen, enabled = idle)
            Spacer(Modifier.height(AppTheme.spacing.m))
            AppTextField(model.passphrase, { model.passphrase = it }, label = "Пароль ключа (если есть)", secret = true, enabled = idle)
            Spacer(Modifier.height(AppTheme.spacing.m))
            AppTextField(model.sudoPassword, { model.sudoPassword = it }, label = "Пароль sudo (если сервер его просит)", secret = true, enabled = idle)
        } else {
            AppTextField(model.password, { model.password = it }, label = "Пароль от сервера", secret = true, enabled = idle)
        }
        Note("Пароль нужен только чтобы прочитать и изменить канал: OpenFlux его не сохраняет.")
        Actions {
            AppButton(if (idle) "Загрузить с сервера" else "Загружаю…", { model.connect() }, enabled = idle, leading = Icons.Rounded.Refresh)
        }
    } else {
        AppCard(padding = 0.dp) {
            KeyValueRow("Сервер", "${model.user.trim()}@${model.host.trim()}")
            model.probe?.let { probe ->
                HorizontalRule()
                KeyValueRow("Система", listOf(probe.os, probe.arch).filter(String::isNotBlank).joinToString(", "))
            }
            HorizontalRule()
            KeyValueRow("Канал", "${editing?.id}, порт ${editing?.port}")
            HorizontalRule()
            KeyValueRow(
                "Состояние", if (editing?.running == true) "работает" else editing?.state?.ifBlank { "неизвестно" }.orEmpty(),
                valueColor = if (editing?.running == true) AppTheme.colors.success else AppTheme.colors.text,
            )
        }
        Spacer(Modifier.height(AppTheme.spacing.s))
        AppButton("Прочитать заново", { model.connect() }, style = ButtonStyle.Ghost, leading = Icons.Rounded.Refresh, enabled = idle)
    }

    Spacer(Modifier.height(AppTheme.spacing.xl))
    SectionLabel("Транспорты канала")
    Spacer(Modifier.height(AppTheme.spacing.s))
    if (!loaded) {
        AppCard {
            Text(
                "Загрузите настройки с сервера: здесь появятся документы канала. Их можно добавить, убрать или заменить; " +
                    "ключ, порт и выданные ссылки останутся прежними.",
                style = AppTheme.typography.body, color = AppTheme.colors.textSecondary,
            )
        }
        return
    }
    if (!model.editingHasProfile) {
        Note("Профиля этого канала на устройстве нет: изменения применятся на сервере.")
    }
    TransportsEditor(model)
    if (verifying) {
        Spacer(Modifier.height(AppTheme.spacing.l))
        VerifyStep(model)
        return
    }
    if (!model.needsYandexDocument || model.documentUrl.isNotEmpty()) {
        Actions {
            AppButton(if (idle) "Применить на сервере" else "Применяю…", model::next, enabled = idle)
            AppButton("Отмена", onClose, style = ButtonStyle.Ghost, enabled = idle)
        }
    }
}

/** After the channel was changed and checked: what it is now, and the ways to hand it on. */
@Composable
private fun ColumnScope.NodeEditorResult(model: NodeWizardModel, onShowQr: () -> Unit, onSaved: (String) -> Unit) {
    val toaster = LocalToaster.current
    Banner(
        when {
            model.verifiedIp.isNotEmpty() -> "Транспорты изменены. Трафик выходит с адреса ${model.verifiedIp}."
            model.sessionProven -> "Транспорты изменены, нода отвечает по защищённому каналу."
            else -> "Транспорты изменены на сервере."
        },
        Tone.Success, icon = Icons.Rounded.CheckCircle,
    )
    if ((model.verifiedIp.isNotEmpty() || model.sessionProven) && !model.primaryUp) {
        Spacer(Modifier.height(AppTheme.spacing.m))
        Banner(
            "Сейчас работает резервный канал (прямое подключение). Канал через ${model.primaryType?.shortLabel.orEmpty()} ещё не поднялся: " +
                "когда нода попросит проверку Яндекса, OpenFlux покажет её.",
            Tone.Warning, icon = Icons.Rounded.Warning,
        )
    }
    Spacer(Modifier.height(AppTheme.spacing.l))
    AppCard(padding = 0.dp) {
        KeyValueRow("Профиль", model.name.trim().ifEmpty { model.profile?.name.orEmpty() })
        HorizontalRule()
        KeyValueRow("Канал", model.resultChannel)
        HorizontalRule()
        KeyValueRow("Транспорты", NodeTransports.describe(model.transportTypes))
        HorizontalRule()
        KeyValueRow(
            "На этом устройстве", if (model.saved) "профиль обновлён" else "не сохранено",
            valueColor = if (model.saved) AppTheme.colors.success else AppTheme.colors.danger,
        )
    }
    Actions {
        AppButton("Открыть профиль", { model.profile?.let { onSaved(it.id) } ?: onSaved("") }, leading = Icons.Rounded.CheckCircle)
        AppButton("QR для другого устройства", onShowQr, style = ButtonStyle.Secondary, leading = Icons.Rounded.QrCode2, enabled = model.shareLink.isNotEmpty())
        AppButton("Копировать ссылку", {
            model.copyLink()
            toaster.show("Ссылка скопирована", Tone.Success)
        }, style = ButtonStyle.Secondary, leading = Icons.Rounded.ContentCopy, enabled = model.shareLink.isNotEmpty())
    }
    Spacer(Modifier.height(AppTheme.spacing.m))
    Banner("В QR и ссылке лежит ключ канала: передавайте их только тому, кто будет им пользоваться.", Tone.Neutral, icon = Icons.Rounded.Lock)
}

package io.openflux.desktop.ui.profiles

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import io.openflux.desktop.model.AccountKind
import io.openflux.desktop.model.DocumentKind
import io.openflux.desktop.model.NodeDocuments
import io.openflux.desktop.model.Profile
import io.openflux.desktop.model.ProfileSource
import io.openflux.desktop.model.ShareConfig
import io.openflux.desktop.model.TransportType
import io.openflux.desktop.model.isActive
import io.openflux.desktop.model.managed
import io.openflux.desktop.model.profile
import io.openflux.desktop.service.AccountException
import io.openflux.desktop.service.AppContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** A profile being edited; [isNew] until it is saved once. */
data class EditorState(val draft: Profile, val isNew: Boolean, val showProblems: Boolean = false)

/** What the import dialog found in the text it was given. */
sealed interface ImportPreview {
    data object Empty : ImportPreview
    data class Ready(val config: ShareConfig, val source: ProfileSource) : ImportPreview
    data class Invalid(val message: String) : ImportPreview
}

/**
 * What is done with profiles: found in the list, edited field by field, connected, copied, deleted, imported from a
 * link or a code, shared. Where the person goes between those screens is the navigation's business
 * ([io.openflux.desktop.ui.nav.AppNav]); this holds only the state of what they are doing.
 */
class ProfilesScreenModel(private val container: AppContainer) : ScreenModel {
    val profiles = container.profiles
    val connection = container.connection
    val settings = container.settings
    val platform = container.platform
    val accounts = container.accounts

    var query by mutableStateOf("")

    // ---- dialogs over the screens ----

    var importOpen by mutableStateOf(false)
    /** A link the import dialog starts with (opened from outside), else the clipboard's. */
    var importText: String? = null
    var shareFor by mutableStateOf<Profile?>(null)
    var deleteFor by mutableStateOf<Profile?>(null)

    fun filter(list: List<Profile>): List<Profile> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return list
        return list.filter { p ->
            p.name.lowercase().contains(q) || p.summary.lowercase().contains(q) ||
                p.carriers.any { it.value.lowercase().contains(q) }
        }
    }

    // ---- editing the fields of a profile ----

    var editor by mutableStateOf<EditorState?>(null)
        private set

    /** Starts editing profile [id], or a new one when [id] is null. A subscription's locations are not the person's to change. */
    fun beginEdit(id: String?) {
        if (id == null) {
            editor = EditorState(
                Profile(id = profiles.newId(), name = "", transport = TransportType.VYANDEX, createdAt = platform.now()),
                isNew = true,
            )
            return
        }
        val profile = profiles.profiles.value.firstOrNull { it.id == id } ?: return
        if (profile.managed) return
        editor = EditorState(profile, isNew = false)
    }

    /** Drops the draft (the screen was left). */
    fun endEdit() {
        editor = null
    }

    /** The carrier (0 the main one) whose document is being created. */
    var documentBusy by mutableStateOf<Int?>(null)
        private set
    var documentError by mutableStateOf<String?>(null)
        private set
    var documentErrorIndex by mutableStateOf<Int?>(null)
        private set

    /** Creates a document with the carrier's account and puts its link into the carrier. */
    fun createDocumentFor(index: Int) {
        val draft = editor?.draft ?: return
        val type = if (index == 0) draft.transport else draft.extras.getOrNull(index - 1)?.type ?: return
        val doc = DocumentKind.of(type) ?: return
        documentBusy = index
        documentError = null
        screenModelScope.launch {
            try {
                val url = accounts.createDocument(doc, NodeDocuments.fileName(draft.name, "", platform.now()))
                updateDraft { p ->
                    if (index == 0) p.copy(value = url)
                    else p.copy(extras = p.extras.mapIndexed { i, e -> if (i == index - 1) e.copy(value = url) else e })
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if ((e as? AccountException)?.cancelled == true) return@launch
                documentError = e.message ?: "Не получилось создать документ"
                documentErrorIndex = index
            } finally {
                documentBusy = null
            }
        }
    }

    /** Opens new cups.online rooms and puts their packed list into the carrier. */
    fun generateRoomsFor(index: Int) {
        if (editor == null) return
        documentBusy = index
        documentError = null
        screenModelScope.launch {
            try {
                val rooms = platform.newCupsRooms()
                updateDraft { p ->
                    if (index == 0) p.copy(value = rooms)
                    else p.copy(extras = p.extras.mapIndexed { i, e -> if (i == index - 1) e.copy(value = rooms) else e })
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                documentError = e.message ?: "Не получилось создать комнаты"
                documentErrorIndex = index
            } finally {
                documentBusy = null
            }
        }
    }

    fun signIn(kind: AccountKind) {
        screenModelScope.launch { runCatching { accounts.signIn(kind) } }
    }

    fun updateDraft(transform: (Profile) -> Profile) {
        editor = editor?.let { it.copy(draft = transform(it.draft)) }
    }

    /**
     * Saves the draft when it can connect and returns it; otherwise shows what is missing and returns null.
     * The editor is closed by the screen (it goes back).
     */
    fun save(): Profile? {
        val state = editor ?: return null
        val draft = state.draft.copy(name = state.draft.name.trim())
        if (draft.problems().isNotEmpty()) {
            editor = state.copy(showProblems = true)
            return null
        }
        val saved = if (draft.session) draft else draft.copy(extras = emptyList())
        profiles.upsert(saved)
        return saved
    }

    // ---- what is done with a profile ----

    /** A copy of [profile] as a manual one; null for a subscription's location. */
    fun duplicate(profile: Profile): Profile? {
        if (profile.managed) return null
        val copy = profile.copy(id = profiles.newId(), name = "${profile.name} (копия)", createdAt = platform.now(), source = ProfileSource.Manual)
        profiles.upsert(copy)
        return copy
    }

    fun delete(profile: Profile) {
        if (profile.managed) return
        if (connection.state.value.profile?.id == profile.id && connection.state.value.isActive) connection.disconnect()
        profiles.delete(profile.id)
        if (editor?.draft?.id == profile.id) editor = null
        if (settings.settings.value.selectedProfileId == profile.id) settings.update { it.copy(selectedProfileId = null) }
    }

    fun connect(profile: Profile) {
        settings.update { it.copy(selectedProfileId = profile.id) }
        connection.connect(profile)
    }

    fun isRunning(profile: Profile): Boolean {
        val state = connection.state.value
        return state.isActive && state.profile?.id == profile.id
    }

    /** The profile is the person's own node (they run it): it is listed with the nodes and managed there. */
    fun markAsOwnNode(profile: Profile) {
        if (profile.managed) return
        profiles.upsert(profile.copy(source = ProfileSource.Node))
    }

    // ---- import ----

    /** The core reads the link (off the UI thread: on the desktop it runs the core). */
    suspend fun preview(text: String, source: ProfileSource = ProfileSource.Link): ImportPreview {
        if (text.isBlank()) return ImportPreview.Empty
        return try {
            ImportPreview.Ready(container.shareCodec.decode(text), source)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ImportPreview.Invalid(e.message ?: "Не удалось прочитать ссылку")
        }
    }

    fun clipboardText(): String = platform.clipboardText().orEmpty().trim()

    fun qrFromClipboard(): String? = platform.qrFromClipboardImage()

    suspend fun qrFromFile(): Pair<String?, Boolean> {
        val path = platform.pickFile("QR-код OpenFlux", listOf("png", "jpg", "jpeg", "bmp", "gif")) ?: return null to false
        return platform.qrFromFile(path) to true
    }

    suspend fun scanQr(): String? = platform.scanQr()

    fun import(preview: ImportPreview.Ready): Profile {
        val profile = Profile.fromShare(preview.config, profiles.newId(), platform.now(), preview.source)
        profiles.upsert(profile)
        importOpen = false
        return profile
    }

    // ---- sharing ----

    /** The link the core makes for [profile], or why it cannot be shared. */
    suspend fun shareLink(profile: Profile): Result<String> {
        val config = profile.toShare().getOrElse { return Result.failure(it) }
        return try {
            Result.success(container.shareCodec.encode(config))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun copyLink(profile: Profile) {
        screenModelScope.launch { shareLink(profile).onSuccess(::copy) }
    }

    fun copy(text: String) = platform.setClipboardText(text)

    fun qr(text: String) = platform.qrMatrix(text)

    fun newSecret() = platform.newSecret()
}

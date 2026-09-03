package com.obsidian.connect.choose

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.obsidian.connect.archive.PhotoArchive
import com.obsidian.connect.camera.ImageCompressor
import com.obsidian.connect.core.data.AuthRepository
import com.obsidian.connect.core.data.ChoiceRepository
import com.obsidian.connect.core.data.UserRepository
import com.obsidian.connect.core.model.Choice
import com.obsidian.connect.widget.WidgetCaptionStore
import com.obsidian.connect.widget.MomentWidgetUpdater
import com.obsidian.connect.sync.SyncState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChooseViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val authRepository: AuthRepository,
    userRepository: UserRepository,
    private val choiceRepository: ChoiceRepository,
    private val syncState: SyncState,
) : ViewModel() {

    val myUid: String? get() = authRepository.currentUid

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val pairingId: StateFlow<String?> = authRepository.uidFlow
        .flatMapLatest { uid -> if (uid == null) flowOf(null) else userRepository.observe(uid) }
        .map { it?.pairingId }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), null)

    val choices: StateFlow<List<Choice>> = pairingId
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList()) else choiceRepository.observe(id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), emptyList())

    /**
     * Loads and downscales a picked photo, ready for the editor.
     *
     * Stops short of sending: the editor needs the bytes to crop and draw on,
     * and pushing them straight to Firestore would mean uploading a photo
     * nobody had finished with.
     */
    suspend fun prepare(uri: Uri): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(uri).use { it?.readBytes() }
                ?.let {
                    // Judged on screen, never rendered by a widget, so the 720
                    // widget cap would only throw away detail.
                    ImageCompressor.compress(
                        source = it,
                        longEdge = ImageCompressor.DETAIL_LONG_EDGE,
                    )
                }
        }.getOrNull()
    }

    /**
     * Puts the yellow dot out.
     *
     * Called when the deck is actually on screen rather than when the app
     * opens, so a card is only marked seen by someone who has looked at it.
     */
    fun markChoicesSeen(choices: List<Choice>) {
        val uid = authRepository.currentUid ?: return
        val newest = choices
            .filter { it.addedBy != uid }
            .maxOfOrNull { it.createdAtMillis }
            ?: return

        if (newest <= syncState.lastSeenChoiceAt && !WidgetCaptionStore.hasNewChoice(context)) {
            return
        }

        syncState.lastSeenChoiceAt = newest
        viewModelScope.launch {
            WidgetCaptionStore.writeNewChoice(context, false)
            MomentWidgetUpdater.refresh(context)
        }
    }

    fun add(jpeg: ByteArray) {
        val id = pairingId.value ?: return
        val uid = authRepository.currentUid ?: return

        viewModelScope.launch {
            _busy.value = true
            choiceRepository.add(id, uid, jpeg).onSuccess { choiceId ->
                // Kept here from the outset, because the copy in Firestore is
                // erased as soon as the other phone has it.
                withContext(Dispatchers.IO) {
                    PhotoArchive.save(context, jpeg, PhotoArchive.Origin.Sent, choiceId)
                }
            }
            _busy.value = false
        }
    }

    /**
     * Files the other person's cards, then takes the photos off the server.
     *
     * Same bargain as the chat: a picture is uploaded only far enough to reach
     * this phone, and once it is on disk here the copy online is erased. The
     * card itself stays — it is still being voted on.
     *
     * Save first, clear second. Erasing a photo this phone had failed to write
     * would destroy the only remaining copy of it.
     */
    fun archiveIncoming(choices: List<Choice>) {
        val id = pairingId.value ?: return
        val uid = authRepository.currentUid ?: return
        val incoming = choices.filter { it.addedBy != uid && it.hasImage }
        if (incoming.isEmpty()) return

        viewModelScope.launch(Dispatchers.IO) {
            incoming.forEach { choice ->
                val bytes = choice.bytes ?: return@forEach

                val saved = runCatching {
                    PhotoArchive.save(
                        context = context,
                        jpeg = bytes,
                        origin = PhotoArchive.Origin.Received,
                        id = choice.id,
                        takenAtMillis = choice.createdAtMillis,
                    )
                }.getOrNull()

                if (saved?.exists() == true) choiceRepository.clearImage(id, choice.id)
            }
        }
    }

    /**
     * Records a verdict, or takes it back.
     *
     * Voting the same way twice clears it, so a mis-tap is undone by repeating
     * it rather than by looking for an undo that is not there.
     */
    fun judge(choice: Choice, verdict: Int) {
        val id = pairingId.value ?: return
        val uid = authRepository.currentUid ?: return
        val next = if (choice.verdict == verdict) 0 else verdict

        viewModelScope.launch { choiceRepository.judge(id, choice.id, uid, next) }
    }

    fun delete(choice: Choice) {
        val id = pairingId.value ?: return
        viewModelScope.launch { choiceRepository.delete(id, choice.id) }
    }

    private companion object {
        const val STOP_TIMEOUT = 5_000L
    }
}

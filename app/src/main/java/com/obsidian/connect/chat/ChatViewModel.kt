package com.obsidian.connect.chat

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.obsidian.connect.archive.PhotoArchive
import com.obsidian.connect.camera.ImageCompressor
import com.obsidian.connect.core.data.AuthRepository
import com.obsidian.connect.core.data.MessageRepository
import com.obsidian.connect.core.data.PairingRepository
import com.obsidian.connect.core.data.UserRepository
import com.obsidian.connect.core.model.Message
import com.obsidian.connect.core.model.Receipt
import com.obsidian.connect.sync.SyncState
import com.obsidian.connect.widget.MomentWidgetUpdater
import com.obsidian.connect.widget.WidgetCaptionStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChatViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val authRepository: AuthRepository,
    userRepository: UserRepository,
    private val messageRepository: MessageRepository,
    private val pairingRepository: PairingRepository,
    private val syncState: SyncState,
) : ViewModel() {

    val myUid: String? get() = authRepository.currentUid

    private val pairingId: StateFlow<String?> = authRepository.uidFlow
        .flatMapLatest { uid -> if (uid == null) flowOf(null) else userRepository.observe(uid) }
        .map { it?.pairingId }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), null)

    val messages: StateFlow<List<Message>> = pairingId
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList()) else messageRepository.observe(id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), emptyList())

    /**
     * Sends a photo picked from the gallery.
     *
     * Compressed on the way in, same as a moment: an oversized document is
     * rejected outright. A copy is archived here so it survives even though
     * only the last 200 messages are read back.
     */
    fun sendPhoto(uri: Uri) {
        val id = pairingId.value ?: return
        val uid = authRepository.currentUid ?: return

        viewModelScope.launch {
            val compressed = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri).use { it?.readBytes() }
                        ?.let {
                            ImageCompressor.compress(
                                source = it,
                                longEdge = ImageCompressor.DETAIL_LONG_EDGE,
                            )
                        }
                }.getOrNull()
            } ?: return@launch

            messageRepository.sendPhoto(id, uid, compressed).onSuccess { messageId ->
                PhotoArchive.save(
                    context = context,
                    jpeg = compressed,
                    origin = PhotoArchive.Origin.Sent,
                    id = messageId,
                )
            }
        }
    }

    private val partnerId: StateFlow<String?> = pairingId
        .flatMapLatest { id -> if (id == null) flowOf(null) else pairingRepository.observe(id) }
        .map { it?.partnerOf(authRepository.currentUid.orEmpty()) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), null)

    /** Their watermarks, which is what says how far your messages have got. */
    val partnerReceipt: StateFlow<Receipt?> = combine(pairingId, partnerId) { id, partner ->
        id to partner
    }
        .flatMapLatest { (id, partner) ->
            if (id == null || partner == null) {
                flowOf(null)
            } else {
                messageRepository.observeReceipt(id, partner)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), null)

    /**
     * Records that this device has the messages, and that they are on screen.
     *
     * Both watermarks move together here because the chat being open means both
     * are true. Delivery is tracked separately in the sync worker, for messages
     * that arrive while the app is closed.
     */
    fun markProgress(messages: List<Message>) {
        val id = pairingId.value ?: return
        val uid = authRepository.currentUid ?: return
        val newest = messages.maxOfOrNull { it.createdAtMillis } ?: return

        viewModelScope.launch {
            messageRepository.markProgress(
                pairingId = id,
                uid = uid,
                deliveredAtMillis = newest,
                seenAtMillis = newest,
            )
        }
    }

    private val _gifs = MutableStateFlow<List<GifSearch.Gif>>(emptyList())
    val gifs: StateFlow<List<GifSearch.Gif>> = _gifs.asStateFlow()

    private val _gifsLoading = MutableStateFlow(false)
    val gifsLoading: StateFlow<Boolean> = _gifsLoading.asStateFlow()

    private val _saved = MutableStateFlow<List<java.io.File>>(emptyList())
    val saved: StateFlow<List<java.io.File>> = _saved.asStateFlow()

    private var gifSearchJob: Job? = null

    fun refreshSaved() {
        _saved.value = StickerStore.list(context)
    }

    /**
     * Searches GIFs, cancelling whatever was already in flight.
     *
     * Typing produces a request per keystroke otherwise, and results arriving
     * out of order would leave the grid showing an earlier query.
     */
    fun searchGifs(term: String) {
        gifSearchJob?.cancel()
        gifSearchJob = viewModelScope.launch {
            _gifsLoading.value = true
            delay(GIF_DEBOUNCE_MS)
            _gifs.value = if (term.isBlank()) GifSearch.trending() else GifSearch.search(term)
            _gifsLoading.value = false
        }
    }

    fun sendGif(gif: GifSearch.Gif) {
        val id = pairingId.value ?: return
        val uid = authRepository.currentUid ?: return
        viewModelScope.launch { messageRepository.sendGif(id, uid, gif.sendUrl) }
    }

    /** Sends something already in the saved collection. */
    fun sendSaved(file: java.io.File) {
        val id = pairingId.value ?: return
        val uid = authRepository.currentUid ?: return

        viewModelScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching { file.readBytes() }.getOrNull()
            } ?: return@launch

            messageRepository.sendPhoto(id, uid, bytes).onSuccess { messageId ->
                PhotoArchive.save(context, bytes, PhotoArchive.Origin.Sent, messageId)
            }
        }
    }

    /** Adds an image to the saved collection without sending it. */
    fun saveSticker(uri: Uri) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri).use { it?.readBytes() }
                        ?.let {
                            StickerStore.save(
                                context,
                                ImageCompressor.compress(
                                    source = it,
                                    longEdge = ImageCompressor.DETAIL_LONG_EDGE,
                                ),
                            )
                        }
                }
            }
            refreshSaved()
        }
    }

    private val recorder by lazy { VoiceRecorder(context) }

    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording.asStateFlow()

    fun startRecording(): Boolean {
        val started = recorder.start()
        _recording.value = started
        return started
    }

    /**
     * Ends the recording and sends it.
     *
     * A clip under about a second is dropped rather than sent — that is almost
     * always a mis-tap, and a stray blip is more annoying to receive than a
     * lost recording is to redo.
     */
    fun stopRecordingAndSend() {
        _recording.value = false
        val clip = recorder.stop() ?: return

        val id = pairingId.value ?: return
        val uid = authRepository.currentUid ?: return

        viewModelScope.launch {
            messageRepository.sendAudio(id, uid, clip.bytes, clip.durationMs)
        }
    }

    fun cancelRecording() {
        recorder.cancel()
        _recording.value = false
    }

    override fun onCleared() {
        super.onCleared()
        // A recorder left running holds the microphone open for the whole app.
        recorder.cancel()
    }

    fun send(text: String) {
        val id = pairingId.value ?: return
        val uid = authRepository.currentUid ?: return
        if (text.isBlank()) return

        viewModelScope.launch { messageRepository.send(id, uid, text) }
    }

    /**
     * Files any photos the other person sent into the archive.
     *
     * Done as they are read rather than on receipt, because messages arrive
     * through a listener that has no other reason to touch disk. Saving is
     * idempotent, so re-reading the conversation costs nothing.
     */
    fun archiveIncoming(messages: List<Message>) {
        val uid = authRepository.currentUid ?: return
        val incoming = messages.filter { it.senderId != uid && it.hasImage }
        if (incoming.isEmpty()) return

        viewModelScope.launch(Dispatchers.IO) {
            incoming.forEach { message ->
                message.bytes?.let {
                    PhotoArchive.save(
                        context = context,
                        jpeg = it,
                        origin = PhotoArchive.Origin.Received,
                        id = message.id,
                        takenAtMillis = message.createdAtMillis,
                    )
                }
            }
        }
    }

    /**
     * Marks the conversation seen and clears the watch face's dot.
     *
     * Called when the chat is actually on screen rather than when the app
     * opens, so opening the camera tab does not silently mark unread messages
     * as read.
     */
    fun markRead() {
        val newest = messages.value.maxOfOrNull { it.createdAtMillis } ?: return
        if (newest <= syncState.lastReadMessageAt) return

        syncState.lastReadMessageAt = newest
        viewModelScope.launch {
            WidgetCaptionStore.writeUnread(context, false)
            MomentWidgetUpdater.refresh(context)
        }
    }

    private companion object {
        const val STOP_TIMEOUT = 5_000L

        /** Long enough that typing does not fire a request per keystroke. */
        const val GIF_DEBOUNCE_MS = 350L
    }
}

package com.obsidian.connect.chat

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.obsidian.connect.archive.PhotoArchive
import com.obsidian.connect.camera.ImageCompressor
import com.obsidian.connect.core.data.AuthRepository
import com.obsidian.connect.core.data.ChoiceRepository
import com.obsidian.connect.core.data.JamRepository
import com.obsidian.connect.core.data.MessageRepository
import com.obsidian.connect.core.data.PairingRepository
import com.obsidian.connect.core.data.UserRepository
import com.obsidian.connect.core.model.Choice
import com.obsidian.connect.core.model.ChoiceRef
import com.obsidian.connect.core.model.JamSession
import com.obsidian.connect.core.model.Message
import com.obsidian.connect.core.model.Receipt
import com.obsidian.connect.jam.JamPlayerHolder
import com.obsidian.connect.sync.SyncState
import com.obsidian.connect.sync.UnreadState
import com.obsidian.connect.ui.theme.ChatTheme
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlinx.coroutines.flow.flow

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChatViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val authRepository: AuthRepository,
    private val userRepository: UserRepository,
    private val messageRepository: MessageRepository,
    private val choiceRepository: ChoiceRepository,
    private val jamRepository: JamRepository,
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
    /**
     * Loads and downscales a picked photo, ready for the editor.
     *
     * Stops short of sending: the editor needs the bytes to crop and draw on,
     * and going straight to Firestore would upload a photo nobody had
     * finished with.
     */
    suspend fun prepare(uri: Uri): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(uri).use { it?.readBytes() }
                ?.let {
                    ImageCompressor.compress(
                        source = it,
                        longEdge = ImageCompressor.DETAIL_LONG_EDGE,
                    )
                }
        }.getOrNull()
    }

    /**
     * Sends a photo that has already been through the editor.
     *
     * A copy is archived here so it survives even though only the last 200
     * messages are read back.
     */
    fun sendPhoto(jpeg: ByteArray, replyTo: Message? = null) {
        val id = pairingId.value ?: return
        val uid = authRepository.currentUid ?: return

        viewModelScope.launch {
            messageRepository.sendPhoto(id, uid, jpeg, replyTo = replyTo)
                .onSuccess { messageId ->
                    PhotoArchive.save(
                        context = context,
                        jpeg = jpeg,
                        origin = PhotoArchive.Origin.Sent,
                        id = messageId,
                    )
                }
        }
    }

    /**
     * Sends several photos as one batch.
     *
     * Each is still its own document - a document is capped at a megabyte and
     * a photo nearly fills one - but they share a batch id so the chat can draw
     * them together.
     *
     * Sent oldest first and one at a time rather than in parallel. The order
     * they were picked in is the order they should appear in, and firing them
     * at once puts that at the mercy of which write finishes first.
     */
    fun sendPhotos(uris: List<android.net.Uri>) {
        val id = pairingId.value ?: return
        val uid = authRepository.currentUid ?: return
        if (uris.isEmpty()) return

        val album = if (uris.size > 1) java.util.UUID.randomUUID().toString() else ""

        viewModelScope.launch {
            uris.forEach { uri ->
                val jpeg = prepare(uri) ?: return@forEach

                messageRepository.sendPhoto(
                    pairingId = id,
                    senderId = uid,
                    jpeg = jpeg,
                    albumId = album,
                ).onSuccess { messageId ->
                    PhotoArchive.save(
                        context = context,
                        jpeg = jpeg,
                        origin = PhotoArchive.Origin.Sent,
                        id = messageId,
                    )
                }
            }
        }
    }

    private val partnerId: StateFlow<String?> = pairingId
        .flatMapLatest { id -> if (id == null) flowOf(null) else pairingRepository.observe(id) }
        .map { it?.partnerOf(authRepository.currentUid.orEmpty()) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), null)

    /**
     * The conversation's look, as chosen by whichever of you last changed it.
     *
     * Read from the pairing rather than from this device, so repainting the
     * chat repaints it for both.
     */
    val chatTheme: StateFlow<ChatTheme> = pairingId
        .flatMapLatest { id -> if (id == null) flowOf(null) else pairingRepository.observe(id) }
        .map { ChatTheme.from(it?.chatTheme.orEmpty()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), ChatTheme.Default)

    /**
     * The jam, so the chat can stop it without going back to the jam screen.
     *
     * Leaving that screen no longer stops the music, which is the point - but
     * it does mean the pause control has to exist somewhere you can reach.
     */
    val jamSession: StateFlow<JamSession?> = pairingId
        .flatMapLatest { id -> if (id == null) flowOf(null) else jamRepository.observe(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), null)

    fun setJamPlaying(playing: Boolean) {
        val id = pairingId.value ?: return
        val uid = authRepository.currentUid ?: return

        viewModelScope.launch {
            jamRepository.update(id, uid, playing, JamPlayerHolder.lastPositionMs)
        }
    }

    /**
     * Whether they have the app in front of them.
     *
     * Recomputed on a slow pulse as well as on change, because going away is
     * the absence of a heartbeat - nothing arrives to announce it.
     */
    val partnerOnline: StateFlow<Boolean> = partnerId
        .flatMapLatest { id -> if (id == null) flowOf(null) else userRepository.observe(id) }
        .combine(presenceTicker()) { user, now -> user?.isOnline(now) == true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), false)

    private fun presenceTicker(): Flow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(20_000)
        }
    }

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

    private val _pendingVoice = MutableStateFlow<VoiceRecorder.Recording?>(null)
    val pendingVoice: StateFlow<VoiceRecorder.Recording?> = _pendingVoice.asStateFlow()

    /**
     * Ends the recording and holds it for review.
     *
     * Not sent straight away. A voice note cannot be skimmed before it goes the
     * way a typed message can be re-read, so the one chance to catch a bad take
     * is before it leaves.
     *
     * A clip under about a second is dropped outright — that is almost always
     * a mis-tap, and offering to review a blip is worse than discarding it.
     */
    fun stopRecording() {
        _recording.value = false
        _pendingVoice.value = recorder.stop()
    }

    fun sendPendingVoice() {
        val clip = _pendingVoice.value ?: return
        val id = pairingId.value ?: return
        val uid = authRepository.currentUid ?: return

        _pendingVoice.value = null
        viewModelScope.launch {
            messageRepository.sendAudio(id, uid, clip.bytes, clip.durationMs)
        }
    }

    fun discardPendingVoice() {
        _pendingVoice.value = null
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

    /**
     * Sends, optionally answering a message or a card from the choose deck.
     *
     * When it is about a card, the link is written at both ends: the message
     * remembers which card it was about, and the card gains an entry pointing
     * back. Either end can then be walked to the other, which is the whole
     * point - a decision keeps everything ever said about it.
     */
    fun send(text: String, replyTo: Message? = null, choice: Choice? = null) {
        val id = pairingId.value ?: return
        val uid = authRepository.currentUid ?: return
        if (text.isBlank()) return

        viewModelScope.launch {
            messageRepository.send(
                pairingId = id,
                senderId = uid,
                text = text,
                replyTo = replyTo,
                choiceRefId = choice?.id.orEmpty(),
            ).onSuccess { messageId ->
                val card = choice ?: return@onSuccess
                choiceRepository.addRef(
                    pairingId = id,
                    choiceId = card.id,
                    ref = ChoiceRef(
                        messageId = messageId,
                        byUid = uid,
                        text = text.trim().take(REF_LENGTH),
                        atMillis = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }

    /**
     * Files any photos the other person sent, then takes them off the server.
     *
     * This is the whole transfer-only idea in one place. A photo goes up only
     * far enough to reach the other phone; the moment this device has written
     * its own copy, the copy in Firestore is erased and every later read comes
     * off local disk. Nothing of yours accumulates online.
     *
     * The order is not negotiable — save first, clear second. Clearing a photo
     * this phone had not managed to write would destroy it outright, and there
     * is no second copy anywhere to recover it from.
     *
     * Saving is idempotent, so re-reading the conversation costs nothing.
     */
    fun archiveIncoming(messages: List<Message>) {
        val id = pairingId.value ?: return
        val uid = authRepository.currentUid ?: return
        val incoming = messages.filter { it.senderId != uid && it.hasImage }
        if (incoming.isEmpty()) return

        viewModelScope.launch(Dispatchers.IO) {
            incoming.forEach { message ->
                val bytes = message.bytes ?: return@forEach

                val saved = runCatching {
                    PhotoArchive.save(
                        context = context,
                        jpeg = bytes,
                        origin = PhotoArchive.Origin.Received,
                        id = message.id,
                        takenAtMillis = message.createdAtMillis,
                    )
                }.getOrNull()

                if (saved?.exists() == true) {
                    messageRepository.clearImage(id, message.id)
                }
            }
        }
    }

    /**
     * Keeps a message, for you.
     *
     * The other person's stars are their own; this only ever adds or removes
     * your own uid.
     */
    fun toggleStar(message: Message) {
        val id = pairingId.value ?: return
        val uid = authRepository.currentUid ?: return

        viewModelScope.launch {
            messageRepository.setStarred(
                pairingId = id,
                messageId = message.id,
                uid = uid,
                starred = !message.isStarredBy(uid),
            )
        }
    }

    /**
     * Withdraws a message from both sides of the conversation.
     *
     * Guarded here so the option is never offered for someone else's message,
     * and guarded again in the security rules so it cannot be taken anyway.
     */
    fun delete(message: Message) {
        val id = pairingId.value ?: return
        val uid = authRepository.currentUid ?: return
        if (message.senderId != uid) return

        viewModelScope.launch { messageRepository.delete(id, message.id) }
    }

    /**
     * Marks the conversation seen and clears the watch face's dot.
     *
     * Called when the chat is actually on screen rather than when the app
     * opens, so opening the camera tab does not silently mark unread messages
     * as read.
     */
    private var lastTypingWrite = 0L

    /**
     * Tells them you are writing something.
     *
     * Throttled hard. Typing fires per keystroke and this is a network write -
     * once every few seconds says the same thing for a fraction of the cost,
     * and the other end treats the note as good for longer than the gap.
     */
    fun noteTyping() {
        val id = pairingId.value ?: return
        val uid = authRepository.currentUid ?: return

        val now = System.currentTimeMillis()
        if (now - lastTypingWrite < TYPING_THROTTLE_MS) return
        lastTypingWrite = now

        viewModelScope.launch { messageRepository.setTyping(id, uid) }
    }

    fun markRead() {
        val newest = messages.value.maxOfOrNull { it.createdAtMillis } ?: return
        if (newest <= syncState.lastReadMessageAt) return

        syncState.lastReadMessageAt = newest
        UnreadState.set(false)
        viewModelScope.launch {
            WidgetCaptionStore.writeUnread(context, false)
            MomentWidgetUpdater.refresh(context)
        }
    }

    private companion object {
        const val STOP_TIMEOUT = 5_000L

        /** Long enough that typing does not fire a request per keystroke. */
        const val GIF_DEBOUNCE_MS = 350L

        /** Comfortably shorter than the window the other end trusts it for. */
        const val TYPING_THROTTLE_MS = 3_000L

        /** A card's reference list is a set of reminders, not a transcript. */
        const val REF_LENGTH = 120
    }
}

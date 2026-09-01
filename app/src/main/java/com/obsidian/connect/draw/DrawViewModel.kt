package com.obsidian.connect.draw

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.obsidian.connect.core.data.AuthRepository
import com.obsidian.connect.core.data.StrokeRepository
import com.obsidian.connect.core.data.UserRepository
import com.obsidian.connect.core.model.Stroke
import com.obsidian.connect.core.model.StrokePoint
import com.obsidian.connect.sync.SyncState
import com.obsidian.connect.widget.StrokeRasterizer
import com.obsidian.connect.widget.WatchWidgetProvider
import com.obsidian.connect.widget.WidgetCaptionStore
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DrawViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val authRepository: AuthRepository,
    userRepository: UserRepository,
    private val strokeRepository: StrokeRepository,
    private val syncState: SyncState,
) : ViewModel() {

    private val pairingId: StateFlow<String?> = authRepository.uidFlow
        .flatMapLatest { uid -> if (uid == null) flowOf(null) else userRepository.observe(uid) }
        .map { it?.pairingId }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), null)

    val strokes: StateFlow<List<Stroke>> = pairingId
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList()) else strokeRepository.observe(id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), emptyList())

    init {
        // Declared after `strokes` on purpose: Kotlin runs initialisers in
        // declaration order, so an init block above it would collect a property
        // that has not been assigned yet.
        viewModelScope.launch {
            strokes.collect { current ->
                if (current.isEmpty()) return@collect

                // Keep the rasterised copy current so the overlay opens
                // instantly from a home screen with nothing to fetch.
                withContext(Dispatchers.Default) {
                    StrokeRasterizer.render(context, current)
                }

                // Looking at the canvas counts as having seen it, so the blue
                // light goes out rather than announcing something already read.
                val uid = authRepository.currentUid
                val newest = current.filter { it.senderId != uid }
                    .maxOfOrNull { it.createdAtMillis }
                if (newest != null && newest > syncState.lastSeenStrokeAt) {
                    syncState.lastSeenStrokeAt = newest
                }
                WidgetCaptionStore.writeNewDrawing(context, false)
                WatchWidgetProvider.refreshAll(context)
            }
        }
    }

    private val _color = MutableStateFlow(DrawPalette.default)
    val color: StateFlow<Long> = _color.asStateFlow()

    private val _width = MutableStateFlow(DEFAULT_WIDTH)
    val width: StateFlow<Float> = _width.asStateFlow()

    val myUid: String? get() = authRepository.currentUid

    fun selectColor(value: Long) {
        _color.value = value
    }

    fun selectWidth(value: Float) {
        _width.value = value
    }

    /**
     * Commits a finished stroke.
     *
     * Sent on lift rather than while dragging. A document per touch event would
     * mean hundreds of writes for one scribble, and the free tier allows 20,000
     * a day — a couple of minutes of drawing would exhaust it.
     */
    fun commit(points: List<StrokePoint>) {
        val id = pairingId.value ?: return
        val uid = authRepository.currentUid ?: return
        if (points.size < 2) return

        viewModelScope.launch {
            strokeRepository.add(
                pairingId = id,
                senderId = uid,
                points = points.thinned(),
                color = _color.value,
                width = _width.value,
            )
        }
    }

    fun clear() {
        val id = pairingId.value ?: return
        viewModelScope.launch { strokeRepository.clear(id) }
    }

    private companion object {
        const val STOP_TIMEOUT = 5_000L
        const val DEFAULT_WIDTH = 6f
    }
}

/**
 * Drops points that add nothing to the shape.
 *
 * A finger dragged across the screen produces a sample every few milliseconds,
 * far more than the line needs. Since every point is stored in the document and
 * Firestore caps documents at 1MiB, a slow deliberate stroke could otherwise be
 * rejected outright.
 */
private fun List<StrokePoint>.thinned(
    minimumSpacing: Float = 0.004f,
    cap: Int = 300,
): List<StrokePoint> {
    if (size <= 2) return this

    val kept = ArrayList<StrokePoint>(size.coerceAtMost(cap))
    kept.add(first())

    for (point in drop(1)) {
        val last = kept.last()
        val dx = point.x - last.x
        val dy = point.y - last.y
        if (dx * dx + dy * dy >= minimumSpacing * minimumSpacing) kept.add(point)
        if (kept.size >= cap - 1) break
    }

    // The final point always survives, so the line ends where the finger did
    // rather than at the last sample that happened to clear the threshold.
    if (kept.last() != last()) kept.add(last())
    return kept
}

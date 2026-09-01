package com.obsidian.connect.draw

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.obsidian.connect.core.data.AuthRepository
import com.obsidian.connect.core.data.StrokeRepository
import com.obsidian.connect.core.data.UserRepository
import com.obsidian.connect.core.model.Stroke
import com.obsidian.connect.sync.SyncState
import com.obsidian.connect.widget.DrawingBubble
import com.obsidian.connect.widget.WatchWidgetProvider
import com.obsidian.connect.widget.WidgetCaptionStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Loads the shared canvas for the overlay.
 *
 * A one-shot read rather than a listener. This is opened from a home screen to
 * glance at something and dismissed a second later; Firestore's local cache
 * answers immediately, and subscribing for that would cost a live socket for
 * no benefit.
 */
@HiltViewModel
class DrawingOverlayViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val authRepository: AuthRepository,
    private val userRepository: UserRepository,
    private val strokeRepository: StrokeRepository,
    private val syncState: SyncState,
) : ViewModel() {

    private val _strokes = MutableStateFlow<List<Stroke>>(emptyList())
    val strokes: StateFlow<List<Stroke>> = _strokes.asStateFlow()

    init {
        load()
    }

    private fun load() {
        val uid = authRepository.currentUid ?: return
        viewModelScope.launch {
            val pairingId = runCatching { userRepository.get(uid)?.pairingId }.getOrNull() ?: return@launch
            val loaded = runCatching { strokeRepository.latest(pairingId) }.getOrDefault(emptyList())
            _strokes.value = loaded

            markSeen(loaded, uid)
        }
    }

    /**
     * Opening the overlay counts as having seen the drawing, so the blue light
     * goes out now rather than at the next sync.
     */
    private fun markSeen(loaded: List<Stroke>, uid: String) {
        loaded.filter { it.senderId != uid }
            .maxOfOrNull { it.createdAtMillis }
            ?.let { newest ->
                if (newest > syncState.lastSeenStrokeAt) syncState.lastSeenStrokeAt = newest
            }

        WidgetCaptionStore.writeNewDrawing(context, false)
        DrawingBubble.hide(context)
        WatchWidgetProvider.refreshAll(context)
    }
}

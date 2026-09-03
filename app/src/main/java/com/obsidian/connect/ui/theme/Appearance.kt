package com.obsidian.connect.ui.theme

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/** Follow the phone, or override it. */
enum class ThemeMode { System, Light, Dark }

/** The four colours a conversation is made of. */
data class ChatColors(
    val mine: Color,
    val onMine: Color,
    val theirs: Color,
    val onTheirs: Color,
    val background: Color,
)

/**
 * A look for the chat, separate from the rest of the app.
 *
 * Each carries a light and a dark version rather than one set of colours,
 * because a palette that reads as calm on white is usually glaring on black.
 * [Default] returns null and lets the ordinary Material colours through, so
 * "no theme" stays a real choice rather than another opinion.
 */
enum class ChatTheme(val label: String) {
    Default("Default"),
    Dusk("Dusk"),
    Forest("Forest"),
    Rose("Rose"),
    Mono("Mono");

    companion object {
        /** Unknown names fall back rather than crash - a later build may add some. */
        fun from(name: String): ChatTheme =
            entries.firstOrNull { it.name == name } ?: Default
    }

    fun colors(dark: Boolean): ChatColors? = when (this) {
        Default -> null

        Dusk -> if (dark) {
            ChatColors(
                mine = Color(0xFF3B4A7A), onMine = Color(0xFFE9EDFF),
                theirs = Color(0xFF23252F), onTheirs = Color(0xFFDDE0EA),
                background = Color(0xFF14151B),
            )
        } else {
            ChatColors(
                mine = Color(0xFFD8DEFF), onMine = Color(0xFF1B2450),
                theirs = Color(0xFFEDEEF3), onTheirs = Color(0xFF232634),
                background = Color(0xFFF6F7FC),
            )
        }

        Forest -> if (dark) {
            ChatColors(
                mine = Color(0xFF2F5347), onMine = Color(0xFFE2F3EB),
                theirs = Color(0xFF212722), onTheirs = Color(0xFFDCE5DE),
                background = Color(0xFF12160F),
            )
        } else {
            ChatColors(
                mine = Color(0xFFD3EBDF), onMine = Color(0xFF17372B),
                theirs = Color(0xFFEDF0EA), onTheirs = Color(0xFF232A24),
                background = Color(0xFFF4F8F1),
            )
        }

        Rose -> if (dark) {
            ChatColors(
                mine = Color(0xFF6A3247), onMine = Color(0xFFFFE3EC),
                theirs = Color(0xFF2A2126), onTheirs = Color(0xFFEBDDE3),
                background = Color(0xFF190F14),
            )
        } else {
            ChatColors(
                mine = Color(0xFFFFD9E4), onMine = Color(0xFF4B1B2C),
                theirs = Color(0xFFF3EEF0), onTheirs = Color(0xFF2E2429),
                background = Color(0xFFFDF5F8),
            )
        }

        Mono -> if (dark) {
            ChatColors(
                mine = Color(0xFF3A3A3A), onMine = Color(0xFFF2F2F2),
                theirs = Color(0xFF1E1E1E), onTheirs = Color(0xFFDCDCDC),
                background = Color(0xFF0E0E0E),
            )
        } else {
            ChatColors(
                mine = Color(0xFFE4E4E4), onMine = Color(0xFF1A1A1A),
                theirs = Color(0xFFF3F3F3), onTheirs = Color(0xFF262626),
                background = Color(0xFFFBFBFB),
            )
        }
    }
}

/**
 * How dark this phone draws the app, remembered.
 *
 * Personal, unlike the chat theme. Dark mode is about your screen and your
 * eyes - often about the time of day - and pushing your choice onto the other
 * person's phone would be deciding something that was never yours to decide.
 * The chat's palette is the opposite, and lives on the pairing.
 *
 *
 * Compose state rather than a flow, and a plain object rather than something
 * injected: the theme is read from every activity in the app, including ones
 * with no view model of their own, and every one of them has to repaint the
 * instant the setting changes.
 *
 * On disk as well as in memory, or the choice would last only as long as the
 * process.
 */
object AppearanceStore {

    private const val PREFS = "connect_appearance"
    private const val KEY_MODE = "theme_mode"

    private var prefs: android.content.SharedPreferences? = null

    var themeMode by mutableStateOf(ThemeMode.System)
        private set

    /** Called once, before anything can paint. */
    fun init(context: Context) {
        val store = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = store

        themeMode = runCatching {
            ThemeMode.valueOf(store.getString(KEY_MODE, null) ?: ThemeMode.System.name)
        }.getOrDefault(ThemeMode.System)

    }

    fun setMode(mode: ThemeMode) {
        themeMode = mode
        prefs?.edit()?.putString(KEY_MODE, mode.name)?.apply()
    }
}

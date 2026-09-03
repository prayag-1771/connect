package com.obsidian.connect.jam

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebChromeClient
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader

/**
 * A YouTube player this app can actually steer.
 *
 * The IFrame API rather than any Android SDK, because the official player
 * library was retired and nothing replaced it. A WebView loading YouTube's own
 * player script is the supported route, and it is the only one that exposes
 * seek and current-time - which is the entire basis of keeping two phones in
 * step.
 *
 * The page is served over https by an asset loader rather than handed to the
 * WebView as data. The IFrame API verifies the embedding origin, and a page
 * with no real address does not have one it will accept.
 */
@SuppressLint("SetJavaScriptEnabled")
class JamPlayer(
    context: Context,
    private val onReady: () -> Unit,
    private val onStateChange: (playing: Boolean) -> Unit,
    private val onPositionMs: (Long) -> Unit,
    private val onError: (String) -> Unit = {},
) {
    val view: WebView = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false

        // Serves the player page over a genuine https origin.
        //
        // It was previously handed to the WebView as raw data with youtube.com
        // claimed as the base URL, which is not an origin YouTube will accept
        // for embedding - it refused every video with error 152, indis-
        // tinguishable from the video itself being blocked. An asset loader
        // gives the page a real address that matches what the player is told.
        val assets = WebViewAssetLoader.Builder()
            .setDomain(ASSET_DOMAIN)
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .build()

        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? = assets.shouldInterceptRequest(request.url)
        }

        // Without a WebChromeClient a WebView will not play HTML5 video at all.
        // It is not optional decoration: the video element needs it to obtain a
        // surface, and its absence fails silently with a black frame.
        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                Log.d(TAG, "player: ${message.message()} @${message.lineNumber()}")
                return true
            }
        }
        addJavascriptInterface(Bridge(), "Android")
        loadUrl("$ORIGIN/assets/player.html")
    }

    private inner class Bridge {
        @JavascriptInterface
        fun ready() = view.post { onReady() }

        @JavascriptInterface
        fun state(playing: Boolean) = view.post { onStateChange(playing) }

        @JavascriptInterface
        fun position(seconds: Double) = view.post { onPositionMs((seconds * 1000).toLong()) }

        @JavascriptInterface
        fun failed(code: Int) = view.post { onError(explain(code)) }
    }

    fun load(videoId: String, startMs: Long, play: Boolean) {
        run("load('$videoId', ${startMs / 1000.0}, $play)")
    }

    fun play() = run("api && (api.unMute(), api.setVolume(100), api.playVideo())")

    fun pause() = run("api && api.pauseVideo()")

    fun seekTo(positionMs: Long) = run("api && api.seekTo(${positionMs / 1000.0}, true)")

    /** Asks the page where it is; the answer comes back through the bridge. */
    fun requestPosition() = run("report()")

    fun release() {
        runCatching {
            view.loadUrl("about:blank")
            view.destroy()
        }
    }

    /**
     * What a YouTube error code actually means to somebody holding a phone.
     *
     * The 101/150/152 family means the video would not play in an embed. That
     * is usually the owner having disabled it - but it is also what YouTube
     * returns when it cannot verify the embedding origin, which is what this
     * player was doing wrong before it was served from a real address. Worth
     * remembering if it ever comes back for every video at once, because it
     * looks like a content problem and is not.
     */
    private fun explain(code: Int): String = when (code) {
        101, 150, 152 -> "YouTube would not play that one in the app. If other " +
            "links work, the owner has blocked embedding for that video - try " +
            "another upload of the same song."
        100 -> "That video does not exist or was removed."
        2 -> "That link was not something YouTube recognised."
        5 -> "The player could not handle that video."
        else -> "YouTube would not play that one (error $code)."
    }

    private fun run(js: String) {
        view.post { view.evaluateJavascript(js, null) }
    }

    private companion object {
        const val TAG = "JamPlayer"
        const val ASSET_DOMAIN = "appassets.androidplatform.net"
        const val ORIGIN = "https://$ASSET_DOMAIN"

    }
}

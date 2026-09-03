package com.obsidian.connect.jam

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebChromeClient
import android.webkit.WebViewClient

/**
 * A YouTube player this app can actually steer.
 *
 * The IFrame API rather than any Android SDK, because the official player
 * library was retired and nothing replaced it. A WebView loading YouTube's own
 * player script is the supported route, and it is the only one that exposes
 * seek and current-time - which is the entire basis of keeping two phones in
 * step.
 *
 * The page is loaded with a youtube.com base URL rather than about:blank. The
 * IFrame API checks the embedding origin, and a null origin is refused.
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

        webViewClient = WebViewClient()

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
        loadDataWithBaseURL(ORIGIN, PAGE, "text/html", "utf-8", null)
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
     * 101 and 150 are the same refusal reported twice over the years, and 152
     * is the current one: the video is not allowed to play inside another app.
     * A great many official music videos are published that way, so this is the
     * common case rather than an edge case, and saying so is more use than a
     * number.
     */
    private fun explain(code: Int): String = when (code) {
        101, 150, 152 -> "That video will not play outside YouTube. The owner " +
            "blocked embedding - most official music videos are. Try another " +
            "upload of the same song."
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
        const val ORIGIN = "https://www.youtube.com"

        /**
         * Deliberately minimal.
         *
         * Everything the app needs is play, pause, seek and the current time.
         * Anything else - related videos, sharing, the branding - is chrome
         * that only gets in the way of two people listening to one song.
         */
        val PAGE = """
            <!DOCTYPE html>
            <html>
              <head>
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <style>
                  html, body { margin:0; padding:0; background:#000; height:100%; }
                  #player { width:100%; height:100%; }
                </style>
              </head>
              <body>
                <div id="player"></div>
                <script src="https://www.youtube.com/iframe_api"></script>
                <script>
                  var api = null;

                  function onYouTubeIframeAPIReady() {
                    api = new YT.Player('player', {
                      height: '100%',
                      width: '100%',
                      playerVars: {
                        playsinline: 1,
                        controls: 1,
                        rel: 0,
                        modestbranding: 1
                      },
                      events: {
                        onReady: function () {
                          // Autoplay is only permitted muted. The player starts
                          // that way whether or not it was asked to, so sound
                          // has to be turned back on by hand - otherwise the
                          // video runs correctly and silently, which looks
                          // exactly like nothing happening.
                          api.unMute();
                          api.setVolume(100);
                          console.log('ready, muted=' + api.isMuted());
                          Android.ready();
                        },
                        onError: function (e) {
                          console.log('player error ' + e.data);
                          Android.failed(e.data);
                        },
                        onStateChange: function (e) {
                          if (e.data === YT.PlayerState.PLAYING) Android.state(true);
                          if (e.data === YT.PlayerState.PAUSED) Android.state(false);
                          if (e.data === YT.PlayerState.ENDED) Android.state(false);
                        }
                      }
                    });
                  }

                  function load(id, startSeconds, play) {
                    if (!api) return;
                    if (play) {
                      api.loadVideoById(id, startSeconds);
                    } else {
                      api.cueVideoById(id, startSeconds);
                    }
                    // Loading a new video can re-mute it, so this is repeated
                    // rather than relied on from onReady alone.
                    api.unMute();
                    api.setVolume(100);
                    console.log('load ' + id + ' muted=' + api.isMuted());
                  }

                  function report() {
                    if (!api || !api.getCurrentTime) return;
                    Android.position(api.getCurrentTime());
                  }
                </script>
              </body>
            </html>
        """.trimIndent()
    }
}

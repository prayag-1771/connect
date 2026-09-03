package com.obsidian.connect.jam

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebView
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
) {
    val view: WebView = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        webViewClient = WebViewClient()
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
    }

    fun load(videoId: String, startMs: Long, play: Boolean) {
        run("load('$videoId', ${startMs / 1000.0}, $play)")
    }

    fun play() = run("api && api.playVideo()")

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

    private fun run(js: String) {
        view.post { view.evaluateJavascript(js, null) }
    }

    private companion object {
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
                        controls: 0,
                        rel: 0,
                        modestbranding: 1
                      },
                      events: {
                        onReady: function () { Android.ready(); },
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

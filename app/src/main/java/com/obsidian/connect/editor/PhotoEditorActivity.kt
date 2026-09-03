package com.obsidian.connect.editor

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContract
import androidx.fragment.app.FragmentActivity
import com.obsidian.connect.ui.theme.ConnectTheme
import java.io.File

/**
 * The crop-and-draw step, as its own screen.
 *
 * Previously a dialog laid over the chat, which did not work: a dialog window
 * does not receive the activity's window insets, so padding for the system
 * bars resolved to nothing and the confirm button sat off the bottom of the
 * display. An activity gets those insets for free, covers the app's bottom
 * navigation bar rather than sitting under it, and gives the back gesture
 * something sensible to do.
 *
 * Photos are passed by file path, not in the intent. An intent crosses a
 * Binder transaction with a hard limit well under the size these images can
 * reach, and a photo large enough to be worth editing is exactly the one that
 * would fail.
 */
@Suppress("DEPRECATION")
class PhotoEditorActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val path = intent.getStringExtra(EXTRA_INPUT)
        val source = path?.let { File(it) }
        val jpeg = source?.takeIf { it.exists() }?.readBytes()

        if (jpeg == null) {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }

        setContent {
            ConnectTheme {
                PhotoEditorScreen(
                    jpeg = jpeg,
                    onCancel = {
                        setResult(Activity.RESULT_CANCELED)
                        finish()
                    },
                    onConfirm = { edited ->
                        val out = File(cacheDir, "edited_${System.currentTimeMillis()}.jpg")
                        runCatching { out.writeBytes(edited) }
                            .onSuccess {
                                setResult(
                                    Activity.RESULT_OK,
                                    Intent().putExtra(EXTRA_OUTPUT, out.absolutePath),
                                )
                            }
                            .onFailure { setResult(Activity.RESULT_CANCELED) }
                        finish()
                    },
                )
            }
        }
    }

    companion object {
        private const val EXTRA_INPUT = "input_path"
        private const val EXTRA_OUTPUT = "output_path"

        fun intent(context: Context, inputPath: String): Intent =
            Intent(context, PhotoEditorActivity::class.java)
                .putExtra(EXTRA_INPUT, inputPath)

        fun outputPathOf(data: Intent?): String? = data?.getStringExtra(EXTRA_OUTPUT)
    }
}

/**
 * Launches the editor and hands back the edited bytes.
 *
 * Writes the input to the cache and reads the result back, so callers deal in
 * byte arrays and never see the file shuffling underneath.
 */
class EditPhotoContract(
    private val context: Context,
) : ActivityResultContract<ByteArray, ByteArray?>() {

    override fun createIntent(context: Context, input: ByteArray): Intent {
        val file = File(context.cacheDir, "to_edit_${System.currentTimeMillis()}.jpg")
        file.writeBytes(input)
        return PhotoEditorActivity.intent(context, file.absolutePath)
    }

    override fun parseResult(resultCode: Int, intent: Intent?): ByteArray? {
        if (resultCode != Activity.RESULT_OK) return null
        val path = PhotoEditorActivity.outputPathOf(intent) ?: return null

        val file = File(path)
        val bytes = runCatching { file.readBytes() }.getOrNull()
        // The cache is the system's to clear, but an edited photo is large and
        // has already been handed on by this point.
        file.delete()
        return bytes
    }
}

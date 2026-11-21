package com.agentos.assist

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.agentos.agent.Attachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The assistant sheet's camera, photos and file buttons. A voice interaction session cannot receive activity
 * results, so the sheet starts this invisible activity, which runs the system picker or camera, reads what was
 * chosen (while it still holds the read grant) and hands the attachments back through [Request].
 */
class AttachPickerActivity : ComponentActivity() {
    /** Where the picked attachments go; [onDone] runs on the main thread, with an empty list when nothing was picked. */
    class Request(val onLoading: () -> Unit, val onDone: (List<Attachment>, String?) -> Unit)

    private var captureUri: Uri? = null
    private var delivered = false

    private val photos = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(5)) { load(it) }
    private val files = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { load(it) }
    private val camera = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok -> load(if (ok) listOfNotNull(captureUri) else emptyList()) }
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) shoot() else deliver(emptyList(), "Camera permission was denied.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        captureUri = savedInstanceState?.getParcelable(STATE_CAPTURE, Uri::class.java)
        if (savedInstanceState != null) return // recreated while the picker was up: its result still arrives
        try {
            when (intent.getStringExtra(EXTRA_KIND)) {
                KIND_CAMERA -> if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) shoot()
                    else cameraPermission.launch(Manifest.permission.CAMERA)
                KIND_PHOTOS -> photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                else -> files.launch(arrayOf("*/*"))
            }
        } catch (t: Throwable) {
            Log.w(TAG, "picker", t)
            deliver(emptyList(), "Could not open the picker: ${t.message}")
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        captureUri?.let { outState.putParcelable(STATE_CAPTURE, it) }
    }

    override fun onDestroy() {
        // Leaving any other way (e.g. Home) still clears the sheet's "Loading…".
        if (isFinishing && !delivered) deliver(emptyList(), null)
        super.onDestroy()
    }

    private fun shoot() {
        val dir = File(cacheDir, "captures").apply { mkdirs() }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", File(dir, "photo_${System.currentTimeMillis()}.jpg"))
        captureUri = uri
        try { camera.launch(uri) } catch (t: Throwable) { captureUri = null; deliver(emptyList(), "No camera app available: ${t.message}") }
    }

    private fun load(uris: List<Uri>) {
        if (uris.isEmpty()) return deliver(emptyList(), null)
        pending?.onLoading?.invoke()
        lifecycleScope.launch {
            var error: String? = null
            val loaded = withContext(Dispatchers.IO) {
                uris.mapNotNull { u -> try { Attachment.load(applicationContext, u) } catch (t: Throwable) { error = "Could not attach: ${t.message}"; null } }
            }
            deliver(loaded, error)
        }
    }

    private fun deliver(list: List<Attachment>, error: String?) {
        if (delivered) return
        delivered = true
        val r = pending
        pending = null
        r?.onDone?.invoke(list, error)
        if (!isFinishing) finish()
        overridePendingTransition(0, 0)
    }

    companion object {
        private const val TAG = "AgentOS/AttachPicker"
        private const val EXTRA_KIND = "kind"
        private const val STATE_CAPTURE = "capture"
        const val KIND_CAMERA = "camera"
        const val KIND_PHOTOS = "photos"
        const val KIND_FILE = "file"

        @Volatile private var pending: Request? = null

        fun intent(ctx: Context, kind: String, request: Request): Intent {
            pending = request
            return Intent(ctx, AttachPickerActivity::class.java).putExtra(EXTRA_KIND, kind)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        }
    }
}

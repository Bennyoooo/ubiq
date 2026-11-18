package com.agentos.agent

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import java.io.ByteArrayOutputStream

/**
 * Something the user attached to a chat message. Images are downscaled for the model; every
 * attachment is also copied into shared storage (Pictures/Ubiq or Download/Ubiq) so the
 * agent can find it from other apps' pickers.
 */
class Attachment private constructor(
    val name: String,
    val mime: String,
    loadBytes: () -> ByteArray,
    loadThumbnail: () -> Bitmap?,
    /** Where the copy lives on the phone, as the user would see it in a file picker. */
    val savedAs: String?,
) {
    constructor(name: String, mime: String, bytes: ByteArray, thumbnail: Bitmap?, savedAs: String?) : this(name, mime, { bytes }, { thumbnail }, savedAs)

    val bytes: ByteArray by lazy(loadBytes)
    val thumbnail: Bitmap? by lazy(loadThumbnail)
    val isImage: Boolean get() = mime.startsWith("image/")
    val isPdf: Boolean get() = mime == "application/pdf"
    val isText: Boolean get() = mime.startsWith("text/") || mime in TEXT_MIMES || name.substringAfterLast('.', "").lowercase() in TEXT_EXTS

    companion object {
        private const val TAG = "AgentOS/Attach"
        private const val MAX_IMAGE_DIM = 1600
        private const val MAX_BYTES = 25 * 1024 * 1024
        private val TEXT_MIMES = setOf("application/json", "application/xml", "application/javascript", "application/x-yaml", "application/csv")
        private val TEXT_EXTS = setOf("txt", "md", "csv", "json", "xml", "yaml", "yml", "log", "ics", "html", "htm", "js", "kt", "py", "java")

        /** An in-memory image (e.g. the screenshot the assistant sheet received); not copied to shared storage. */
        fun fromBitmap(name: String, bmp: Bitmap, thumbDim: Int = 256): Attachment {
            val scale = MAX_IMAGE_DIM.toFloat() / maxOf(bmp.width, bmp.height)
            val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
            val bos = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 80, bos)
            val ts = thumbDim.toFloat() / maxOf(scaled.width, scaled.height)
            val thumb = if (ts < 1f) Bitmap.createScaledBitmap(scaled, (scaled.width * ts).toInt(), (scaled.height * ts).toInt(), true) else scaled
            return Attachment(name, "image/jpeg", bos.toByteArray(), thumb, null)
        }

        /** An image a saved chat kept in [file]: read only when the chat shows it or the model needs it. */
        fun saved(name: String, file: java.io.File, thumbDim: Int): Attachment = Attachment(name, "image/jpeg",
            { runCatching { file.readBytes() }.getOrDefault(ByteArray(0)) },
            {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= thumbDim) sample *= 2
                BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })?.let { bmp ->
                    val ts = thumbDim.toFloat() / maxOf(bmp.width, bmp.height)
                    if (ts < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * ts).toInt(), (bmp.height * ts).toInt(), true) else bmp
                }
            }, null)

        fun load(ctx: Context, uri: Uri): Attachment {
            val cr = ctx.contentResolver
            var name = uri.lastPathSegment?.substringAfterLast('/') ?: "attachment"
            var mime = cr.getType(uri) ?: ""
            if (uri.scheme == "content") cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { name = it }
            }
            if (mime.isBlank()) mime = android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
            val raw = cr.openInputStream(uri)?.use { it.readBytes() } ?: throw IllegalArgumentException("Could not read $name")
            if (raw.size > MAX_BYTES) throw IllegalArgumentException("$name is larger than 25 MB")

            var bytes = raw
            var thumb: Bitmap? = null
            if (mime.startsWith("image/")) {
                val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size)
                if (bmp != null) {
                    val scale = MAX_IMAGE_DIM.toFloat() / maxOf(bmp.width, bmp.height)
                    val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
                    val bos = ByteArrayOutputStream()
                    scaled.compress(Bitmap.CompressFormat.JPEG, 85, bos)
                    bytes = bos.toByteArray(); mime = "image/jpeg"
                    if (!name.lowercase().endsWith(".jpg") && !name.lowercase().endsWith(".jpeg")) name = name.substringBeforeLast('.') + ".jpg"
                    val ts = 256f / maxOf(scaled.width, scaled.height)
                    thumb = if (ts < 1f) Bitmap.createScaledBitmap(scaled, (scaled.width * ts).toInt(), (scaled.height * ts).toInt(), true) else scaled
                }
            }
            val saved = try { saveToSharedStorage(ctx, name, mime, bytes) } catch (t: Throwable) { Log.w(TAG, "could not save copy", t); null }
            return Attachment(name, mime, bytes, thumb, saved)
        }

        /** Copies the attachment where other apps can see it. Returns the path as shown in file pickers. */
        private fun saveToSharedStorage(ctx: Context, name: String, mime: String, bytes: ByteArray): String {
            val image = mime.startsWith("image/")
            val collection = if (image) MediaStore.Images.Media.EXTERNAL_CONTENT_URI else MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val dir = if (image) Environment.DIRECTORY_PICTURES else Environment.DIRECTORY_DOWNLOADS
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$dir/Ubiq")
            }
            val uri = ctx.contentResolver.insert(collection, values) ?: throw IllegalStateException("insert failed")
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            // The display name may have been de-duplicated (e.g. "photo (1).jpg").
            val finalName = ctx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: name
            return "$dir/Ubiq/$finalName"
        }
    }
}

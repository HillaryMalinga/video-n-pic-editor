package com.hdstatus.app

import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File

object Sharing {

    private fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)

    /**
     * Opens WhatsApp's share screen with the converted file(s), where "My status"
     * can be picked. Falls back to WhatsApp Business, then to the normal share sheet.
     */
    fun shareToWhatsApp(context: Context, files: List<File>, mime: String) {
        val uris = files.map { uriFor(context, it) }
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        intent.type = mime
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b")) {
            try {
                context.startActivity(Intent(intent).setPackage(pkg))
                return
            } catch (_: ActivityNotFoundException) {
            }
        }
        context.startActivity(Intent.createChooser(intent, "Share status"))
    }

    /** Copies the file(s) into the phone's gallery (Pictures/HD Status or Movies/HD Status). */
    fun saveToGallery(context: Context, files: List<File>, isVideo: Boolean) {
        val resolver = context.contentResolver
        for (f in files) {
            val collection = if (isVideo) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }
            val dir = if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "HDStatus_${System.currentTimeMillis()}_${f.name}")
                put(MediaStore.MediaColumns.MIME_TYPE, if (isVideo) "video/mp4" else "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$dir/HD Status")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val item = resolver.insert(collection, values) ?: continue
            resolver.openOutputStream(item)?.use { out -> f.inputStream().use { it.copyTo(out) } }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(item, values, null, null)
        }
    }
}

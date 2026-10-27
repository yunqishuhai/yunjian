package io.github.miyouchat.data

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID

class CameraFileProvider : FileProvider()

/** Only this narrow cache directory is writable by the camera's temporary URI grant. */
class CameraCaptureStore(private val context: Context) {
    private val directory = File(context.cacheDir, "chat-camera")
    fun create(): Uri {
        check(directory.mkdirs() || directory.isDirectory)
        val file = File(directory, "photo-${UUID.randomUUID()}.jpg")
        check(file.createNewFile())
        return try { FileProvider.getUriForFile(context, "${context.packageName}.camera", file) }
        catch (failure: Exception) { file.delete(); throw failure }
    }
    private fun ownedName(name: String) = name.matches(Regex("photo-[0-9a-f-]{36}\\.jpg"))
    private fun file(uri: Uri): File? {
        if (uri.scheme != "content" || uri.authority != "${context.packageName}.camera") return null
        val segments = uri.pathSegments
        if (segments.size != 2 || segments[0] != "captures" || !ownedName(segments[1])) return null
        val file = File(directory, segments[1]).canonicalFile
        return file.takeIf { it.parentFile == directory.canonicalFile }
    }
    fun ready(uri: Uri): Boolean = runCatching {
        val file = file(uri) ?: return false
        if (!file.isFile || file.length() == 0L) return false
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, options)
        options.outWidth > 0 && options.outHeight > 0
    }.getOrDefault(false)
    fun discard(uri: Uri) {
        val file = runCatching { file(uri) }.getOrNull() ?: return
        runCatching { context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        runCatching { file.delete() }
    }
    fun pruneExpired() {
        directory.listFiles()?.filter { ownedName(it.name) && it.lastModified() < System.currentTimeMillis() - 24 * 60 * 60 * 1000L }?.forEach { it.delete() }
    }
}

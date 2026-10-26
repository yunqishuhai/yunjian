package io.github.miyouchat.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

data class ChatBackgrounds(val defaultImage: String? = null, val conversations: Map<String,String> = emptyMap()) {
    fun image(chat: String?): String? = chat?.let(conversations::get) ?: defaultImage
}

/** Private, durable local copies. Picker grants and original gallery files are never retained. */
class ChatBackgroundStore(private val context: Context) {
    private val preferences = context.getSharedPreferences("chat_backgrounds",Context.MODE_PRIVATE)
    private val root = File(context.filesDir,"chat-backgrounds")
    private fun key(account: String): String {
        require(account.isNotBlank())
        return MessageDigest.getInstance("SHA-256").digest(account.toByteArray()).joinToString("") { "%02x".format(it) }
    }
    private fun directory(account: String) = File(root,key(account))
    private fun entries(account: String): MutableMap<String,String> {
        val json = runCatching { JSONObject(preferences.getString(key(account),"{}").orEmpty()) }.getOrDefault(JSONObject())
        return json.keys().asSequence().mapNotNull { scope ->
            val name = json.optString(scope)
            if (name.matches(Regex("[0-9a-f-]{36}\\.jpg"))) scope to name else null
        }.toMap().toMutableMap()
    }
    private fun scope(chat: String?) = chat?.let { "chat:$it" } ?: "default"
    fun load(account: String): ChatBackgrounds = synchronized(lock) {
        val folder = directory(account)
        val values = entries(account).mapValues { File(folder,it.value) }.filterValues { it.isFile }
        prune(folder,values.values.map { it.name }.toSet())
        ChatBackgrounds(values["default"]?.absolutePath,
            values.filterKeys { it.startsWith("chat:") }.mapKeys { it.key.removePrefix("chat:") }.mapValues { it.value.absolutePath })
    }
    fun set(account: String, chat: String?, uri: Uri) = synchronized(lock) {
        val folder = directory(account).apply { check(mkdirs() || isDirectory) }
        val source = File.createTempFile("import-",".tmp",folder)
        val target = File(folder,"${UUID.randomUUID()}.jpg")
        var committed = false
        try {
            context.contentResolver.openInputStream(uri)?.use { input -> source.outputStream().use { output ->
                val buffer = ByteArray(16*1024); var total = 0L
                while (true) {
                    val count = input.read(buffer); if (count < 0) break
                    total += count; require(total <= 40L*1024*1024) { "图片超过 40 MB，请选择较小的图片" }
                    output.write(buffer,0,count)
                }
            } } ?: error("无法读取所选图片")
            val bitmap = decode(source)
            try {
                // JPEG flattens transparency against the same cold-white base on every device.
                val flattened = Bitmap.createBitmap(bitmap.width,bitmap.height,Bitmap.Config.ARGB_8888)
                try {
                    Canvas(flattened).apply { drawColor(Color.rgb(245,248,252)); drawBitmap(bitmap,0f,0f,null) }
                    target.outputStream().use { check(flattened.compress(Bitmap.CompressFormat.JPEG,90,it)) }
                } finally { flattened.recycle() }
            } finally { bitmap.recycle() }
            val values = entries(account).apply { put(scope(chat),target.name) }
            persist(account,values); committed = true
            prune(folder,values.values.toSet())
        } finally { source.delete(); if (!committed) target.delete() }
    }
    fun reset(account: String, chat: String?) = synchronized(lock) {
        val values = entries(account).apply { remove(scope(chat)) }
        persist(account,values); prune(directory(account),values.values.toSet())
    }
    fun clear(account: String) = synchronized(lock) {
        check(preferences.edit().remove(key(account)).commit())
        prune(directory(account),emptySet()); directory(account).delete()
    }
    private fun persist(account: String, values: Map<String,String>) {
        check(preferences.edit().putString(key(account),JSONObject(values).toString()).commit())
    }
    private fun prune(folder: File, retained: Set<String>) {
        folder.listFiles()?.filter { it.isFile && it.name !in retained &&
            (it.name.matches(Regex("[0-9a-f-]{36}\\.jpg")) || it.name.startsWith("import-")) }?.forEach { it.delete() }
    }
    private fun decode(file: File): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path,bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "图片格式不支持，请换一张图片" }
        require(bounds.outWidth.toLong()*bounds.outHeight <= 100_000_000L) { "图片尺寸过大，请选择较小的图片" }
        if (Build.VERSION.SDK_INT >= 28) return ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder,info,_ ->
            val scale = maxOf(info.size.width,info.size.height).toFloat() / MAX_SIDE
            if (scale > 1) decoder.setTargetSize((info.size.width/scale).toInt().coerceAtLeast(1),(info.size.height/scale).toInt().coerceAtLeast(1))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        var sample = 1
        while (maxOf(bounds.outWidth,bounds.outHeight)/sample > MAX_SIDE) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.path,BitmapFactory.Options().apply { inSampleSize = sample }) ?: error("无法读取所选图片")
        val orientation = runCatching { ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL) }.getOrDefault(1)
        val matrix = Matrix().apply {
            when (orientation) {
                2 -> setScale(-1f,1f); 3 -> setRotate(180f); 4 -> setScale(1f,-1f)
                5 -> { setRotate(90f); postScale(-1f,1f) }; 6 -> setRotate(90f)
                7 -> { setRotate(270f); postScale(-1f,1f) }; 8 -> setRotate(270f)
            }
        }
        return Bitmap.createBitmap(bitmap,0,0,bitmap.width,bitmap.height,matrix,true).also { if (it !== bitmap) bitmap.recycle() }
    }
    companion object {
        private const val MAX_SIDE = 2560
        private val lock = Any()
    }
}

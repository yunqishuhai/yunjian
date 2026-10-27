package io.github.miyouchat

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.github.miyouchat.data.*
import io.github.miyouchat.ui.CapturePhoto
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Only generated images and a private test preferences namespace; never sends a message. */
class CameraAppearanceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun cameraOutputUsesFullImageUriAndTemporaryGrantsThenCleansOnlyOwnedFile() {
        val store = CameraCaptureStore(context)
        val uri = store.create()
        val other = store.create()
        try {
            assertEquals("content",uri.scheme)
            assertEquals("${context.packageName}.camera",uri.authority)
            assertFalse(store.ready(uri))
            val intent = CapturePhoto().createIntent(context,uri)
            assertEquals(uri,intent.clipData?.getItemAt(0)?.uri)
            assertTrue(intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            val bitmap = Bitmap.createBitmap(64,96,Bitmap.Config.ARGB_8888)
            try { context.contentResolver.openOutputStream(uri)!!.use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG,90,it)) } } finally { bitmap.recycle() }
            assertTrue(store.ready(uri))
            store.discard(uri)
            assertFalse(store.ready(uri))
            context.contentResolver.openOutputStream(other)!!.use { it.write(byteArrayOf(1,2,3)) }
            store.discard(Uri.parse("content://${context.packageName}.updates/captures/${other.lastPathSegment}"))
            assertNotNull(context.contentResolver.openInputStream(other)?.use { it.read() })
            assertFalse(store.ready(other))
        } finally { store.discard(uri); store.discard(other) }
    }
    @Test fun cameraProviderCannotExposeOtherAppFiles() {
        val unrelated = File(context.cacheDir,"camera-isolation-fixture.txt").apply { writeText("fixture") }
        try {
            try { FileProvider.getUriForFile(context,"${context.packageName}.camera",unrelated); fail("Must not share the whole cache directory") }
            catch (_: IllegalArgumentException) { }
        } finally { unrelated.delete() }
    }
    @Test fun appearancePersistsAcrossStoreRecreationWithoutTouchingAccounts() {
        val isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("test-camera-appearance-$name",mode)
        }
        val prefs = isolated.getSharedPreferences("appearance",Context.MODE_PRIVATE)
        try {
            prefs.edit().clear().commit()
            assertEquals(Appearance(),AppearanceStore(isolated).state.value)
            val selected = Appearance(ThemePalette.ICE_BLUE,ThemeMode.DARK)
            AppearanceStore(isolated).set(selected)
            assertEquals(selected,AppearanceStore(isolated).state.value)
            prefs.edit().putString("palette","unknown").putString("mode","unknown").commit()
            assertEquals(Appearance(),AppearanceStore(isolated).state.value)
        } finally { prefs.edit().clear().commit() }
    }
}

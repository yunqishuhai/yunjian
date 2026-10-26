package io.github.miyouchat

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import io.github.miyouchat.data.ChatBackgroundStore
import org.junit.*
import org.junit.Assert.*
import java.io.File

/** Synthetic files and isolated preferences only; never touches saved accounts or gallery. */
class ChatBackgroundStoreTest {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(target.cacheDir,"background-store-fixture")
    private val isolated = object : ContextWrapper(target) {
        override fun getFilesDir() = root
        override fun getSharedPreferences(name: String,mode: Int) = super.getSharedPreferences("test-background-$name",mode)
    }
    private val store get() = ChatBackgroundStore(isolated)
    @Before fun setup() { done(); root.mkdirs() }
    @After fun done() {
        isolated.getSharedPreferences("chat_backgrounds",Context.MODE_PRIVATE).edit().clear().commit()
        root.deleteRecursively()
    }
    private fun image(name: String = "original.png",width: Int = 80,height: Int = 120): File {
        val file = File(root,name)
        val bitmap = Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(70,110,150)) }
        try { file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) } } finally { bitmap.recycle() }
        return file
    }
    @Test fun defaultAndChatOverridesSurviveReloadAndStayIsolatedByAccount() {
        val source = image(); val bytes = source.readBytes()
        store.set("a",null,Uri.fromFile(source)); val default = store.load("a").defaultImage!!
        store.set("a","chat",Uri.fromFile(source)); val chat = store.load("a").image("chat")!!
        store.set("b",null,Uri.fromFile(source)); val other = store.load("b").defaultImage!!
        assertNotEquals(default,chat); assertNotEquals(default,other)
        assertEquals(default,store.load("a").image("unconfigured"))
        assertEquals(chat,ChatBackgroundStore(isolated).load("a").image("chat"))
        assertArrayEquals(bytes,source.readBytes())
        store.reset("a",null)
        assertNull(store.load("a").defaultImage); assertEquals(chat,store.load("a").image("chat"))
        assertFalse(File(default).exists()); assertTrue(File(chat).exists()); assertTrue(File(other).exists())
        store.reset("a","chat")
        assertNull(store.load("a").image("chat")); assertFalse(File(chat).exists()); assertTrue(File(other).exists())
    }
    @Test fun resetChatFollowsDefaultAndReplacementDeletesOnlyItsPreviousCopy() {
        val source = image()
        store.set("a",null,Uri.fromFile(source)); val default = store.load("a").defaultImage!!
        store.set("a","chat",Uri.fromFile(source)); val previous = store.load("a").image("chat")!!
        store.set("a","chat",Uri.fromFile(source)); val next = store.load("a").image("chat")!!
        assertFalse(File(previous).exists()); assertTrue(File(default).exists()); assertTrue(File(next).exists())
        store.reset("a","chat")
        assertEquals(default,store.load("a").image("chat")); assertFalse(File(next).exists())
        assertEquals(1,File(root,"chat-backgrounds").walkTopDown().count { it.isFile })
    }
    @Test fun invalidImageKeepsPreviousSelectionAndCleansPartialImports() {
        val source = image(); store.set("a",null,Uri.fromFile(source)); val previous = store.load("a")
        val invalid = File(root,"invalid.txt").apply { writeText("not an image") }
        assertThrows(Exception::class.java) { store.set("a",null,Uri.fromFile(invalid)) }
        assertEquals(previous,store.load("a")); assertTrue(File(previous.defaultImage!!).exists())
        assertEquals(1,File(root,"chat-backgrounds").walkTopDown().count { it.isFile })
    }
    @Test fun downsampleAndMissingOverrideFallbackKeepDefaultsUsable() {
        val source = image(width = 3000,height = 20)
        store.set("a",null,Uri.fromFile(source)); store.set("a","chat",Uri.fromFile(source))
        val snapshot = store.load("a")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(snapshot.defaultImage,bounds)
        assertTrue(maxOf(bounds.outWidth,bounds.outHeight) <= 2560)
        File(snapshot.image("chat")!!).delete()
        assertEquals(snapshot.defaultImage,store.load("a").image("chat"))
    }
    @Test fun clearOneAccountDoesNotDeleteAnotherAccountOrOriginalImage() {
        val source = image(); store.set("a",null,Uri.fromFile(source)); store.set("b",null,Uri.fromFile(source))
        val other = store.load("b"); store.clear("a")
        assertNull(store.load("a").defaultImage); assertEquals(other,store.load("b"))
        assertTrue(File(other.defaultImage!!).exists()); assertTrue(source.exists())
    }
}

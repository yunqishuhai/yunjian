package io.github.miyouchat

import android.content.ContextWrapper
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import io.github.miyouchat.update.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import java.io.File
import java.security.MessageDigest

/** Intercepted HTTPS responses and app-owned APK fixtures only. Never invokes the installer. */
class ReleaseUpdaterTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val target = instrumentation.targetContext
    private val root = File(target.cacheDir,"app-updates/test-fixtures")
    private val context = object : ContextWrapper(target) { override fun getCacheDir() = root }
    private val apk get() = File(target.applicationInfo.sourceDir).readBytes()
    private fun release(bytes: ByteArray) = AppRelease(BuildConfig.VERSION_CODE.toLong(),BuildConfig.VERSION_NAME,26,"test.apk",
        "https://github.com/example/yunjian/releases/download/v0.3.2/test.apk",bytes.size.toLong(),sha(bytes),"","")
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun updater(bytes: ByteArray) = ReleaseUpdater(context,OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        assertNull(request.header("Cookie")); assertNull(request.header("DS")); assertNull(request.header("Authorization"))
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(bytes.toResponseBody("application/vnd.android.package-archive".toMediaType())).build()
    }.build(),installedVersion = 0)
    @Before fun setup() { root.deleteRecursively(); root.mkdirs() }
    @After fun done() { root.deleteRecursively() }
    @Test fun verifiedDownloadCreatesReadOnlyInstallerUriWithoutLaunchingIt() {
        val bytes = apk; val updater = updater(bytes)
        val file = runBlocking { updater.download(release(bytes)) {} }
        assertTrue(file.isFile); assertEquals(sha(bytes),sha(file.readBytes()))
        val intent = updater.installIntent(file)
        assertEquals("content",intent.data!!.scheme); assertEquals("${target.packageName}.updates",intent.data!!.authority)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION == 0)
        assertEquals('P'.code,target.contentResolver.openInputStream(intent.data!!)?.use { it.read() })
        assertFalse(File(file.parentFile,"update.part").exists())
    }
    @Test fun corruptedDigestAndTruncatedDownloadDoNotLeaveInstallableFiles() {
        val bytes = apk
        assertThrows(Exception::class.java) { runBlocking { updater(bytes).download(release(bytes).copy(sha256 = "0".repeat(64))) {} } }
        assertFalse(File(root,"app-updates/update.apk").exists()); assertFalse(File(root,"app-updates/update.part").exists())
        assertThrows(Exception::class.java) { runBlocking { updater(bytes.copyOf(bytes.size-1)).download(release(bytes)) {} } }
        assertFalse(File(root,"app-updates/update.apk").exists())
    }
    @Test fun cancellationCleansPartialDownload() {
        val bytes = apk
        assertThrows(CancellationException::class.java) {
            runBlocking { updater(bytes).download(release(bytes)) { throw CancellationException("Synthetic cancellation") } }
        }
        assertFalse(File(root,"app-updates/update.apk").exists()); assertFalse(File(root,"app-updates/update.part").exists())
    }
    @Test fun invalidArchiveAndForeignPackageAreRejectedAfterHashValidation() {
        for (bytes in listOf(byteArrayOf(1,2,3),File(instrumentation.context.applicationInfo.sourceDir).readBytes())) {
            assertThrows(Exception::class.java) { runBlocking { updater(bytes).download(release(bytes)) {} } }
            assertFalse(File(root,"app-updates/update.apk").exists())
        }
    }
    @Test fun fileProviderCannotExposeOtherPrivateAppFiles() {
        assertThrows(IllegalArgumentException::class.java) { updater(byteArrayOf()).installIntent(File(target.filesDir,"not-an-update")) }
    }
    @Test fun realReleaseCertificateCannotReplaceInstalledDebugCertificate() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("inspect_release_apk") == "true")
        val file = File(target.getExternalFilesDir("update-fixtures"),"release.apk")
        assertTrue("Provide the locally signed release fixture first",file.isFile)
        val bytes = file.readBytes()
        val failure = assertThrows(IllegalArgumentException::class.java) {
            updater(bytes).validateApk(file,release(bytes).copy(versionName = "0.3.2"))
        }
        assertTrue("Signature mismatch must be diagnosed before system installation",failure.message.orEmpty().contains("签名"))
    }
}

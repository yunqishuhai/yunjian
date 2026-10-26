package io.github.miyouchat.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import io.github.miyouchat.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** A separate public GitHub client: never shares MiYouShe cookies, DS headers or account tokens. */
class ReleaseUpdater(private val context: Context,private val client: OkHttpClient = publicClient(),
    private val installedVersion: Long = BuildConfig.VERSION_CODE.toLong()) {
    private val directory get() = File(context.cacheDir,"app-updates")
    private fun request(url: String) = Request.Builder().url(url).header("User-Agent","Yunjian/${BuildConfig.VERSION_NAME}").build()
    private fun text(url: String,api: Boolean = false): String {
        val request = request(url).newBuilder().apply { if (api) header("Accept","application/vnd.github+json") }.build()
        val call = client.newCall(request).apply { timeout().timeout(30,TimeUnit.SECONDS) }
        call.execute().use { response ->
            check(response.isSuccessful) { when (response.code) { 404 -> "尚无公开正式版本，或发布仓库暂不可访问"; 403,429 -> "检查次数受限，请稍后重试"; else -> "GitHub 暂时无法访问，请稍后重试" } }
            val body = response.body ?: error("更新信息为空")
            require(body.contentLength() <= 512*1024) { "更新信息过大" }
            body.byteStream().use { stream ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = stream.read(buffer); if (count < 0) break
                    require(output.size()+count <= 512*1024) { "更新信息过大" }
                    output.write(buffer,0,count)
                }
                return output.toString("UTF-8")
            }
        }
    }
    suspend fun check(repository: ReleaseRepository): AppRelease = withContext(Dispatchers.IO) {
        val release = ReleaseManifest.published(repository,text(repository.apiUrl,true))
        ReleaseManifest.parse(repository,release,text(release.manifestUrl))
    }
    suspend fun download(release: AppRelease,progress: (Long) -> Unit): File = withContext(Dispatchers.IO) {
        check(directory.mkdirs() || directory.isDirectory)
        val partial = File(directory,"update.part")
        val target = File(directory,"update.apk")
        partial.delete(); target.delete()
        val call = client.newCall(request(release.apkUrl))
        val cancellation = launch(Dispatchers.Default,start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        try {
            call.execute().use { response ->
                check(response.isSuccessful) { "安装包下载失败，请稍后重试" }
                val body = response.body ?: error("安装包内容为空")
                require(body.contentLength() == -1L || body.contentLength() == release.bytes) { "安装包大小与清单不符" }
                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L
                body.byteStream().use { input -> partial.outputStream().use { output ->
                    val buffer = ByteArray(64*1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer); if (count < 0) break
                        total += count; require(total <= release.bytes) { "安装包大小与清单不符" }
                        output.write(buffer,0,count); digest.update(buffer,0,count); progress(total)
                    }
                } }
                require(total == release.bytes && digest.digest().joinToString("") { "%02x".format(it) } == release.sha256) { "安装包校验失败，请重新下载" }
            }
            validateApk(partial,release)
            check(partial.renameTo(target)) { "安装包保存失败" }
            target
        } catch (failure: Throwable) { partial.delete(); target.delete(); throw failure }
        finally { cancellation.cancel() }
    }
    @Suppress("DEPRECATION")
    fun validateApk(file: File,release: AppRelease) {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val manager = context.packageManager
        val next = manager.getPackageArchiveInfo(file.absolutePath,flags) ?: error("安装包格式无效")
        val nextCode = if (Build.VERSION.SDK_INT >= 28) next.longVersionCode else next.versionCode.toLong()
        val current = manager.getPackageInfo(context.packageName,flags)
        val currentSignatures = if (Build.VERSION.SDK_INT >= 28) current.signingInfo?.apkContentsSigners else current.signatures
        val nextSignatures = if (Build.VERSION.SDK_INT >= 28) next.signingInfo?.apkContentsSigners else next.signatures
        val installed = currentSignatures?.map { it.toCharsString() }?.toSet().orEmpty()
        val downloaded = nextSignatures?.map { it.toCharsString() }?.toSet().orEmpty()
        ApkUpdatePolicy.validate(ApkIdentity(next.packageName,nextCode,next.versionName,next.applicationInfo?.minSdkVersion ?: Int.MAX_VALUE,downloaded),
            release,ApkIdentity(context.packageName,installedVersion,current.versionName,26,installed),Build.VERSION.SDK_INT)
    }
    fun installIntent(file: File) = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(FileProvider.getUriForFile(context,"${context.packageName}.updates",file),"application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    companion object {
        private fun publicClient() = OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS)
            .callTimeout(10,TimeUnit.MINUTES).followSslRedirects(false).retryOnConnectionFailure(false).build()
    }
}

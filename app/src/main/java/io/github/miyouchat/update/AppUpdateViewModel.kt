package io.github.miyouchat.update

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.miyouchat.BuildConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File

data class UpdateState(val checking: Boolean = false,val downloading: Boolean = false,val available: AppRelease? = null,
    val checked: Boolean = false,val downloadedBytes: Long = 0,val ready: File? = null,val error: String? = null)

class AppUpdateViewModel(application: Application) : AndroidViewModel(application) {
    val repository = ReleaseRepository.parse(BuildConfig.UPDATE_REPOSITORY)
    private val updater = ReleaseUpdater(application)
    private val mutable = MutableStateFlow(UpdateState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    fun check() {
        val source = repository ?: return
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            mutable.value = UpdateState(checking = true)
            try {
                val release = updater.check(source)
                mutable.value = UpdateState(checked = true,available = release.takeIf { it.versionCode > BuildConfig.VERSION_CODE })
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { mutable.update { it.copy(error = message(failure)) } }
            finally { mutable.update { it.copy(checking = false) } }
        }
    }
    fun download() {
        val release = mutable.value.available ?: return
        if (job?.isActive == true) return
        if (release.minSdk > android.os.Build.VERSION.SDK_INT) { reportError("此版本暂不支持当前系统，请在发布页查看兼容版本"); return }
        job = viewModelScope.launch {
            mutable.update { it.copy(downloading = true,downloadedBytes = 0,ready = null,error = null) }
            try {
                val file = updater.download(release) { bytes -> mutable.update { it.copy(downloadedBytes = bytes) } }
                mutable.update { it.copy(ready = file) }
            } catch (cancel: CancellationException) { mutable.update { it.copy(error = "已取消下载") }; throw cancel }
            catch (failure: Exception) { mutable.update { it.copy(error = message(failure)) } }
            finally { mutable.update { it.copy(downloading = false) } }
        }
    }
    fun cancelDownload() { if (mutable.value.downloading) job?.cancel() }
    fun installIntent() = mutable.value.ready?.takeIf { it.isFile }?.let(updater::installIntent)
    fun reportError(value: String) { mutable.update { it.copy(error = value) } }
    private fun message(failure: Exception) = when (failure) {
        is java.io.IOException -> "无法连接 GitHub，请检查网络后重试，也可在浏览器打开发布页"
        is IllegalArgumentException, is IllegalStateException -> failure.message?.take(200) ?: "更新信息不完整，请稍后重试"
        else -> "暂时无法获取更新，请稍后重试"
    }
}

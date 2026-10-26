package io.github.miyouchat.update

data class ApkIdentity(val packageName: String,val versionCode: Long,val versionName: String?,val minSdk: Int,val signers: Set<String>)

object ApkUpdatePolicy {
    fun validate(apk: ApkIdentity,release: AppRelease,current: ApkIdentity,deviceSdk: Int) {
        require(apk.packageName == current.packageName) { "安装包不属于云笺" }
        require(apk.versionCode == release.versionCode && apk.versionName == release.versionName) { "安装包版本与更新说明不一致" }
        require(apk.versionCode > current.versionCode && apk.minSdk <= deviceSdk) { "该安装包不适合当前版本或设备" }
        require(current.signers.isNotEmpty() && current.signers == apk.signers) {
            "更新包签名与当前安装版不同，无法覆盖安装。请保留当前应用，先确认版本迁移方式"
        }
    }
}

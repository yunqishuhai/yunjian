package io.github.miyouchat

import io.github.miyouchat.update.*
import org.junit.Assert.*
import org.junit.Test

class ApkUpdatePolicyTest {
    private val current = ApkIdentity("io.github.miyouchat",8,"0.3.1",26,setOf("same-certificate"))
    private val next = current.copy(versionCode = 9,versionName = "0.3.2")
    private val release = AppRelease(9,"0.3.2",26,"yunjian-0.3.2.apk","https://github.com/example/yunjian/releases/download/v0.3.2/yunjian-0.3.2.apk",1024,"a".repeat(64),"","")
    @Test fun newerVersionWithSamePackageAndSignerIsAllowed() { ApkUpdatePolicy.validate(next,release,current,36) }
    @Test fun changedPackageOrDeclaredVersionIsRejected() {
        for (identity in listOf(next.copy(packageName = "other"),next.copy(versionCode = 10),next.copy(versionName = "0.3.3")))
            assertThrows(IllegalArgumentException::class.java) { ApkUpdatePolicy.validate(identity,release,current,36) }
    }
    @Test fun ReinstallDowngradeAndUnsupportedAndroidAreRejected() {
        for (installed in listOf(current.copy(versionCode = 9),current.copy(versionCode = 10)))
            assertThrows(IllegalArgumentException::class.java) { ApkUpdatePolicy.validate(next,release,installed,36) }
        assertThrows(IllegalArgumentException::class.java) { ApkUpdatePolicy.validate(next.copy(minSdk = 37),release,current,36) }
    }
    @Test fun unknownChangedOrAdditionalSignersCannotReplaceInstalledApp() {
        for (signers in listOf(emptySet(),setOf("other-certificate"),setOf("same-certificate","other-certificate")))
            assertThrows(IllegalArgumentException::class.java) { ApkUpdatePolicy.validate(next.copy(signers = signers),release,current,36) }
        assertThrows(IllegalArgumentException::class.java) { ApkUpdatePolicy.validate(next,release,current.copy(signers = emptySet()),36) }
    }
}

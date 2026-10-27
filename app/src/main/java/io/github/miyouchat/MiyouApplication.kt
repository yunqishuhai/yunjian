package io.github.miyouchat

import android.app.Application
import io.github.miyouchat.data.*

class MiyouApplication : Application() {
    lateinit var repository: ChatRepository
        private set
    override fun onCreate() {
        super.onCreate()
        CameraCaptureStore(this).pruneExpired()
        cacheDir.listFiles()?.filter { it.name.startsWith("history-export-") && it.extension == "jsonl" }?.forEach { it.delete() }
        // A restarted FileProvider may still be serving update.apk to the system installer.
        java.io.File(cacheDir,"app-updates/update.part").delete()
        repository = ChatRepository(this, SessionStore(this), MiyouApi(), ChatDatabase(this))
        repository.session()?.let { repository.database.markInterrupted(it.aid) }
    }
}

package io.github.miyouchat

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.mutableStateOf
import io.github.miyouchat.ui.ChatViewModel
import io.github.miyouchat.ui.MiyouChatApp
import io.github.miyouchat.sync.MessageSyncService

data class ChatDestination(val account: String?, val chat: String)

class MainActivity : ComponentActivity() {
    private val model: ChatViewModel by viewModels()
    private val destination = mutableStateOf<ChatDestination?>(null)
    private fun destination(intent: Intent) = intent.getStringExtra("chat_id")?.let { ChatDestination(intent.getStringExtra("account_id"),it) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        destination.value = destination(intent)
        setContent { MiyouChatApp(model, destination.value) { destination.value = null } }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); destination.value = destination(intent) }
    override fun onStart() {
        super.onStart(); model.setVisible(true)
        if (model.repository.store.backgroundSync && model.repository.session() != null) {
            androidx.core.content.ContextCompat.startForegroundService(this, Intent(this, MessageSyncService::class.java))
        }
    }
    override fun onStop() { model.setVisible(false); super.onStop() }
}

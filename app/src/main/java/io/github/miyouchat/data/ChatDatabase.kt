package io.github.miyouchat.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import org.json.JSONObject

class ChatDatabase(context: Context, name: String = "chat.db") : SQLiteOpenHelper(context, name, null, 3) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE chats (account TEXT NOT NULL, id TEXT NOT NULL, target TEXT, name TEXT, avatar TEXT, preview TEXT, time INTEGER, unread INTEGER, PRIMARY KEY(account,id))")
        db.execSQL("CREATE TABLE messages (account TEXT NOT NULL, key TEXT NOT NULL, id TEXT, local_id TEXT, chat TEXT NOT NULL, sender TEXT, type INTEGER, content TEXT, time INTEGER, delivery TEXT, error TEXT, search_text TEXT NOT NULL DEFAULT '', PRIMARY KEY(account,key))")
        db.execSQL("CREATE INDEX message_order ON messages(account,chat,time)")
        db.execSQL("CREATE INDEX message_local ON messages(account,local_id)")
        db.execSQL("CREATE TABLE cursors (account TEXT, chat TEXT, cursor TEXT, is_last INTEGER, PRIMARY KEY(account,chat))")
        createImageJobs(db)
        createHistorySettings(db)
    }
    private fun createImageJobs(db: SQLiteDatabase) { db.execSQL("CREATE TABLE IF NOT EXISTS image_jobs (account TEXT NOT NULL, local_id TEXT NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(account,local_id))") }
    private fun createHistorySettings(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS chat_options (account TEXT NOT NULL, chat TEXT NOT NULL, pinned INTEGER NOT NULL DEFAULT 0, muted INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(account,chat))")
        db.execSQL("CREATE TABLE IF NOT EXISTS sync_state (account TEXT PRIMARY KEY, initialized INTEGER NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createImageJobs(db)
        if (oldVersion < 3) {
            createHistorySettings(db)
            db.execSQL("ALTER TABLE messages ADD COLUMN search_text TEXT NOT NULL DEFAULT ''")
            db.rawQuery("SELECT account,key,type,content FROM messages", null).use { cursor ->
                while (cursor.moveToNext()) db.update("messages", ContentValues().apply {
                    put("search_text", searchable(cursor.getInt(2), cursor.getString(3)))
                }, "account=? AND key=?", arrayOf(cursor.getString(0), cursor.getString(1)))
            }
        }
    }
    private fun searchable(type: Int, content: String) = MessageCodec.text(Message("", "", "", "", type, content, 0))
    @Synchronized fun initialized(account: String): Boolean = readableDatabase.rawQuery("SELECT initialized FROM sync_state WHERE account=?", arrayOf(account)).use { it.moveToFirst() && it.getInt(0) == 1 }
    @Synchronized fun markInitialized(account: String) { writableDatabase.execSQL("INSERT OR REPLACE INTO sync_state(account,initialized) VALUES(?,1)", arrayOf(account)) }
    @Synchronized fun setOptions(account: String, chat: String, pinned: Boolean, muted: Boolean) {
        writableDatabase.execSQL("INSERT OR REPLACE INTO chat_options(account,chat,pinned,muted) VALUES(?,?,?,?)", arrayOf<Any>(account, chat, if (pinned) 1 else 0, if (muted) 1 else 0))
    }
    @Synchronized fun muted(account: String, chat: String): Boolean = readableDatabase.rawQuery("SELECT muted FROM chat_options WHERE account=? AND chat=?", arrayOf(account, chat)).use { it.moveToFirst() && it.getInt(0) == 1 }
    @Synchronized fun imageJobs(account: String): List<ImageJob> = readableDatabase.rawQuery("SELECT payload FROM image_jobs WHERE account=? ORDER BY rowid", arrayOf(account)).use { cursor ->
        buildList { while (cursor.moveToNext()) add(TemporaryImages.decode(cursor.getString(0))) }
    }
    @Synchronized fun saveImageJob(job: ImageJob) {
        val db = writableDatabase; db.beginTransaction()
        try {
            db.insertWithOnConflict("image_jobs", null, ContentValues().apply {
                put("account", job.account); put("local_id", job.localId); put("payload", TemporaryImages.encode(job))
            }, SQLiteDatabase.CONFLICT_REPLACE)
            saveMessages(job.account, listOf(job.message()))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    @Synchronized fun finishImageJob(job: ImageJob) {
        val db = writableDatabase; db.beginTransaction()
        try {
            if (job.cancelled) db.delete("messages", "account=? AND local_id=? AND id=''", arrayOf(job.account, job.localId))
            else saveMessages(job.account, listOf(job.message()))
            db.delete("image_jobs", "account=? AND local_id=?", arrayOf(job.account, job.localId))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    @Synchronized fun saveChats(account: String, chats: List<Chat>) {
        val db = writableDatabase; db.beginTransaction()
        try {
            chats.forEach { chat -> db.insertWithOnConflict("chats", null, ContentValues().apply {
                put("account", account); put("id", chat.id); put("target", chat.targetUid); put("name", chat.name)
                put("avatar", chat.avatar); put("preview", chat.preview); put("time", chat.updatedAt); put("unread", chat.unread)
            }, SQLiteDatabase.CONFLICT_REPLACE) }; db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    @Synchronized fun chats(account: String): List<Chat> = readableDatabase.rawQuery("SELECT c.*,COALESCE(o.pinned,0),COALESCE(o.muted,0) FROM chats c LEFT JOIN chat_options o ON c.account=o.account AND c.id=o.chat WHERE c.account=? ORDER BY COALESCE(o.pinned,0) DESC,c.time DESC", arrayOf(account)).use { c ->
        buildList { while (c.moveToNext()) add(Chat(c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getLong(6), c.getInt(7), c.getInt(8) == 1, c.getInt(9) == 1)) }
    }
    @Synchronized fun saveMessages(account: String, messages: List<Message>): List<Message> {
        val db = writableDatabase; val inserted = mutableListOf<Message>(); db.beginTransaction()
        try {
            messages.forEach { message ->
                val exists = db.rawQuery("SELECT 1 FROM messages WHERE account=? AND (key=? OR (local_id<>'' AND local_id=?)) LIMIT 1", arrayOf(account, message.key, message.localId)).use { it.moveToFirst() }
                if (!exists) inserted += message
                if (message.id.isNotBlank() && message.localId.isNotBlank()) db.delete("messages", "account=? AND local_id=? AND id=''", arrayOf(account, message.localId))
                db.insertWithOnConflict("messages", null, ContentValues().apply {
                    put("account", account); put("key", message.key); put("id", message.id); put("local_id", message.localId)
                    put("chat", message.chatId); put("sender", message.senderId); put("type", message.type); put("content", message.content)
                    put("time", message.time); put("delivery", message.delivery.name); put("error", message.error)
                    put("search_text", searchable(message.type, message.content))
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }; db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return inserted
    }
    @Synchronized fun messages(account: String, chatId: String): List<Message> = readableDatabase.rawQuery(
        "SELECT id,local_id,chat,sender,type,content,time,delivery,error FROM messages WHERE account=? AND chat=? ORDER BY time ASC,key ASC", arrayOf(account, chatId)).use { c ->
        buildList { while (c.moveToNext()) add(Message(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getInt(4), c.getString(5), c.getLong(6), Delivery.valueOf(c.getString(7)), c.getString(8))) }
    }
    @Synchronized fun cursor(account: String, chat: String): Pair<String, Boolean>? = readableDatabase.rawQuery(
        "SELECT cursor,is_last FROM cursors WHERE account=? AND chat=?", arrayOf(account, chat)).use { if (it.moveToFirst()) it.getString(0) to (it.getInt(1) != 0) else null }
    @Synchronized fun cursor(account: String, chat: String, cursor: String, isLast: Boolean) {
        writableDatabase.insertWithOnConflict("cursors", null, ContentValues().apply {
            put("account", account); put("chat", chat); put("cursor", cursor); put("is_last", if (isLast) 1 else 0)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }
    private fun selection(account: String, filter: HistoryFilter, cursor: SearchCursor? = null): Pair<String, Array<String>> {
        val clauses = mutableListOf("m.account=?"); val args = mutableListOf(account)
        if (filter.chatIds.isNotEmpty()) { clauses += "m.chat IN (${filter.chatIds.joinToString(",") { "?" }})"; args += filter.chatIds }
        if (filter.keyword.isNotBlank()) { clauses += "m.search_text LIKE ? ESCAPE '\\'"; args += HistoryDates.likeLiteral(filter.keyword.trim()) }
        filter.from?.let { clauses += "m.time>=?"; args += it.toString() }
        filter.until?.let { clauses += "m.time<?"; args += it.toString() }
        when (filter.sender) { SenderFilter.SELF -> { clauses += "m.sender=?"; args += account }; SenderFilter.OTHER -> { clauses += "m.sender<>?"; args += account }; else -> Unit }
        when (filter.type) { MessageFilter.TEXT -> clauses += "m.type IN (1,2,9)"; MessageFilter.IMAGES -> clauses += "m.type IN (3,8)"; else -> Unit }
        cursor?.let { clauses += "(m.time<? OR (m.time=? AND m.key<?))"; args += listOf(it.time.toString(), it.time.toString(), it.key) }
        return clauses.joinToString(" AND ") to args.toTypedArray()
    }
    @Synchronized fun search(account: String, filter: HistoryFilter, cursor: SearchCursor? = null, limit: Int = 50): SearchPage {
        val (where, args) = selection(account, filter, cursor)
        val hits = readableDatabase.rawQuery("SELECT m.id,m.local_id,m.chat,m.sender,m.type,m.content,m.time,m.delivery,m.error,COALESCE(c.name,'会话') FROM messages m LEFT JOIN chats c ON c.account=m.account AND c.id=m.chat WHERE $where ORDER BY m.time DESC,m.key DESC LIMIT ${(limit.coerceIn(1, 200) + 1)}", args).use { c ->
            buildList { while (c.moveToNext()) add(SearchHit(Message(c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getInt(4),c.getString(5),c.getLong(6),Delivery.valueOf(c.getString(7)),c.getString(8)),c.getString(9))) }
        }
        val page = hits.take(limit.coerceIn(1,200))
        return SearchPage(page, if (hits.size > page.size) page.last().message.let { SearchCursor(it.time, it.key) } else null)
    }
    @Synchronized fun coverage(account: String): List<HistoryCoverage> = chats(account).map { chat ->
        val count = readableDatabase.rawQuery("SELECT COUNT(*) FROM messages WHERE account=? AND chat=?", arrayOf(account,chat.id)).use { it.moveToFirst(); it.getInt(0) }
        HistoryCoverage(chat.id, chat.name, count, cursor(account,chat.id)?.second == true)
    }
    /** Consistent, bounded-memory snapshot stays in the app's private cache. No credentials. */
    @Synchronized fun exportSnapshot(account: String, filter: HistoryFilter, target: File, check: () -> Unit = {}): Int {
        val (where, args) = selection(account, filter)
        var count = 0
        target.bufferedWriter().use { out ->
            readableDatabase.rawQuery("SELECT m.id,m.local_id,m.chat,m.sender,m.type,m.content,m.time,m.delivery,m.error,COALESCE(c.name,'会话') FROM messages m LEFT JOIN chats c ON c.account=m.account AND c.id=m.chat WHERE $where AND m.id<>'' ORDER BY m.chat,m.time,m.key", args).use { c ->
                while (c.moveToNext()) {
                    check()
                    if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
                    out.write(JSONObject().put("id",c.getString(0)).put("local_id",c.getString(1)).put("chat_id",c.getString(2)).put("sender_id",c.getString(3)).put("type",c.getInt(4)).put("content",c.getString(5)).put("time",c.getLong(6)).put("delivery",c.getString(7)).put("error",c.getString(8)).put("chat_name",c.getString(9)).toString())
                    out.newLine(); count++
                }
            }
        }
        return count
    }
    @Synchronized fun markInterrupted(account: String) {
        writableDatabase.execSQL("UPDATE messages SET delivery='UNCERTAIN',error='发送结果待确认，请刷新核对' WHERE account=? AND delivery='SENDING' AND NOT EXISTS (SELECT 1 FROM image_jobs WHERE image_jobs.account=messages.account AND image_jobs.local_id=messages.local_id)", arrayOf(account))
    }
    @Synchronized fun clearAccount(account: String) {
        writableDatabase.beginTransaction()
        try { for (table in listOf("chats", "messages", "cursors", "image_jobs", "chat_options", "sync_state")) writableDatabase.delete(table, "account=?", arrayOf(account)); writableDatabase.setTransactionSuccessful() }
        finally { writableDatabase.endTransaction() }
    }
}

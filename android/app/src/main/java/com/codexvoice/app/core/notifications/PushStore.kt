package com.codexvoice.app.core.notifications

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class PushReceipt(val eventId: String, val stage: String)
data class PushState(val fid: String? = null, val preferences: PushPreferences = PushPreferences(), val events: List<PushEvent> = emptyList(),
    val receipts: List<PushReceipt> = emptyList(), val seen: Map<String, Long> = emptyMap()) {
    override fun toString() = "PushState(redacted)"
}
class PushStore private constructor(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "notifications.enc"))
    private val alias = "codex-voice-notifications-v1"
    private val gson = Gson()
    private val mutable = MutableStateFlow(read()); val state = mutable.asStateFlow()
    private fun key(create: Boolean): SecretKey {
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keys.getKey(alias, null) as? SecretKey)?.let { return it }; check(create)
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build()); generateKey()
        }
    }
    private fun read(): PushState {
        if (!file.baseFile.exists()) return PushState()
        val bytes = file.readFully(); require(bytes.size in 29..262144 && bytes[0] == 1.toByte())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, bytes.copyOfRange(1, 13))); cipher.updateAAD(alias.toByteArray())
        return gson.fromJson(cipher.doFinal(bytes.copyOfRange(13, bytes.size)).toString(Charsets.UTF_8), PushState::class.java).also {
            require(it.events.size <= 200 && it.receipts.size <= 100 && it.seen.size <= 500)
            require(it.fid == null || it.fid.matches(Regex("[cdef][A-Za-z0-9_-]{21}")))
        }
    }
    @Synchronized fun update(action: (PushState) -> PushState) {
        val next = action(mutable.value)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key(true)); cipher.updateAAD(alias.toByteArray())
        val bytes = byteArrayOf(1) + cipher.iv + cipher.doFinal(gson.toJson(next).toByteArray(Charsets.UTF_8)); require(bytes.size <= 262144)
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream); mutable.value = next } catch (e: Exception) { file.failWrite(stream); throw e }
    }
    fun register(fid: String) { require(fid.matches(Regex("[cdef][A-Za-z0-9_-]{21}"))); update { it.copy(fid = fid) } }
    @Synchronized fun accept(event: PushEvent, now: Long): Boolean {
        val current = mutable.value; if (!acceptsPush(event, current.preferences, current.seen, now)) return false
        val fresh = current.seen.filterValues { it > now }; if (fresh.size >= 500) return false
        update { it.copy(events = (it.events + event).takeLast(200), seen = fresh + (event.eventId to event.expires), receipts = queued(it.receipts, event.eventId, "received")) }
        return true
    }
    fun receipt(eventId: String, stage: String) {
        require(stage in setOf("received", "posted", "opened", "dismissed"))
        update { if (it.events.none { event -> event.eventId == eventId }) it else it.copy(receipts = queued(it.receipts, eventId, stage)) }
    }
    fun acknowledge(eventId: String, stage: String) = update { it.copy(receipts = it.receipts.filterNot { r -> r.eventId == eventId && r.stage == stage }) }
    private fun queued(rows: List<PushReceipt>, id: String, stage: String) = (rows.filterNot { it.eventId == id && it.stage == stage } + PushReceipt(id, stage)).takeLast(100)
    companion object {
        @Volatile private var instance: PushStore? = null
        fun get(context: Context): PushStore = instance ?: synchronized(this) { instance ?: PushStore(context.applicationContext).also { instance = it } }
    }
}

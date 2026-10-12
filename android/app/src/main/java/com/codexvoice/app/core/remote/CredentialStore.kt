package com.codexvoice.app.core.remote

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import com.google.gson.Gson

data class Credential(val endpoint: String, val deviceId: String, val token: String, val bridgeName: String, val enabled: Boolean = true, val lastConnected: Long = 0) {
    override fun toString() = "Credential(redacted)"
}
interface CredentialStore { fun load(): Credential?; fun save(credential: Credential); fun clear() }
class KeystoreCredentialStore(context: Context, private val alias: String = "codex-voice-pairing-v1") : CredentialStore {
    private val file = AtomicFile(File(context.noBackupFilesDir, "paired-pc.enc"))
    private val gson = Gson()
    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        check(create) { "Credential key unavailable" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }
    @Synchronized override fun load(): Credential? {
        if (!file.baseFile.exists()) return null
        val bytes = file.readFully(); require(bytes.size in 29..16384 && bytes[0] == 1.toByte())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
        cipher.updateAAD(alias.toByteArray())
        val credential = gson.fromJson(cipher.doFinal(bytes.copyOfRange(13, bytes.size)).toString(Charsets.UTF_8), Credential::class.java)
        RemoteProtocol.endpoint(credential.endpoint)
        require(credential.deviceId.matches(Regex("[a-f0-9-]{36}")) && credential.token.matches(Regex("[A-Za-z0-9_-]{43}")))
        return credential
    }
    @Synchronized override fun save(credential: Credential) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key(true)); cipher.updateAAD(alias.toByteArray())
        val bytes = byteArrayOf(1) + cipher.iv + cipher.doFinal(gson.toJson(credential).toByteArray(Charsets.UTF_8))
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) } catch (e: Exception) { file.failWrite(stream); throw e }
    }
    @Synchronized override fun clear() {
        file.delete()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); if (containsAlias(alias)) deleteEntry(alias) }
    }
}

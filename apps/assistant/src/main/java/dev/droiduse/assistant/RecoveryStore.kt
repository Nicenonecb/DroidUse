package dev.droiduse.assistant

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Private encrypted continuation state, separate from audit logs; no stored API key. */
class RecoveryStore(context: Context) {
    private val file=AtomicFile(File(context.noBackupFilesDir,"task-recovery.enc"))
    private val alias="droiduse.recovery.v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    @Synchronized fun save(value: JSONObject) {
        val raw=value.toString().toByteArray();require(raw.size<=1048576)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE,key());cipher.updateAAD(alias.toByteArray())
        val output=file.startWrite()
        try { output.write(byteArrayOf(1)+cipher.iv+cipher.doFinal(raw));file.finishWrite(output) }
        catch(error: Exception) { file.failWrite(output);throw error }
    }
    @Synchronized fun load(): JSONObject? {
        val bytes=try { file.readFully() } catch(_: java.io.FileNotFoundException) { return null }
        require(bytes.size in 29..1048605 && bytes[0]==1.toByte())
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,bytes.copyOfRange(1,13)))
        cipher.updateAAD(alias.toByteArray())
        return JSONObject(String(cipher.doFinal(bytes.copyOfRange(13,bytes.size)),Charsets.UTF_8))
    }
    @Synchronized fun clear() { file.delete() }
}

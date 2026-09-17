package dev.droiduse.assistant

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import dev.droiduse.agent.ModelProfile
import dev.droiduse.agent.Protocol
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class ProfileStore(context: Context) {
    data class State(val profiles: List<ModelProfile>, val activeId: String?)
    private val file = AtomicFile(File(context.noBackupFilesDir, "models.enc"))
    private val alias = "droiduse.models.v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun installBuiltInIfNeeded(context: Context, state: State): State {
        if (!BuildConfig.DEBUG || state.profiles.any { it.id == "builtin-qwen-max" }) return state
        val raw = try { context.assets.open("builtin-model.json").bufferedReader().use { it.readText() } }
            catch (_: java.io.FileNotFoundException) { return state }
        val json=JSONObject(raw)
        val profile=ModelProfile(json.getString("id"),json.getString("name"),json.getString("baseUrl"),json.getString("model"),json.getString("apiKey"))
        profile.validate()
        if(state.profiles.size >= 20) return state
        val next=State(state.profiles + profile,state.activeId ?: profile.id)
        save(next)
        return next
    }
    @Synchronized fun load(): State {
        val bytes = try { file.readFully() } catch (_: java.io.FileNotFoundException) { return State(emptyList(), null) }
        require(bytes.size >= 29 && bytes[0] == 1.toByte()) { "配置文件版本或内容不正确" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
        cipher.updateAAD("DroidUse-models-v1".toByteArray())
        val json = JSONObject(String(cipher.doFinal(bytes.copyOfRange(13, bytes.size)), Charsets.UTF_8))
        val array = json.getJSONArray("profiles")
        val profiles = (0 until array.length()).map { i -> array.getJSONObject(i).let {
            ModelProfile(it.getString("id"), it.getString("name"), it.getString("baseUrl"), it.getString("model"), it.getString("apiKey"), Protocol.valueOf(it.getString("protocol")))
        } }
        require(profiles.size <= 20 && profiles.map { it.id }.toSet().size == profiles.size)
        profiles.forEach { it.validate() }
        val active = json.optString("activeId").takeIf { id -> profiles.any { it.id == id } }
        return State(profiles, active)
    }
    @Synchronized fun save(state: State) {
        require(state.profiles.size <= 20 && state.profiles.map { it.id }.toSet().size == state.profiles.size)
        require(state.activeId == null || state.profiles.any { it.id == state.activeId })
        val profiles = JSONArray()
        state.profiles.forEach { p -> p.validate(); profiles.put(JSONObject().put("id", p.id).put("name", p.name)
            .put("baseUrl", p.baseUrl).put("model", p.model).put("apiKey", p.apiKey).put("protocol", p.protocol.name)) }
        val raw = JSONObject().put("version", 1).put("activeId", state.activeId ?: "").put("profiles", profiles).toString().toByteArray()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()); updateAAD("DroidUse-models-v1".toByteArray()) }
        val encrypted = byteArrayOf(1) + cipher.iv + cipher.doFinal(raw)
        val stream = file.startWrite()
        try { stream.write(encrypted); file.finishWrite(stream) } catch (e: Exception) { file.failWrite(stream); throw e }
    }
}

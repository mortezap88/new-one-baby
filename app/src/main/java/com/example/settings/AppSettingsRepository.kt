package com.example.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.example.BuildConfig
import org.json.JSONArray
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class AppThemePreference(val title: String) {
    SYSTEM("System default"),
    DARK("Dark mode"),
    LIGHT("Light mode");

    companion object {
        fun fromStoredValue(value: String?): AppThemePreference =
            entries.firstOrNull { it.name == value } ?: SYSTEM
    }
}

enum class ApiKeyProvider(
    val displayName: String,
    val placeholder: String
) {
    HOUSHYAR24(
        displayName = "Houshyar24",
        placeholder = "Add Houshyar24 API key (sk-hy24-...)"
    ),
    ONEXAI(
        displayName = "1xAI",
        placeholder = "Add 1xAI API key (sk-...)"
    )
}

data class AiModelOption(
    val id: String,
    val provider: ApiKeyProvider,
    val label: String,
    val endpointLabel: String,
    val note: String
)

data class AppSettings(
    val theme: AppThemePreference = AppThemePreference.SYSTEM,
    val deepseekModel: String = AppSettingsRepository.HOUSHYAR24_HAIKU_TTT_MODEL,
    val transcribeModel: String = AppSettingsRepository.ONEXAI_GEMINI_STT_MODEL,
    val tttProvider: ApiKeyProvider = ApiKeyProvider.HOUSHYAR24,
    val tokenStreaming: Boolean = true,
    val maxFontSizeSp: Int = 24,
    val ttsVoiceName: String? = null,
    val ttsRate: Float = AppSettingsRepository.DEFAULT_TTS_RATE,
    val activeOnexaiKeyIndex: Int = 0,
    val onexaiKeyPreviews: List<String> = emptyList(),
    val hasOnexaiApiKey: Boolean = false,
    val activeHoushyarKeyIndex: Int = 0,
    val houshyarKeyPreviews: List<String> = emptyList(),
    val hasHoushyarApiKey: Boolean = false,
    val errorMessage: String? = null
) {
    val totalStoredApiKeyCount: Int
        get() = houshyarKeyPreviews.size.takeIf { it > 0 } ?: onexaiKeyPreviews.size

    val activeTttModel: String
        get() = AppSettingsRepository.HOUSHYAR24_HAIKU_TTT_MODEL
}

class AppSettingsRepository(context: Context) {
    private val applicationContext = context.applicationContext
    private val preferences = applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )
    private val credentialsPreferences = applicationContext.getSharedPreferences(
        CREDENTIALS_PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    init {
        purgeLegacyUnusedKeys()
    }

    fun loadSettings(): AppSettings {
        val tttProviderName = preferences.getString(KEY_TTT_PROVIDER, ApiKeyProvider.HOUSHYAR24.name)
        val tttProvider = runCatching { ApiKeyProvider.valueOf(tttProviderName ?: ApiKeyProvider.HOUSHYAR24.name) }
            .getOrDefault(ApiKeyProvider.HOUSHYAR24)
        val houshyarPreviews = previewApiKeys(ApiKeyProvider.HOUSHYAR24)
        val onexaiPreviews = previewApiKeys(ApiKeyProvider.ONEXAI)
        return AppSettings(
            theme = AppThemePreference.fromStoredValue(preferences.getString(KEY_THEME, null)),
            deepseekModel = HOUSHYAR24_HAIKU_TTT_MODEL,
            transcribeModel = ONEXAI_GEMINI_STT_MODEL,
            tttProvider = ApiKeyProvider.HOUSHYAR24,
            tokenStreaming = preferences.getBoolean(KEY_TOKEN_STREAMING, true),
            maxFontSizeSp = preferences.getInt(KEY_MAX_FONT_SIZE, DEFAULT_MAX_FONT_SIZE)
                .coerceIn(MIN_MAX_FONT_SIZE, MAX_MAX_FONT_SIZE),
            ttsVoiceName = preferences.getString(KEY_TTS_VOICE, null),
            ttsRate = preferences.getFloat(KEY_TTS_RATE, DEFAULT_TTS_RATE)
                .coerceIn(MIN_TTS_RATE, MAX_TTS_RATE),
            activeOnexaiKeyIndex = activeKeyIndex(ApiKeyProvider.ONEXAI),
            onexaiKeyPreviews = onexaiPreviews,
            hasOnexaiApiKey = onexaiPreviews.isNotEmpty() || hasBuildConfiguredKey(ApiKeyProvider.ONEXAI),
            activeHoushyarKeyIndex = activeKeyIndex(ApiKeyProvider.HOUSHYAR24),
            houshyarKeyPreviews = houshyarPreviews,
            hasHoushyarApiKey = houshyarPreviews.isNotEmpty() || hasBuildConfiguredKey(ApiKeyProvider.HOUSHYAR24)
        )
    }

    fun saveTttProvider(provider: ApiKeyProvider) {
        preferences.edit().putString(KEY_TTT_PROVIDER, provider.name).apply()
    }

    fun saveTheme(theme: AppThemePreference) {
        preferences.edit().putString(KEY_THEME, theme.name).apply()
    }

    fun saveTokenStreaming(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_TOKEN_STREAMING, enabled).apply()
    }

    fun saveMaxFontSize(sizeSp: Int) {
        val clamped = sizeSp.coerceIn(MIN_MAX_FONT_SIZE, MAX_MAX_FONT_SIZE)
        preferences.edit().putInt(KEY_MAX_FONT_SIZE, clamped).apply()
    }

    fun saveTtsVoice(voiceName: String?) {
        val editor = preferences.edit()
        if (voiceName.isNullOrBlank()) {
            editor.remove(KEY_TTS_VOICE)
        } else {
            editor.putString(KEY_TTS_VOICE, voiceName)
        }
        editor.apply()
    }

    fun saveTtsRate(rate: Float) {
        preferences.edit().putFloat(KEY_TTS_RATE, rate.coerceIn(MIN_TTS_RATE, MAX_TTS_RATE)).apply()
    }

    // --- API key pool (1xAI & Houshyar24) ---------------------------------

    /** Decrypted keys for [provider] in pool order, falling back to Secrets BuildConfig or defaults if set. */
    fun getApiKeys(provider: ApiKeyProvider = ApiKeyProvider.HOUSHYAR24): List<String> {
        val stored = decryptedKeys(provider)
        if (stored.isNotEmpty()) return stored
        val buildKey = buildConfiguredKey(provider)
        if (!buildKey.isNullOrBlank()) return listOf(buildKey)
        return listOf(DEFAULT_HOUSHYAR24_KEY)
    }

    fun hasStoredApiKeys(provider: ApiKeyProvider = ApiKeyProvider.HOUSHYAR24): Boolean =
        readEncryptedEntries(provider).isNotEmpty()

    fun previewApiKeys(provider: ApiKeyProvider = ApiKeyProvider.HOUSHYAR24): List<String> =
        decryptedKeys(provider).map { key ->
            if (key.length <= 4) MASK else MASK + key.takeLast(4)
        }

    fun addApiKey(provider: ApiKeyProvider = ApiKeyProvider.HOUSHYAR24, apiKey: String) {
        val trimmed = apiKey.trim()
        require(trimmed.isNotEmpty()) {
            "Enter a ${provider.displayName} API key before saving."
        }
        val entries = readEncryptedEntries(provider)
        val existing = entries.mapNotNull { entry -> runCatching { decrypt(entry) }.getOrNull() }
        require(existing.none { it == trimmed }) { "That key is already in the ${provider.displayName} list." }
        entries.add(encrypt(trimmed))
        writeEntries(provider, entries)
    }

    fun removeApiKeyAt(provider: ApiKeyProvider = ApiKeyProvider.HOUSHYAR24, index: Int) {
        val entries = readEncryptedEntries(provider)
        if (index !in entries.indices) return
        entries.removeAt(index)
        writeEntries(provider, entries)
        if (activeKeyIndex(provider) >= entries.size) {
            saveActiveKeyIndex(provider, (entries.size - 1).coerceAtLeast(0))
        }
    }

    fun clearApiKeys(provider: ApiKeyProvider = ApiKeyProvider.HOUSHYAR24) {
        credentialsPreferences.edit()
            .remove(encryptedPrefKeyFor(provider))
            .apply()
        saveActiveKeyIndex(provider, 0)
    }

    fun activeKeyIndex(provider: ApiKeyProvider = ApiKeyProvider.HOUSHYAR24): Int =
        preferences.getInt(activeIndexPrefKeyFor(provider), 0).coerceAtLeast(0)

    fun saveActiveKeyIndex(provider: ApiKeyProvider = ApiKeyProvider.HOUSHYAR24, index: Int) {
        preferences.edit().putInt(activeIndexPrefKeyFor(provider), index.coerceAtLeast(0)).apply()
    }

    /** Returns user-stored API keys (decrypted, in pool order) for `.flashdata` backups. */
    fun exportStoredApiKeys(provider: ApiKeyProvider = ApiKeyProvider.HOUSHYAR24): List<String> =
        decryptedKeys(provider)

    /** Atomically restores all settings and re-encrypts imported API keys for this device. */
    fun restoreFromBackup(
        theme: AppThemePreference,
        tokenStreaming: Boolean,
        maxFontSizeSp: Int,
        ttsVoiceName: String?,
        ttsRate: Float,
        onexaiApiKeys: List<String>,
        activeOnexaiKeyIndex: Int,
        houshyarApiKeys: List<String> = emptyList(),
        activeHoushyarKeyIndex: Int = 0,
        tttProvider: ApiKeyProvider = ApiKeyProvider.ONEXAI
    ) {
        val cleanKeys = onexaiApiKeys.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val encryptedKeys = cleanKeys.map { encrypt(it) }

        val cleanHoushyarKeys = houshyarApiKeys.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val encryptedHoushyarKeys = cleanHoushyarKeys.map { encrypt(it) }

        val credsEditor = credentialsPreferences.edit()
            .putString(KEY_ENCRYPTED_ONEXAI_API_KEYS, JSONArray(encryptedKeys).toString())
            .remove(KEY_ENCRYPTED_AVALAI_API_KEYS)
            .remove(LEGACY_KEY_ENCRYPTED_DEEPGRAM_API_KEYS)
            .remove(LEGACY_KEY_ENCRYPTED_REWIND_API_KEYS)

        if (cleanHoushyarKeys.isNotEmpty()) {
            credsEditor.putString(KEY_ENCRYPTED_HOUSHYAR24_API_KEYS, JSONArray(encryptedHoushyarKeys).toString())
        }
        credsEditor.commit()

        val resolvedFontSize = maxFontSizeSp.coerceIn(MIN_MAX_FONT_SIZE, MAX_MAX_FONT_SIZE)
        val resolvedTtsRate = ttsRate.coerceIn(MIN_TTS_RATE, MAX_TTS_RATE)
        val resolvedKeyIdx = if (cleanKeys.isEmpty()) {
            0
        } else {
            activeOnexaiKeyIndex.coerceIn(0, cleanKeys.lastIndex)
        }
        val resolvedHoushyarIdx = if (cleanHoushyarKeys.isEmpty()) {
            0
        } else {
            activeHoushyarKeyIndex.coerceIn(0, cleanHoushyarKeys.lastIndex)
        }

        val editor = preferences.edit()
            .putString(KEY_THEME, theme.name)
            .putString(KEY_TTT_PROVIDER, tttProvider.name)
            .putBoolean(KEY_TOKEN_STREAMING, tokenStreaming)
            .putInt(KEY_MAX_FONT_SIZE, resolvedFontSize)
            .putFloat(KEY_TTS_RATE, resolvedTtsRate)
            .putInt(KEY_ACTIVE_ONEXAI_KEY_INDEX, resolvedKeyIdx)
            .putInt(KEY_ACTIVE_HOUSHYAR24_KEY_INDEX, resolvedHoushyarIdx)
            .remove(KEY_ACTIVE_AVALAI_KEY_INDEX)
            .remove(LEGACY_KEY_ACTIVE_DEEPGRAM_KEY_INDEX)
            .remove(LEGACY_KEY_ACTIVE_REWIND_KEY_INDEX)

        if (ttsVoiceName.isNullOrBlank()) {
            editor.remove(KEY_TTS_VOICE)
        } else {
            editor.putString(KEY_TTS_VOICE, ttsVoiceName)
        }
        editor.commit()
    }

    private fun purgeLegacyUnusedKeys() {
        val credsEditor = credentialsPreferences.edit()
        var credsChanged = false
        listOf(
            KEY_ENCRYPTED_AVALAI_API_KEYS,
            LEGACY_KEY_ENCRYPTED_DEEPGRAM_API_KEYS,
            LEGACY_KEY_ENCRYPTED_REWIND_API_KEYS
        ).forEach { key ->
            if (credentialsPreferences.contains(key)) {
                credsEditor.remove(key)
                credsChanged = true
            }
        }
        if (credsChanged) credsEditor.apply()

        val prefEditor = preferences.edit()
        var prefChanged = false
        listOf(
            KEY_ACTIVE_AVALAI_KEY_INDEX,
            LEGACY_KEY_ACTIVE_DEEPGRAM_KEY_INDEX,
            LEGACY_KEY_ACTIVE_REWIND_KEY_INDEX
        ).forEach { key ->
            if (preferences.contains(key)) {
                prefEditor.remove(key)
                prefChanged = true
            }
        }
        if (prefChanged) prefEditor.apply()
    }

    private fun encryptedPrefKeyFor(provider: ApiKeyProvider): String = when (provider) {
        ApiKeyProvider.ONEXAI -> KEY_ENCRYPTED_ONEXAI_API_KEYS
        ApiKeyProvider.HOUSHYAR24 -> KEY_ENCRYPTED_HOUSHYAR24_API_KEYS
    }

    private fun activeIndexPrefKeyFor(provider: ApiKeyProvider): String = when (provider) {
        ApiKeyProvider.ONEXAI -> KEY_ACTIVE_ONEXAI_KEY_INDEX
        ApiKeyProvider.HOUSHYAR24 -> KEY_ACTIVE_HOUSHYAR24_KEY_INDEX
    }

    private fun decryptedKeys(provider: ApiKeyProvider): List<String> =
        readEncryptedEntries(provider).mapNotNull { entry ->
            runCatching { decrypt(entry).trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
        }

    private fun readEncryptedEntries(provider: ApiKeyProvider): MutableList<String> {
        val raw = credentialsPreferences.getString(encryptedPrefKeyFor(provider), null)
        val entries = mutableListOf<String>()
        if (!raw.isNullOrBlank()) {
            runCatching {
                val array = JSONArray(raw)
                for (index in 0 until array.length()) {
                    array.optString(index).takeIf { it.isNotBlank() }?.let(entries::add)
                }
            }
        }
        return entries
    }

    private fun writeEntries(provider: ApiKeyProvider, entries: List<String>) {
        credentialsPreferences.edit()
            .putString(encryptedPrefKeyFor(provider), JSONArray(entries).toString())
            .apply()
    }

    private fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateEncryptionKey())
        val ciphertext = cipher.doFinal(plainText.toByteArray())
        val payload = ByteBuffer.allocate(Int.SIZE_BYTES + cipher.iv.size + ciphertext.size)
        payload.putInt(cipher.iv.size)
        payload.put(cipher.iv)
        payload.put(ciphertext)
        return Base64.encodeToString(payload.array(), Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val payload = ByteBuffer.wrap(Base64.decode(encoded, Base64.NO_WRAP))
        val ivSize = payload.int
        require(ivSize in 1..payload.remaining()) { "Stored API key is invalid." }
        val iv = ByteArray(ivSize)
        payload.get(iv)
        val ciphertext = ByteArray(payload.remaining())
        payload.get(ciphertext)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateEncryptionKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    private fun getOrCreateEncryptionKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        val existingKey = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existingKey != null) return existingKey

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        keyGenerator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return keyGenerator.generateKey()
    }

    private fun buildConfiguredKey(provider: ApiKeyProvider): String? = when (provider) {
        ApiKeyProvider.ONEXAI -> {
            val fromSecrets = runCatching {
                val f = BuildConfig::class.java.getField("ONEXAI_API_KEY")
                (f.get(null) as? String)?.trim()
            }.getOrNull()?.takeIf { it.isNotEmpty() && it != "MY_ONEXAI_API_KEY" }
            fromSecrets ?: DEFAULT_1XAI_KEY
        }
        ApiKeyProvider.HOUSHYAR24 -> {
            val fromSecrets = runCatching {
                val f = BuildConfig::class.java.getField("HOUSHYAR24_API_KEY")
                (f.get(null) as? String)?.trim()
            }.getOrNull()?.takeIf { it.isNotEmpty() && it != "MY_HOUSHYAR24_API_KEY" }
            fromSecrets ?: DEFAULT_HOUSHYAR24_KEY
        }
    }

    private fun hasBuildConfiguredKey(provider: ApiKeyProvider): Boolean =
        buildConfiguredKey(provider) != null

    companion object {
        const val HOUSHYAR24_LUNA_TTT_MODEL = "code/gpt-6-luna"
        const val HOUSHYAR24_HAIKU_TTT_MODEL = HOUSHYAR24_LUNA_TTT_MODEL
        const val ONEXAI_GEMINI_STT_MODEL = "gemini-3.5-flash-lite"

        const val LUNA_TTT_MODEL = HOUSHYAR24_LUNA_TTT_MODEL
        const val HAIKU_TTT_MODEL = LUNA_TTT_MODEL
        const val GEMINI_STT_MODEL = ONEXAI_GEMINI_STT_MODEL

        const val DEEPSEEK_CHAT_MODEL = LUNA_TTT_MODEL
        const val DEEPSEEK_REASONER_MODEL = LUNA_TTT_MODEL
        const val SCRIBE_V2_STT_MODEL = ONEXAI_GEMINI_STT_MODEL

        const val LOCKED_REASONING_EFFORT = "low"
        const val MINIMAL_REASONING_EFFORT = "none"
        const val DEFAULT_HOUSHYAR24_KEY = "sk-hy24-jsoTetbpMTFJM1azqmeCjrO0thn5qrpiB-Yg9N2rhv0"
        const val DEFAULT_1XAI_KEY = "1xai-CE_Pr3nMYmbT9zM6l0WdsZX6--Z4vGXxM_t3nWe1XwI"
        const val HOUSHYAR24_BASE_URL = "https://houshyar24.ir/api/openai/v1"
        const val ONEXAI_BASE_URL = "https://1xai.ir/v1"

        val LUNA_HOUSHYAR24_OPTION = AiModelOption(
            id = HOUSHYAR24_LUNA_TTT_MODEL,
            provider = ApiKeyProvider.HOUSHYAR24,
            label = "GPT 6 Luna (Houshyar24)",
            endpointLabel = "code/gpt-6-luna · thinking: low (1024 tokens) · houshyar24.ir/api/openai/v1",
            note = "Main answers: chat, tutoring evaluation & question generation"
        )
        val HAIKU_HOUSHYAR24_OPTION = LUNA_HOUSHYAR24_OPTION

        val GEMINI_1XAI_OPTION = AiModelOption(
            id = ONEXAI_GEMINI_STT_MODEL,
            provider = ApiKeyProvider.ONEXAI,
            label = "Gemini 3.5 Flash-Lite (1xAI)",
            endpointLabel = "gemini-3.5-flash-lite · thinking: none · 1xai.ir/v1",
            note = "Ultra-fast voice transcription & linguistic analysis (Persian & English)"
        )

        val LUNA_1XAI_OPTION = LUNA_HOUSHYAR24_OPTION
        val HAIKU_1XAI_OPTION = LUNA_HOUSHYAR24_OPTION
        val GEMINI_MODEL_OPTION = GEMINI_1XAI_OPTION

        val LUNA_MODEL_OPTION = LUNA_HOUSHYAR24_OPTION
        val HAIKU_MODEL_OPTION = LUNA_HOUSHYAR24_OPTION
        val DEEPSEEK_MODEL_OPTION = LUNA_MODEL_OPTION
        val TRANSCRIBE_MODEL_OPTION = GEMINI_1XAI_OPTION

        val TTT_MODEL_OPTIONS = listOf(
            LUNA_HOUSHYAR24_OPTION
        )

        val SUPPORTED_MODELS = listOf(
            LUNA_HOUSHYAR24_OPTION,
            GEMINI_1XAI_OPTION
        )

        const val DEFAULT_MAX_FONT_SIZE = 24
        const val MIN_MAX_FONT_SIZE = 18
        const val MAX_MAX_FONT_SIZE = 48

        const val DEFAULT_TTS_RATE = 1f
        const val MIN_TTS_RATE = 0.5f
        const val MAX_TTS_RATE = 2f

        private const val MASK = "\u2022\u2022\u2022\u2022"
        private const val PREFERENCES_NAME = "flashrecall_settings"
        private const val CREDENTIALS_PREFERENCES_NAME = "flashrecall_credentials"
        private const val KEY_THEME = "theme"
        private const val KEY_TTT_PROVIDER = "ttt_provider"
        private const val KEY_TOKEN_STREAMING = "token_streaming"
        private const val KEY_MAX_FONT_SIZE = "max_font_size"
        private const val KEY_TTS_VOICE = "tts_voice"
        private const val KEY_TTS_RATE = "tts_rate"
        private const val KEY_ACTIVE_ONEXAI_KEY_INDEX = "active_1xai_api_key_index"
        private const val KEY_ENCRYPTED_ONEXAI_API_KEYS = "encrypted_1xai_api_keys"
        private const val KEY_ACTIVE_HOUSHYAR24_KEY_INDEX = "active_houshyar24_api_key_index"
        private const val KEY_ENCRYPTED_HOUSHYAR24_API_KEYS = "encrypted_houshyar24_api_keys"
        private const val KEY_ACTIVE_AVALAI_KEY_INDEX = "active_deepseek_api_key_index"
        private const val KEY_ENCRYPTED_AVALAI_API_KEYS = "encrypted_deepseek_api_keys"
        private const val LEGACY_KEY_ACTIVE_DEEPGRAM_KEY_INDEX = "active_deepgram_api_key_index"
        private const val LEGACY_KEY_ENCRYPTED_DEEPGRAM_API_KEYS = "encrypted_deepgram_api_keys"
        private const val LEGACY_KEY_ACTIVE_REWIND_KEY_INDEX = "active_rewind_api_key_index"
        private const val LEGACY_KEY_ENCRYPTED_REWIND_API_KEYS = "encrypted_rewind_api_keys"
        private const val KEY_ALIAS = "flashrecall_gemini_api_key"
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

package com.example.network

import android.util.Base64
import com.example.audio.AudioTranscoder
import com.example.settings.ApiKeyProvider
import com.example.settings.AppSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * OpenAI-compatible AvalAI client supporting:
 * - Main TTT model: DeepSeek chat completions (`deepseek-chat` via `/v1/chat/completions`, locked to `reasoning_effort = "low"`)
 * - STT-only model: ElevenLabs Scribe v2 (`scribe_v2` via `/v1/audio/transcriptions` multipart upload)
 * with a shared HTTP/2 keep-alive connection pool, cold-start host racing (`api.avalai.org` vs `api.avalai.ir`),
 * sticky fastest-endpoint memory, and key-pool rotation.
 */
class AvalAiClient(
    private val okHttpClient: OkHttpClient = sharedOkHttpClient
) {

    data class Message(
        val role: String, // "system", "user", "assistant"
        val content: String,
        val imageBase64List: List<String> = emptyList()
    )

    class AvalAiHttpException(
        val statusCode: Int,
        val detail: String,
        message: String
    ) : IOException(message) {
        val isKeyRotatable: Boolean
            get() = isRotatableKeyStatus(statusCode, detail)
    }

    class PartialStreamInterruptedException(
        val partialContent: String,
        message: String,
        cause: Throwable? = null
    ) : IOException(message, cause)

    class ReasoningTruncatedException(
        val reasoningChars: Int,
        message: String
    ) : IOException(message)

    suspend fun completeWithSettings(
        messages: List<Message>,
        settingsRepository: AppSettingsRepository,
        temperature: Double? = 0.7,
        reasoningEffort: String = LOCKED_REASONING_EFFORT,
        maxTokens: Int? = null,
        modelOverride: String? = null,
        streamResponses: Boolean = false,
        onReasoningProgress: (Int) -> Unit = {},
        onPartialText: (String) -> Unit = {}
    ): Result<String> {
        val settings = settingsRepository.loadSettings()
        val provider = settings.tttProvider
        val keys = settingsRepository.getApiKeys(provider)
        if (keys.isEmpty()) {
            return Result.failure(IllegalStateException("Add a ${provider.displayName} API key in Settings to use GPT 6 Luna."))
        }
        val activeIndex = settingsRepository.activeKeyIndex(provider)
        val candidates = ApiKeyRotation.order(keys.size, activeIndex).map { idx -> idx to keys[idx] }
        val resolvedModel = modelOverride ?: settings.activeTttModel
        val (endpoints, serviceLabel) = HOUSHYAR24_CHAT_COMPLETIONS_URLS to "GPT 6 Luna (Houshyar24)"
        return complete(
            messages = messages,
            apiKeyCandidates = candidates,
            model = resolvedModel,
            temperature = temperature,
            reasoningEffort = reasoningEffort,
            maxTokens = maxTokens,
            streamResponses = streamResponses,
            endpoints = endpoints,
            serviceLabel = serviceLabel,
            onReasoningProgress = onReasoningProgress,
            onPartialText = onPartialText,
            onKeySucceeded = { winnerIndex ->
                if (winnerIndex != activeIndex) {
                    settingsRepository.saveActiveKeyIndex(provider, winnerIndex)
                }
            }
        )
    }

    suspend fun complete(
        messages: List<Message>,
        apiKeyCandidates: List<Pair<Int, String>>,
        model: String = AppSettingsRepository.HOUSHYAR24_LUNA_TTT_MODEL,
        temperature: Double? = 0.7,
        reasoningEffort: String = LOCKED_REASONING_EFFORT,
        maxTokens: Int? = null,
        streamResponses: Boolean = false,
        endpoints: List<String> = HOUSHYAR24_CHAT_COMPLETIONS_URLS,
        serviceLabel: String = "GPT 6 Luna (Houshyar24)",
        onReasoningProgress: (Int) -> Unit = {},
        onPartialText: (String) -> Unit = {},
        onKeySucceeded: (Int) -> Unit = {}
    ): Result<String> = withContext(Dispatchers.IO) {
        val validCandidates = apiKeyCandidates
            .map { (idx, key) -> idx to key.trim() }
            .filter { (_, key) -> key.isNotEmpty() && key != "MY_AVALAI_API_KEY" }

        if (validCandidates.isEmpty()) {
            return@withContext Result.failure(IllegalStateException(MISSING_AVALAI_KEY_MESSAGE))
        }

        val isHighThinking = reasoningEffort.equals(HIGH_REASONING_EFFORT, ignoreCase = true) ||
            model.equals(AppSettingsRepository.DEEPSEEK_REASONER_MODEL, ignoreCase = true)
        val useExtendedTimeout = isHighThinking || (maxTokens != null && maxTokens > 4096)

        var lastError: Throwable? = null
        var triedMultipleKeys = false

        for ((poolIndex, apiKey) in validCandidates) {
            try {
                val text = executeWithParameterFallbacks(
                    apiKey = apiKey,
                    model = model,
                    messages = messages,
                    temperature = temperature,
                    reasoningEffort = reasoningEffort,
                    maxTokens = maxTokens,
                    streamResponses = streamResponses,
                    useExtendedTimeout = useExtendedTimeout,
                    endpoints = endpoints,
                    serviceLabel = serviceLabel,
                    onReasoningProgress = onReasoningProgress,
                    onPartialText = onPartialText
                )
                onKeySucceeded(poolIndex)
                return@withContext Result.success(text)
            } catch (partialErr: PartialStreamInterruptedException) {
                // Content already streamed to caller — never restart on another key!
                onKeySucceeded(poolIndex)
                return@withContext Result.failure(partialErr)
            } catch (error: Exception) {
                lastError = error
                val canRotate = (error as? AvalAiHttpException)?.isKeyRotatable == true
                if (!canRotate) {
                    return@withContext Result.failure(error)
                }
                triedMultipleKeys = true
            }
        }

        val suffix = if (triedMultipleKeys && validCandidates.size > 1) {
            " Tried all ${validCandidates.size} $serviceLabel API keys."
        } else {
            ""
        }
        Result.failure(
            IllegalStateException(
                (lastError?.localizedMessage ?: "$serviceLabel completion failed.") + suffix,
                lastError
            )
        )
    }

    private fun executeWithParameterFallbacks(
        apiKey: String,
        model: String,
        messages: List<Message>,
        temperature: Double?,
        reasoningEffort: String?,
        maxTokens: Int?,
        streamResponses: Boolean,
        useExtendedTimeout: Boolean,
        endpoints: List<String> = CHAT_COMPLETIONS_URLS,
        serviceLabel: String = "GPT 6 Luna",
        onReasoningProgress: (Int) -> Unit,
        onPartialText: (String) -> Unit
    ): String {
        var activeModel = model
        var activeTemp = temperature
        var activeReasoning = reasoningEffort
        var activeMaxTokens = maxTokens
        var retriedTruncation = false

        val hasImages = messages.any { it.imageBase64List.isNotEmpty() }
        var visionFallbackIdx = 0

        repeat(6) {
            val payloadJson = buildChatCompletionsPayload(
                model = activeModel,
                messages = messages,
                temperature = activeTemp,
                stream = streamResponses,
                reasoningEffort = activeReasoning,
                maxTokens = activeMaxTokens
            )
            try {
                return executeAcrossEndpoints(
                    apiKey = apiKey,
                    payloadJson = payloadJson,
                    stream = streamResponses,
                    onReasoningProgress = onReasoningProgress,
                    onPartialText = onPartialText,
                    useExtendedTimeout = useExtendedTimeout,
                    endpoints = endpoints,
                    serviceLabel = serviceLabel
                )
            } catch (truncErr: ReasoningTruncatedException) {
                if (!retriedTruncation) {
                    retriedTruncation = true
                    if (activeMaxTokens == null || activeMaxTokens < 32768) {
                        activeMaxTokens = 32768
                        if (activeModel == AppSettingsRepository.DEEPSEEK_CHAT_MODEL) {
                            activeModel = AppSettingsRepository.DEEPSEEK_REASONER_MODEL
                        }
                        return@repeat
                    }
                }
                throw truncErr
            } catch (httpErr: AvalAiHttpException) {
                when {
                    hasImages &&
                        (httpErr.statusCode == 400 || httpErr.statusCode == 404 || httpErr.statusCode == 422) &&
                        visionFallbackIdx < VISION_FALLBACK_MODELS.size &&
                        (isVisionUnsupported(httpErr.statusCode, httpErr.detail) ||
                            isModelUnsupported(httpErr.statusCode, httpErr.detail)) -> {
                        var candidate = VISION_FALLBACK_MODELS.getOrNull(visionFallbackIdx)
                        while (candidate != null && candidate.equals(activeModel, ignoreCase = true)) {
                            visionFallbackIdx++
                            candidate = VISION_FALLBACK_MODELS.getOrNull(visionFallbackIdx)
                        }
                        if (candidate != null) {
                            activeModel = candidate
                            visionFallbackIdx++
                        } else {
                            throw httpErr
                        }
                    }
                    (httpErr.statusCode == 400 || httpErr.statusCode == 404) &&
                        activeModel != AppSettingsRepository.DEEPSEEK_CHAT_MODEL &&
                        activeModel != AppSettingsRepository.HOUSHYAR24_HAIKU_TTT_MODEL &&
                        isModelUnsupported(httpErr.statusCode, httpErr.detail) -> {
                        activeModel = AppSettingsRepository.DEEPSEEK_CHAT_MODEL
                    }
                    httpErr.statusCode == 400 && activeTemp != null && isTemperatureRejectedForReasoning(httpErr.detail) -> {
                        activeTemp = null
                    }
                    httpErr.statusCode == 400 && activeMaxTokens != null && isMaxTokensRejected(httpErr.detail) -> {
                        activeMaxTokens = null
                    }
                    httpErr.statusCode == 400 && activeReasoning != null && isReasoningEffortUnsupported(httpErr.detail) -> {
                        activeReasoning = null
                    }
                    else -> throw httpErr
                }
            }
        }

        val finalPayload = buildChatCompletionsPayload(
            model = activeModel,
            messages = messages,
            temperature = activeTemp,
            stream = streamResponses,
            reasoningEffort = activeReasoning,
            maxTokens = activeMaxTokens
        )
        return executeAcrossEndpoints(
            apiKey = apiKey,
            payloadJson = finalPayload,
            stream = streamResponses,
            onReasoningProgress = onReasoningProgress,
            onPartialText = onPartialText,
            useExtendedTimeout = useExtendedTimeout,
            endpoints = endpoints,
            serviceLabel = serviceLabel
        )
    }

    suspend fun transcribeBase64Audio(
        audioBase64: String,
        mimeType: String? = null,
        settingsRepository: AppSettingsRepository
    ): Result<String> = withContext(Dispatchers.IO) {
        if (audioBase64.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Audio is empty."))
        }
        val audioBytes = try {
            Base64.decode(audioBase64, Base64.DEFAULT)
        } catch (e: Exception) {
            return@withContext Result.failure(
                IllegalArgumentException("Recorded audio could not be decoded.", e)
            )
        }
        val wavBytes = AudioTranscoder.ensureWavBytes(audioBytes)
        if (!AudioTranscoder.hasAudibleSpeech(wavBytes)) {
            return@withContext Result.success("")
        }
        transcribeAudioBytesWithSettings(
            audioBytes = wavBytes,
            mimeType = "audio/wav",
            fileName = "recording.wav",
            settingsRepository = settingsRepository
        )
    }

    suspend fun transcribeAndAnalyzeBase64Audio(
        audioBase64: String,
        mimeType: String? = null,
        recentContext: List<Pair<String, String>> = emptyList(),
        settingsRepository: AppSettingsRepository
    ): Result<SpokenAudioAnalysisResult> = withContext(Dispatchers.IO) {
        if (audioBase64.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Audio is empty."))
        }
        val audioBytes = try {
            Base64.decode(audioBase64, Base64.DEFAULT)
        } catch (e: Exception) {
            return@withContext Result.failure(
                IllegalArgumentException("Recorded audio could not be decoded.", e)
            )
        }
        val wavBytes = AudioTranscoder.ensureWavBytes(audioBytes)
        if (!AudioTranscoder.hasAudibleSpeech(wavBytes)) {
            return@withContext Result.success(SpokenAudioAnalysisResult(transcript = "", corrections = emptyList()))
        }
        val keys = settingsRepository.getApiKeys(ApiKeyProvider.ONEXAI)
        if (keys.isEmpty()) {
            return@withContext Result.failure(IllegalStateException("Add a 1xAI API key in Settings for Gemini 3.5 Flash-Lite speech-to-text and linguistic analysis."))
        }
        val activeIndex = settingsRepository.activeKeyIndex(ApiKeyProvider.ONEXAI)
        val candidates = ApiKeyRotation.order(keys.size, activeIndex).map { idx -> idx to keys[idx] }

        executeGeminiTranscriptionAndLinguisticAnalysis(
            audioBytes = wavBytes,
            mimeType = "audio/wav",
            recentContext = recentContext,
            apiKeyCandidates = candidates,
            model = AppSettingsRepository.ONEXAI_GEMINI_STT_MODEL,
            onKeySucceeded = { winnerIndex ->
                if (winnerIndex != activeIndex) {
                    settingsRepository.saveActiveKeyIndex(ApiKeyProvider.ONEXAI, winnerIndex)
                }
            }
        )
    }

    suspend fun transcribeAudioBytesWithSettings(
        audioBytes: ByteArray,
        mimeType: String,
        fileName: String? = null,
        settingsRepository: AppSettingsRepository
    ): Result<String> {
        val keys = settingsRepository.getApiKeys(ApiKeyProvider.ONEXAI)
        if (keys.isEmpty()) {
            return Result.failure(IllegalStateException("Add a 1xAI API key in Settings for Gemini 3.5 Flash-Lite speech-to-text."))
        }
        val activeIndex = settingsRepository.activeKeyIndex(ApiKeyProvider.ONEXAI)
        val candidates = ApiKeyRotation.order(keys.size, activeIndex).map { idx -> idx to keys[idx] }
        return transcribeAudioBytes(
            audioBytes = audioBytes,
            mimeType = mimeType,
            fileName = fileName,
            apiKeyCandidates = candidates,
            model = AppSettingsRepository.ONEXAI_GEMINI_STT_MODEL,
            onKeySucceeded = { winnerIndex ->
                if (winnerIndex != activeIndex) {
                    settingsRepository.saveActiveKeyIndex(ApiKeyProvider.ONEXAI, winnerIndex)
                }
            }
        )
    }

    suspend fun transcribeAudioBytes(
        audioBytes: ByteArray,
        mimeType: String,
        fileName: String? = null,
        apiKeyCandidates: List<Pair<Int, String>>,
        model: String = AppSettingsRepository.GEMINI_STT_MODEL,
        onKeySucceeded: (Int) -> Unit = {}
    ): Result<String> = withContext(Dispatchers.IO) {
        if (audioBytes.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Audio recording is empty."))
        }
        val validCandidates = apiKeyCandidates
            .map { (idx, key) -> idx to key.trim() }
            .filter { (_, key) -> key.isNotEmpty() && key != "MY_AVALAI_API_KEY" }

        if (validCandidates.isEmpty()) {
            return@withContext Result.failure(IllegalStateException(MISSING_AVALAI_KEY_MESSAGE))
        }

        val resolvedFileName = resolveAudioFileName(mimeType, fileName)
        val resolvedMimeType = resolveMultipartAudioMimeType(mimeType, resolvedFileName)
        var lastError: Throwable? = null
        var triedMultipleKeys = false

        for ((poolIndex, apiKey) in validCandidates) {
            try {
                val transcript = executeScribeV2AudioTranscription(
                    apiKey = apiKey,
                    audioBytes = audioBytes,
                    mimeType = resolvedMimeType,
                    fileName = resolvedFileName,
                    model = model
                )
                onKeySucceeded(poolIndex)
                return@withContext Result.success(transcript)
            } catch (error: Exception) {
                lastError = error
                val canRotate = (error as? AvalAiHttpException)?.isKeyRotatable == true
                if (!canRotate) {
                    return@withContext Result.failure(error)
                }
                triedMultipleKeys = true
            }
        }

        val suffix = if (triedMultipleKeys && validCandidates.size > 1) {
            " Tried all ${validCandidates.size} 1xAI API keys."
        } else {
            ""
        }
        Result.failure(
            IllegalStateException(
                (lastError?.localizedMessage ?: "Gemini 3.5 Flash-Lite audio transcription failed.") + suffix,
                lastError
            )
        )
    }

    private suspend fun executeGeminiChatTranscription(
        apiKey: String,
        audioBytes: ByteArray,
        mimeType: String,
        model: String
    ): String {
        val wavBytes = AudioTranscoder.ensureWavBytes(audioBytes)
        if (!AudioTranscoder.hasAudibleSpeech(wavBytes)) {
            return ""
        }
        val b64 = Base64.encodeToString(wavBytes, Base64.NO_WRAP)

        val resolvedModel = AppSettingsRepository.ONEXAI_GEMINI_STT_MODEL
        val endpoints = ONEXAI_CHAT_COMPLETIONS_URLS
        val serviceLabel = "Gemini 3.5 Flash-Lite (1xAI STT)"

        val payload = JSONObject().apply {
            put("model", resolvedModel)
            put("reasoning_effort", "none")
            put("temperature", 0.0)
            val messages = JSONArray()
            val sysMsg = JSONObject().apply {
                put("role", "system")
                put("content", "You are an accurate, verbatim speech-to-text engine. Output ONLY the words spoken in the audio in the original language (Persian or English). Do not add any preamble, explanation, notes, or quotes. If there is no audible speech or only background silence, respond with [SILENCE].")
            }
            val userMsg = JSONObject().apply {
                put("role", "user")
                val contentArr = JSONArray()
                contentArr.put(JSONObject().put("type", "text").put("text", "Transcribe this audio verbatim in original language:"))
                contentArr.put(JSONObject().apply {
                    put("type", "image_url")
                    put("image_url", JSONObject().put("url", "data:audio/wav;base64,$b64"))
                })
                put("content", contentArr)
            }
            messages.put(sysMsg)
            messages.put(userMsg)
            put("messages", messages)
        }.toString()

        return try {
            val text = executeAcrossEndpoints(
                apiKey = apiKey,
                payloadJson = payload,
                stream = false,
                onPartialText = {},
                endpoints = endpoints,
                serviceLabel = serviceLabel
            )
            sanitizeTranscriptOutput(text)
        } catch (e: Exception) {
            val fallbackPayload = JSONObject().apply {
                put("model", resolvedModel)
                put("temperature", 0.0)
                val messages = JSONArray()
                val userMsg = JSONObject().apply {
                    put("role", "user")
                    val contentArr = JSONArray()
                    contentArr.put(JSONObject().put("type", "text").put("text", "Transcribe this audio verbatim in original language (Persian or English). If silent, output [SILENCE]:"))
                    contentArr.put(JSONObject().apply {
                        put("type", "image_url")
                        put("image_url", JSONObject().put("url", "data:audio/wav;base64,$b64"))
                    })
                    put("content", contentArr)
                }
                messages.put(userMsg)
                put("messages", messages)
            }.toString()
            val text = executeAcrossEndpoints(
                apiKey = apiKey,
                payloadJson = fallbackPayload,
                stream = false,
                onPartialText = {},
                endpoints = endpoints,
                serviceLabel = serviceLabel
            )
            sanitizeTranscriptOutput(text)
        }
    }

    suspend fun executeGeminiTranscriptionAndLinguisticAnalysis(
        audioBytes: ByteArray,
        mimeType: String,
        recentContext: List<Pair<String, String>>,
        apiKeyCandidates: List<Pair<Int, String>>,
        model: String = AppSettingsRepository.GEMINI_STT_MODEL,
        onKeySucceeded: (Int) -> Unit = {}
    ): Result<SpokenAudioAnalysisResult> = withContext(Dispatchers.IO) {
        if (audioBytes.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Audio recording is empty."))
        }
        val validCandidates = apiKeyCandidates
            .map { (idx, key) -> idx to key.trim() }
            .filter { (_, key) -> key.isNotEmpty() && key != "MY_AVALAI_API_KEY" }

        if (validCandidates.isEmpty()) {
            return@withContext Result.failure(IllegalStateException(MISSING_AVALAI_KEY_MESSAGE))
        }

        var lastError: Throwable? = null
        for ((poolIndex, apiKey) in validCandidates) {
            try {
                val rawOutput = executeGeminiChatTranscriptionWithLinguisticAnalysis(
                    apiKey = apiKey,
                    audioBytes = audioBytes,
                    mimeType = mimeType,
                    recentContext = recentContext,
                    model = model
                )
                onKeySucceeded(poolIndex)
                val parsed = parseTranscriptionAndCorrections(rawOutput)
                return@withContext Result.success(parsed)
            } catch (error: Exception) {
                lastError = error
                val canRotate = (error as? AvalAiHttpException)?.isKeyRotatable == true
                if (!canRotate) {
                    return@withContext Result.failure(error)
                }
            }
        }
        Result.failure(
            IllegalStateException(
                lastError?.localizedMessage ?: "Gemini 3.5 Flash-Lite transcription and analysis failed.",
                lastError
            )
        )
    }

    internal fun parseTranscriptionAndCorrections(rawOutput: String): SpokenAudioAnalysisResult {
        val cleaned = rawOutput.trim()
        val corrIndex = cleaned.indexOf("[linguistic_correction]", ignoreCase = true)
        val transcriptPart: String
        val corrPart: String
        if (corrIndex >= 0) {
            transcriptPart = cleaned.substring(0, corrIndex)
            corrPart = cleaned.substring(corrIndex + "[linguistic_correction]".length).trim()
        } else {
            transcriptPart = cleaned
            corrPart = ""
        }

        val sanitizedTranscript = sanitizeTranscriptOutput(
            transcriptPart.replace(Regex("(?i)\\[transcription\\]"), "").trim()
        )
        val finalTranscript = if (sanitizedTranscript.contains(SILENCE_SENTINEL, ignoreCase = true)) {
            ""
        } else {
            sanitizedTranscript
        }

        val corrections = if (corrPart.isNotBlank() && finalTranscript.isNotBlank()) {
            CorrectionJsonCodec.parseFromModelOutput(
                raw = corrPart,
                transcript = finalTranscript,
                isSpokenStt = true
            )
        } else {
            emptyList()
        }

        return SpokenAudioAnalysisResult(
            transcript = finalTranscript,
            corrections = corrections
        )
    }

    private suspend fun executeGeminiChatTranscriptionWithLinguisticAnalysis(
        apiKey: String,
        audioBytes: ByteArray,
        mimeType: String,
        recentContext: List<Pair<String, String>>,
        model: String
    ): String {
        val wavBytes = AudioTranscoder.ensureWavBytes(audioBytes)
        if (!AudioTranscoder.hasAudibleSpeech(wavBytes)) {
            return "[transcription]\n$SILENCE_SENTINEL\n[linguistic_correction]\n[]"
        }
        val b64 = Base64.encodeToString(wavBytes, Base64.NO_WRAP)

        val resolvedModel = AppSettingsRepository.ONEXAI_GEMINI_STT_MODEL
        val endpoints = ONEXAI_CHAT_COMPLETIONS_URLS
        val serviceLabel = "Gemini 3.5 Flash-Lite (1xAI STT & Linguistic Analysis)"

        val systemPrompt = buildString {
            append("You are an expert bilingual (Persian & English) speech transcription and linguistic analysis engine.\n")
            append("You are given an audio recording of a student speaking, preceded by recent conversation messages for context (topic, technical vocabulary, and conversational continuity).\n\n")
            append("TASKS:\n")
            append("1. Transcribe the student's speech verbatim in the original spoken language (Persian or English). If there is no speech or only background silence, respond with [SILENCE].\n")
            append("2. In the context of the recent conversation, identify any clear spoken grammatical errors or genuinely awkward/unnatural word choices in what was said.\n\n")
            append("CRITICAL SPOKEN SPEECH RULES:\n")
            append("- Casual spoken speech, contractions, fillers (\"خب\", \"امم\", \"well\", \"uh\"), and colloquial Persian forms (\"یه\", \"خونه\", \"می‌رم\") are completely normal — NEVER flag them.\n")
            append("- NEVER suggest adding, removing, or changing punctuation marks (., ,, !, ?), capitalization, or Persian half-spaces (ZWNJ).\n")
            append("- \"GRAMMAR\": Use ONLY for unmistakable spoken grammar mistakes (wrong tense, wrong agreement, wrong preposition, broken syntax).\n")
            append("- \"WORD_CHOICE\": Use ONLY when a word or phrase sounds genuinely unnatural or awkward in spoken context and has a clearly better natural alternative.\n")
            append("- If the speech is already natural and grammatically sound, return [] for linguistic_correction. When in doubt, return [].\n\n")
            append("OUTPUT FORMAT (Strictly output these two sections with no other text):\n")
            append("[transcription]\n")
            append("<verbatim transcription>\n")
            append("[linguistic_correction]\n")
            append("[{\"type\":\"GRAMMAR\"|\"WORD_CHOICE\",\"original\":\"faulty substring\",\"suggested\":\"correction\",\"reason\":\"concise explanation in English\"}]")
        }

        val payload = JSONObject().apply {
            put("model", resolvedModel)
            put("reasoning_effort", "none")
            put("temperature", 0.0)
            val messages = JSONArray()
            messages.put(JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            })

            val filteredContext = recentContext.takeLast(6)
            for ((role, text) in filteredContext) {
                if (text.isNotBlank()) {
                    messages.put(JSONObject().apply {
                        put("role", if (role == "user") "user" else "assistant")
                        put("content", text)
                    })
                }
            }

            val userMsg = JSONObject().apply {
                put("role", "user")
                val contentArr = JSONArray()
                contentArr.put(JSONObject().apply {
                    put("type", "text")
                    put("text", "Transcribe this audio verbatim and identify any clear linguistic corrections in the specified format:")
                })
                contentArr.put(JSONObject().apply {
                    put("type", "image_url")
                    put("image_url", JSONObject().put("url", "data:audio/wav;base64,$b64"))
                })
                put("content", contentArr)
            }
            messages.put(userMsg)
            put("messages", messages)
        }.toString()

        return try {
            executeAcrossEndpoints(
                apiKey = apiKey,
                payloadJson = payload,
                stream = false,
                onPartialText = {},
                endpoints = endpoints,
                serviceLabel = serviceLabel
            )
        } catch (e: Exception) {
            val fallbackPayload = JSONObject().apply {
                put("model", resolvedModel)
                put("temperature", 0.0)
                val messages = JSONArray()
                messages.put(JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                })
                val filteredContext = recentContext.takeLast(6)
                for ((role, text) in filteredContext) {
                    if (text.isNotBlank()) {
                        messages.put(JSONObject().apply {
                            put("role", if (role == "user") "user" else "assistant")
                            put("content", text)
                        })
                    }
                }
                val userMsg = JSONObject().apply {
                    put("role", "user")
                    val contentArr = JSONArray()
                    contentArr.put(JSONObject().apply {
                        put("type", "text")
                        put("text", "Transcribe this audio verbatim and identify any clear linguistic corrections in the specified format:")
                    })
                    contentArr.put(JSONObject().apply {
                        put("type", "image_url")
                        put("image_url", JSONObject().put("url", "data:audio/wav;base64,$b64"))
                    })
                    put("content", contentArr)
                }
                messages.put(userMsg)
                put("messages", messages)
            }.toString()

            executeAcrossEndpoints(
                apiKey = apiKey,
                payloadJson = fallbackPayload,
                stream = false,
                onPartialText = {},
                endpoints = endpoints,
                serviceLabel = serviceLabel
            )
        }
    }

    /**
     * Transcribes audio on Gemini 3.5 Flash-Lite via `/v1/audio/transcriptions` or multimodal chat completions.
     */
    private suspend fun executeScribeV2AudioTranscription(
        apiKey: String,
        audioBytes: ByteArray,
        mimeType: String,
        fileName: String,
        model: String
    ): String {
        if (model.contains("gemini", ignoreCase = true)) {
            return executeGeminiChatTranscription(apiKey, audioBytes, mimeType, model)
        }
        if (preferredEndpointIndex < 0) {
            probeFastestAvalAiEndpoint()
        }
        val sendFastExtraFields = scribeExtraFieldsSupported
        val rawBody = try {
            executeAudioTranscriptionAcrossEndpoints(
                apiKey = apiKey,
                audioBytes = audioBytes,
                mimeType = mimeType,
                fileName = fileName,
                model = model,
                includeFastScribeOptions = sendFastExtraFields
            )
        } catch (httpErr: AvalAiHttpException) {
            if (sendFastExtraFields &&
                httpErr.statusCode == 400 &&
                isScribeExtraFieldRejected(httpErr.detail)
            ) {
                scribeExtraFieldsSupported = false
                executeAudioTranscriptionAcrossEndpoints(
                    apiKey = apiKey,
                    audioBytes = audioBytes,
                    mimeType = mimeType,
                    fileName = fileName,
                    model = model,
                    includeFastScribeOptions = false
                )
            } else if (httpErr.statusCode == 400 || httpErr.statusCode == 404 || httpErr.statusCode == 422) {
                return executeGeminiChatTranscription(apiKey, audioBytes, mimeType, model)
            } else {
                throw httpErr
            }
        }
        return extractTranscriptionText(rawBody)
    }

    private fun executeAudioTranscriptionAcrossEndpoints(
        apiKey: String,
        audioBytes: ByteArray,
        mimeType: String,
        fileName: String,
        model: String,
        includeFastScribeOptions: Boolean
    ): String {
        var lastNetworkFailure: IOException? = null
        val orderedIndices = orderedEndpointIndices()
        for ((step, endpointIdx) in orderedIndices.withIndex()) {
            val endpoint = AUDIO_TRANSCRIPTIONS_URLS[endpointIdx]
            try {
                val multipartBody = buildScribeV2MultipartBody(
                    audioBytes = audioBytes,
                    mimeType = mimeType,
                    fileName = fileName,
                    model = model,
                    includeFastScribeOptions = includeFastScribeOptions
                )
                val request = Request.Builder()
                    .url(endpoint)
                    .header("Authorization", "Bearer $apiKey")
                    .post(multipartBody)
                    .build()

                val responseBody = okHttpClient.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        throw buildHttpException(
                            response.code,
                            body,
                            "ElevenLabs Scribe v2 (AvalAI)"
                        )
                    }
                    body
                }
                preferredEndpointIndex = endpointIdx
                return responseBody
            } catch (httpErr: AvalAiHttpException) {
                if (httpErr.statusCode in 500..599 && step < orderedIndices.lastIndex) {
                    lastNetworkFailure = httpErr
                    continue
                }
                preferredEndpointIndex = endpointIdx
                throw httpErr
            } catch (ioErr: IOException) {
                lastNetworkFailure = ioErr
            }
        }
        throw lastNetworkFailure ?: IOException("Could not reach Houshyar24 audio transcription service.")
    }

    private fun executeAcrossEndpoints(
        apiKey: String,
        payloadJson: String,
        stream: Boolean,
        onReasoningProgress: (Int) -> Unit = {},
        onPartialText: (String) -> Unit,
        allowEmptyContent: Boolean = false,
        useExtendedTimeout: Boolean = false,
        endpoints: List<String> = HOUSHYAR24_CHAT_COMPLETIONS_URLS,
        serviceLabel: String = "GPT 6 Luna (Houshyar24)"
    ): String {
        val client = if (useExtendedTimeout && okHttpClient === sharedOkHttpClient) {
            longRunningOkHttpClient
        } else {
            okHttpClient
        }
        var lastNetworkFailure: IOException? = null
        for ((step, endpoint) in endpoints.withIndex()) {
            try {
                val result = if (stream) {
                    executeStreamingCall(
                        client = client,
                        endpoint = endpoint,
                        apiKey = apiKey,
                        payloadJson = payloadJson,
                        onReasoningProgress = onReasoningProgress,
                        onPartialText = onPartialText,
                        serviceLabel = serviceLabel
                    )
                } else {
                    executeBlockingCall(client, endpoint, apiKey, payloadJson, allowEmptyContent, serviceLabel)
                }
                return result
            } catch (partialErr: PartialStreamInterruptedException) {
                throw partialErr
            } catch (truncErr: ReasoningTruncatedException) {
                throw truncErr
            } catch (httpErr: AvalAiHttpException) {
                if ((httpErr.statusCode in 500..599 || httpErr.statusCode == 404 || httpErr.statusCode == 405) && step < endpoints.lastIndex) {
                    lastNetworkFailure = httpErr
                    continue
                }
                throw httpErr
            } catch (ioErr: IOException) {
                lastNetworkFailure = ioErr
            }
        }
        throw lastNetworkFailure ?: IOException("Could not reach $serviceLabel.")
    }

    private fun executeBlockingCall(
        client: OkHttpClient,
        endpoint: String,
        apiKey: String,
        payloadJson: String,
        allowEmptyContent: Boolean,
        serviceLabel: String
    ): String {
        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(payloadJson.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw buildHttpException(response.code, responseBody, serviceLabel)
            }
            val content = extractChatCompletionText(responseBody)
            if (content.isBlank() && !allowEmptyContent) {
                throw IOException("$serviceLabel returned an empty response.")
            }
            return content
        }
    }

    private fun executeStreamingCall(
        client: OkHttpClient,
        endpoint: String,
        apiKey: String,
        payloadJson: String,
        onReasoningProgress: (Int) -> Unit,
        onPartialText: (String) -> Unit,
        serviceLabel: String
    ): String {
        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .post(payloadJson.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val detail = response.body?.string()?.take(500).orEmpty()
                throw buildHttpException(response.code, detail, serviceLabel)
            }

            val source = response.body?.source()
                ?: throw IOException("$serviceLabel returned an empty streaming response.")
            val fullText = StringBuilder()
            val nonSseFallback = StringBuilder()
            var sseDataLinesSeen = 0
            var reasoningChars = 0
            var lastFinishReason: String? = null

            try {
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    val trimmed = line.trim()
                    if (trimmed.isEmpty() || trimmed.startsWith(":")) continue

                    if (!trimmed.startsWith("data:")) {
                        if (sseDataLinesSeen == 0 && nonSseFallback.length < 65_536) {
                            nonSseFallback.append(trimmed).append('\n')
                        }
                        continue
                    }

                    sseDataLinesSeen++
                    val payload = trimmed.removePrefix("data:").trim()
                    if (payload.isBlank() || payload == "[DONE]") continue

                    val streamError = extractStreamErrorMessage(payload)
                    if (!streamError.isNullOrBlank()) {
                        if (fullText.isNotBlank()) {
                            throw PartialStreamInterruptedException(
                                partialContent = fullText.toString(),
                                message = "$serviceLabel stream interrupted: $streamError"
                            )
                        } else {
                            throw AvalAiHttpException(
                                statusCode = 502,
                                detail = payload.take(500),
                                message = "$serviceLabel stream error: $streamError"
                            )
                        }
                    }

                    extractStreamFinishReason(payload)?.let { reason ->
                        lastFinishReason = reason
                    }

                    val reasoningDelta = extractStreamDeltaReasoning(payload)
                    if (reasoningDelta.isNotEmpty()) {
                        reasoningChars += reasoningDelta.length
                        onReasoningProgress(reasoningChars)
                    }

                    val deltaText = extractStreamDeltaContent(payload)
                    if (deltaText.isNotEmpty()) {
                        fullText.append(deltaText)
                        onPartialText(fullText.toString())
                    }
                }
            } catch (partialErr: PartialStreamInterruptedException) {
                throw partialErr
            } catch (httpErr: AvalAiHttpException) {
                throw httpErr
            } catch (ioErr: IOException) {
                if (fullText.isNotBlank()) {
                    throw PartialStreamInterruptedException(
                        partialContent = fullText.toString(),
                        message = ioErr.localizedMessage ?: "Network stream interrupted mid-response.",
                        cause = ioErr
                    )
                }
                throw ioErr
            }

            if (fullText.isBlank() && sseDataLinesSeen == 0 && nonSseFallback.isNotBlank()) {
                val rawFallback = nonSseFallback.toString().trim()
                val fallbackError = extractStreamErrorMessage(rawFallback)
                if (!fallbackError.isNullOrBlank()) {
                    throw AvalAiHttpException(
                        statusCode = 502,
                        detail = rawFallback.take(500),
                        message = "$serviceLabel error: $fallbackError"
                    )
                }
                val extracted = extractChatCompletionText(rawFallback)
                if (extracted.isNotBlank()) {
                    onPartialText(extracted)
                    return extracted
                }
            }

            if (fullText.isBlank()) {
                if (reasoningChars > 0) {
                    throw ReasoningTruncatedException(
                        reasoningChars = reasoningChars,
                        message = "$serviceLabel exhausted its token budget during thinking (finish_reason=${lastFinishReason ?: "unknown"})."
                    )
                }
                throw IOException("$serviceLabel returned an empty streaming response.")
            }

            if (lastFinishReason.equals("length", ignoreCase = true)) {
                // Output hit the token ceiling mid-response; if caller handles partial streams, signal gracefully
                // ONLY when caller opts in? Better: return fullText normally OR notice in content?
                // Returning fullText lets normal chat & incremental parsers keep 100% of completed items!
            }
            return fullText.toString()
        }
    }

    companion object {
        const val LOCKED_REASONING_EFFORT = "low"
        const val HIGH_REASONING_EFFORT = "high"
        const val SILENCE_SENTINEL = "[SILENCE]"

        val ONEXAI_CHAT_COMPLETIONS_URLS = listOf(
            "https://1xai.ir/v1/chat/completions",
            "https://api.1xai.ir/v1/chat/completions"
        )

        val HOUSHYAR24_CHAT_COMPLETIONS_URLS = listOf(
            "https://houshyar24.ir/api/openai/v1/chat/completions",
            "https://api.houshyar24.ir/api/openai/v1/chat/completions",
            "https://houshyar24.ir/v1/chat/completions",
            "https://api.houshyar24.ir/v1/chat/completions"
        )

        val CHAT_COMPLETIONS_URLS = HOUSHYAR24_CHAT_COMPLETIONS_URLS

        val AUDIO_TRANSCRIPTIONS_URLS = listOf(
            "https://1xai.ir/v1/audio/transcriptions",
            "https://api.1xai.ir/v1/audio/transcriptions"
        )

        val BASE_PROBE_URLS = listOf(
            "https://1xai.ir/v1/models",
            "https://houshyar24.ir/api/openai/v1/models",
            "https://api.houshyar24.ir/api/openai/v1/models"
        )

        @Volatile
        private var preferredEndpointIndex: Int = -1

        @Volatile
        private var scribeExtraFieldsSupported: Boolean = true

        private val warmupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        val sharedOkHttpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectionPool(ConnectionPool(6, 5, TimeUnit.MINUTES))
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }

        private val longRunningOkHttpClient: OkHttpClient by lazy {
            sharedOkHttpClient.newBuilder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(300, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .callTimeout(0, TimeUnit.MILLISECONDS)
                .build()
        }

        private val probeHttpClient: OkHttpClient by lazy {
            sharedOkHttpClient.newBuilder()
                .connectTimeout(4, TimeUnit.SECONDS)
                .readTimeout(4, TimeUnit.SECONDS)
                .build()
        }

        /**
         * Pre-warms DNS + TLS + HTTP/2 connection to the fastest reachable AvalAI host
         * (`api.avalai.org` vs `api.avalai.ir`) while the user is holding the mic to speak,
         * so releasing the finger uploads over an already-warm HTTP/2 socket with zero handshake delay!
         */
        fun warmUpConnectionAsync() {
            warmupScope.launch {
                runCatching { probeFastestAvalAiEndpoint() }
            }
        }

        internal suspend fun probeFastestAvalAiEndpoint(): Int = coroutineScope {
            val currentPreferred = preferredEndpointIndex
            if (currentPreferred in BASE_PROBE_URLS.indices) {
                // Touch the preferred host's HTTP/2 keep-alive pool in case it went idle
                runCatching {
                    val req = Request.Builder()
                        .url(BASE_PROBE_URLS[currentPreferred])
                        .head()
                        .build()
                    probeHttpClient.newCall(req).execute().close()
                }.onSuccess { return@coroutineScope currentPreferred }
            }

            // Race both AvalAI hosts in parallel; first TCP+TLS+HTTP response wins!
            val jobs = BASE_PROBE_URLS.mapIndexed { idx, url ->
                async(Dispatchers.IO) {
                    runCatching {
                        val req = Request.Builder().url(url).head().build()
                        probeHttpClient.newCall(req).execute().use { idx }
                    }.getOrNull()
                }
            }

            var winner: Int? = null
            val remaining = jobs.toMutableList()
            while (remaining.isNotEmpty() && winner == null) {
                val finishedPair = select<Pair<Int, Int?>> {
                    remaining.forEachIndexed { listIdx, deferred ->
                        deferred.onAwait { res -> listIdx to res }
                    }
                }
                remaining.removeAt(finishedPair.first)
                if (finishedPair.second != null) {
                    winner = finishedPair.second
                }
            }
            remaining.forEach { it.cancel() }

            val resolved = winner ?: 0
            preferredEndpointIndex = resolved
            resolved
        }

        internal fun orderedEndpointIndices(): List<Int> {
            val start = preferredEndpointIndex.takeIf { it in CHAT_COMPLETIONS_URLS.indices } ?: 0
            return CHAT_COMPLETIONS_URLS.indices.sortedBy { idx ->
                if (idx == start) 0 else 1
            }
        }

        const val MISSING_AVALAI_KEY_MESSAGE =
            "Add a 1xAI or Houshyar24 API key in Settings (or ONEXAI_API_KEY in AI Studio Secrets) to use Gemini 3.5 Flash-Lite and GPT 6 Luna."

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val SUPPORTED_AUDIO_EXTENSIONS = setOf(
            "flac", "mp3", "mp4", "mpeg", "mpga", "m4a", "ogg", "wav", "webm", "aac"
        )
        private val PURE_NON_SPEECH_EVENT_REGEX =
            Regex("(?is)^(?:\\[silence\\]|\\(silence\\)|silence|no\\s+speech(?:\\s+detected)?|\\([^()]{1,60}\\)|\\[[^\\[\\]]{1,60}\\])(?:\\s*(?:\\([^()]{1,60}\\)|\\[[^\\[\\]]{1,60}\\]))*$")

        private fun JSONObject.optNonNullString(key: String): String? {
            if (isNull(key)) return null
            return optString(key, "").takeIf { it.isNotEmpty() }
        }

        /**
         * Detects container format from magic bytes (`RIFF....WAVE` vs ISO BMFF `ftyp` M4A/MP4)
         * so Base64 voice clips compressed to M4A or legacy WAV clips are always tagged accurately.
         */
        internal fun sniffAudioMimeType(audioBytes: ByteArray): String {
            if (audioBytes.size >= 12) {
                if (audioBytes[0] == 'R'.code.toByte() &&
                    audioBytes[1] == 'I'.code.toByte() &&
                    audioBytes[2] == 'F'.code.toByte() &&
                    audioBytes[3] == 'F'.code.toByte() &&
                    audioBytes[8] == 'W'.code.toByte() &&
                    audioBytes[9] == 'A'.code.toByte() &&
                    audioBytes[10] == 'V'.code.toByte() &&
                    audioBytes[11] == 'E'.code.toByte()
                ) {
                    return "audio/wav"
                }
                if (audioBytes[4] == 'f'.code.toByte() &&
                    audioBytes[5] == 't'.code.toByte() &&
                    audioBytes[6] == 'y'.code.toByte() &&
                    audioBytes[7] == 'p'.code.toByte()
                ) {
                    return "audio/mp4"
                }
                if (audioBytes[0] == 'O'.code.toByte() &&
                    audioBytes[1] == 'g'.code.toByte() &&
                    audioBytes[2] == 'g'.code.toByte() &&
                    audioBytes[3] == 'S'.code.toByte()
                ) {
                    return "audio/ogg"
                }
            }
            return "audio/wav"
        }

        internal fun isScribeExtraFieldRejected(detail: String): Boolean {
            val lower = detail.lowercase()
            return "tag_audio_events" in lower ||
                "timestamps_granularity" in lower ||
                "timestamp_granularities" in lower ||
                "extra_forbidden" in lower ||
                "unrecognized" in lower ||
                "unexpected" in lower
        }

        internal fun resolveAudioFileName(mimeType: String, fileNameHint: String?): String {
            val cleanedHint = fileNameHint?.trim()?.substringAfterLast('/')?.substringAfterLast('\\')
            if (!cleanedHint.isNullOrBlank()) {
                val ext = cleanedHint.substringAfterLast('.', "").lowercase()
                if (ext in SUPPORTED_AUDIO_EXTENSIONS) {
                    return cleanedHint
                }
            }
            val format = resolveInputAudioFormat(mimeType, null)
            return "recording.$format"
        }

        internal fun resolveInputAudioFormat(mimeType: String, fileNameHint: String?): String {
            val ext = fileNameHint
                ?.trim()
                ?.substringAfterLast('.', "")
                ?.lowercase()
                .orEmpty()
            val lowerMime = mimeType.lowercase()
            return when {
                ext == "wav" || "wav" in lowerMime -> "wav"
                ext == "m4a" || "m4a" in lowerMime -> "m4a"
                ext == "mp4" || "mp4" in lowerMime -> "m4a"
                ext == "aac" || "aac" in lowerMime -> "aac"
                ext == "mp3" || ext == "mpeg" || ext == "mpga" || "mp3" in lowerMime || "mpeg" in lowerMime -> "mp3"
                ext == "ogg" || "ogg" in lowerMime -> "ogg"
                ext == "webm" || "webm" in lowerMime -> "webm"
                ext == "flac" || "flac" in lowerMime -> "flac"
                else -> "wav"
            }
        }

        internal fun resolveMultipartAudioMimeType(mimeType: String, fileName: String): String {
            val trimmed = mimeType.trim()
            if (trimmed.isNotEmpty() && "/" in trimmed) {
                return trimmed
            }
            return when (resolveInputAudioFormat(mimeType, fileName)) {
                "wav" -> "audio/wav"
                "m4a" -> "audio/mp4"
                "mp3" -> "audio/mpeg"
                "ogg" -> "audio/ogg"
                "webm" -> "audio/webm"
                "flac" -> "audio/flac"
                "aac" -> "audio/aac"
                else -> "audio/wav"
            }
        }

        internal fun buildScribeV2MultipartBody(
            audioBytes: ByteArray,
            mimeType: String,
            fileName: String,
            model: String = AppSettingsRepository.SCRIBE_V2_STT_MODEL,
            includeFastScribeOptions: Boolean = true
        ): MultipartBody {
            val fileMediaType = runCatching { mimeType.toMediaType() }
                .getOrElse { "application/octet-stream".toMediaType() }
            return MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    fileName,
                    audioBytes.toRequestBody(fileMediaType)
                )
                .addFormDataPart("model", model)
                .addFormDataPart("response_format", "json")
                .apply {
                    if (includeFastScribeOptions) {
                        addFormDataPart("tag_audio_events", "false")
                        addFormDataPart("timestamps_granularity", "none")
                    }
                }
                .build()
        }

        internal fun sanitizeTranscriptOutput(raw: String): String {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return ""
            if (PURE_NON_SPEECH_EVENT_REGEX.matches(trimmed)) {
                return ""
            }
            return trimmed
        }

        internal fun extractTranscriptionText(responseBody: String): String {
            val trimmed = responseBody.trim()
            if (trimmed.isEmpty()) return ""
            val rawText = if (trimmed.startsWith("{")) {
                runCatching {
                    val root = JSONObject(trimmed)
                    val chatText = extractChatCompletionText(trimmed).takeIf { it.isNotEmpty() }
                    (chatText
                        ?: root.optNonNullString("text")
                        ?: root.optNonNullString("transcript")
                        ?: root.optNonNullString("transcription"))
                        ?.trim()
                }.getOrNull().orEmpty()
            } else {
                trimmed
            }
            return sanitizeTranscriptOutput(rawText)
        }

        internal fun extractStreamDeltaContent(payload: String): String {
            return runCatching {
                val choice = JSONObject(payload)
                    .optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?: return ""
                val delta = choice.optJSONObject("delta")
                if (delta != null) {
                    delta.optNonNullString("content").orEmpty()
                } else {
                    choice.optJSONObject("message")?.optNonNullString("content").orEmpty()
                }
            }.getOrDefault("")
        }

        internal fun extractStreamDeltaReasoning(payload: String): String {
            return runCatching {
                val choice = JSONObject(payload)
                    .optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?: return ""
                val delta = choice.optJSONObject("delta")
                if (delta != null) {
                    (delta.optNonNullString("reasoning_content")
                        ?: delta.optNonNullString("reasoning"))
                        .orEmpty()
                } else {
                    val msg = choice.optJSONObject("message")
                    (msg?.optNonNullString("reasoning_content")
                        ?: msg?.optNonNullString("reasoning"))
                        .orEmpty()
                }
            }.getOrDefault("")
        }

        internal fun extractStreamFinishReason(payload: String): String? {
            return runCatching {
                JSONObject(payload)
                    .optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optNonNullString("finish_reason")
                    ?.takeIf { it.isNotBlank() }
            }.getOrNull()
        }

        internal fun extractStreamErrorMessage(payload: String): String? {
            return runCatching {
                val root = JSONObject(payload)
                val errObj = root.optJSONObject("error")
                when {
                    errObj != null -> {
                        errObj.optNonNullString("message")?.takeIf { it.isNotBlank() }
                            ?: errObj.optNonNullString("code")?.takeIf { it.isNotBlank() }
                            ?: errObj.toString().take(240)
                    }
                    else -> root.optNonNullString("error")?.takeIf { it.isNotBlank() }
                }
            }.getOrNull()
        }

        private val VISION_FALLBACK_MODELS = listOf(
            "deepseek-flash",
            "gemini-2.5-flash",
            "gpt-4o-mini"
        )

        internal fun buildChatCompletionsPayload(
            model: String,
            messages: List<Message>,
            temperature: Double?,
            stream: Boolean,
            reasoningEffort: String? = LOCKED_REASONING_EFFORT,
            maxTokens: Int? = null
        ): String {
            val messagesJson = JSONArray()
            for (msg in messages) {
                val cleanImages = msg.imageBase64List.filter { it.isNotBlank() }
                val messageJson = JSONObject().put("role", msg.role)
                if (cleanImages.isEmpty()) {
                    messageJson.put("content", msg.content)
                } else {
                    val contentParts = JSONArray()
                    if (msg.content.isNotBlank()) {
                        contentParts.put(
                            JSONObject()
                                .put("type", "text")
                                .put("text", msg.content)
                        )
                    }
                    for (b64 in cleanImages) {
                        val dataUrl = if (b64.startsWith("data:", ignoreCase = true)) {
                            b64
                        } else {
                            "data:image/jpeg;base64,$b64"
                        }
                        contentParts.put(
                            JSONObject()
                                .put("type", "image_url")
                                .put(
                                    "image_url",
                                    JSONObject().put("url", dataUrl)
                                )
                        )
                    }
                    messageJson.put("content", contentParts)
                }
                messagesJson.put(messageJson)
            }
            return JSONObject().apply {
                put("model", model)
                put("messages", messagesJson)
                if (temperature != null) {
                    put("temperature", temperature)
                }
                if (!reasoningEffort.isNullOrBlank()) {
                    put("reasoning_effort", reasoningEffort)
                    if (model.contains("claude", ignoreCase = true) && !reasoningEffort.equals("none", ignoreCase = true)) {
                        put("thinking", JSONObject().apply {
                            put("type", "enabled")
                            put("budget_tokens", 1024)
                        })
                    }
                }
                if (maxTokens != null && maxTokens > 0) {
                    put("max_tokens", maxTokens)
                }
                put("stream", stream)
            }.toString()
        }

        internal fun isVisionUnsupported(statusCode: Int, detail: String): Boolean {
            if (statusCode != 400 && statusCode != 422) return false
            val lower = detail.lowercase()
            return "image_url" in lower ||
                "image input" in lower ||
                "vision" in lower ||
                "multimodal" in lower ||
                "expected a string" in lower ||
                "must be a string" in lower ||
                ("sequence" in lower && "string" in lower) ||
                ("array" in lower && "content" in lower) ||
                "unknown variant" in lower
        }

        internal fun isModelUnsupported(statusCode: Int, detail: String): Boolean {
            if (statusCode == 404) return true
            val lower = detail.lowercase()
            return "model" in lower &&
                ("not found" in lower || "does not exist" in lower || "unsupported" in lower || "invalid" in lower || "no access" in lower)
        }

        internal fun isMaxTokensRejected(detail: String): Boolean {
            val lower = detail.lowercase()
            return "max_tokens" in lower || "max_completion_tokens" in lower
        }

        internal fun isTemperatureRejectedForReasoning(detail: String): Boolean {
            val lower = detail.lowercase()
            return "temperature" in lower &&
                ("unsupported" in lower || "not support" in lower || "reasoning" in lower || "thinking" in lower || "invalid" in lower)
        }

        internal fun isReasoningEffortUnsupported(detail: String): Boolean {
            val lower = detail.lowercase()
            return "reasoning_effort" in lower || "thinking" in lower || "budget_tokens" in lower
        }

        internal fun isThinkingOrReasoningParameterRejected(detail: String): Boolean {
            val lower = detail.lowercase()
            return ("reasoning_effort" in lower || "thinking" in lower || "budget" in lower) &&
                ("unsupported" in lower ||
                    "not support" in lower ||
                    "invalid" in lower ||
                    "unknown" in lower ||
                    "unrecognized" in lower ||
                    "must be" in lower ||
                    "allowed" in lower)
        }

        internal fun extractChatCompletionText(responseBody: String): String {
            return runCatching {
                val contentObj = JSONObject(responseBody)
                    .optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                if (contentObj == null || contentObj.isNull("content")) {
                    return ""
                }
                val direct = contentObj.optNonNullString("content")
                val arr = contentObj.optJSONArray("content")
                if (arr != null) {
                    buildString {
                        for (i in 0 until arr.length()) {
                            val item = arr.optJSONObject(i)
                            val partText = item?.optNonNullString("text")
                            if (!partText.isNullOrEmpty()) {
                                append(partText)
                            }
                        }
                    }.trim()
                } else {
                    direct.orEmpty().trim()
                }
            }.getOrDefault("")
        }

        internal fun isRotatableKeyStatus(statusCode: Int, detail: String): Boolean {
            if (statusCode == 401 || statusCode == 402 || statusCode == 403 || statusCode == 429) {
                return true
            }
            if (statusCode != 400) return false
            val lower = detail.lowercase()
            return "api key" in lower ||
                "api_key" in lower ||
                "invalid_api_key" in lower ||
                "insufficient" in lower ||
                "quota" in lower ||
                "credit" in lower
        }

        private fun buildHttpException(
            statusCode: Int,
            body: String,
            serviceLabel: String = "GPT 6 Luna (Houshyar24)"
        ): AvalAiHttpException {
            val apiMessage = runCatching {
                val root = JSONObject(body)
                root.optJSONObject("error")?.optNonNullString("message")
                    ?.takeIf { it.isNotBlank() }
                    ?: root.optNonNullString("detail")?.takeIf { it.isNotBlank() }
            }.getOrNull()?.trim()

            val summary = when (statusCode) {
                401 -> "Houshyar24 API key was rejected"
                402 -> "Houshyar24 account balance is insufficient"
                403 -> "Houshyar24 API key lacks permission"
                404 -> if (body.contains("<!doctype html", ignoreCase = true) || body.contains("not found on this server", ignoreCase = true)) {
                    "Houshyar24 endpoint not found"
                } else {
                    "Model was not found on Houshyar24"
                }
                405 -> "Houshyar24 method not allowed"
                429 -> "Houshyar24 rate limit or quota exceeded"
                else -> "$serviceLabel request failed"
            }
            val fullMsg = if (!apiMessage.isNullOrBlank()) {
                "$summary (HTTP $statusCode): $apiMessage"
            } else {
                "$summary (HTTP $statusCode)."
            }
            return AvalAiHttpException(
                statusCode = statusCode,
                detail = body.take(500),
                message = fullMsg
            )
        }
    }
}

typealias OneXAiClient = AvalAiClient

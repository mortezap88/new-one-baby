package com.example.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.settings.AiModelOption
import com.example.settings.ApiKeyProvider
import com.example.settings.AppSettings
import com.example.settings.AppSettingsRepository
import com.example.settings.AppThemePreference
import com.example.ui.theme.AccentPink
import com.example.ui.theme.BadgeGreenBg
import com.example.ui.theme.BadgeGreenBorder
import com.example.ui.theme.BadgeGreenText
import com.example.ui.theme.BgDark
import com.example.ui.theme.PrimaryPurple
import com.example.ui.theme.SurfaceCardBorder
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.SurfaceInput
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    settings: AppSettings,
    ttsVoices: List<TtsVoiceOption>,
    onThemeSelected: (AppThemePreference) -> Unit,
    onTttProviderSelected: (ApiKeyProvider) -> Unit = {},
    onTokenStreamingSelected: (Boolean) -> Unit,
    onMaxFontSizeSelected: (Int) -> Unit,
    onTtsVoiceSelected: (String?) -> Unit,
    onTtsRateSelected: (Float) -> Unit,
    onTestTts: () -> Unit,
    onAddApiKey: (ApiKeyProvider, String) -> Boolean,
    onRemoveApiKey: (ApiKeyProvider, Int) -> Unit,
    onClearApiKeys: (ApiKeyProvider) -> Unit,
    reviewItemCount: Int = 0,
    subjectCount: Int = 0,
    backupState: BackupUiState = BackupUiState.Idle,
    onExportFlashData: (Uri) -> Unit = {},
    onImportFlashData: (Uri) -> Unit = {}
) {
    var onexaiKeyInput by rememberSaveable { mutableStateOf("") }
    var houshyarKeyInput by rememberSaveable { mutableStateOf("") }
    var activeKeyFieldProvider by remember { mutableStateOf<ApiKeyProvider?>(null) }
    var onexaiSelection by remember { mutableIntStateOf(0) }
    var houshyarSelection by remember { mutableIntStateOf(0) }
    val scrollState = rememberScrollState()

    val isKeyboardVisible = activeKeyFieldProvider != null

    LaunchedEffect(activeKeyFieldProvider) {
        if (activeKeyFieldProvider != null) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    BackHandler(enabled = isKeyboardVisible) {
        activeKeyFieldProvider = null
    }

    KeyboardDismissSurface(
        isKeyboardVisible = isKeyboardVisible,
        onDismissKeyboard = { activeKeyFieldProvider = null },
        modifier = Modifier.fillMaxSize()
    ) {
        Scaffold(
            containerColor = BgDark,
            topBar = {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Settings",
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary
                    )
                }
            },
            bottomBar = {
                val activeProvider = activeKeyFieldProvider
                if (activeProvider != null) {
                    val currentText = when (activeProvider) {
                        ApiKeyProvider.ONEXAI -> onexaiKeyInput
                        ApiKeyProvider.HOUSHYAR24 -> houshyarKeyInput
                    }
                    val currentSelection = when (activeProvider) {
                        ApiKeyProvider.ONEXAI -> onexaiSelection
                        ApiKeyProvider.HOUSHYAR24 -> houshyarSelection
                    }
                    CreationVirtualKeyboard(
                        text = TextFieldValue(
                            currentText,
                            TextRange(currentSelection.coerceIn(0, currentText.length))
                        ),
                        onTextChange = { updated ->
                            when (activeProvider) {
                                ApiKeyProvider.ONEXAI -> {
                                    onexaiKeyInput = updated.text
                                    onexaiSelection = updated.selection.end
                                }
                                ApiKeyProvider.HOUSHYAR24 -> {
                                    houshyarKeyInput = updated.text
                                    houshyarSelection = updated.selection.end
                                }
                            }
                        },
                        onSend = { activeKeyFieldProvider = null },
                        showInputField = false,
                        sendLabel = "Done"
                    )
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    ThemeSection(settings = settings, onThemeSelected = onThemeSelected)
                    ChatSection(
                        settings = settings,
                        onTokenStreamingSelected = onTokenStreamingSelected,
                        onMaxFontSizeSelected = onMaxFontSizeSelected
                    )
                    TtsSection(
                        settings = settings,
                        ttsVoices = ttsVoices,
                        onTtsVoiceSelected = onTtsVoiceSelected,
                        onTtsRateSelected = onTtsRateSelected,
                        onTestTts = onTestTts
                    )
                    ModelSection(
                        settings = settings,
                        onTttProviderSelected = onTttProviderSelected,
                        onexaiKeyInput = onexaiKeyInput,
                        onOnexaiKeyInputChange = { onexaiKeyInput = it },
                        onexaiSelection = onexaiSelection,
                        onOnexaiSelectionChange = { onexaiSelection = it },
                        houshyarKeyInput = houshyarKeyInput,
                        onHoushyarKeyInputChange = { houshyarKeyInput = it },
                        houshyarSelection = houshyarSelection,
                        onHoushyarSelectionChange = { houshyarSelection = it },
                        activeFieldProvider = activeKeyFieldProvider,
                        onFieldActivate = { provider -> activeKeyFieldProvider = provider },
                        onAddApiKey = onAddApiKey,
                        onRemoveApiKey = onRemoveApiKey,
                        onClearApiKeys = onClearApiKeys
                    )
                    DataBackupSection(
                        storedApiKeyCount = settings.totalStoredApiKeyCount,
                        reviewItemCount = reviewItemCount,
                        subjectCount = subjectCount,
                        backupState = backupState,
                        onExportFlashData = onExportFlashData,
                        onImportFlashData = onImportFlashData
                    )
                }
            }
        }
    }
}

@Composable
private fun ThemeSection(
    settings: AppSettings,
    onThemeSelected: (AppThemePreference) -> Unit
) {
    SettingsCard(title = "Theme", subtitle = "How FlashRecall looks.") {
        AppThemePreference.entries.forEach { theme ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onThemeSelected(theme) }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = settings.theme == theme,
                    onClick = { onThemeSelected(theme) }
                )
                Text(theme.title, color = TextPrimary)
            }
        }
    }
}

@Composable
private fun ChatSection(
    settings: AppSettings,
    onTokenStreamingSelected: (Boolean) -> Unit,
    onMaxFontSizeSelected: (Int) -> Unit
) {
    var fontDraft by remember(settings.maxFontSizeSp) {
        mutableFloatStateOf(settings.maxFontSizeSp.toFloat())
    }

    SettingsCard(title = "Chat", subtitle = "How AI responses are shown.") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Token streaming", color = TextPrimary)
                Text("Show responses as they are generated.", color = TextSecondary, fontSize = 12.sp)
            }
            Switch(
                checked = settings.tokenStreaming,
                onCheckedChange = onTokenStreamingSelected
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Font size", color = TextPrimary)
            Text("${fontDraft.roundToInt()}sp", color = TextSecondary, fontSize = 13.sp)
        }
        Slider(
            value = fontDraft,
            onValueChange = { fontDraft = it },
            onValueChangeFinished = { onMaxFontSizeSelected(fontDraft.roundToInt()) },
            valueRange = AppSettingsRepository.MIN_MAX_FONT_SIZE.toFloat()..
                AppSettingsRepository.MAX_MAX_FONT_SIZE.toFloat(),
            steps = 14
        )
    }
}

@Composable
private fun TtsSection(
    settings: AppSettings,
    ttsVoices: List<TtsVoiceOption>,
    onTtsVoiceSelected: (String?) -> Unit,
    onTtsRateSelected: (Float) -> Unit,
    onTestTts: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var rateDraft by remember(settings.ttsRate) { mutableFloatStateOf(settings.ttsRate) }
    val selectedVoice = ttsVoices.firstOrNull { it.name == settings.ttsVoiceName }

    SettingsCard(title = "Text to speech", subtitle = "The voice used to read cards aloud.") {
        Box {
            OutlinedButton(
                onClick = { menuExpanded = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = selectedVoice?.label ?: "System default voice",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 13.sp
                )
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                modifier = Modifier.heightIn(max = 320.dp)
            ) {
                DropdownMenuItem(
                    text = { Text("System default voice") },
                    onClick = {
                        onTtsVoiceSelected(null)
                        menuExpanded = false
                    }
                )
                ttsVoices.forEach { voice ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = voice.label,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        onClick = {
                            onTtsVoiceSelected(voice.name)
                            menuExpanded = false
                        }
                    )
                }
            }
        }
        if (ttsVoices.isEmpty()) {
            Text(
                text = "No extra voices reported by the system engine. The default voice will be used.",
                color = TextMuted,
                fontSize = 12.sp
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Speed", color = TextPrimary)
            Text(
                text = String.format(Locale.getDefault(), "%.1f×", rateDraft),
                color = TextSecondary,
                fontSize = 13.sp
            )
        }
        Slider(
            value = rateDraft,
            onValueChange = { rateDraft = it },
            onValueChangeFinished = { onTtsRateSelected(rateDraft) },
            valueRange = AppSettingsRepository.MIN_TTS_RATE..AppSettingsRepository.MAX_TTS_RATE,
            steps = 14
        )

        OutlinedButton(onClick = onTestTts, modifier = Modifier.fillMaxWidth()) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = null,
                modifier = Modifier.width(18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text("Test voice")
        }
    }
}

@Composable
private fun ModelSection(
    settings: AppSettings,
    onTttProviderSelected: (ApiKeyProvider) -> Unit,
    onexaiKeyInput: String,
    onOnexaiKeyInputChange: (String) -> Unit,
    onexaiSelection: Int,
    onOnexaiSelectionChange: (Int) -> Unit,
    houshyarKeyInput: String,
    onHoushyarKeyInputChange: (String) -> Unit,
    houshyarSelection: Int,
    onHoushyarSelectionChange: (Int) -> Unit,
    activeFieldProvider: ApiKeyProvider?,
    onFieldActivate: (ApiKeyProvider) -> Unit,
    onAddApiKey: (ApiKeyProvider, String) -> Boolean,
    onRemoveApiKey: (ApiKeyProvider, Int) -> Unit,
    onClearApiKeys: (ApiKeyProvider) -> Unit
) {
    SettingsCard(
        title = "AI Provider & Dual Models (1xAI & Houshyar24)",
        subtitle = "FlashRecall runs via 1xAI & Houshyar24: GPT 6 Luna generates main answers & evaluations, while Gemini 3.5 Flash-Lite (1xAI) powers speech transcription & linguistic analysis with minimal/none thinking for maximum speed."
    ) {
        Text(
            text = "Active AI Pipeline (1xAI & Houshyar24)",
            color = TextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold
        )

        // GPT 6 Luna (Main Answers)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(SurfaceDark)
                .border(
                    width = 1.dp,
                    color = PrimaryPurple,
                    shape = RoundedCornerShape(14.dp)
                )
                .padding(12.dp)
                .testTag("provider_choice_houshyar24"),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "GPT 6 Luna",
                        color = TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Badge(text = "Main Answers Only")
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "code/gpt-6-luna · thinking: low (1024 tokens) · houshyar24.ir/api/openai/v1",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "Generates tutoring answers, evaluations, flashcards & chat responses",
                    color = TextMuted,
                    fontSize = 11.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Gemini 3.5 Flash-Lite (Transcription & Linguistics)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(SurfaceInput)
                .border(
                    width = 1.dp,
                    color = SurfaceCardBorder,
                    shape = RoundedCornerShape(14.dp)
                )
                .padding(12.dp)
                .testTag("model_gemini_flash"),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Gemini 3.5 Flash-Lite",
                        color = TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Badge(text = "STT & Linguistics Only")
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "gemini-3.5-flash-lite · thinking: none · 1xai.ir/v1",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "Ultra-fast verbatim speech transcription & spoken grammar / word-choice analysis (thinking budget: none)",
                    color = TextMuted,
                    fontSize = 11.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // 1xAI API Key Pool (1xai.ir/v1)
        ProviderKeyPoolCard(
            provider = ApiKeyProvider.ONEXAI,
            cardTitle = "1xAI API Keys (1xai.ir/v1)",
            previews = settings.onexaiKeyPreviews.ifEmpty { listOf("••••" + AppSettingsRepository.DEFAULT_1XAI_KEY.takeLast(4)) },
            activeIndex = settings.activeOnexaiKeyIndex,
            hasKey = true,
            missingKeyHint = "1xAI API key active (1xai.ir/v1). Used strictly for Gemini 3.5 Flash-Lite speech-to-text and spoken linguistic analysis.",
            keyInput = onexaiKeyInput,
            onKeyInputChange = onOnexaiKeyInputChange,
            selection = onexaiSelection,
            onSelectionChange = onOnexaiSelectionChange,
            isFieldActive = activeFieldProvider == ApiKeyProvider.ONEXAI,
            onFieldActivate = { onFieldActivate(ApiKeyProvider.ONEXAI) },
            onAddApiKey = { key -> onAddApiKey(ApiKeyProvider.ONEXAI, key) },
            onRemoveApiKey = { idx -> onRemoveApiKey(ApiKeyProvider.ONEXAI, idx) },
            onClearApiKeys = { onClearApiKeys(ApiKeyProvider.ONEXAI) },
            tagPrefix = "onexai"
        )

        Spacer(modifier = Modifier.height(6.dp))

        // Houshyar24 API Key Pool
        ProviderKeyPoolCard(
            provider = ApiKeyProvider.HOUSHYAR24,
            cardTitle = "Houshyar24 API Keys (houshyar24.ir/api/openai/v1)",
            previews = settings.houshyarKeyPreviews.ifEmpty { listOf("••••" + AppSettingsRepository.DEFAULT_HOUSHYAR24_KEY.takeLast(4)) },
            activeIndex = settings.activeHoushyarKeyIndex,
            hasKey = true,
            missingKeyHint = "Houshyar24 API key active (houshyar24.ir/api/openai/v1). You can add or rotate additional Houshyar24 keys.",
            keyInput = houshyarKeyInput,
            onKeyInputChange = onHoushyarKeyInputChange,
            selection = houshyarSelection,
            onSelectionChange = onHoushyarSelectionChange,
            isFieldActive = activeFieldProvider == ApiKeyProvider.HOUSHYAR24,
            onFieldActivate = { onFieldActivate(ApiKeyProvider.HOUSHYAR24) },
            onAddApiKey = { key -> onAddApiKey(ApiKeyProvider.HOUSHYAR24, key) },
            onRemoveApiKey = { idx -> onRemoveApiKey(ApiKeyProvider.HOUSHYAR24, idx) },
            onClearApiKeys = { onClearApiKeys(ApiKeyProvider.HOUSHYAR24) },
            tagPrefix = "houshyar"
        )

        settings.errorMessage?.let { error ->
            Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        }

        Text(
            "Keys are AES-GCM encrypted on this device. FlashRecall uses GPT 6 Luna for main answers & evaluations, and Gemini 3.5 Flash-Lite (1xAI) with minimal/none thinking for ultra-fast speech-to-text and linguistic analysis.",
            color = TextMuted,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun ActiveModelSummaryCard(
    option: AiModelOption,
    hasKey: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceInput)
            .border(1.dp, SurfaceCardBorder, RoundedCornerShape(16.dp))
            .padding(14.dp)
            .testTag("model_option_${option.id}"),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = option.label,
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = option.endpointLabel,
                    color = TextSecondary,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            Badge(text = if (hasKey) "Ready" else "Active")
        }

        Text(text = option.note, color = TextMuted, fontSize = 12.sp)
    }
}

@Composable
private fun ProviderKeyPoolCard(
    provider: ApiKeyProvider,
    cardTitle: String,
    previews: List<String>,
    activeIndex: Int,
    hasKey: Boolean,
    missingKeyHint: String,
    keyInput: String,
    onKeyInputChange: (String) -> Unit,
    selection: Int,
    onSelectionChange: (Int) -> Unit,
    isFieldActive: Boolean,
    onFieldActivate: () -> Unit,
    onAddApiKey: (String) -> Boolean,
    onRemoveApiKey: (Int) -> Unit,
    onClearApiKeys: () -> Unit,
    tagPrefix: String
) {
    val optionLabel = provider.displayName
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceInput)
            .border(1.dp, SurfaceCardBorder, RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = cardTitle,
            color = TextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold
        )

        previews.forEachIndexed { index, preview ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(SurfaceDark)
                    .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("${index + 1}", color = TextMuted, fontSize = 12.sp)
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = preview,
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace
                )
                if (index == activeIndex) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Badge(text = "In use")
                }
                Spacer(modifier = Modifier.weight(1f))
                IconButton(
                    onClick = { onRemoveApiKey(index) },
                    modifier = Modifier.testTag("${tagPrefix}_remove_key_$index")
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Remove $optionLabel key ${index + 1}",
                        tint = AccentPink.copy(alpha = 0.85f)
                    )
                }
            }
        }

        if (previews.isEmpty()) {
            Text(
                text = if (hasKey) {
                    "A key from AI Studio Secrets is active."
                } else {
                    missingKeyHint
                },
                color = TextMuted,
                fontSize = 12.sp
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(SurfaceDark)
                .border(
                    width = 1.dp,
                    color = if (isFieldActive) PrimaryPurple else SurfaceCardBorder,
                    shape = RoundedCornerShape(14.dp)
                )
                .virtualFieldContainer {
                    onSelectionChange(keyInput.length)
                    onFieldActivate()
                }
                .padding(horizontal = 14.dp, vertical = 12.dp)
                .testTag("${tagPrefix}_key_field")
        ) {
            VirtualTextField(
                text = keyInput,
                selection = selection,
                isActive = isFieldActive,
                onActivate = { offset ->
                    onSelectionChange(offset)
                    onFieldActivate()
                },
                placeholder = provider.placeholder,
                color = TextPrimary,
                fontSize = 14.sp,
                lineHeight = 18.sp,
                maxLines = 1,
                onPaste = { clip ->
                    onKeyInputChange(clip.trim())
                    onSelectionChange(clip.trim().length)
                    onFieldActivate()
                }
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    if (onAddApiKey(keyInput)) {
                        onKeyInputChange("")
                        onSelectionChange(0)
                    }
                },
                enabled = keyInput.isNotBlank(),
                modifier = Modifier.testTag("${tagPrefix}_add_key_button")
            ) {
                Text("Add key")
            }
            if (previews.isNotEmpty()) {
                TextButton(
                    onClick = onClearApiKeys,
                    modifier = Modifier.testTag("${tagPrefix}_clear_keys_button")
                ) {
                    Text("Remove all", color = PrimaryPurple)
                }
            }
        }
    }
}

@Composable
internal fun SettingsCard(
    title: String,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(SurfaceDark)
            .border(1.dp, SurfaceCardBorder, RoundedCornerShape(20.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                fontWeight = FontWeight.Bold
            )
            subtitle?.let {
                Text(it, color = TextSecondary, fontSize = 13.sp)
            }
        }
        content()
    }
}

@Composable
private fun Badge(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(BadgeGreenBg)
            .border(1.dp, BadgeGreenBorder, RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(text, color = BadgeGreenText, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

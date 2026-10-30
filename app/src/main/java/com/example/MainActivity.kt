package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.notepad.NotepadPage
import com.example.settings.AppThemePreference
import com.example.ui.AddFlashcardSheet
import com.example.ui.HomeScreen
import com.example.ui.MainViewModel
import com.example.ui.ReviewExtractQuestionSheet
import com.example.ui.ReviewScreen
import com.example.ui.SubjectCentreScreen
import com.example.ui.SettingsScreen
import com.example.ui.theme.BgDark
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.PrimaryPurple
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.SurfaceInput
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import kotlinx.coroutines.launch

private const val PAGE_STAGES = 0
private const val PAGE_SUBJECTS = 1
private const val PAGE_NOTES = 2
private const val PAGE_SETTINGS = 3
private const val PAGE_COUNT = 4

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val appSettings by viewModel.appSettings.collectAsStateWithLifecycle()
            MyApplicationTheme(themePreference = appSettings.theme) {
                FlashRecallApp(viewModel = viewModel)
            }
        }
    }
}

@Composable
fun FlashRecallApp(viewModel: MainViewModel) {
    val allCards by viewModel.allCards.collectAsStateWithLifecycle()
    val subjects by viewModel.subjects.collectAsStateWithLifecycle()
    val appSettings by viewModel.appSettings.collectAsStateWithLifecycle()
    val activeReviewList by viewModel.activeReviewList.collectAsStateWithLifecycle()
    val currentReviewIndex by viewModel.currentReviewIndex.collectAsStateWithLifecycle()
    val currentScenarioOrQuestion by viewModel.currentScenarioOrQuestion.collectAsStateWithLifecycle()
    val reviewChatHistory by viewModel.reviewChatHistory.collectAsStateWithLifecycle()
    val reviewScenarioLoading by viewModel.reviewScenarioLoading.collectAsStateWithLifecycle()
    val isReviewReplying by viewModel.isReviewReplying.collectAsStateWithLifecycle()
    val generatingQuestionsSubjectIds by viewModel.generatingQuestionsSubjectIds.collectAsStateWithLifecycle()
    val generatingQuestionProgress by viewModel.generatingQuestionProgress.collectAsStateWithLifecycle()
    val subjectStatusMessages by viewModel.subjectStatusMessages.collectAsStateWithLifecycle()
    val subjectQuestionChatMessages by viewModel.subjectQuestionChatMessages.collectAsStateWithLifecycle()
    val isSubjectQuestionThinking by viewModel.isSubjectQuestionThinking.collectAsStateWithLifecycle()
    val subjectQuestionDraftState by viewModel.subjectQuestionDraftState.collectAsStateWithLifecycle()
    val creationChatMessages by viewModel.creationChatMessages.collectAsStateWithLifecycle()
    val isCreationThinking by viewModel.isCreationThinking.collectAsStateWithLifecycle()
    val isReviewExtractOpen by viewModel.isReviewExtractOpen.collectAsStateWithLifecycle()
    val isReviewExtractThinking by viewModel.isReviewExtractThinking.collectAsStateWithLifecycle()
    val reviewExtractChatMessages by viewModel.reviewExtractChatMessages.collectAsStateWithLifecycle()
    val reviewExtractDraftState by viewModel.reviewExtractDraftState.collectAsStateWithLifecycle()
    val backupState by viewModel.backupState.collectAsStateWithLifecycle()
    val ttsVoices by viewModel.ttsVoices.collectAsStateWithLifecycle()

    val coroutineScope = rememberCoroutineScope()
    val pagerState = rememberPagerState(initialPage = 0) { PAGE_COUNT }
    var isAddCardOpen by rememberSaveable { mutableStateOf(false) }

    val inReviewMode = activeReviewList.isNotEmpty() && currentReviewIndex in activeReviewList.indices

    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage == PAGE_SETTINGS) {
            viewModel.refreshTtsVoices()
        }
    }

    BackHandler(enabled = inReviewMode) {
        viewModel.endReviewSession()
    }

    Box(modifier = Modifier.fillMaxSize().background(BgDark)) {
        if (inReviewMode) {
            ReviewScreen(
                cards = activeReviewList,
                currentIndex = currentReviewIndex,
                maxFontSizeSp = appSettings.maxFontSizeSp,
                scenarioLoading = reviewScenarioLoading,
                scenarioOrQuestion = currentScenarioOrQuestion,
                reviewChatHistory = reviewChatHistory,
                isReviewReplying = isReviewReplying,
                onSendReviewReply = { text -> viewModel.sendReviewReply(text) },
                onSendReviewAudioReply = { b64 -> viewModel.sendReviewAudioReply(b64) },
                onRetryReviewReply = { viewModel.retryLastReviewReply() },
                onApplyCorrection = { msgId, corrId -> viewModel.applyReviewCorrection(msgId, corrId) },
                onSelectStage = { card, stage -> viewModel.updateCardStage(card, stage) },
                onMarkDumb = { card -> viewModel.markCardDumb(card) },
                onMarkLearned = { card -> viewModel.markCardLearned(card) },
                onOpenExtractQuestion = { viewModel.openReviewExtractQuestion() },
                onSpeak = { text -> viewModel.speakText(text) },
                onClose = { viewModel.endReviewSession() }
            )
        } else {
            Scaffold(
                containerColor = BgDark,
                bottomBar = {
                    FlashRecallBottomNavBar(
                        selectedPage = pagerState.currentPage,
                        onPageSelect = { page ->
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(page)
                            }
                        }
                    )
                }
            ) { innerPadding ->
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    userScrollEnabled = true
                ) { page ->
                    when (page) {
                        PAGE_STAGES -> HomeScreen(
                            cards = allCards,
                            onOpenAdd = { isAddCardOpen = true },
                            onOpenReview = { stageIndex -> viewModel.startReviewSession(stageIndex) }
                        )

                        PAGE_SUBJECTS -> SubjectCentreScreen(
                            subjects = subjects,
                            cards = allCards,
                            maxFontSizeSp = appSettings.maxFontSizeSp,
                            generatingSubjectIds = generatingQuestionsSubjectIds,
                            generatingSubjectProgress = generatingQuestionProgress,
                            subjectStatusMessages = subjectStatusMessages,
                            subjectQuestionMessages = subjectQuestionChatMessages,
                            isSubjectQuestionThinking = isSubjectQuestionThinking,
                            subjectQuestionDraftState = subjectQuestionDraftState,
                            onCreateSubject = { name -> viewModel.createSubject(name) },
                            onUpdateTextbook = { id, text -> viewModel.updateSubjectTextbook(id, text) },
                            onUpdateExtraKnowledge = { id, text -> viewModel.updateSubjectExtraKnowledge(id, text) },
                            onAddMaterialCameraImage = { id, kind, bmp -> viewModel.addSubjectMaterialBitmap(id, kind, bmp) },
                            onAddMaterialGalleryImages = { id, kind, uris -> viewModel.addSubjectMaterialUris(id, kind, uris) },
                            onDeleteMaterialImage = { id, kind, imgId -> viewModel.deleteSubjectMaterialImage(id, kind, imgId) },
                            onRetryMaterialImageOcr = { id, kind, imgId -> viewModel.retrySubjectMaterialImageOcr(id, kind, imgId) },
                            onGenerateQuestions = { id -> viewModel.generateQuestionsForSubject(id) },
                            onDismissSubjectStatus = { id -> viewModel.dismissSubjectStatus(id) },
                            onSendSubjectQuestionMessage = { id, text, bmps -> viewModel.sendSubjectQuestionMessage(id, text, bmps) },
                            onSendSubjectQuestionAudio = { id, b64, bmps -> viewModel.sendSubjectQuestionAudioMessage(id, b64, bmps) },
                            onApplySubjectQuestionCorrection = { msgId, corrId -> viewModel.applySubjectQuestionCorrection(msgId, corrId) },
                            onClearSubjectQuestionChat = { viewModel.clearSubjectQuestionChat() },
                            onGenerateDraftQuestionFromConversation = { id -> viewModel.generateDraftQuestionFromConversation(id) },
                            onDismissSubjectQuestionDraft = { viewModel.dismissSubjectQuestionDraft() },
                            onSaveGeneratedSubjectQuestion = { id -> viewModel.saveGeneratedSubjectQuestion(id) },
                            onDeleteSubject = { subject -> viewModel.deleteSubject(subject) },
                            onDeleteQuestion = { card -> viewModel.deleteQuestion(card) },
                            onRestoreQuestion = { card -> viewModel.restoreQuestion(card) },
                            onStartReview = { subjectId -> viewModel.startSubjectReview(subjectId) }
                        )

                        PAGE_NOTES -> NotepadPage()

                        PAGE_SETTINGS -> SettingsScreen(
                            settings = appSettings,
                            ttsVoices = ttsVoices,
                            onThemeSelected = { theme -> viewModel.saveTheme(theme) },
                            onTttProviderSelected = { provider -> viewModel.selectTttProvider(provider) },
                            onTokenStreamingSelected = { en -> viewModel.saveTokenStreaming(en) },
                            onMaxFontSizeSelected = { size -> viewModel.saveMaxFontSize(size) },
                            onTtsVoiceSelected = { voice -> viewModel.saveTtsVoice(voice) },
                            onTtsRateSelected = { rate -> viewModel.saveTtsRate(rate) },
                            onTestTts = { viewModel.testTts() },
                            onAddApiKey = { provider, key -> viewModel.addApiKey(provider, key) },
                            onRemoveApiKey = { provider, idx -> viewModel.removeApiKey(provider, idx) },
                            onClearApiKeys = { provider -> viewModel.clearApiKeys(provider) },
                            reviewItemCount = allCards.size,
                            subjectCount = subjects.size,
                            backupState = backupState,
                            onExportFlashData = { uri -> viewModel.exportFlashData(uri) },
                            onImportFlashData = { uri -> viewModel.importFlashData(uri) }
                        )
                    }
                }
            }
        }

        // Add Flashcard Bottom Sheet
        if (isAddCardOpen) {
            AddFlashcardSheet(
                onDismiss = { isAddCardOpen = false },
                onAddCard = { type, word, backText, imageUrl, convHist, focusSummary ->
                    viewModel.addFlashcard(
                        type = type,
                        word = word,
                        backText = backText,
                        imageUrl = imageUrl,
                        conversationHistory = convHist,
                        focusSummary = focusSummary
                    )
                },
                creationChatMessages = creationChatMessages,
                isCreationThinking = isCreationThinking,
                maxFontSizeSp = appSettings.maxFontSizeSp,
                onSendMessage = { text, bmps -> viewModel.sendCreationMessage(text, bmps) },
                onSendAudioMessage = { b64, bmps -> viewModel.sendCreationAudioMessage(b64, bmps) },
                onApplyCorrection = { msgId, corrId -> viewModel.applyCreationCorrection(msgId, corrId) },
                onClearChat = { viewModel.clearCreationChat() }
            )
        }

        // Review Extract Question Sheet
        if (isReviewExtractOpen) {
            val currentCard = activeReviewList.getOrNull(currentReviewIndex)
            val parentSubject = subjects.firstOrNull { it.id == currentCard?.subjectId }
            ReviewExtractQuestionSheet(
                subjectName = parentSubject?.name,
                hasTextbook = !parentSubject?.effectiveTextbook().isNullOrBlank(),
                hasExtraKnowledge = !parentSubject?.effectiveExtraKnowledge().isNullOrBlank(),
                messages = reviewExtractChatMessages,
                isThinking = isReviewExtractThinking,
                draftState = reviewExtractDraftState,
                maxFontSizeSp = appSettings.maxFontSizeSp,
                onDismiss = { viewModel.closeReviewExtractQuestion() },
                onSendMessage = { text, bmps -> viewModel.sendReviewExtractMessage(text, bmps) },
                onSendAudioMessage = { b64, bmps -> viewModel.sendReviewExtractAudioMessage(b64, bmps) },
                onApplyCorrection = { msgId, corrId -> viewModel.applyReviewExtractCorrection(msgId, corrId) },
                onGenerateQuestion = { viewModel.generateDraftQuestionFromReviewExtraction() },
                onDismissDraft = { viewModel.dismissReviewExtractDraft() },
                onAcceptDraft = { viewModel.saveExtractedReviewQuestion() }
            )
        }
    }
}

@Composable
private fun FlashRecallBottomNavBar(
    selectedPage: Int,
    onPageSelect: (Int) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = SurfaceDark,
        tonalElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BottomNavItem(
                icon = Icons.Default.Schedule,
                label = "Stages",
                isSelected = selectedPage == PAGE_STAGES,
                onClick = { onPageSelect(PAGE_STAGES) },
                modifier = Modifier.weight(1f).testTag("tab_stages")
            )
            BottomNavItem(
                icon = Icons.Default.MenuBook,
                label = "Subjects",
                isSelected = selectedPage == PAGE_SUBJECTS,
                onClick = { onPageSelect(PAGE_SUBJECTS) },
                modifier = Modifier.weight(1f).testTag("tab_subjects")
            )
            BottomNavItem(
                icon = Icons.Default.Description,
                label = "Notes",
                isSelected = selectedPage == PAGE_NOTES,
                onClick = { onPageSelect(PAGE_NOTES) },
                modifier = Modifier.weight(1f).testTag("tab_notes")
            )
            BottomNavItem(
                icon = Icons.Default.Settings,
                label = "Settings",
                isSelected = selectedPage == PAGE_SETTINGS,
                onClick = { onPageSelect(PAGE_SETTINGS) },
                modifier = Modifier.weight(1f).testTag("tab_settings")
            )
        }
    }
}

@Composable
private fun BottomNavItem(
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(if (isSelected) SurfaceInput else androidx.compose.ui.graphics.Color.Transparent)
                .padding(horizontal = 14.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (isSelected) PrimaryPurple else TextMuted,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            color = if (isSelected) TextPrimary else TextMuted
        )
    }
}

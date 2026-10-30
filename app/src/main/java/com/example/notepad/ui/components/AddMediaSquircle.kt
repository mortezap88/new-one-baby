package com.example.notepad.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.notepad.ui.theme.SquircleBg
import com.example.notepad.ui.theme.RecordAccent
import com.example.notepad.ui.theme.SquircleBorder
import com.example.notepad.ui.theme.TextOffWhite
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun AddMediaSquircle(
    onTap: () -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onCancelRecording: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp? = null
) {
    var isRecordingActive by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // The held button keeps its size and instead flashes its red frame.
    val frameAlpha = remember { Animatable(1f) }

    LaunchedEffect(isRecordingActive) {
        if (isRecordingActive) {
            frameAlpha.animateTo(
                targetValue = 0.15f,
                animationSpec = infiniteRepeatable(
                    animation = tween(600),
                    repeatMode = RepeatMode.Reverse
                )
            )
        } else {
            frameAlpha.snapTo(1f)
        }
    }

    Box(
        modifier = modifier
            .then(if (size != null) Modifier.size(size) else Modifier.aspectRatio(1f))
            .testTag("add_media_squircle_button")
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = if (isRecordingActive) RecordAccent.copy(alpha = 0.16f) else SquircleBg,
            border = BorderStroke(
                1.5.dp,
                if (isRecordingActive) RecordAccent.copy(alpha = frameAlpha.value) else SquircleBorder
            ),
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: continue

                            when (event.type) {
                                PointerEventType.Press -> {
                                    change.consume()
                                    var isLongPressed = false
                                    val holdJob: Job = scope.launch {
                                        delay(350)
                                        isLongPressed = true
                                        isRecordingActive = true
                                        onStartRecording()
                                    }

                                    // Wait for release or cancel
                                    var released = false
                                    while (!released) {
                                        val nextEvent = awaitPointerEvent()
                                        val nextChange = nextEvent.changes.firstOrNull()
                                        if (nextEvent.type == PointerEventType.Release || nextChange?.pressed == false) {
                                            released = true
                                            holdJob.cancel()
                                            if (isLongPressed) {
                                                isRecordingActive = false
                                                onStopRecording()
                                            } else {
                                                // Short tap -> photo mode!
                                                onTap()
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                if (isRecordingActive) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = "Recording voice",
                        tint = RecordAccent,
                        modifier = Modifier.size(20.dp)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Tap for photo, hold for voice",
                        tint = TextOffWhite,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

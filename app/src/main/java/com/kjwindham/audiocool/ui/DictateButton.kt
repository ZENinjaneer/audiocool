package com.kjwindham.audiocool.ui

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Hold to speak a note, let go to add it. A quick tap listens hands-free until the next tap, which is
 * also what TalkBack's double-tap does.
 */
@Composable
fun DictateButton(
    listening: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    size: Dp = 48.dp,
    idleBackground: Color = Color.Transparent,
    idleTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val haptics = LocalHapticFeedback.current
    val isListening by rememberUpdatedState(listening)
    var handsFree by remember { mutableStateOf(false) }
    LaunchedEffect(listening) { if (!listening) handsFree = false }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (listening) RecordRed else idleBackground)
            .semantics {
                role = Role.Button
                contentDescription = if (listening) "Stop and add the spoken note" else "Speak a note"
                onClick {
                    if (isListening) {
                        onStop()
                    } else {
                        handsFree = true
                        onStart()
                    }
                    true
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    if (handsFree) {
                        handsFree = false
                        onStop()
                        return@detectTapGestures
                    }
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    val pressedAt = SystemClock.uptimeMillis()
                    onStart()
                    val released = tryAwaitRelease()
                    if (released && SystemClock.uptimeMillis() - pressedAt < 400) handsFree = true else onStop()
                })
            },
    ) {
        Icon(AppIcons.Mic, contentDescription = null, tint = if (listening) Color.White else idleTint)
    }
}

/*
 * Ported from org.connectbot.ui.components.FloatingTextInputDialog
 * (connectbot/connectbot, Apache-2.0) — upstream license header retained
 * below per Apache-2.0 §4. Haven changes: re-namespaced, text/send state
 * hoisted to the caller (per-tab drafts + bracket-paste-aware send live in
 * TerminalScreen), Haven string resources, TalkBack custom actions for
 * move/resize.
 *
 * ConnectBot: simple, powerful, open-source SSH client for Android
 * Copyright 2025-2026 Kenny Root
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package sh.haven.feature.terminal

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.core.content.edit
import kotlin.math.roundToInt

private const val NEWLINE_SYMBOL = "↩"
private const val TAB_SYMBOL = "⇥"

/**
 * Shows embedded newlines as ↩ (followed by the real line break) and tabs as ⇥
 * inline, while the underlying string keeps the real characters — so what's
 * sent to the terminal is exactly what was typed, but the user can *see* the
 * control characters before sending.
 */
private object SpecialCharVisualTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val original = text.text

        // Build transformed string and a map from original index to transformed index
        val transformedBuilder = StringBuilder()
        val originalToTransformed = IntArray(original.length + 1)
        for (i in original.indices) {
            originalToTransformed[i] = transformedBuilder.length
            when (original[i]) {
                '\n' -> transformedBuilder.append("$NEWLINE_SYMBOL\n")
                '\t' -> transformedBuilder.append(TAB_SYMBOL)
                else -> transformedBuilder.append(original[i])
            }
        }
        originalToTransformed[original.length] = transformedBuilder.length
        val transformed = transformedBuilder.toString()

        // Build reverse map from transformed index to original index
        val transformedToOriginal = IntArray(transformed.length + 1)
        for (i in original.indices) {
            val tStart = originalToTransformed[i]
            val tEnd = originalToTransformed[i + 1]
            for (t in tStart until tEnd) {
                transformedToOriginal[t] = i
            }
        }
        transformedToOriginal[transformed.length] = original.length

        val offsetMapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int = originalToTransformed[offset.coerceIn(0, original.length)]

            override fun transformedToOriginal(offset: Int): Int = transformedToOriginal[offset.coerceIn(0, transformed.length)]
        }

        return TransformedText(AnnotatedString(transformed), offsetMapping)
    }
}

// Position/size persistence. Same screen-fraction keys as upstream, but in a
// dedicated prefs file (Haven has no androidx.preference default-prefs users;
// DataStore is the app-wide store and isn't worth an async round-trip for a
// window geometry). Shared across all tabs/profiles, matching upstream.
private const val PREFS_NAME = "floating_text_input"
private const val PREF_FLOATING_INPUT_X = "floating_input_x"
private const val PREF_FLOATING_INPUT_Y = "floating_input_y"
private const val PREF_FLOATING_INPUT_WIDTH = "floating_input_width"
private const val PREF_FLOATING_INPUT_HEIGHT = "floating_input_height"
private const val DEFAULT_X_RATIO = 0.05f
private const val DEFAULT_Y_RATIO = 0.3f
private const val DEFAULT_WIDTH_RATIO = 0.9f
private const val DEFAULT_HEIGHT_RATIO = 0.25f
private const val MIN_WIDTH_DP = 200f
private const val MIN_HEIGHT_DP = 80f

/** Screen-fraction step used by the TalkBack custom move/resize actions. */
private const val A11Y_STEP_RATIO = 0.1f

/**
 * Floating, draggable, resizable text-entry window over the terminal: type a
 * full command/line with the normal IME (autocorrect, swipe typing, voice
 * input, cursor movement), review it, then send the whole string to the
 * session in one shot instead of fighting the raw terminal cell.
 *
 * State is intentionally hoisted: [text] / [onTextChange] are backed by a
 * per-tab draft map in TerminalScreen (so an unsent draft survives tab
 * switches, rotation and process death), and [onSend] owns the
 * bracket-paste-aware injection into the active tab. This composable only
 * renders and reports.
 *
 * @param onSend fired on the send button; the caller sends the current [text]
 *   (bracket-wrapped as needed) and clears the draft.
 * @param onDismiss fired on the close button, back press or a tap outside the
 *   window. The draft is NOT cleared on dismiss — only a successful send
 *   clears it.
 */
@Composable
internal fun FloatingTextInputDialog(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current

    // Calculate screen dimensions
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }
    val minWidthPx = with(density) { MIN_WIDTH_DP.dp.toPx() }
    val minHeightPx = with(density) { MIN_HEIGHT_DP.dp.toPx() }

    // Load saved position/size or use defaults
    val savedX = prefs.getFloat(PREF_FLOATING_INPUT_X, DEFAULT_X_RATIO)
    val savedY = prefs.getFloat(PREF_FLOATING_INPUT_Y, DEFAULT_Y_RATIO)
    val savedWidth = prefs.getFloat(PREF_FLOATING_INPUT_WIDTH, DEFAULT_WIDTH_RATIO)
    val savedHeight = prefs.getFloat(PREF_FLOATING_INPUT_HEIGHT, DEFAULT_HEIGHT_RATIO)

    // Current position and size in pixels
    var offsetX by remember { mutableFloatStateOf(screenWidthPx * savedX) }
    var offsetY by remember { mutableFloatStateOf(screenHeightPx * savedY) }
    var windowWidthPx by remember { mutableFloatStateOf(screenWidthPx * savedWidth) }
    var windowHeightPx by remember { mutableFloatStateOf(screenHeightPx * savedHeight) }

    val textFieldFocusRequester = remember { FocusRequester() }

    // Focus the field as soon as the window opens so typing can start
    // immediately.
    LaunchedEffect(Unit) {
        textFieldFocusRequester.requestFocus()
    }

    // Save position and size when dialog closes
    DisposableEffect(Unit) {
        onDispose {
            prefs.edit {
                putFloat(PREF_FLOATING_INPUT_X, offsetX / screenWidthPx)
                putFloat(PREF_FLOATING_INPUT_Y, offsetY / screenHeightPx)
                putFloat(PREF_FLOATING_INPUT_WIDTH, windowWidthPx / screenWidthPx)
                putFloat(PREF_FLOATING_INPUT_HEIGHT, windowHeightPx / screenHeightPx)
            }
        }
    }

    fun moveBy(dx: Float, dy: Float) {
        offsetX = (offsetX + dx).coerceIn(0f, (screenWidthPx - windowWidthPx).coerceAtLeast(0f))
        offsetY = (offsetY + dy).coerceIn(0f, (screenHeightPx - windowHeightPx).coerceAtLeast(0f))
    }

    fun resizeBy(dw: Float, dh: Float) {
        windowWidthPx = (windowWidthPx + dw).coerceIn(minWidthPx, screenWidthPx - offsetX)
        windowHeightPx = (windowHeightPx + dh).coerceIn(minHeightPx, screenHeightPx - offsetY)
    }

    // TalkBack: the raw drag gestures below are invisible to accessibility
    // services, so expose move/resize as semantic custom actions (on the
    // title and the resize handle respectively), stepping by a fixed screen
    // fraction per activation.
    val moveStepX = screenWidthPx * A11Y_STEP_RATIO
    val moveStepY = screenHeightPx * A11Y_STEP_RATIO
    val moveActions = listOf(
        CustomAccessibilityAction(stringResource(R.string.terminal_text_input_move_up)) {
            moveBy(0f, -moveStepY); true
        },
        CustomAccessibilityAction(stringResource(R.string.terminal_text_input_move_down)) {
            moveBy(0f, moveStepY); true
        },
        CustomAccessibilityAction(stringResource(R.string.terminal_text_input_move_left)) {
            moveBy(-moveStepX, 0f); true
        },
        CustomAccessibilityAction(stringResource(R.string.terminal_text_input_move_right)) {
            moveBy(moveStepX, 0f); true
        },
    )
    val resizeActions = listOf(
        CustomAccessibilityAction(stringResource(R.string.terminal_text_input_expand)) {
            resizeBy(moveStepX, moveStepY); true
        },
        CustomAccessibilityAction(stringResource(R.string.terminal_text_input_shrink)) {
            resizeBy(-moveStepX, -moveStepY); true
        },
    )
    val resizeHandleDescription = stringResource(R.string.terminal_text_input_resize_handle)

    Popup(
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    // Tap outside the window dismisses (without clearing the
                    // draft — it's hoisted). The full-screen scrim also
                    // deliberately keeps terminal gestures frozen while the
                    // dialog is up, matching upstream.
                    detectTapGestures(onTap = { onDismiss() })
                },
        ) {
            Column(
                modifier = Modifier
                    .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                    .width(with(density) { windowWidthPx.toDp() })
                    .background(
                        MaterialTheme.colorScheme.surface,
                        RoundedCornerShape(12.dp),
                    ),
            ) {
                // Draggable header with title and close button
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp)
                        .background(
                            MaterialTheme.colorScheme.primary,
                            RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
                        )
                        .pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                moveBy(dragAmount.x, dragAmount.y)
                            }
                        }
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.terminal_text_input_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier
                            .weight(1f)
                            .semantics { customActions = moveActions },
                    )

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(24.dp),
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.common_close),
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }

                // TextField with send button and resize handle to the right
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(with(density) { (windowHeightPx - 36.dp.toPx()).coerceAtLeast(minHeightPx).toDp() }),
                ) {
                    TextField(
                        value = text,
                        onValueChange = onTextChange,
                        placeholder = {
                            Text(stringResource(R.string.terminal_text_input_placeholder))
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Text,
                        ),
                        visualTransformation = SpecialCharVisualTransformation,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                        shape = RoundedCornerShape(bottomStart = 12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .focusRequester(textFieldFocusRequester),
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxHeight()
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(bottomEnd = 12.dp),
                            ),
                        verticalArrangement = Arrangement.SpaceBetween,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        IconButton(
                            onClick = onSend,
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = stringResource(R.string.terminal_text_input_send),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }

                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .semantics {
                                    contentDescription = resizeHandleDescription
                                    customActions = resizeActions
                                }
                                .pointerInput(Unit) {
                                    detectDragGestures { change, dragAmount ->
                                        change.consume()
                                        resizeBy(dragAmount.x, dragAmount.y)
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Default.OpenInFull,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

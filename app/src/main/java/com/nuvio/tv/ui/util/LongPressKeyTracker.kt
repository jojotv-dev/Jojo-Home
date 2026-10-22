package com.nuvio.tv.ui.util

import android.view.KeyEvent
import android.view.ViewConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
fun rememberLongPressKeyTracker(): LongPressKeyTracker = remember { LongPressKeyTracker() }

class LongPressKeyTracker(
    private val timeoutMillis: Long = ViewConfiguration.getLongPressTimeout().toLong()
) {
    private var pressedKeyCode: Int? = null
    private var pressedAtMillis: Long = 0L
    private var handledLongPress = false

    /** Supports the original trailing-lambda call form: handle(event, keys) { ... }. */
    fun handle(
        event: KeyEvent,
        isLongPressKey: (Int) -> Boolean,
        onClick: (() -> Unit)? = null,
        consumeUntilUp: Boolean = false,
        onLongPressCallback: () -> Unit
    ): Boolean = handle(
        event = event,
        isLongPressKey = isLongPressKey,
        onLongPress = onLongPressCallback,
        onClick = onClick,
        consumeUntilUp = consumeUntilUp
    )

    fun handle(
        event: KeyEvent,
        isLongPressKey: (Int) -> Boolean,
        onLongPress: () -> Unit,
        onClick: (() -> Unit)? = null,
        consumeUntilUp: Boolean = false
    ): Boolean {
        if (!isLongPressKey(event.keyCode)) return false

        return when (event.action) {
            KeyEvent.ACTION_DOWN -> handleDown(event, onLongPress, consumeUntilUp)
            KeyEvent.ACTION_UP -> handleUp(event, onLongPress, onClick)
            else -> false
        }
    }

    private fun handleDown(
        event: KeyEvent,
        onLongPress: () -> Unit,
        consumeUntilUp: Boolean
    ): Boolean {
        if (event.repeatCount == 0 || pressedKeyCode != event.keyCode) {
            pressedKeyCode = event.keyCode
            pressedAtMillis = event.eventTime
            handledLongPress = false
        }

        if (event.isLongPress || event.repeatCount > 0 || heldDurationMillis(event) >= timeoutMillis) {
            if (!handledLongPress) {
                handledLongPress = true
                onLongPress()
            }
            return true
        }

        return consumeUntilUp
    }

    private fun handleUp(
        event: KeyEvent,
        onLongPress: () -> Unit,
        onClick: (() -> Unit)?
    ): Boolean {
        val wasHandled = handledLongPress && pressedKeyCode == event.keyCode
        val isLongPress = !wasHandled && heldDurationMillis(event) >= timeoutMillis

        reset()

        if (isLongPress) {
            onLongPress()
            return true
        }

        if (!wasHandled) onClick?.invoke()
        return wasHandled || onClick != null
    }

    private fun heldDurationMillis(event: KeyEvent): Long {
        val startedAt = if (pressedKeyCode == event.keyCode) pressedAtMillis else event.downTime
        return event.eventTime - startedAt
    }

    private fun reset() {
        pressedKeyCode = null
        pressedAtMillis = 0L
        handledLongPress = false
    }
}

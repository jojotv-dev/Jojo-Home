package com.nuvio.tv.ui.screens.detail

import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

fun formatReleaseDate(isoDate: String): String = try {
    LocalDate.parse(isoDate).format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
} catch (_: DateTimeParseException) {
    isoDate
}

suspend fun FocusRequester.requestFocusAfterFrames(frames: Int = 2) {
    repeat(frames.coerceAtLeast(0)) { withFrameNanos { } }
    requestFocus()
}

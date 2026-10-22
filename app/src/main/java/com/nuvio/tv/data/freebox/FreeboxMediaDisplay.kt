package com.nuvio.tv.data.freebox

import java.util.Locale
import java.util.concurrent.TimeUnit

private val VIDEO_FILE_EXTENSION_REGEX = Regex("\\.(3g2|3gp|avi|flv|m2ts|m4v|mkv|mov|mp4|mpeg|mpg|mts|ts|webm|wmv)$", RegexOption.IGNORE_CASE)
private val DISPLAY_FILE_EXTENSION_REGEX = Regex("\\.(3g2|3gp|avi|avif|bmp|flv|gif|heic|heif|jpeg|jpg|m2ts|m4v|mkv|mov|mp4|mpeg|mpg|mts|pdf|png|tif|tiff|ts|webm|webp|wmv)$", RegexOption.IGNORE_CASE)
private val RELEASE_QUALITY_REGEX = Regex("\\b(2160p|1080p|720p|480p|4k|uhd|hdr10?|dv|dolby\\s*vision|bluray|blu[- ]?ray|bdrip|webrip|web[- ]?dl|hdtv|x264|x265|h264|h265|hevc|aac|dts|truehd|atmos|multi|vostfr|vf|french|eng|extended|remux|proper|repack)\\b", RegexOption.IGNORE_CASE)
private val SEASON_EPISODE_REGEX = Regex("\\b[sS]\\d{1,2}[eE]\\d{1,2}\\b")
private val YEAR_REGEX = Regex("\\b(19\\d{2}|20\\d{2})\\b")
private val RECORDING_DATE_SEGMENT_REGEX = Regex(
    """\b(?:\d{1,2}[-_./\\]\d{1,2}[-_./\\](?:19|20)?\d{2}|(?:19|20)\d{2}[-_./\\]\d{1,2}[-_./\\]\d{1,2})\b"""
)
private val RECORDING_TIME_SEGMENT_REGEX = Regex("""\b\d{1,2}\s*[h:.]\s*\d{2}\b""", RegexOption.IGNORE_CASE)
private val RECORDING_CHANNEL_SEGMENT_REGEX = Regex("""^[A-Z0-9+\s]{2,18}$""")
private val DURATION_PREFIX_REGEX = Regex("^(?:\\d+h\\s*\\d{1,2}m?|\\d+h|\\d+min)\\s+", RegexOption.IGNORE_CASE)
private val DURATION_VALUE_REGEX = Regex("^(\\d+)h\\s*(\\d{1,2})m?|^(\\d+)min", RegexOption.IGNORE_CASE)
private val GENERATED_TIMESTAMP_PREFIX_REGEX = Regex("^\\d{10,14}[\\s._-]*")

private const val FREEBOX_CONTENT_PREFIX = "freebox:"
private const val FREEBOX_FILE_FINGERPRINT_SEPARATOR = "#fb:"

fun freeboxContentIdForPath(path: String): String = "$FREEBOX_CONTENT_PREFIX${path.trim()}"

fun freeboxContentIdForFile(path: String, sizeBytes: Long?, modifiedMs: Long?): String {
    val base = freeboxContentIdForPath(path)
    val size = sizeBytes?.takeIf { it > 0L }
    val modified = modifiedMs?.takeIf { it > 0L }
    if (size == null && modified == null) return base
    return "$base$FREEBOX_FILE_FINGERPRINT_SEPARATOR${size ?: 0L}:${modified ?: 0L}"
}

fun freeboxContentIdForEntry(entry: FreeboxFileEntry): String =
    freeboxContentIdForFile(entry.path, entry.size, entry.modifiedMs)

fun freeboxPathFromContentId(contentIdOrPath: String): String {
    val raw = contentIdOrPath.trim()
    val withoutPrefix = raw.removePrefix(FREEBOX_CONTENT_PREFIX)
    return withoutPrefix.substringBefore(FREEBOX_FILE_FINGERPRINT_SEPARATOR).trim()
}

fun freeboxLegacyContentIdFor(contentIdOrPath: String): String =
    freeboxContentIdForPath(freeboxPathFromContentId(contentIdOrPath))

fun freeboxHasFileFingerprint(contentId: String): Boolean =
    contentId.contains(FREEBOX_FILE_FINGERPRINT_SEPARATOR)

fun freeboxFileNameOnly(rawNameOrPath: String): String {
    return rawNameOrPath
        .replace('\\', '/')
        .substringAfterLast('/')
        .substringAfterLast(':')
        .trim()
        .ifBlank { rawNameOrPath.trim() }
}


fun freeboxDisplayName(rawNameOrPath: String, showExtensions: Boolean = false): String {
    val fileName = freeboxFileNameOnly(rawNameOrPath)
        .replace(DURATION_PREFIX_REGEX, "")
        .replace(GENERATED_TIMESTAMP_PREFIX_REGEX, "")
        .trim()
    return if (showExtensions) {
        fileName.ifBlank { rawNameOrPath.trim() }
    } else {
        fileName
            .replace(DISPLAY_FILE_EXTENSION_REGEX, "")
            .trim()
            .ifBlank { fileName }
    }
}

fun freeboxFolderDisplayName(rawNameOrPath: String): String =
    freeboxFileNameOnly(rawNameOrPath)
        .replace(DURATION_PREFIX_REGEX, "")
        .replace(GENERATED_TIMESTAMP_PREFIX_REGEX, "")
        .replace('.', ' ')
        .replace('_', ' ')
        .replace(Regex("\\s+"), " ")
        .trim()
        .ifBlank { rawNameOrPath.trim() }

fun formatFreeboxDurationCompact(durationMs: Long?): String? {
    val safeMs = durationMs?.takeIf { it > 0L } ?: return null
    val totalMinutes = (TimeUnit.MILLISECONDS.toMinutes(safeMs) +
        if (safeMs % 60_000L >= 30_000L) 1L else 0L).coerceAtLeast(1L)
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return if (hours > 0L) {
        "${hours}h${minutes.toString().padStart(2, '0')}"
    } else {
        "0h${minutes.toString().padStart(2, '0')}"
    }
}

fun freeboxDurationMsFromName(rawNameOrPath: String): Long? {
    val match = DURATION_VALUE_REGEX.find(freeboxFileNameOnly(rawNameOrPath)) ?: return null
    val hours = match.groups[1]?.value?.toLongOrNull()
    val minutes = match.groups[2]?.value?.toLongOrNull() ?: match.groups[3]?.value?.toLongOrNull()
    return when {
        hours != null -> TimeUnit.HOURS.toMillis(hours) + TimeUnit.MINUTES.toMillis(minutes ?: 0L)
        minutes != null -> TimeUnit.MINUTES.toMillis(minutes)
        else -> null
    }
}

private fun extractDurationFromName(rawNameOrPath: String): String? =
    formatFreeboxDurationCompact(freeboxDurationMsFromName(rawNameOrPath))
fun freeboxVideoDisplayTitle(rawNameOrPath: String, durationMs: Long? = null, showExtensions: Boolean = false): String {
    val fileName = freeboxDisplayName(rawNameOrPath, showExtensions)
    val duration = formatFreeboxDurationCompact(durationMs) ?: extractDurationFromName(rawNameOrPath)
    return if (duration.isNullOrBlank()) fileName else "$duration $fileName"
}

fun freeboxTmdbSearchQuery(rawNameOrPath: String): String {
    val fileName = freeboxDisplayName(rawNameOrPath)
    freeboxRecordingTmdbSearchQuery(fileName)?.let { return it }
    val withoutExtension = fileName.replace(VIDEO_FILE_EXTENSION_REGEX, "")
    val cleaned = withoutExtension
        .replace('.', ' ')
        .replace('_', ' ')
        .replace('-', ' ')
        .replace(SEASON_EPISODE_REGEX, " ")
        .replace(YEAR_REGEX, " ")
        .replace(RELEASE_QUALITY_REGEX, " ")
        .replace(Regex("\\[[^]]*]"), " ")
        .replace(Regex("\\([^)]*\\)"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .replaceFirstChar { char -> if (char.isLowerCase()) char.titlecase(Locale.getDefault()) else char.toString() }
        .ifBlank { withoutExtension.trim() }
    // Retire un numero de segment isole en fin de titre (ex: "Envoye Special 3" -> "Envoye Special")
    val withoutTrailingNumber = cleaned.replace(Regex("\\s+\\d{1,3}$"), "").trim()
    return withoutTrailingNumber.ifBlank { cleaned }
}

fun freeboxRecordingTmdbSearchQuery(rawNameOrPath: String): String? {
    val fileName = freeboxDisplayName(rawNameOrPath)
        .replace(VIDEO_FILE_EXTENSION_REGEX, "")
        .replace('_', ' ')
        .trim()
    if (!fileName.contains(" - ")) return null

    val parts = fileName
        .split(" - ")
        .map { part ->
            part
                .replace(RECORDING_DATE_SEGMENT_REGEX, " ")
                .replace(RECORDING_TIME_SEGMENT_REGEX, " ")
                .replace(Regex("""\b\d{1,2}h\b""", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("""\s+"""), " ")
                .trim()
        }
        .filter { it.isNotBlank() }
        .toMutableList()

    if (parts.size < 2) return null
    if (parts.first().matches(RECORDING_CHANNEL_SEGMENT_REGEX)) {
        parts.removeAt(0)
    }

    val title = parts
        .firstOrNull { part ->
            part.length >= 3 &&
                !part.matches(RECORDING_CHANNEL_SEGMENT_REGEX) &&
                !part.matches(YEAR_REGEX)
        }
        ?.replace(RELEASE_QUALITY_REGEX, " ")
        ?.replace(Regex("""\s+"""), " ")
        ?.trim()

    return title?.takeIf { it.length >= 3 }
}

/**
 * Stable key shared by recurring recordings of the same programme.
 * Unlike a Freebox content id, this deliberately ignores the air date,
 * time and file fingerprint so a manually chosen artwork is reused next week.
 */
fun freeboxProgrammeOverrideKey(rawNameOrPath: String, isDirectory: Boolean = false): String {
    val nameWithoutRecordingTimestamp = rawNameOrPath
        .replace(RECORDING_DATE_SEGMENT_REGEX, " ")
        .replace(RECORDING_TIME_SEGMENT_REGEX, " ")
    val title = if (isDirectory) {
        freeboxFolderTmdbSearchQuery(nameWithoutRecordingTimestamp)
    } else {
        freeboxTmdbSearchQuery(nameWithoutRecordingTimestamp)
    }
    val normalized = title
        .lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
    return "freebox-programme:$normalized"
}

/** Canonicalizes dated programme keys saved by older versions for cross-week reuse. */
fun freeboxCanonicalProgrammeOverrideKey(storedKey: String): String? {
    val prefix = "freebox-programme:"
    if (!storedKey.startsWith(prefix)) return null

    val titleParts = storedKey.removePrefix(prefix)
        .replace(RECORDING_TIME_SEGMENT_REGEX, " ")
        .trim()
        .split(Regex("\\s+"))
        .filter(String::isNotBlank)
        .toMutableList()
    if (titleParts.isEmpty()) return null

    fun isYear(value: String): Boolean = value.toIntOrNull() in 1900..2100
    fun isShortYear(value: String): Boolean = value.toIntOrNull() in 0..99
    fun isMonthDay(first: String, second: String): Boolean {
        val a = first.toIntOrNull() ?: return false
        val b = second.toIntOrNull() ?: return false
        return a in 1..31 && b in 1..31 && (a in 1..12 || b in 1..12)
    }

    var lastIndex = titleParts.lastIndex
    if (titleParts.size >= 4) {
        val hour = titleParts[lastIndex - 1].toIntOrNull()
        val minute = titleParts[lastIndex].toIntOrNull()
        val hasDateBeforeTime = isYear(titleParts[lastIndex - 2]) ||
            (titleParts.size >= 5 && isYear(titleParts[lastIndex - 3])) ||
            (titleParts.size >= 4 && isMonthDay(titleParts[lastIndex - 3], titleParts[lastIndex - 2]))
        if (hour != null && minute != null && hour in 0..23 && minute in 0..59 && hasDateBeforeTime) {
            titleParts.removeAt(lastIndex)
            titleParts.removeAt(titleParts.lastIndex)
        }
    }

    lastIndex = titleParts.lastIndex
    val legacyDateTokenCount = when {
        titleParts.size >= 3 && isYear(titleParts[lastIndex]) &&
            isMonthDay(titleParts[lastIndex - 2], titleParts[lastIndex - 1]) -> 3
        titleParts.size >= 3 && isShortYear(titleParts[lastIndex]) &&
            isMonthDay(titleParts[lastIndex - 2], titleParts[lastIndex - 1]) -> 3
        titleParts.size >= 3 && isYear(titleParts[lastIndex - 2]) &&
            isMonthDay(titleParts[lastIndex - 1], titleParts[lastIndex]) -> 3
        titleParts.size >= 3 && isShortYear(titleParts[lastIndex - 2]) &&
            isMonthDay(titleParts[lastIndex - 1], titleParts[lastIndex]) -> 3
        titleParts.size >= 2 && isMonthDay(titleParts[lastIndex - 1], titleParts[lastIndex]) -> 2
        else -> 0
    }
    repeat(legacyDateTokenCount) { titleParts.removeAt(titleParts.lastIndex) }

    val canonicalTitle = titleParts.joinToString(" ").trim()
    return canonicalTitle.takeIf(String::isNotBlank)?.let { "$prefix$it" }
}

fun freeboxFolderTmdbSearchQuery(rawNameOrPath: String): String {
    val cleaned = freeboxFolderDisplayName(rawNameOrPath)
        .replace(SEASON_EPISODE_REGEX, " ")
        .replace(YEAR_REGEX, " ")
        .replace(RELEASE_QUALITY_REGEX, " ")
        .replace(Regex("\\[[^]]*]"), " ")
        .replace(Regex("\\([^)]*\\)"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .replaceFirstChar { char -> if (char.isLowerCase()) char.titlecase(Locale.getDefault()) else char.toString() }
    return cleaned.ifBlank { freeboxFolderDisplayName(rawNameOrPath) }
}

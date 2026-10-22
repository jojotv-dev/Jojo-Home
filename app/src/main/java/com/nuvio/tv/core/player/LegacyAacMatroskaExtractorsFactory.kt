package com.nuvio.tv.core.player

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.text.SubtitleParser
import com.nuvio.tv.core.player.dvmkv.MatroskaExtractor
import java.io.IOException

private val LEGACY_HE_AAC_CODEC_ID = "A_AAC/MPEG2/LC/SBR".encodeToByteArray()
private const val LEGACY_HE_AAC_SCAN_BYTES = 4 * 1024 * 1024

internal fun containsLegacyHeAacCodecId(data: ByteArray): Boolean {
    if (data.size < LEGACY_HE_AAC_CODEC_ID.size) return false
    val lastStart = data.size - LEGACY_HE_AAC_CODEC_ID.size
    for (start in 0..lastStart) {
        var matches = true
        for (index in LEGACY_HE_AAC_CODEC_ID.indices) {
            if (data[start + index] != LEGACY_HE_AAC_CODEC_ID[index]) {
                matches = false
                break
            }
        }
        if (matches) return true
    }
    return false
}

@UnstableApi
internal class LegacyAacMatroskaExtractorsFactory(
    private val delegate: ExtractorsFactory,
    private val subtitleParserFactory: SubtitleParser.Factory
) : ExtractorsFactory {
    override fun createExtractors(): Array<Extractor> =
        withLegacyAacExtractor(delegate.createExtractors())

    override fun createExtractors(
        uri: Uri,
        responseHeaders: Map<String, List<String>>
    ): Array<Extractor> = withLegacyAacExtractor(delegate.createExtractors(uri, responseHeaders))

    private fun withLegacyAacExtractor(extractors: Array<Extractor>): Array<Extractor> =
        arrayOf<Extractor>(LegacyAacMatroskaExtractor(subtitleParserFactory), *extractors)
}

@UnstableApi
private class LegacyAacMatroskaExtractor(
    subtitleParserFactory: SubtitleParser.Factory
) : Extractor {
    private val delegate = MatroskaExtractor(subtitleParserFactory)

    @Throws(IOException::class)
    override fun sniff(input: ExtractorInput): Boolean {
        return delegate.sniff(input)
    }

    override fun init(output: ExtractorOutput) = delegate.init(output)

    @Throws(IOException::class)
    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int =
        delegate.read(input, seekPosition)

    override fun seek(position: Long, timeUs: Long) = delegate.seek(position, timeUs)

    override fun release() = delegate.release()

    override fun getUnderlyingImplementation(): Extractor = delegate.underlyingImplementation
}

package com.nuvio.tv.ui.screens.player

import androidx.media3.common.MimeTypes
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import kotlin.random.Random

class DolbyVisionHdr10PlusTest {
    private val dv = MimeTypes.VIDEO_DOLBY_VISION
    private val hdr10Plus = bytes(0xb5, 0, 0x3c, 0, 1, 4, 1, 0x11, 0x22, 0x33)

    @Test fun synchronousAdapterOnlyTargetsTheObservedPhilipsDecoder() {
        val decoder = "OMX.dolby.vision.dvhe.st.decoder"
        assertTrue(needsSynchronousPhilipsDolbyVisionAdapter("TPM191E", 31, decoder))
        assertTrue(needsSynchronousPhilipsDolbyVisionAdapter("tpm191e", 31, decoder))
        for (model in listOf("AFTMM", "AFTSSS", "AFTKA", "BRAVIA_4K_VH2", "", "TPM211EA")) {
            assertFalse(needsSynchronousPhilipsDolbyVisionAdapter(model, 31, decoder))
        }
        for (sdk in listOf(25, 28, 30, 32, 33, 35, 36)) {
            assertFalse(needsSynchronousPhilipsDolbyVisionAdapter("TPM191E", sdk, decoder))
        }
        for (other in listOf("", "OMX.MTK.VIDEO.DECODER.HEVC", "OMX.MTK.VIDEO.DECODER.AVC",
            "OMX.dolby.vision.dvhe.stn.decoder", "c2.amlogic.dvhe.decoder")) {
            assertFalse(needsSynchronousPhilipsDolbyVisionAdapter("TPM191E", 31, other))
        }
    }

    @Test fun onlyNativeDolbyVisionProfile8EnablesFiltering() {
        for (codecs in listOf("dvhe.08.06", "dvh1.08.09", "hvc1.2.4, dvhe.08.06")) {
            assertTrue(shouldFilterHdr10PlusForDolbyVision(dv, codecs, dv))
        }
        for (codecs in listOf(null, "", "dvhe", "dvhe.05.06", "dvhe.07.06", "hvc1.2.4", "dvhe.invalid.08")) {
            assertFalse(shouldFilterHdr10PlusForDolbyVision(dv, codecs, dv))
        }
        assertFalse(shouldFilterHdr10PlusForDolbyVision(dv, "dvhe.08.06", MimeTypes.VIDEO_H265))
        assertFalse(shouldFilterHdr10PlusForDolbyVision(MimeTypes.VIDEO_H265, "dvhe.08.06", dv))
        assertFalse(shouldFilterHdr10PlusForDolbyVision(null, "dvhe.08.06", dv))
    }

    @Test fun removesHdr10PlusButPreservesVideoAndDolbyRpu() {
        val video = nal(19, bytes(11, 12, 13))
        val rpu = nal(62, bytes(21, 22, 23))
        val metadata = sei(4, hdr10Plus)
        assertFiltered(video + rpu, video + metadata + rpu)
    }

    @Test fun supportsThreeAndFourBytePrefixesAndSuffixSei() {
        for (prefix in 3..4) for (type in listOf(39, 40)) {
            val video = nal(19, bytes(11, 12), prefix)
            val metadata = nal(type, seiMessage(4, hdr10Plus) + bytes(0x80), prefix)
            assertFiltered(video, metadata + video + metadata)
        }
    }

    @Test fun preservesMasteringMetadataAndCaptionNalUnits() {
        val mastering = sei(137, bytes(10, 20, 30))
        val captions = sei(4, bytes(0xb5, 0, 0x31, 0x47, 0x41, 0x39, 0x34))
        val video = nal(19, bytes(11, 12))
        assertFiltered(mastering + captions + video,
            mastering + sei(4, hdr10Plus) + captions + video)
    }

    @Test fun retainsMixedSeiWithoutLosingCaptionsOrStaticHdr() {
        val dynamic = seiMessage(4, hdr10Plus)
        val other = seiMessage(137, bytes(0x11, 0x22))
        for (messages in listOf(dynamic + other, other + dynamic)) {
            val mixed = nal(39, messages + bytes(0x80))
            assertFiltered(mixed, mixed)
        }
    }

    @Test fun validatesTheCompleteHdr10PlusIdentifier() {
        for (index in 0..5) {
            val other = hdr10Plus.copyOf().also { it[index] = (it[index].toInt() xor 0x10).toByte() }
            val sample = sei(4, other)
            assertFiltered(sample, sample)
        }
        val unsupportedVersion = sei(4, hdr10Plus.copyOf().also { it[6] = 2 })
        assertFiltered(unsupportedVersion, unsupportedVersion)
        assertFiltered(ByteArray(0), sei(4, hdr10Plus.copyOf().also { it[6] = 0 }))
    }

    @Test fun handlesEscapedPayloadsAndExtendedPayloadSizes() {
        val payload = hdr10Plus + bytes(0, 0, 1, 0, 0, 3) + ByteArray(300) { 0x55 }
        val sample = nal(39, escape(seiMessage(4, payload) + bytes(0x80)))
        assertFiltered(ByteArray(0), sample)
    }

    @Test fun removesSeveralHdr10PlusMessagesInOneIsolatedNal() {
        val sample = nal(39, seiMessage(4, hdr10Plus) + seiMessage(4, hdr10Plus) + bytes(0x80))
        assertFiltered(ByteArray(0), sample)
    }

    @Test fun neverInterpretsVideoOrDolbyRpuAsSeiMetadata() {
        val payload = seiMessage(4, hdr10Plus) + bytes(0x80)
        val sample = nal(19, payload) + nal(33, payload) + nal(62, payload)
        assertFiltered(sample, sample)
    }

    @Test fun requeuedSamplesRemainIntactAfterTheFirstFilteringPass() {
        val video = nal(19, ByteArray(256 * 1024) { 0x55 })
        val sample = sei(4, hdr10Plus) + video
        val buffer = ByteBuffer.allocateDirect(sample.size).put(sample)
        buffer.flip()
        val removed = stripHdr10PlusSeiNalUnits(buffer, 0, sample.size)
        assertEquals(sample.size - video.size, removed)
        assertEquals(0, stripHdr10PlusSeiNalUnits(buffer, 0, video.size))
        assertArrayEquals(video, ByteArray(video.size) { buffer.get(it) })
    }

    @Test fun leavesMalformedAndLengthDelimitedSamplesUnchanged() {
        val samples = listOf(
            bytes(0, 0, 1),
            bytes(0, 0, 1, 78),
            nal(39, bytes(4, 0xff)),
            nal(39, bytes(4, 0x7f) + hdr10Plus),
            nal(39, seiMessage(4, hdr10Plus)),
            nal(39, seiMessage(4, hdr10Plus) + bytes(0x80, 0x10)),
            bytes(0, 0, 0, 32, 78, 1) + seiMessage(4, hdr10Plus) + bytes(0x80)
        )
        for (sample in samples) assertFiltered(sample, sample)
    }

    @Test fun preservesBufferPositionLimitAndBytesOutsideTheQueuedRange() {
        val video = nal(19, bytes(10, 20, 30))
        val sample = sei(4, hdr10Plus) + video
        val buffer = ByteBuffer.allocateDirect(sample.size + 12)
        buffer.put(bytes(7, 8, 9)).put(sample).put(bytes(4, 5, 6))
        buffer.limit(3 + sample.size + 3).position(2)
        val removed = stripHdr10PlusSeiNalUnits(buffer, 3, sample.size)
        assertEquals(sample.size - video.size, removed)
        assertEquals(2, buffer.position())
        assertEquals(sample.size + 6, buffer.limit())
        assertArrayEquals(video, ByteArray(video.size) { buffer.get(3 + it) })
        assertArrayEquals(bytes(7, 8, 9), ByteArray(3) { buffer.get(it) })
        assertArrayEquals(bytes(4, 5, 6), ByteArray(3) { buffer.get(3 + sample.size + it) })
    }

    @Test fun rejectsInvalidRangesAndReadOnlyBuffers() {
        val sample = sei(4, hdr10Plus)
        val buffer = ByteBuffer.wrap(sample.copyOf())
        for ((offset, size) in listOf(-1 to 5, 0 to -1, 0 to 0,
            0 to (sample.size + 1), Int.MAX_VALUE to 1, 1 to Int.MAX_VALUE)) {
            assertEquals(0, stripHdr10PlusSeiNalUnits(buffer, offset, size))
        }
        assertEquals(0, stripHdr10PlusSeiNalUnits(buffer.asReadOnlyBuffer(), 0, sample.size))
        assertArrayEquals(sample, buffer.array())
    }

    @Test fun arbitraryTruncatedNalUnitsNeverThrowOrExpandTheSample() {
        val random = Random(163)
        repeat(1500) {
            val sample = nal(listOf(19, 39, 40, 62)[it % 4], random.nextBytes(it % 200), 3 + it % 2)
            val buffer = ByteBuffer.wrap(sample)
            val removed = stripHdr10PlusSeiNalUnits(buffer, 0, sample.size)
            assertTrue(removed in 0..sample.size)
            assertEquals(0, buffer.position())
        }
    }

    private fun assertFiltered(expected: ByteArray, sample: ByteArray) {
        val buffer = ByteBuffer.wrap(sample.copyOf())
        val removed = stripHdr10PlusSeiNalUnits(buffer, 0, sample.size)
        assertEquals(sample.size - expected.size, removed)
        assertArrayEquals(expected, buffer.array().copyOf(sample.size - removed))
    }

    private fun sei(type: Int, payload: ByteArray): ByteArray =
        nal(39, seiMessage(type, payload) + bytes(0x80))

    private fun seiMessage(type: Int, payload: ByteArray): ByteArray =
        bytes(type) + ByteArray(payload.size / 255) { 0xff.toByte() } +
            bytes(payload.size % 255) + payload

    private fun nal(type: Int, payload: ByteArray, prefix: Int = 4): ByteArray =
        ByteArray(prefix - 1) + bytes(1, type shl 1, 1) + payload

    private fun escape(rbsp: ByteArray): ByteArray {
        val output = ArrayList<Byte>()
        var zeros = 0
        for (value in rbsp) {
            if (zeros == 2 && (value.toInt() and 0xff) <= 3) {
                output.add(3)
                zeros = 0
            }
            output.add(value)
            zeros = if (value == 0.toByte()) zeros + 1 else 0
        }
        return output.toByteArray()
    }

    private fun bytes(vararg values: Int): ByteArray = values.map { it.toByte() }.toByteArray()
}

/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.exoplayer.internal

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the STREAMINFO packing the bundled FLAC decoder depends on.
 *
 * The packed bytes are handed to dr_flac inside a synthetic FLAC stream, so a wrong shift here
 * shows up as silence or a decode failure on a device rather than as an error at build time.
 * These tests read the fields back the way the FLAC bitstream defines them (RFC 9639 §8.2).
 */
class FlacStreamInfoTest {
    @Test
    fun `packs sample rate channels and bit depth into the fixed-width fields`() {
        val streamInfo = FlacDecoderNative.packStreamInfo(
            sampleRate = 96000,
            channelCount = 2,
            bitsPerSample = 24,
            maxBlockSize = 4096,
        )

        assertEquals(34, streamInfo.size)

        // Minimum and maximum block size share the same value here.
        assertEquals(4096, readU16(streamInfo, 0))
        assertEquals(4096, readU16(streamInfo, 2))

        // 20 bits of sample rate, then 3 bits of channels - 1, then 5 bits of bits per sample - 1.
        val packed = readU64(streamInfo, 10)
        assertEquals(96000L, (packed ushr 44) and 0xFFFFF)
        assertEquals(1L, (packed ushr 41) and 0x7) // channels - 1
        assertEquals(23L, (packed ushr 36) and 0x1F) // bits per sample - 1

        // Total samples is left unknown; the extractor does not report it.
        assertEquals(0L, packed and 0xFFFFFFFFFL)
    }

    @Test
    fun `handles the extremes of every field`() {
        val streamInfo = FlacDecoderNative.packStreamInfo(
            sampleRate = 655350,
            channelCount = 8,
            bitsPerSample = 32,
            maxBlockSize = 65535,
        )

        val packed = readU64(streamInfo, 10)
        assertEquals(655350L, (packed ushr 44) and 0xFFFFF)
        assertEquals(7L, (packed ushr 41) and 0x7)
        assertEquals(31L, (packed ushr 36) and 0x1F)
    }

    @Test
    fun `keeps a 44100 stereo 16 bit stream intact`() {
        val streamInfo = FlacDecoderNative.packStreamInfo(
            sampleRate = 44100,
            channelCount = 2,
            bitsPerSample = 16,
            maxBlockSize = 4608,
        )

        val packed = readU64(streamInfo, 10)
        assertEquals(44100L, (packed ushr 44) and 0xFFFFF)
        assertEquals(1L, (packed ushr 41) and 0x7)
        assertEquals(15L, (packed ushr 36) and 0x1F)
    }

    private fun readU16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

    /** Reads the 8 bytes starting at [offset] as a big-endian unsigned 64-bit value. */
    private fun readU64(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (index in 0 until 8) {
            value = (value shl 8) or (bytes[offset + index].toLong() and 0xFF)
        }
        return value
    }
}
